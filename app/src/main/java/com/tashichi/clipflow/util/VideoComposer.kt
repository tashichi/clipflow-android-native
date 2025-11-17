package com.tashichi.clipflow.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.SegmentTimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * VideoComposer - 複数の動画セグメントを統合してシームレス再生を実現するクラス
 *
 * iOS の AVComposition に相当する機能を Media3 で実装
 * 複数の1秒動画セグメントをギャップレスに再生・エクスポート可能にする
 */
@OptIn(UnstableApi::class)
class VideoComposer(private val context: Context) {

    companion object {
        private const val TAG = "VideoComposer"
    }

    /**
     * プロジェクトから Composition を作成
     *
     * iOS の ProjectManager.createComposition() に対応
     * 複数のセグメントを order 順にソートして統合
     *
     * @param project 対象のプロジェクト
     * @param onProgress 進捗コールバック (処理済み, 総数)
     * @return 作成された Composition (失敗時は null)
     */
    suspend fun createComposition(
        project: Project,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Composition? = withContext(Dispatchers.IO) {
        try {
            val sortedSegments = project.getSortedSegments()
            if (sortedSegments.isEmpty()) {
                Log.w(TAG, "No segments to compose")
                return@withContext null
            }

            onProgress(0, sortedSegments.size)

            // EditedMediaItem のリストを作成
            val editedMediaItems = mutableListOf<EditedMediaItem>()
            var firstRotation: Int? = null

            sortedSegments.forEachIndexed { index, segment ->
                Log.d(TAG, "[Segment $index] Processing: ${segment.uri}")

                val file = File(context.filesDir, segment.uri)
                if (!file.exists()) {
                    Log.w(TAG, "[Segment $index] File not found: ${segment.uri}")
                    return@forEachIndexed
                }

                // ファイルの読み取り可能性をチェック
                if (!file.canRead()) {
                    Log.e(TAG, "[Segment $index] File exists but cannot be read: ${segment.uri}")
                    return@forEachIndexed
                }

                Log.d(TAG, "[Segment $index] File exists: ${file.absolutePath}, size: ${file.length()} bytes")

                // 動画のメタデータを取得・検証
                val retriever = MediaMetadataRetriever()
                var isValidVideo = false
                try {
                    Log.d(TAG, "[Segment $index] Setting data source...")
                    retriever.setDataSource(file.absolutePath)
                    Log.d(TAG, "[Segment $index] Data source set successfully")

                    // 必須メタデータを取得・検証
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

                    Log.d(TAG, "[Segment $index] Metadata - Duration: $durationStr, Width: $widthStr, Height: $heightStr, Rotation: $rotationStr")

                    // Duration の検証
                    val duration = durationStr?.toLongOrNull()
                    if (duration == null || duration <= 0) {
                        Log.e(TAG, "[Segment $index] Invalid duration: $durationStr")
                        return@forEachIndexed
                    }

                    // Width/Height の検証
                    val width = widthStr?.toIntOrNull()
                    val height = heightStr?.toIntOrNull()
                    if (width == null || height == null || width <= 0 || height <= 0) {
                        Log.e(TAG, "[Segment $index] Invalid dimensions: ${width}x${height}")
                        return@forEachIndexed
                    }

                    // 回転情報を取得 (最初のセグメントの回転を基準とする)
                    if (index == 0) {
                        val rotation = rotationStr?.toIntOrNull() ?: 0
                        firstRotation = rotation
                        Log.d(TAG, "[Segment $index] First segment rotation: $rotation degrees")
                    }

                    isValidVideo = true
                    Log.d(TAG, "[Segment $index] Metadata validation successful - Duration: ${duration}ms, Size: ${width}x${height}")

                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "[Segment $index] Invalid data source (file might be corrupted or incomplete): ${segment.uri}", e)
                    return@forEachIndexed
                } catch (e: RuntimeException) {
                    Log.e(TAG, "[Segment $index] Runtime error extracting metadata: ${segment.uri}", e)
                    Log.e(TAG, "[Segment $index] Error details: ${e.message}")
                    return@forEachIndexed
                } catch (e: Exception) {
                    Log.e(TAG, "[Segment $index] Unexpected error extracting metadata: ${segment.uri}", e)
                    Log.e(TAG, "[Segment $index] Error type: ${e.javaClass.simpleName}")
                    return@forEachIndexed
                } finally {
                    try {
                        retriever.release()
                        Log.d(TAG, "[Segment $index] MediaMetadataRetriever released")
                    } catch (e: Exception) {
                        Log.e(TAG, "[Segment $index] Error releasing retriever", e)
                    }
                }

                if (!isValidVideo) {
                    Log.e(TAG, "[Segment $index] Video validation failed, skipping")
                    return@forEachIndexed
                }

                // MediaItem を作成
                try {
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    Log.d(TAG, "[Segment $index] MediaItem created")

                    // EditedMediaItem を作成
                    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                    editedMediaItems.add(editedMediaItem)
                    Log.d(TAG, "[Segment $index] EditedMediaItem added to list (total: ${editedMediaItems.size})")
                } catch (e: Exception) {
                    Log.e(TAG, "[Segment $index] Failed to create MediaItem", e)
                    return@forEachIndexed
                }

                // 進捗を通知 (最大80%まで)
                onProgress(index + 1, sortedSegments.size)

                // 視覚的なフィードバックのため少し遅延 (iOSの実装を参考)
                delay(10)
            }

            if (editedMediaItems.isEmpty()) {
                Log.w(TAG, "No valid segments to compose")
                return@withContext null
            }

            // EditedMediaItemSequence を作成
            val sequence = EditedMediaItemSequence(editedMediaItems)

            // Composition を作成
            val composition = Composition.Builder(listOf(sequence))
                .setEffects(
                    // 回転補正が必要な場合は Effects を設定
                    if (firstRotation != null && firstRotation != 0) {
                        Effects(
                            /* audioProcessors = */ emptyList(),
                            /* videoEffects = */ listOf(
                                Presentation.createForWidthAndHeight(
                                    /* width = */ C.LENGTH_UNSET,
                                    /* height = */ C.LENGTH_UNSET,
                                    /* presentationLayout = */ Presentation.LAYOUT_SCALE_TO_FIT
                                )
                            )
                        )
                    } else {
                        Effects.EMPTY
                    }
                )
                .build()

            onProgress(sortedSegments.size, sortedSegments.size)
            Log.d(TAG, "Composition created successfully with ${editedMediaItems.size} segments")

            composition
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to create composition", e)
            Log.e(TAG, "[ERROR] Exception: ${e.message}")
            Log.e(TAG, "[ERROR] Stack trace: ${e.stackTraceToString()}")
            return@withContext null
        }
    }

