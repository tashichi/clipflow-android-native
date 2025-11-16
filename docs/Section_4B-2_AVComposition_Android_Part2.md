# セクション4B-2: AVComposition実装ガイド（Android版 Part 2）

## 概要

本ドキュメントは、iOS版ClipFlowの`AVComposition`に相当する機能をAndroid（Media3）で実装するための詳細ガイドです。Part 1で基本構造を説明し、本Part 2では以下の高度なトピックを扱います：

- ギャップレス再生のための正確な時間管理
- 大量セグメント対応（100個以上）
- ExoPlayerとの統合と再生

---

## 4.7 ギャップレス再生のための正確な時間管理

### 4.7.1 iOS版の時間管理（CMTime）

iOS版では`CMTime`を使用して高精度な時間管理を行います。

**iOS版コード（ProjectManager.swift:143-246）:**
```swift
// CMTime基本操作
var currentTime = CMTime.zero  // 0秒

// 時間範囲作成
let timeRange = CMTimeRange(start: .zero, duration: assetDuration)

// 時間加算（ギャップレスの要）
currentTime = CMTimeAdd(currentTime, assetDuration)

// 秒数取得
let totalDuration = currentTime.seconds  // Double型

// 時間範囲チェック
if CMTimeRangeContainsTime(timeRange, time: currentPlayerTime) {
    // 範囲内
}
```

### 4.7.2 Android版の時間管理（Long演算）

Android版ではマイクロ秒（μs）またはミリ秒（ms）の`Long`型で時間を管理します。

#### 時間単位の対応関係

| iOS (CMTime) | Android (Long) | 変換 |
|--------------|----------------|------|
| `CMTime.zero` | `0L` | 直接対応 |
| `CMTimeScale(NSEC_PER_SEC)` | マイクロ秒/ミリ秒 | 精度を選択 |
| `CMTime.seconds` | `timeMs / 1000.0` | ミリ秒→秒 |

#### 基本的な時間演算

```kotlin
/**
 * セグメント時間範囲を表すデータクラス
 *
 * iOS版の CMTimeRange に対応
 */
data class SegmentTimeRange(
    val segmentIndex: Int,
    val startTimeMs: Long,    // ミリ秒
    val durationMs: Long      // ミリ秒
) {
    val endTimeMs: Long get() = startTimeMs + durationMs

    /**
     * 指定時刻がこの範囲内にあるかチェック
     * iOS版: CMTimeRangeContainsTime
     */
    fun contains(timeMs: Long): Boolean {
        return timeMs >= startTimeMs && timeMs < endTimeMs
    }
}
```

#### ギャップレス時間計算の実装

```kotlin
/**
 * 各セグメントの時間範囲を正確に計算
 *
 * iOS版参考: ProjectManager.getSegmentTimeRanges()
 *
 * 重要: 累積誤差を防ぐため、Long型で厳密に計算
 */
suspend fun calculateSegmentTimeRanges(
    project: Project
): List<SegmentTimeRange> = withContext(Dispatchers.IO) {

    val sortedSegments = project.getSortedSegments()
    val timeRanges = mutableListOf<SegmentTimeRange>()

    // 現在時刻（累積）- CMTime.zero に対応
    var currentTimeMs = 0L

    sortedSegments.forEachIndexed { index, segment ->
        val file = File(context.filesDir, segment.uri)
        if (!file.exists()) {
            Log.w(TAG, "Segment $index: File not found")
            return@forEachIndexed
        }

        // 動画の正確な長さを取得
        val durationMs = getVideoDurationMs(file)

        if (durationMs <= 0) {
            Log.e(TAG, "Segment $index: Invalid duration")
            return@forEachIndexed
        }

        // 時間範囲を作成（iOS版の CMTimeRange に対応）
        val timeRange = SegmentTimeRange(
            segmentIndex = index,
            startTimeMs = currentTimeMs,
            durationMs = durationMs
        )
        timeRanges.add(timeRange)

        // デバッグログ
        Log.d(TAG, "Segment $index: ${currentTimeMs}ms - ${currentTimeMs + durationMs}ms (${durationMs}ms)")

        // 次のセグメントの開始時刻を更新（ギャップレスの核心）
        // iOS版: currentTime = CMTimeAdd(currentTime, assetDuration)
        currentTimeMs += durationMs
    }

    Log.d(TAG, "Total composition duration: ${currentTimeMs}ms")
    timeRanges
}

/**
 * 動画ファイルから正確な再生時間を取得
 *
 * 注意: MediaMetadataRetriever は use{} で確実に解放
 */
private fun getVideoDurationMs(file: File): Long {
    return MediaMetadataRetriever().use { retriever ->
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration: ${file.name}", e)
            0L
        }
    }
}
```

### 4.7.3 高精度時間管理（マイクロ秒レベル）

より高精度な時間管理が必要な場合は、マイクロ秒（μs）を使用します。

