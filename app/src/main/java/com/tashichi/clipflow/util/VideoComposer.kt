package com.tashichi.clipflow.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.SegmentTimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoComposer - 複数の動画セグメントを統合してシームレス再生を実現するクラス
 *
 * iOS の AVComposition に相当する機能を Media3 で実装
 * 複数の1秒動画セグメントをギャップレスに再生・エクスポート可能にする
 */
class VideoComposer(private val context: Context) {

    companion object {
        private const val TAG = "VideoComposer"
    }

    /**
     * プロジェクトから統合ビデオファイルを作成（真の Composition 実装）
     *
     * iOS の AVMutableComposition に対応
     * MediaMuxer を使用して複数セグメントを1つの MP4 ファイルに結合
     *
     * @param project 対象のプロジェクト
     * @param onProgress 進捗コールバック (処理済み, 総数)
     * @return 作成された統合ビデオファイル (失敗時は null)
     */
    suspend fun createComposition(
        project: Project,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sortedSegments = project.getSortedSegments()
            if (sortedSegments.isEmpty()) {
                Log.w(TAG, "No segments to compose")
                return@withContext null
            }

            onProgress(0, sortedSegments.size)

            // キャッシュディレクトリに統合ビデオファイルを作成
            val cacheDir = File(context.cacheDir, "compositions")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // プロジェクトIDとタイムスタンプから一意のファイル名を生成
            val outputFile = File(cacheDir, "composition_${project.id}_${System.currentTimeMillis()}.mp4")

            Log.d(TAG, "Creating merged video file: ${outputFile.absolutePath}")

            // MediaMuxer でセグメントを結合
            val success = mergeSegmentsToSingleFile(
                sortedSegments = sortedSegments,
                outputFile = outputFile,
                onProgress = onProgress
            )

            if (!success || !outputFile.exists()) {
                Log.e(TAG, "Failed to merge segments")
                return@withContext null
            }

            Log.d(TAG, "Composition created successfully: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to create composition", e)
            Log.e(TAG, "[ERROR] Exception: ${e.message}")
            Log.e(TAG, "[ERROR] Stack trace: ${e.stackTraceToString()}")
            return@withContext null
        }
    }

    /**
     * MediaMuxer を使用して複数セグメントを1つのファイルに結合
     *
     * iOS の AVMutableComposition の実装に相当
     *
     * @param sortedSegments ソート済みセグメントリスト
     * @param outputFile 出力ファイル
     * @param onProgress 進捗コールバック
     * @return 成功時は true
     */
    private suspend fun mergeSegmentsToSingleFile(
        sortedSegments: List<com.tashichi.clipflow.data.model.VideoSegment>,
        outputFile: File,
        onProgress: (Int, Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var muxer: MediaMuxer? = null
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var firstRotation: Int? = null

        // 累積時間（マイクロ秒）を追跡
        var videoTimeOffsetUs = 0L
        var audioTimeOffsetUs = 0L

        try {
            // MediaMuxer を作成
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var isFirstSegment = true

            sortedSegments.forEachIndexed { index, segment ->
                Log.d(TAG, "[Merge $index] Processing segment: ${segment.uri}")

                val file = File(context.filesDir, segment.uri)
                if (!file.exists()) {
                    Log.w(TAG, "[Merge $index] File not found: ${segment.uri}")
                    return@forEachIndexed
                }

                // MediaExtractor でセグメントを読み込む
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val trackCount = extractor.trackCount
                    Log.d(TAG, "[Merge $index] Track count: $trackCount")

                    // 各トラック（ビデオ・オーディオ）を処理
                    var videoMaxTimeUs = 0L
                    var audioMaxTimeUs = 0L

                    for (trackIndex in 0 until trackCount) {
                        val format = extractor.getTrackFormat(trackIndex)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                        Log.d(TAG, "[Merge $index] Track $trackIndex: MIME=$mime")

                        when {
                            mime.startsWith("video/") -> {
                                if (isFirstSegment) {
                                    // 最初のセグメントで回転情報を取得
                                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                        firstRotation = format.getInteger(MediaFormat.KEY_ROTATION)
                                        Log.d(TAG, "[Merge $index] Video rotation: $firstRotation degrees")
                                    }

                                    // ビデオトラックを追加
                                    videoTrackIndex = muxer.addTrack(format)
                                    Log.d(TAG, "[Merge $index] Added video track: $videoTrackIndex")
                                }

                                // ビデオデータをコピー
                                val maxTime = copyTrackData(extractor, muxer, trackIndex, videoTrackIndex, videoTimeOffsetUs)
                                if (maxTime > videoMaxTimeUs) {
                                    videoMaxTimeUs = maxTime
                                }
                            }
                            mime.startsWith("audio/") -> {
                                if (isFirstSegment) {
                                    // オーディオトラックを追加
                                    audioTrackIndex = muxer.addTrack(format)
                                    Log.d(TAG, "[Merge $index] Added audio track: $audioTrackIndex")
                                }

                                // オーディオデータをコピー
                                val maxTime = copyTrackData(extractor, muxer, trackIndex, audioTrackIndex, audioTimeOffsetUs)
                                if (maxTime > audioMaxTimeUs) {
                                    audioMaxTimeUs = maxTime
                                }
                            }
                        }
                    }

                    if (isFirstSegment) {
                        // 回転情報を設定
                        if (firstRotation != null && firstRotation != 0) {
                            muxer.setOrientationHint(firstRotation!!)
                            Log.d(TAG, "[Merge] Set orientation hint: $firstRotation degrees")
                        }

                        // Muxer を開始
                        muxer.start()
                        Log.d(TAG, "[Merge] Muxer started")
                        isFirstSegment = false
                    }

                    // 次のセグメント用に累積時間を更新
                    if (videoMaxTimeUs > 0) {
                        videoTimeOffsetUs = videoMaxTimeUs
                        Log.d(TAG, "[Merge $index] Video offset updated to: ${videoTimeOffsetUs}us")
                    }
                    if (audioMaxTimeUs > 0) {
                        audioTimeOffsetUs = audioMaxTimeUs
                        Log.d(TAG, "[Merge $index] Audio offset updated to: ${audioTimeOffsetUs}us")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "[Merge $index] Error processing segment", e)
                } finally {
                    extractor.release()
                }

                // 進捗を通知
                onProgress(index + 1, sortedSegments.size)
                delay(10)
            }

            // Muxer を停止
            muxer.stop()
            Log.d(TAG, "[Merge] Muxer stopped")

            true
        } catch (e: Exception) {
            Log.e(TAG, "[Merge] Failed to merge segments", e)
            Log.e(TAG, "[Merge] Error: ${e.message}")
            false
        } finally {
            try {
                muxer?.release()
                Log.d(TAG, "[Merge] Muxer released")
            } catch (e: Exception) {
                Log.e(TAG, "[Merge] Error releasing muxer", e)
            }
        }
    }

    /**
     * MediaExtractor から MediaMuxer へトラックデータをコピー
     *
     * @param extractor ソース extractor
     * @param muxer ターゲット muxer
     * @param sourceTrackIndex ソーストラックインデックス
     * @param targetTrackIndex ターゲットトラックインデックス
     * @param timeOffsetUs 時間オフセット（マイクロ秒）
     * @return このトラックの最大 presentation time（マイクロ秒）
     */
    private fun copyTrackData(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        sourceTrackIndex: Int,
        targetTrackIndex: Int,
        timeOffsetUs: Long
    ): Long {
        if (targetTrackIndex < 0) {
            Log.w(TAG, "[CopyTrack] Invalid target track index: $targetTrackIndex")
            return 0L
        }

        extractor.selectTrack(sourceTrackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB バッファ
        val bufferInfo = MediaCodec.BufferInfo()
        var sampleCount = 0
        var maxTimeUs = timeOffsetUs

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break // データの終わり
            }

            // 元の presentation time に offset を追加
            val originalTimeUs = extractor.sampleTime
            val adjustedTimeUs = originalTimeUs + timeOffsetUs

            bufferInfo.presentationTimeUs = adjustedTimeUs
            bufferInfo.flags = extractor.sampleFlags
            bufferInfo.size = sampleSize
            bufferInfo.offset = 0

            muxer.writeSampleData(targetTrackIndex, buffer, bufferInfo)

            if (adjustedTimeUs > maxTimeUs) {
                maxTimeUs = adjustedTimeUs
            }

            extractor.advance()
            sampleCount++
        }

        extractor.unselectTrack(sourceTrackIndex)
        Log.d(TAG, "[CopyTrack] Copied $sampleCount samples from track $sourceTrackIndex to $targetTrackIndex (offset: ${timeOffsetUs}us, max: ${maxTimeUs}us)")

        return maxTimeUs
    }

    /**
     * 統合ビデオファイルをエクスポート（真の Composition 実装）
     *
     * iOS の PlayerView.exportVideo() に対応
     * 既に統合されたファイルを指定された場所にコピー
     *
     * @param compositionFile エクスポートする統合ビデオファイル
     * @param outputFile 出力先ファイル
     * @param onProgress 進捗コールバック (0.0 ~ 1.0)
     * @return エクスポート成功時は true
     */
    suspend fun exportComposition(
        compositionFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!compositionFile.exists()) {
                Log.e(TAG, "Composition file not found: ${compositionFile.absolutePath}")
                return@withContext false
            }

            Log.d(TAG, "Exporting composition from ${compositionFile.absolutePath} to ${outputFile.absolutePath}")

            onProgress(0.0f)

            // ファイルをコピー
            val bufferSize = 8192
            val totalBytes = compositionFile.length()
            var bytesCopied = 0L

            compositionFile.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        // 進捗を通知
                        val progress = (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(progress)
                    }
                }
            }

            onProgress(1.0f)
            Log.d(TAG, "Export completed successfully: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export composition", e)
            Log.e(TAG, "Error: ${e.message}")
            false
        }
    }

    /**
     * 各セグメントの Composition 内での時間範囲を計算
     *
     * iOS の ProjectManager.getSegmentTimeRanges() に対応
     * シーク機能で使用 (タップ位置から対応セグメントを特定)
     *
     * @param project 対象のプロジェクト
     * @return セグメント時間範囲のリスト
     */
    suspend fun getSegmentTimeRanges(project: Project): List<SegmentTimeRange> =
        withContext(Dispatchers.IO) {
            val sortedSegments = project.getSortedSegments()
            val timeRanges = mutableListOf<SegmentTimeRange>()
            var currentTimeMs = 0L

            sortedSegments.forEachIndexed { index, segment ->
                val file = File(context.filesDir, segment.uri)
                if (!file.exists()) {
                    Log.w(TAG, "Segment file not found: ${segment.uri}")
                    return@forEachIndexed
                }

                // 動画の長さを取得
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L

                    val timeRange = SegmentTimeRange(
                        segmentIndex = index,
                        startTimeMs = currentTimeMs,
                        durationMs = durationMs
                    )
                    timeRanges.add(timeRange)

                    Log.d(
                        TAG,
                        "Segment $index: ${currentTimeMs}ms - ${currentTimeMs + durationMs}ms (${durationMs}ms)"
                    )

                    currentTimeMs += durationMs
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get duration for ${segment.uri}", e)
                } finally {
                    retriever.release()
                }
            }

            Log.d(TAG, "Total composition duration: ${currentTimeMs}ms")
            timeRanges
        }

    /**
     * Composition の総再生時間を取得
     *
     * @param project 対象のプロジェクト
     * @return 総再生時間 (ミリ秒)
     */
    suspend fun getTotalDuration(project: Project): Long =
        withContext(Dispatchers.IO) {
            val sortedSegments = project.getSortedSegments()
            var totalDurationMs = 0L

            sortedSegments.forEach { segment ->
                val file = File(context.filesDir, segment.uri)
                if (!file.exists()) {
                    return@forEach
                }

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    totalDurationMs += durationMs
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get duration for ${segment.uri}", e)
                } finally {
                    retriever.release()
                }
            }

            totalDurationMs
        }

    /**
     * 指定された時刻に対応するセグメントインデックスを取得
     *
     * @param timeRanges セグメント時間範囲のリスト
     * @param timeMs 対象時刻 (ミリ秒)
     * @return セグメントインデックス (見つからない場合は null)
     */
    fun getSegmentIndexAtTime(timeRanges: List<SegmentTimeRange>, timeMs: Long): Int? {
        return timeRanges.find { it.contains(timeMs) }?.segmentIndex
    }
}