    /**
     * Composition を動画ファイルにエクスポート
     *
     * iOS の PlayerView.exportVideo() に対応
     * AVAssetExportSession の代わりに Transformer を使用
     *
     * @param composition エクスポートする Composition
     * @param outputFile 出力先ファイル
     * @param onProgress 進捗コールバック (0.0 ~ 1.0)
     * @return エクスポート成功時は true
     */
    suspend fun exportComposition(
        composition: Composition,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            // Transformer リスナーを定義
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.d(TAG, "Export completed successfully")
                    onProgress(1.0f)
                    continuation.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Export failed", exportException)
                    continuation.resume(false)
                }
            }

            // Transformer を作成
            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .build()

            try {
                // エクスポート開始
                transformer.start(composition, outputFile.absolutePath)

                // 進捗監視を開始
                launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            val progressHolder = ProgressHolder()
                            transformer.getProgress(progressHolder)
                            val progress = progressHolder.progress / 100f
                            onProgress(progress)

                            // 完了したら監視終了
                            if (progress >= 1.0f) {
                                break
                            }
                        } catch (e: Exception) {
                            // Transformer が既に解放されている可能性がある
                            break
                        }

                        delay(100) // 0.1秒ごとに更新
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start export", e)
                continuation.resume(false)
            }
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