```kotlin
/**
 * マイクロ秒ベースの時間範囲
 *
 * MediaExtractor/MediaMuxer はマイクロ秒を使用
 * ExoPlayer はミリ秒を使用
 */
data class SegmentTimeRangeUs(
    val segmentIndex: Int,
    val startTimeUs: Long,    // マイクロ秒
    val durationUs: Long      // マイクロ秒
) {
    val endTimeUs: Long get() = startTimeUs + durationUs

    // ミリ秒への変換（ExoPlayer用）
    val startTimeMs: Long get() = startTimeUs / 1000
    val durationMs: Long get() = durationUs / 1000
    val endTimeMs: Long get() = endTimeUs / 1000

    fun contains(timeUs: Long): Boolean {
        return timeUs >= startTimeUs && timeUs < endTimeUs
    }

    fun containsMs(timeMs: Long): Boolean {
        return contains(timeMs * 1000)
    }
}

/**
 * MediaExtractor を使用した高精度時間取得
 */
suspend fun calculateSegmentTimeRangesHighPrecision(
    project: Project
): List<SegmentTimeRangeUs> = withContext(Dispatchers.IO) {

    val sortedSegments = project.getSortedSegments()
    val timeRanges = mutableListOf<SegmentTimeRangeUs>()
    var currentTimeUs = 0L

    sortedSegments.forEachIndexed { index, segment ->
        val file = File(context.filesDir, segment.uri)
        if (!file.exists()) return@forEachIndexed

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            // ビデオトラックを選択
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    // マイクロ秒で取得（最高精度）
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)

                    val timeRange = SegmentTimeRangeUs(
                        segmentIndex = index,
                        startTimeUs = currentTimeUs,
                        durationUs = durationUs
                    )
                    timeRanges.add(timeRange)

                    currentTimeUs += durationUs
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract duration for segment $index", e)
        } finally {
            extractor.release()
        }
    }

    timeRanges
}
```

### 4.7.4 ギャップレス再生の検証方法

```kotlin
/**
 * ギャップレス再生の検証テスト
 */
class GaplessPlaybackVerifier(private val context: Context) {

    /**
     * セグメント間のギャップを検出
     *
     * @return ギャップがあった場合はそのミリ秒数、なければ 0
     */
    suspend fun detectGaps(
        timeRanges: List<SegmentTimeRange>
    ): List<Pair<Int, Long>> {
        val gaps = mutableListOf<Pair<Int, Long>>()

        for (i in 0 until timeRanges.size - 1) {
            val current = timeRanges[i]
            val next = timeRanges[i + 1]

            val gap = next.startTimeMs - current.endTimeMs

            if (gap != 0L) {
                gaps.add(Pair(i, gap))
                Log.w(TAG, "Gap detected between segment $i and ${i + 1}: ${gap}ms")
            }
        }

        if (gaps.isEmpty()) {
            Log.d(TAG, "No gaps detected - perfect gapless playback")
        }

        return gaps
    }

    /**
     * 総再生時間の整合性を検証
     */
    fun verifyTotalDuration(
        timeRanges: List<SegmentTimeRange>,
        expectedDurationMs: Long
    ): Boolean {
        if (timeRanges.isEmpty()) return false

        val lastRange = timeRanges.last()
        val calculatedDuration = lastRange.endTimeMs

        val difference = kotlin.math.abs(calculatedDuration - expectedDurationMs)

        // 許容誤差: 10ms以内
        val isValid = difference <= 10

        Log.d(TAG, "Duration verification: " +
            "calculated=${calculatedDuration}ms, " +
            "expected=${expectedDurationMs}ms, " +
            "diff=${difference}ms, " +
            "valid=$isValid")

        return isValid
    }
}
```

### 4.7.5 時間同期のベストプラクティス

```kotlin
/**
 * 時間同期マネージャー
 *
 * ExoPlayer の再生位置とセグメントインデックスを同期
 */
class TimeSyncManager {

    private var segmentTimeRanges: List<SegmentTimeRange> = emptyList()
    private var lastUpdateTimeMs = 0L
    private var currentSegmentIndex = 0

    fun setTimeRanges(ranges: List<SegmentTimeRange>) {
        segmentTimeRanges = ranges
    }

    /**
     * 現在の再生位置からセグメントインデックスを取得
     *
     * iOS版参考: PlayerView.updateCurrentSegmentIndex()
     *
     * @param currentPositionMs ExoPlayer.currentPosition
     * @return セグメントインデックス（見つからない場合は -1）
     */
    fun getCurrentSegmentIndex(currentPositionMs: Long): Int {
        // 最後の更新から変化がなければキャッシュを使用
        if (currentPositionMs == lastUpdateTimeMs) {
            return currentSegmentIndex
        }

        lastUpdateTimeMs = currentPositionMs

        // 線形検索（セグメント数が少ない場合は十分高速）
        for ((index, range) in segmentTimeRanges.withIndex()) {
            if (range.contains(currentPositionMs)) {
                currentSegmentIndex = index
                return index
            }
        }

        // 末尾を超えた場合は最後のセグメント
        if (currentPositionMs >= segmentTimeRanges.lastOrNull()?.endTimeMs ?: 0) {
            currentSegmentIndex = segmentTimeRanges.size - 1
            return currentSegmentIndex
        }

        return -1
    }

    /**
     * セグメントインデックスから開始時刻を取得
     *
     * @param segmentIndex 対象セグメント
     * @return 開始時刻（ミリ秒）
     */
    fun getSegmentStartTime(segmentIndex: Int): Long? {
        return segmentTimeRanges.getOrNull(segmentIndex)?.startTimeMs
    }

    /**
     * プログレスバーの位置（0.0～1.0）からセグメントインデックスを取得
     *
     * iOS版参考: PlayerView.handleSeekGesture()
     */
    fun getSegmentIndexFromProgress(
        progress: Float,
        totalDurationMs: Long
    ): Int {
        val targetTimeMs = (progress * totalDurationMs).toLong()
        return getCurrentSegmentIndex(targetTimeMs)
    }
}
```

---

## 4.8 大量セグメント対応（100個以上）

### 4.8.1 前回の15セグメント制限の根本原因

Stage 2分析で特定された問題：

1. **MediaMetadataRetrieverのリソースリーク**
   - `release()` 呼び忘れによるネイティブメモリ枯渇
   - 各セグメントで新しいインスタンスを作成し解放しない

2. **メモリ圧迫**
   - EditedMediaItem を一度に全て保持
   - GCが追いつかない

3. **エラーハンドリング不足**
   - 1つのセグメントでエラーが発生すると全体が失敗

### 4.8.2 iOS版の大量セグメント処理パターン

**iOS版コード（ProjectManager.swift:249-370）:**
```swift
func createCompositionWithProgress(
    for project: Project,
    progressCallback: @escaping (Int, Int) -> Void
) async -> AVComposition? {

    let composition = AVMutableComposition()
    var currentTime = CMTime.zero
    let sortedSegments = project.segments.sorted { $0.order < $1.order }
    let totalSegments = sortedSegments.count

    for (index, segment) in sortedSegments.enumerated() {
        // 進捗コールバック
        progressCallback(index, totalSegments)

        let asset = AVURLAsset(url: fileURL)

        do {
            let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
            // ... 処理

            // 処理間隔（リソース解放時間）
            try await Task.sleep(nanoseconds: 10_000_000) // 0.01秒

        } catch {
            continue  // エラー時はスキップ
        }

        // デバッグログ（50セグメントごと）
        if (index + 1) % 50 == 0 || index == totalSegments - 1 {
            print("Processed \(index + 1)/\(totalSegments) segments")
        }
    }

    progressCallback(totalSegments, totalSegments)
    return composition
}
```

**重要なパターン:**
- 進捗コールバック
- エラー時はスキップ（continue）
- 処理間隔（リソース解放時間）
- 定期的なログ出力

### 4.8.3 Android版の大量セグメント処理実装

```kotlin
/**
 * 大量セグメント対応の Composition 作成
 *
 * 改善ポイント:
 * 1. MediaMetadataRetriever の確実な解放（use{}）
 * 2. 定期的な GC 促進
 * 3. エラー時のスキップ処理
 * 4. 進捗の詳細表示
 * 5. キャンセル対応
 */
suspend fun createCompositionForLargeProject(
    project: Project,
    onProgress: (processed: Int, total: Int, message: String) -> Unit
): Result<Composition> = withContext(Dispatchers.IO) {

    val sortedSegments = project.getSortedSegments()
    val totalSegments = sortedSegments.size

    if (sortedSegments.isEmpty()) {
        return@withContext Result.failure(IllegalArgumentException("No segments"))
    }

    Log.d(TAG, "Starting composition for $totalSegments segments")
    onProgress(0, totalSegments, "Starting...")

    val editedMediaItems = mutableListOf<EditedMediaItem>()
    var skippedCount = 0
    var firstRotation: Int? = null

    // バッチ処理設定
    val batchSize = 10  // 10セグメントごとに GC
    val gcDelay = 10L   // GC 後の待機時間（ms）

    sortedSegments.forEachIndexed { index, segment ->
        // キャンセルチェック
        if (!isActive) {
            return@withContext Result.failure(CancellationException("Cancelled"))
        }

        val file = File(context.filesDir, segment.uri)

        // ファイル存在チェック
        if (!file.exists()) {
            Log.w(TAG, "[$index] File not found: ${segment.uri}")
            skippedCount++
            return@forEachIndexed
        }

        // メタデータ取得（確実なリソース解放）
        val isValid = MediaMetadataRetriever().use { retriever ->
            try {
                retriever.setDataSource(file.absolutePath)

                // 必須メタデータの検証
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()

                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull()

                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull()

                if (duration == null || duration <= 0 ||
                    width == null || width <= 0 ||
                    height == null || height <= 0) {
                    Log.e(TAG, "[$index] Invalid metadata: duration=$duration, size=${width}x${height}")
                    false
                } else {
                    // 回転情報を保存（最初のセグメントのみ）
                    if (index == 0) {
                        firstRotation = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                        )?.toIntOrNull() ?: 0
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$index] Metadata extraction failed", e)
                false
            }
        } // retriever は自動的に release() される

        if (!isValid) {
            skippedCount++
            return@forEachIndexed
        }

        // MediaItem 作成
        try {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
            editedMediaItems.add(editedMediaItem)
        } catch (e: Exception) {
            Log.e(TAG, "[$index] Failed to create MediaItem", e)
            skippedCount++
            return@forEachIndexed
        }

        // 進捗通知
        val progressPercent = ((index + 1) * 100) / totalSegments
        onProgress(index + 1, totalSegments, "Processing: $progressPercent%")

        // 定期的なログ出力（50セグメントごと）
        if ((index + 1) % 50 == 0 || index == totalSegments - 1) {
            Log.d(TAG, "Processed ${index + 1}/$totalSegments segments " +
                  "(skipped: $skippedCount)")
        }

        // バッチ処理: GC 促進
        if ((index + 1) % batchSize == 0) {
            Log.d(TAG, "[$index] Running GC...")
            System.gc()
            delay(gcDelay)
        }
    }

    // 結果チェック
    if (editedMediaItems.isEmpty()) {
        return@withContext Result.failure(
            IllegalStateException("No valid segments (skipped: $skippedCount)")
        )
    }

    // Composition 作成
    val sequence = EditedMediaItemSequence(editedMediaItems)
    val composition = Composition.Builder(listOf(sequence))
        .setEffects(createEffects(firstRotation))
        .build()

    Log.d(TAG, "Composition created: " +
          "${editedMediaItems.size} segments, $skippedCount skipped")

    onProgress(totalSegments, totalSegments, "Complete!")
    Result.success(composition)
}

private fun createEffects(rotation: Int?): Effects {
    return if (rotation != null && rotation != 0) {
        Effects(
            emptyList(),
            listOf(
                Presentation.createForWidthAndHeight(
                    C.LENGTH_UNSET,
                    C.LENGTH_UNSET,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
        )
    } else {
        Effects.EMPTY
    }
}
```

### 4.8.4 メモリ効率的なチャンク処理

超大量セグメント（500個以上）の場合は、チャンク処理を検討します。

```kotlin
/**
 * チャンク単位でセグメントを処理
 *
 * メモリ使用量を制限しつつ大量セグメントを処理
 */
class ChunkedCompositionBuilder(private val context: Context) {

    companion object {
        private const val CHUNK_SIZE = 50  // 50セグメントずつ処理
    }

    suspend fun buildComposition(
        project: Project,
        onProgress: (Int, Int) -> Unit
    ): Result<List<EditedMediaItem>> = withContext(Dispatchers.IO) {

        val sortedSegments = project.getSortedSegments()
        val totalSegments = sortedSegments.size
        val allItems = mutableListOf<EditedMediaItem>()

        // チャンクに分割
        val chunks = sortedSegments.chunked(CHUNK_SIZE)

        chunks.forEachIndexed { chunkIndex, chunk ->
            Log.d(TAG, "Processing chunk ${chunkIndex + 1}/${chunks.size}")

            // チャンク内のアイテムを処理
            val chunkItems = processChunk(chunk, chunkIndex * CHUNK_SIZE)
            allItems.addAll(chunkItems)

            // 進捗更新
            val processed = minOf((chunkIndex + 1) * CHUNK_SIZE, totalSegments)
            onProgress(processed, totalSegments)

            // チャンク間でメモリ解放
            System.gc()
            delay(50) // GC が実行される時間を確保
        }

        Result.success(allItems)
    }

    private suspend fun processChunk(
        segments: List<VideoSegment>,
        startIndex: Int
    ): List<EditedMediaItem> {
        val items = mutableListOf<EditedMediaItem>()

        segments.forEachIndexed { localIndex, segment ->
            val globalIndex = startIndex + localIndex

            val file = File(context.filesDir, segment.uri)
            if (!file.exists()) return@forEachIndexed

            // リソースを確実に解放
            MediaMetadataRetriever().use { retriever ->
                try {
                    retriever.setDataSource(file.absolutePath)
                    val duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: return@forEachIndexed

                    if (duration <= 0) return@forEachIndexed

                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    val editedItem = EditedMediaItem.Builder(mediaItem).build()
                    items.add(editedItem)

                } catch (e: Exception) {
                    Log.e(TAG, "[$globalIndex] Error processing segment", e)
                }
            }
        }

        return items
    }
}
```

### 4.8.5 エラー回復メカニズム

```kotlin
/**
 * エラー回復付きの Composition 作成
 *
 * 失敗したセグメントをスキップし、可能な限り再生を継続
 */
sealed class CompositionResult {
    data class Success(
        val composition: Composition,
        val processedCount: Int,
        val skippedSegments: List<SkippedSegment>
    ) : CompositionResult()

    data class PartialSuccess(
        val composition: Composition,
        val processedCount: Int,
        val skippedSegments: List<SkippedSegment>,
        val warning: String
    ) : CompositionResult()

    data class Failure(
        val error: Exception,
        val skippedSegments: List<SkippedSegment>
    ) : CompositionResult()
}

data class SkippedSegment(
    val index: Int,
    val uri: String,
    val reason: String
)

suspend fun createCompositionWithRecovery(
    project: Project,
    onProgress: (Int, Int) -> Unit
): CompositionResult = withContext(Dispatchers.IO) {

    val sortedSegments = project.getSortedSegments()
    val totalSegments = sortedSegments.size

    val editedMediaItems = mutableListOf<EditedMediaItem>()
    val skippedSegments = mutableListOf<SkippedSegment>()

    sortedSegments.forEachIndexed { index, segment ->
        try {
            val file = File(context.filesDir, segment.uri)

            // 検証
            if (!file.exists()) {
                throw FileNotFoundException("File not found: ${segment.uri}")
            }

            if (!file.canRead()) {
                throw IOException("File not readable: ${segment.uri}")
            }

            if (file.length() == 0L) {
                throw IOException("File is empty: ${segment.uri}")
            }

            // メタデータ検証
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)

                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid duration")

                if (duration <= 0) {
                    throw IllegalArgumentException("Duration is 0 or negative")
                }
            }

            // MediaItem 作成
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            val editedItem = EditedMediaItem.Builder(mediaItem).build()
            editedMediaItems.add(editedItem)

        } catch (e: Exception) {
            Log.e(TAG, "[$index] Skipping segment due to error: ${e.message}")
            skippedSegments.add(
                SkippedSegment(index, segment.uri, e.message ?: "Unknown error")
            )
        }

        onProgress(index + 1, totalSegments)

        // GC 促進
        if ((index + 1) % 10 == 0) {
            System.gc()
            delay(10)
        }
    }

    // 結果判定
    when {
        editedMediaItems.isEmpty() -> {
            CompositionResult.Failure(
                IllegalStateException("No valid segments"),
                skippedSegments
            )
        }

        skippedSegments.size > totalSegments / 2 -> {
            // 半分以上スキップした場合は部分成功扱い
            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            CompositionResult.PartialSuccess(
                composition = composition,
                processedCount = editedMediaItems.size,
                skippedSegments = skippedSegments,
                warning = "More than half of segments were skipped"
            )
        }

        skippedSegments.isNotEmpty() -> {
            // 一部スキップした場合
            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            CompositionResult.Success(
                composition = composition,
                processedCount = editedMediaItems.size,
                skippedSegments = skippedSegments
            )
        }

        else -> {
            // 完全成功
            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            CompositionResult.Success(
                composition = composition,
                processedCount = editedMediaItems.size,
                skippedSegments = emptyList()
            )
        }
    }
}
```

### 4.8.6 パフォーマンス最適化のベストプラクティス

```kotlin
/**
 * パフォーマンス最適化設定
 */
object CompositionOptimizer {

    /**
     * 最適なバッチサイズを決定
     *
     * デバイスのメモリ量に基づいて調整
     */
    fun getOptimalBatchSize(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // 利用可能メモリに基づいてバッチサイズを決定
        return when {
            memoryInfo.availMem > 2_000_000_000 -> 25  // 2GB以上: 25セグメント
            memoryInfo.availMem > 1_000_000_000 -> 15  // 1GB以上: 15セグメント
            else -> 10  // それ以下: 10セグメント
        }
    }

    /**
     * メモリ使用量を監視
     */
    fun logMemoryUsage(context: Context, tag: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024

        Log.d(TAG, "[$tag] Memory: used=${usedMemory}MB, " +
              "free=${freeMemory}MB, max=${maxMemory}MB")
    }

    /**
     * ローメモリ状態をチェック
     */
    fun isLowMemory(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory
    }
}
```

---

## 4.9 ExoPlayer との統合と再生

### 4.9.1 iOS版のプレイヤー統合パターン

**iOS版コード（PlayerView.swift:846-966）:**
```swift
private func loadComposition() {
    // 1. ローディング状態開始
    isLoadingComposition = true
    loadingProgress = 0.0

    Task {
        // 2. Composition作成（進捗付き）
        guard let newComposition = await createCompositionWithProgress() else {
            // フォールバック: 個別再生モード
            useSeamlessPlayback = false
            loadCurrentSegment()
            return
        }

        // 3. セグメント時間範囲取得
        segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

        await MainActor.run {
            // 4. 既存オブザーバー削除
            removeTimeObserver()

            // 5. PlayerItem作成
            let newPlayerItem = AVPlayerItem(asset: newComposition)

            // 6. 再生完了監視登録
            NotificationCenter.default.addObserver(...)

            // 7. Player設定
            composition = newComposition
            player.replaceCurrentItem(with: newPlayerItem)
            playerItem = newPlayerItem

            // 8. 初期状態設定
            player.pause()
            isPlaying = false
            currentTime = 0
            duration = newComposition.duration.seconds

            // 9. 時間監視開始
            startTimeObserver()

            // 10. ローディング終了
            isLoadingComposition = false
        }
    }
}
```

### 4.9.2 Android版の基本統合パターン

```kotlin
/**
 * ExoPlayer統合マネージャー
 *
 * iOS版の PlayerView に対応
 */
class ExoPlayerIntegrationManager(
    private val context: Context,
    private val viewModelScope: CoroutineScope
) {

    companion object {
        private const val TAG = "ExoPlayerIntegration"
    }

    private var exoPlayer: ExoPlayer? = null
    private var composition: Composition? = null
    private var segmentTimeRanges: List<SegmentTimeRange> = emptyList()

    // 状態管理
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentTime = MutableStateFlow(0L)
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * ExoPlayer を初期化
     *
     * 最適化されたバッファ設定でメモリ使用量を削減
     */
    fun initializePlayer(): ExoPlayer {
        // 既存のプレイヤーを解放
        exoPlayer?.release()

        // メモリ最適化 LoadControl
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2000,      // 最小バッファ: 2秒
                /* maxBufferMs = */ 5000,      // 最大バッファ: 5秒
                /* bufferForPlaybackMs = */ 1000,  // 再生開始: 1秒
                /* bufferForPlaybackAfterRebufferMs = */ 2000
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        // イベントリスナーを設定
        player.addListener(createPlayerListener())

        exoPlayer = player
        return player
    }

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        Log.d(TAG, "Player: IDLE")
                    }

                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "Player: BUFFERING")
                    }

                    Player.STATE_READY -> {
                        exoPlayer?.let { player ->
                            _duration.value = player.duration
                            Log.d(TAG, "Player: READY, duration=${player.duration}ms")
                        }
                    }

                    Player.STATE_ENDED -> {
                        _isPlaying.value = false
                        Log.d(TAG, "Player: ENDED")
                        handlePlaybackEnded()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                handlePlaybackError(error)
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                // セグメント切り替え時の処理
                Log.d(TAG, "Media item transition, reason: $reason")
            }
        }
    }

    /**
     * Composition を読み込んでプレイヤーにセット
     *
     * iOS版参考: PlayerView.loadComposition()
     */
    suspend fun loadComposition(
        project: Project,
        videoComposer: VideoComposer
    ) {
        _isLoading.value = true

        try {
            // 1. Composition を作成
            Log.d(TAG, "Creating composition...")
            composition = videoComposer.createComposition(project) { processed, total ->
                Log.d(TAG, "Progress: $processed/$total")
            }

            if (composition == null) {
                Log.e(TAG, "Failed to create composition")
                // フォールバック: 個別セグメント再生
                loadIndividualSegments(project)
                return
            }

            // 2. セグメント時間範囲を取得
            segmentTimeRanges = videoComposer.getSegmentTimeRanges(project)
            Log.d(TAG, "Time ranges: ${segmentTimeRanges.size}")

            // 3. ExoPlayer にセグメントをロード
            loadSegmentsToExoPlayer(project)

            // 4. 総再生時間を設定
            _duration.value = videoComposer.getTotalDuration(project)

            // 5. 時間監視を開始
            startTimeObserver()

            // 6. 初期状態
            _currentTime.value = 0
            _currentSegmentIndex.value = 0
            _isPlaying.value = false

            Log.d(TAG, "Composition loaded successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load composition", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * セグメントを ExoPlayer にロード
     *
     * ExoPlayer のプレイリスト機能を使用してシームレス再生を実現
     */
    private fun loadSegmentsToExoPlayer(project: Project) {
        val player = exoPlayer ?: return

        val sortedSegments = project.getSortedSegments()
        val mediaItems = sortedSegments.mapNotNull { segment ->
            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                MediaItem.fromUri(Uri.fromFile(file))
            } else {
                Log.w(TAG, "File not found: ${segment.uri}")
                null
            }
        }

        if (mediaItems.isEmpty()) {
            Log.e(TAG, "No valid media items")
            return
        }

        // プレイリストとしてセット
        player.setMediaItems(mediaItems)
        player.prepare()

        Log.d(TAG, "Loaded ${mediaItems.size} segments to player")
    }

    /**
     * フォールバック: 個別セグメント読み込み
     */
    private fun loadIndividualSegments(project: Project) {
        Log.d(TAG, "Falling back to individual segment playback")
        loadSegmentsToExoPlayer(project)
    }

    /**
     * 時間監視を開始
     *
     * iOS版参考: PlayerView.startTimeObserver()
     * 0.1秒間隔で現在時刻とセグメントインデックスを更新
     */
    private fun startTimeObserver() {
        viewModelScope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    // 現在時刻を更新
                    _currentTime.value = player.currentPosition

                    // 現在のセグメントインデックスを更新
                    updateCurrentSegmentIndex(player.currentPosition)
                }

                delay(100) // 0.1秒間隔
            }
        }
    }

    /**
     * 現在の再生位置からセグメントインデックスを判定
     *
     * iOS版参考: PlayerView.updateCurrentSegmentIndex()
     */
    private fun updateCurrentSegmentIndex(positionMs: Long) {
        segmentTimeRanges.forEachIndexed { index, range ->
            if (range.contains(positionMs)) {
                if (_currentSegmentIndex.value != index) {
                    _currentSegmentIndex.value = index
                    Log.d(TAG, "Current segment: $index")
                }
                return
            }
        }
    }

    private fun handlePlaybackEnded() {
        Log.d(TAG, "Playback ended")
        // 必要に応じてループ再生や UI 更新
    }

    private fun handlePlaybackError(error: PlaybackException) {
        Log.e(TAG, "Playback error code: ${error.errorCode}")
        // エラー処理
    }

    /**
     * リソース解放
     */
    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        composition = null
        segmentTimeRanges = emptyList()
        Log.d(TAG, "Resources released")
    }
}
```

### 4.9.3 シームレス再生の実装

```kotlin
/**
 * シームレス再生コントローラー
 *
 * ExoPlayer のプレイリスト機能を使用してギャップレス再生を実現
 */
class SeamlessPlaybackController(
    private val exoPlayer: ExoPlayer
) {

    companion object {
        private const val TAG = "SeamlessPlayback"
    }

    private var segmentTimeRanges: List<SegmentTimeRange> = emptyList()

    fun setTimeRanges(ranges: List<SegmentTimeRange>) {
        segmentTimeRanges = ranges
    }

    /**
     * 指定セグメントにシーク
     *
     * iOS版参考: PlayerView.handleSeekGesture()
     *
     * @param segmentIndex セグメントインデックス
     */
    fun seekToSegment(segmentIndex: Int) {
        if (segmentIndex !in segmentTimeRanges.indices) {
            Log.w(TAG, "Invalid segment index: $segmentIndex")
            return
        }

        val targetRange = segmentTimeRanges[segmentIndex]
        val targetTimeMs = targetRange.startTimeMs

        // ExoPlayer にシーク
        // プレイリストモードでは、MediaItem インデックスと再生位置を指定
        exoPlayer.seekTo(segmentIndex, 0)

        Log.d(TAG, "Seeked to segment $segmentIndex (${targetTimeMs}ms)")
    }

    /**
     * プログレスバーの位置（0.0～1.0）にシーク
     *
     * @param progress 進捗（0.0～1.0）
     */
    fun seekToProgress(progress: Float) {
        val totalDuration = segmentTimeRanges.lastOrNull()?.endTimeMs ?: 0L
        val targetTimeMs = (progress * totalDuration).toLong()

        // 対応するセグメントを特定
        val targetIndex = segmentTimeRanges.indexOfFirst { it.contains(targetTimeMs) }

        if (targetIndex != -1) {
            val range = segmentTimeRanges[targetIndex]
            val localPosition = targetTimeMs - range.startTimeMs

            // セグメント内の位置にシーク
            exoPlayer.seekTo(targetIndex, localPosition)

            Log.d(TAG, "Seeked to progress $progress " +
                  "(segment $targetIndex, local ${localPosition}ms)")
        }
    }

    /**
     * 次のセグメントに移動
     */
    fun nextSegment() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            Log.d(TAG, "Moved to next segment")
        }
    }

    /**
     * 前のセグメントに移動
     */
    fun previousSegment() {
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
            Log.d(TAG, "Moved to previous segment")
        }
    }

    /**
     * 再生速度を設定
     *
     * @param speed 再生速度（0.5, 1.0, 1.5, 2.0 など）
     */
    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
        Log.d(TAG, "Playback speed set to ${speed}x")
    }
}
```

### 4.9.4 UI統合（Jetpack Compose）

```kotlin
/**
 * PlayerScreen - Jetpack Compose での ExoPlayer 統合
 *
 * iOS版参考: PlayerView.swift
 */
@Composable
fun PlayerScreen(
    project: Project,
    viewModel: PlayerViewModel = viewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ExoPlayer インスタンスを remember
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 5000, 1000, 2000)
                    .build()
            )
            .build()
    }

    // ViewModel から状態を収集
    val isLoading by viewModel.isLoading.collectAsState()
    val currentTime by viewModel.currentTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val currentSegmentIndex by viewModel.currentSegmentIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()

    // 初期化
    LaunchedEffect(project) {
        viewModel.initialize(context)
        viewModel.setProject(project)
    }

    // ライフサイクル管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // 必要に応じて再開
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 動画プレイヤー
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                // ローディング表示
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = loadingProgress,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading composition... ${(loadingProgress * 100).toInt()}%",
                        color = Color.White
                    )
                }
            } else {
                // ExoPlayer 表示
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false  // カスタムコントローラーを使用
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // プログレスバー
        ProgressBar(
            currentTime = currentTime,
            duration = duration,
            segmentCount = project.segmentCount,
            currentSegmentIndex = currentSegmentIndex,
            onSeek = { progress ->
                val targetTime = (progress * duration).toLong()
                viewModel.seekTo(targetTime)
            }
        )

        // コントロールパネル
        PlayerControls(
            isPlaying = isPlaying,
            onPlayPause = { viewModel.togglePlayback() },
            onPrevious = { viewModel.previousSegment() },
            onNext = { viewModel.nextSegment() }
        )
    }
}

@Composable
private fun ProgressBar(
    currentTime: Long,
    duration: Long,
    segmentCount: Int,
    currentSegmentIndex: Int,
    onSeek: (Float) -> Unit
) {
    val progress = if (duration > 0) {
        (currentTime.toFloat() / duration.toFloat())
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // プログレスバー
        Slider(
            value = progress,
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Blue
            )
        )

        // 時間表示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentTime),
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                text = "Segment: ${currentSegmentIndex + 1}/$segmentCount",
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                text = formatTime(duration),
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前へ
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        // 再生/一時停止
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) {
                    Icons.Default.Pause
                } else {
                    Icons.Default.PlayArrow
                },
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
        }

        // 次へ
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
```

### 4.9.5 トラブルシューティング

#### 問題1: 再生が途切れる

**症状:** セグメント間で一瞬止まる、または音が途切れる

**原因と解決策:**

```kotlin
// 原因1: バッファ不足
// 解決: LoadControl でバッファを増やす
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        3000,  // minBufferMs を増やす
        10000, // maxBufferMs を増やす
        1500,  // bufferForPlaybackMs を増やす
        3000
    )
    .build()

// 原因2: コーデック再初期化
// 解決: 全セグメントを同じ設定で録画
// フレームレート、ビットレート、解像度を統一

// 原因3: オーディオフォーカス競合
// 解決: AudioAttributes を適切に設定
val audioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

exoPlayer.setAudioAttributes(audioAttributes, true)
```

#### 問題2: メモリ不足（OOM）

**症状:** 大量セグメント処理時にクラッシュ

**解決策:**

```kotlin
// 1. バッチ処理でメモリ解放
segments.chunked(10).forEach { chunk ->
    processChunk(chunk)
    System.gc()
    delay(50)
}

// 2. リソースの確実な解放
MediaMetadataRetriever().use { retriever ->
    // 処理
} // 自動 release

// 3. メモリ監視
if (CompositionOptimizer.isLowMemory(context)) {
    Log.w(TAG, "Low memory, reducing batch size")
    // 処理を中断または最適化
}
```

#### 問題3: シーク不正確

**症状:** シーク後、期待した位置と違う

**解決策:**

```kotlin
// 1. キーフレームを考慮
exoPlayer.seekTo(
    targetMediaItemIndex,
    targetPositionMs
)
// ExoPlayer は自動的に最も近いキーフレームにシーク

// 2. 正確なシーク（処理重い）
exoPlayer.setSeekParameters(SeekParameters.EXACT)

// 3. シーク完了を待つ
exoPlayer.addListener(object : Player.Listener {
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        // シーク完了
    }
})
```

#### 問題4: 動画が黒くなる

**症状:** 再生中に画面が黒くなる

**解決策:**

```kotlin
// 1. Surface の状態を確認
AndroidView(
    factory = { ctx ->
        PlayerView(ctx).apply {
            player = exoPlayer
            keepScreenOn = true  // 画面をオンに保つ
            setShutterBackgroundColor(Color.BLACK)
        }
    }
)

// 2. ライフサイクルでプレイヤーを適切に管理
DisposableEffect(lifecycleOwner) {
    onDispose {
        // PlayerView の Surface を解放しない
        // exoPlayer.clearVideoSurface() を呼ばない
    }
}

// 3. エラー処理
exoPlayer.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Player error", error)
        // 再初期化を検討
    }
})
```

### 4.9.6 パフォーマンスベストプラクティス

```kotlin
/**
 * ExoPlayer 最適化チェックリスト
 */
object ExoPlayerOptimization {

    /**
     * メモリ最適化設定
     */
    fun createOptimizedPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2000, 5000, 1000, 2000)
                    .setTargetBufferBytes(C.LENGTH_UNSET)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setReleaseTimeoutMs(5000) // 解放タイムアウト
            .build()
    }

    /**
     * CPU 使用率削減
     */
    fun configureForLowCpu(exoPlayer: ExoPlayer) {
        // ハードウェアアクセラレーション優先
        exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
    }

    /**
     * バッテリー最適化
     */
    fun configureForBattery(exoPlayer: ExoPlayer) {
        // 画面オフ時は音声のみ
        exoPlayer.setWakeMode(C.WAKE_MODE_LOCAL)
    }
}
```

---

## まとめ

本ガイドでは、Android版ClipFlowにおけるAVComposition相当機能の実装について、以下の重要ポイントを解説しました：

### ギャップレス再生の時間管理

- iOS の CMTime に対応する Long 型演算（ミリ秒/マイクロ秒）
- `SegmentTimeRange` による正確な時間範囲管理
- 累積誤差を防ぐ厳密な時間計算

### 大量セグメント対応

- `MediaMetadataRetriever.use{}` による確実なリソース解放
- バッチ処理と定期的な GC によるメモリ管理
- エラー回復メカニズムによる堅牢性向上

### ExoPlayer 統合

- プレイリスト機能を活用したシームレス再生
- LoadControl によるメモリ最適化
- Jetpack Compose との UI 統合

これらのパターンを適用することで、iOS版と同等の高品質な動画編集・再生体験をAndroidで実現できます。

---

**次のステップ:**
- Part 3: エクスポート機能の実装
- Part 4: 高度な編集機能（トリミング、エフェクト）
- Part 5: パフォーマンステストと最適化
