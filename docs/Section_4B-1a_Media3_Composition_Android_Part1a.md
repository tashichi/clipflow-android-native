# セクション4B-1a: Media3 Composition基本実装ガイド（Android Part 1-a）

## 1. 概要

### 1.1 Media3 Composition.Builder とは何か

Media3 Composition.Builderは、Androidでビデオセグメントをプログラマティックに統合するためのAPIです。Google Jetpack Media3ライブラリの一部として提供されており、複数の動画ファイルを1つの連続したメディアとして処理できます。

#### 主な機能

| 機能 | 説明 |
|------|------|
| セグメント統合 | 複数の動画ファイルを順序通りに連結 |
| メディア編集 | トリミング、フィルター適用、エフェクト追加 |
| エンコーディング | 統合したメディアを新しいファイルとして出力 |
| HDR対応 | HDRコンテンツの処理をサポート |

#### 技術的特徴

```kotlin
// Media3 Composition の基本概念
val composition = Composition.Builder(
    EditedMediaItemSequence(
        listOf(editedMediaItem1, editedMediaItem2, editedMediaItem3)
    )
).build()
```

- **EditedMediaItem**: 個々の動画セグメントを表現
- **EditedMediaItemSequence**: セグメントの連続を定義
- **Composition**: 全体の統合を管理

---

### 1.2 iOS版AVCompositionとの対応関係

| iOS版 (AVFoundation) | Android版 (Media3) | 役割 |
|---------------------|-------------------|------|
| AVMutableComposition | Composition.Builder | セグメント統合の管理 |
| composition.addMutableTrack() | EditedMediaItemSequence | トラック管理 |
| videoTrack.insertTimeRange() | EditedMediaItem | セグメント追加 |
| CMTime | Long (マイクロ秒) | 時間管理 |
| AVURLAsset | MediaItem | メディアアセット |
| AVAssetTrack | MediaExtractor | トラック情報取得 |
| preferredTransform | Matrix/Rotation | 回転情報 |
| AVAssetExportSession | Transformer | エクスポート処理 |

#### iOS版のAVCompositionパターン（参考）

```swift
// iOS版: ProjectManager.swift:143-159
func createComposition(for project: Project) async -> AVComposition? {
    let composition = AVMutableComposition()

    guard let videoTrack = composition.addMutableTrack(
        withMediaType: .video,
        preferredTrackID: kCMPersistentTrackID_Invalid
    ) else { return nil }

    var currentTime = CMTime.zero

    for segment in sortedSegments {
        let asset = AVURLAsset(url: fileURL)
        let assetDuration = try await asset.load(.duration)
        let timeRange = CMTimeRange(start: .zero, duration: assetDuration)

        try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
        currentTime = CMTimeAdd(currentTime, assetDuration)
    }

    return composition
}
```

#### Android版の対応実装

```kotlin
// Android版: Composition.Builderを使用
suspend fun createComposition(project: Project, context: Context): Composition {
    val editedMediaItems = mutableListOf<EditedMediaItem>()

    val sortedSegments = project.segments.sortedBy { it.order }

    sortedSegments.forEach { segment ->
        val file = File(context.filesDir, segment.uri)
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
        editedMediaItems.add(editedMediaItem)
    }

    return Composition.Builder(
        EditedMediaItemSequence(editedMediaItems)
    ).build()
}
```

---

### 1.3 Android版での利点・制約

#### 利点

| 利点 | 説明 |
|------|------|
| Google公式サポート | Jetpack Media3はGoogleが積極的にメンテナンス |
| 豊富なエフェクト | フィルター、エフェクト、トランジションをサポート |
| HDR対応 | 最新のHDRコンテンツに対応 |
| ExoPlayerとの統合 | Media3 ExoPlayerとシームレスに連携 |
| 非同期処理 | Kotlin Coroutinesと親和性が高い |

#### 制約

| 制約 | 説明 | iOS版との違い |
|------|------|-------------|
| ファイル出力必須 | Compositionを直接再生できない、Transformerで出力が必要 | iOS版は仮想アセットとして直接再生可能 |
| 処理時間 | 実際にエンコーディングが必要なため時間がかかる | iOS版はメタデータのみ処理 |
| ストレージ使用量 | 一時ファイルの出力に追加ストレージが必要 | iOS版は追加ストレージ不要 |
| API レベル要件 | API 21以上（推奨はAPI 26以上） | iOS 14.0以上 |

#### iOS版との重要な違い

```swift
// iOS版: AVComposition は仮想アセット（直接再生可能）
let composition = AVMutableComposition()
// ... セグメント追加 ...
let playerItem = AVPlayerItem(asset: composition)  // ✅ 直接再生
player.replaceCurrentItem(with: playerItem)
```

```kotlin
// Android版: Composition は設計図（Transformer経由で出力が必要）
val composition = Composition.Builder(sequence).build()

// Transformer で実際のファイルに変換
val transformer = Transformer.Builder(context).build()
transformer.start(composition, outputFilePath)  // ✅ ファイル出力必須

// 出力されたファイルを再生
val mediaItem = MediaItem.fromUri(outputFileUri)
exoPlayer.setMediaItem(mediaItem)
```

---

## 2. 基本的な使用方法

### 2.1 依存関係の追加

#### build.gradle (Module: app)

```groovy
dependencies {
    // Media3 Transformer (Composition統合用)
    implementation "androidx.media3:media3-transformer:1.2.0"
    implementation "androidx.media3:media3-effect:1.2.0"
    implementation "androidx.media3:media3-common:1.2.0"

    // Media3 ExoPlayer (再生用)
    implementation "androidx.media3:media3-exoplayer:1.2.0"
    implementation "androidx.media3:media3-ui:1.2.0"

    // Kotlin Coroutines (非同期処理用)
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}
```

#### バージョン管理（推奨）

```groovy
// gradle.properties
media3Version=1.2.0
coroutinesVersion=1.7.3

// build.gradle
dependencies {
    implementation "androidx.media3:media3-transformer:${media3Version}"
    implementation "androidx.media3:media3-effect:${media3Version}"
    implementation "androidx.media3:media3-common:${media3Version}"
    implementation "androidx.media3:media3-exoplayer:${media3Version}"
    implementation "androidx.media3:media3-ui:${media3Version}"
}
```

---

### 2.2 Composition.Builder の初期化

#### 最小構成の初期化

```kotlin
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

// ✅ 最小構成: 単一セグメントのComposition
fun createMinimalComposition(videoPath: String): Composition {
    // 1. MediaItem を作成
    val mediaItem = MediaItem.fromUri(videoPath)

    // 2. EditedMediaItem を作成
    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

    // 3. EditedMediaItemSequence を作成
    val sequence = EditedMediaItemSequence(listOf(editedMediaItem))

    // 4. Composition を構築
    val composition = Composition.Builder(sequence).build()

    return composition
}
```

#### iOS版との対応

```swift
// iOS版: ProjectManager.swift:147-159
let composition = AVMutableComposition()
guard let videoTrack = composition.addMutableTrack(
    withMediaType: .video,
    preferredTrackID: kCMPersistentTrackID_Invalid
) else { return nil }
```

```kotlin
// Android版: トラックは自動管理
val composition = Composition.Builder(sequence).build()
// ✅ ビデオトラック・音声トラックは自動的に処理される
```

---

### 2.3 最小構成のコード例

#### 完全な最小構成例

```kotlin
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class VideoComposer(private val context: Context) {

    /**
     * 複数のセグメントを統合してファイルに出力
     *
     * iOS版の createComposition() に相当
     * 実装箇所: ProjectManager.swift:143-246
     */
    suspend fun composeSegments(
        segmentPaths: List<String>,
        outputPath: String
    ): Result<File> {
        return try {
            // 1. EditedMediaItem のリストを作成
            val editedMediaItems = segmentPaths.map { path ->
                val mediaItem = MediaItem.fromUri(Uri.parse(path))
                EditedMediaItem.Builder(mediaItem).build()
            }

            // 2. Composition を構築
            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(sequence).build()

            // 3. Transformer で出力
            val outputFile = File(outputPath)
            transformComposition(composition, outputFile)

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Composition をファイルに変換
     */
    private suspend fun transformComposition(
        composition: Composition,
        outputFile: File
    ): Unit = suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition,
                exportResult: androidx.media3.transformer.ExportResult
            ) {
                continuation.resume(Unit)
            }

            override fun onError(
                composition: Composition,
                exportResult: androidx.media3.transformer.ExportResult,
                exportException: androidx.media3.transformer.ExportException
            ) {
                continuation.cancel(exportException)
            }
        })

        transformer.start(composition, outputFile.absolutePath)
    }
}
```

#### 使用例

```kotlin
// ViewModel での使用例
class ProjectViewModel(
    private val context: Context,
    private val repository: ProjectRepository
) : ViewModel() {

    private val videoComposer = VideoComposer(context)

    fun createCompositionForProject(project: Project) {
        viewModelScope.launch {
            val segmentPaths = project.segments
                .sortedBy { it.order }
                .map { segment ->
                    File(context.filesDir, segment.uri).absolutePath
                }

            val outputPath = File(
                context.cacheDir,
                "composition_${System.currentTimeMillis()}.mp4"
            ).absolutePath

            val result = videoComposer.composeSegments(segmentPaths, outputPath)

            result.onSuccess { file ->
                Log.d("ProjectViewModel", "Composition created: ${file.absolutePath}")
            }.onFailure { error ->
                Log.e("ProjectViewModel", "Composition failed", error)
            }
        }
    }
}
```

---

## 3. セグメント統合ロジック（Android版）

### 3.1 単一セグメントの追加

#### 基本パターン

```kotlin
/**
 * 単一セグメントを Composition に追加
 *
 * iOS版の videoTrack.insertTimeRange() に相当
 * 実装箇所: ProjectManager.swift:191-196
 */
fun createSingleSegmentComposition(segmentPath: String): Composition {
    // 1. MediaItem を作成
    val mediaItem = MediaItem.fromUri(Uri.parse(segmentPath))

    // 2. EditedMediaItem を作成（オプションで編集設定を追加）
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        // .setRemoveAudio(false)  // 音声を保持
        // .setRemoveVideo(false)  // ビデオを保持
        .build()

    // 3. シーケンスに追加
    val sequence = EditedMediaItemSequence(listOf(editedMediaItem))

    // 4. Composition を構築
    return Composition.Builder(sequence).build()
}
```

#### iOS版との対応

```swift
// iOS版: 単一セグメントの追加
let asset = AVURLAsset(url: fileURL)
let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
let assetDuration = try await asset.load(.duration)

let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
```

```kotlin
// Android版: 単一セグメントの追加
val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
// ✅ 時間範囲は自動的に処理される（セグメント全体が使用される）
```

---

### 3.2 複数セグメントの統合

#### iOS版の insertTimeRange() に対応するKotlinコード

```kotlin
/**
 * 複数セグメントを順序通り統合
 *
 * iOS版の createComposition() に相当
 * 実装箇所: ProjectManager.swift:161-238
 *
 * iOS版では insertTimeRange() を使用してセグメントを順次追加するが、
 * Android版では EditedMediaItemSequence にリストとして渡す
 */
suspend fun createMultiSegmentComposition(
    project: Project,
    context: Context
): Composition {
    // ✅ iOS版と同様にセグメントをソート
    val sortedSegments = project.segments.sortedBy { it.order }

    val editedMediaItems = mutableListOf<EditedMediaItem>()

    // ✅ 各セグメントをループで追加
    sortedSegments.forEachIndexed { index, segment ->
        // 1. ファイルURL構築（iOS版の処理に対応）
        val file = File(context.filesDir, segment.uri)

        // 2. ファイル存在確認
        if (!file.exists()) {
            Log.w("Composition", "Segment file not found: ${segment.uri}")
            return@forEachIndexed  // スキップして次へ（iOS版の continue に相当）
        }

        // 3. MediaItem を作成
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))

        // 4. EditedMediaItem を作成
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        // 5. リストに追加
        editedMediaItems.add(editedMediaItem)

        Log.d("Composition", "Added segment ${index + 1}: ${segment.uri}")
    }

    // 6. シーケンスを作成
    val sequence = EditedMediaItemSequence(editedMediaItems)

    // 7. Composition を構築
    return Composition.Builder(sequence).build()
}
```

#### iOS版との比較

| iOS版 | Android版 | 説明 |
|-------|----------|------|
| `sortedSegments.sorted { $0.order < $1.order }` | `sortedSegments.sortedBy { it.order }` | セグメントのソート |
| `for (index, segment) in sortedSegments.enumerated()` | `sortedSegments.forEachIndexed { index, segment -> }` | ループ処理 |
| `FileManager.default.fileExists()` | `file.exists()` | ファイル存在確認 |
| `AVURLAsset(url: fileURL)` | `MediaItem.fromUri()` | アセット作成 |
| `try videoTrack.insertTimeRange()` | `editedMediaItems.add()` | セグメント追加 |
| `currentTime = CMTimeAdd(currentTime, assetDuration)` | 自動（リスト順） | 時間管理 |

---

### 3.3 時間管理の正確さ

#### iOS版のCMTime計算方法

```swift
// iOS版: ProjectManager.swift:161, 232
var currentTime = CMTime.zero

for segment in sortedSegments {
    let assetDuration = try await asset.load(.duration)

    // セグメントを currentTime の位置に挿入
    try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)

    // 次のセグメントの開始位置を更新
    currentTime = CMTimeAdd(currentTime, assetDuration)
}

// 結果: currentTime = 総再生時間
let totalDuration = currentTime.seconds  // Double型（秒）
```

#### Android版の対応実装

```kotlin
/**
 * セグメントの総再生時間を計算
 *
 * iOS版の currentTime 計算に相当
 * Media3では自動的に順次配置されるため、
 * 総時間の計算は別途行う必要がある
 */
suspend fun calculateTotalDuration(
    project: Project,
    context: Context
): Long {
    var totalDurationUs = 0L  // マイクロ秒

    val sortedSegments = project.segments.sortedBy { it.order }

    sortedSegments.forEach { segment ->
        val file = File(context.filesDir, segment.uri)

        if (file.exists()) {
            // MediaMetadataRetriever で duration を取得
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                totalDurationUs += durationMs * 1000  // ミリ秒 → マイクロ秒
            }
        }
    }

    return totalDurationUs
}

// 使用例
val totalDurationUs = calculateTotalDuration(project, context)
val totalDurationMs = totalDurationUs / 1000  // ミリ秒に変換
val totalDurationSeconds = totalDurationMs / 1000.0  // 秒に変換

Log.d("Composition", "Total duration: ${totalDurationSeconds}s")
```

#### iOS版との違いと同じ結果を得るための工夫

##### 単位変換の対応表

| iOS版 (CMTime) | Android版 | 説明 |
|---------------|-----------|------|
| CMTime.zero | 0L | 時刻ゼロ |
| CMTimeAdd(a, b) | a + b | 時間の加算 |
| time.seconds | timeUs / 1_000_000.0 | 秒に変換 |
| preferredTimescale: 600 | マイクロ秒 (1/1,000,000) | 精度 |

##### ミリ秒とマイクロ秒の単位変換

```kotlin
/**
 * 時間単位変換ユーティリティ
 *
 * iOS版の CMTime に相当する正確な時間計算
 */
object TimeUtils {
    // ミリ秒 → マイクロ秒
    fun msToUs(ms: Long): Long = ms * 1000

    // マイクロ秒 → ミリ秒
    fun usToMs(us: Long): Long = us / 1000

    // マイクロ秒 → 秒（Double）
    fun usToSeconds(us: Long): Double = us / 1_000_000.0

    // 秒 → マイクロ秒
    fun secondsToUs(seconds: Double): Long = (seconds * 1_000_000).toLong()

    // iOS版の CMTimeAdd に相当
    fun addDuration(currentUs: Long, durationUs: Long): Long {
        return currentUs + durationUs
    }
}

// 使用例
var currentTimeUs = 0L

sortedSegments.forEach { segment ->
    val durationMs = getDurationMs(segment)
    val durationUs = TimeUtils.msToUs(durationMs)

    Log.d("Segment", "Start: ${TimeUtils.usToSeconds(currentTimeUs)}s")
    Log.d("Segment", "Duration: ${TimeUtils.usToSeconds(durationUs)}s")

    // iOS版の CMTimeAdd に相当
    currentTimeUs = TimeUtils.addDuration(currentTimeUs, durationUs)

    Log.d("Segment", "Next start: ${TimeUtils.usToSeconds(currentTimeUs)}s")
}

// 総再生時間
val totalSeconds = TimeUtils.usToSeconds(currentTimeUs)
Log.d("Composition", "Total duration: ${totalSeconds}s")
```

#### 時間管理のデバッグ出力（iOS版と同等）

```kotlin
/**
 * セグメント統合時のデバッグ出力
 *
 * iOS版のログ出力パターンに対応:
 * Video track added: Segment 1 at 0.0s
 * Current composition time: 1.0s
 */
suspend fun createCompositionWithLogging(
    project: Project,
    context: Context
): Composition {
    val sortedSegments = project.segments.sortedBy { it.order }
    val editedMediaItems = mutableListOf<EditedMediaItem>()

    var currentTimeUs = 0L

    sortedSegments.forEachIndexed { index, segment ->
        val file = File(context.filesDir, segment.uri)

        if (!file.exists()) {
            Log.w("Composition", "Segment file not found: ${segment.uri}")
            return@forEachIndexed
        }

        // Duration を取得
        val durationUs = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 1000L
            durationMs * 1000
        }

        // MediaItem を作成
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
        editedMediaItems.add(editedMediaItem)

        // ✅ iOS版と同等のログ出力
        Log.d("Composition",
            "Video track added: Segment ${segment.order} at ${TimeUtils.usToSeconds(currentTimeUs)}s")

        // 次のセグメントの開始位置を更新
        currentTimeUs = TimeUtils.addDuration(currentTimeUs, durationUs)

        Log.d("Composition",
            "Current composition time: ${TimeUtils.usToSeconds(currentTimeUs)}s")
    }

    val sequence = EditedMediaItemSequence(editedMediaItems)
    val composition = Composition.Builder(sequence).build()

    // ✅ 最終結果のログ
    Log.d("Composition", "Composition created successfully")
    Log.d("Composition", "Total duration: ${TimeUtils.usToSeconds(currentTimeUs)}s")
    Log.d("Composition", "Total segments processed: ${editedMediaItems.size}")

    return composition
}
```

---

## 4. メタデータ取得とエラーハンドリング

### 4.1 MediaMetadataRetriever の正しい使用方法

#### 初期化・クローズのタイミング

```kotlin
/**
 * MediaMetadataRetriever の正しい使用パターン
 *
 * iOS版の AVURLAsset.load() に相当
 * 実装箇所: ProjectManager.swift:187-189
 *
 * 重要: リソースリーク防止のため use {} を必ず使用
 */

// ❌ 間違った使い方（リソースリーク）
fun getMetadataBad(path: String): Pair<Int, Int> {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(path)
    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
    // ❌ release() を忘れている！
    return Pair(width, height)
}

// ✅ 正しい使い方（use {} で自動解放）
fun getMetadataGood(path: String): Pair<Int, Int> {
    return MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(path)
        val width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        Pair(width, height)
    } // ✅ 自動的に release() が呼ばれる
}
```

#### try-catch でのエラーハンドリング

```kotlin
/**
 * メタデータ取得の安全なパターン
 *
 * iOS版の do-catch に相当:
 * do {
 *     let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
 * } catch {
 *     print("Failed to add segment: \(error)")
 * }
 */
data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val rotation: Int,
    val bitrate: Int?
)

fun getVideoMetadata(path: String): Result<VideoMetadata> {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path)

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: throw IllegalStateException("Width not available")

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: throw IllegalStateException("Height not available")

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: throw IllegalStateException("Duration not available")

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull()

            Result.success(VideoMetadata(width, height, durationMs, rotation, bitrate))
        }
    } catch (e: Exception) {
        Log.e("Metadata", "Failed to retrieve metadata: ${e.message}")
        Result.failure(e)
    }
}
```

#### リソースリークの防止パターン

```kotlin
/**
 * 大量セグメント処理時のリソース管理
 *
 * iOS版の問題対策に相当:
 * Stage2_Analysis_Results.md の MediaMetadataRetrieverのリソースリーク対策
 *
 * iOS版では AVURLAsset が自動解放されるが、
 * Android版では明示的な release() が必要
 */
suspend fun processSegmentsWithProperResourceManagement(
    segments: List<VideoSegment>,
    context: Context,
    onProgress: (Int, Int) -> Unit
): List<VideoMetadata> {
    val metadataList = mutableListOf<VideoMetadata>()

    segments.forEachIndexed { index, segment ->
        // 進捗通知
        onProgress(index, segments.size)

        val file = File(context.filesDir, segment.uri)

        if (!file.exists()) {
            Log.w("Processing", "Segment not found: ${segment.uri}")
            return@forEachIndexed
        }

        // ✅ 各セグメント処理後に即座にリソース解放
        val metadataResult = getVideoMetadata(file.absolutePath)

        metadataResult.onSuccess { metadata ->
            metadataList.add(metadata)
            Log.d("Processing", "Processed segment ${index + 1}: ${metadata.durationMs}ms")
        }.onFailure { error ->
            Log.e("Processing", "Failed to process segment ${index + 1}", error)
        }

        // ✅ GC促進（15セグメント問題対策）
        if ((index + 1) % 10 == 0) {
            System.gc()
            kotlinx.coroutines.delay(10)
        }
    }

    onProgress(segments.size, segments.size)
    return metadataList
}
```

---

### 4.2 幅・高さ取得エラーの防止

#### 安全な取得方法

```kotlin
/**
 * 安全なメタデータ取得
 *
 * iOS版では:
 * let naturalSize = assetVideoTrack.naturalSize
 * が失敗することは稀だが、Android版では null チェックが必須
 */
data class VideoDimensions(
    val width: Int,
    val height: Int
)

fun getVideoDimensionsSafe(path: String): VideoDimensions {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path)

            // ✅ null安全な取得
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()

            if (width == null || height == null) {
                Log.w("Dimensions", "Could not extract dimensions, using defaults")
                return@use getDefaultDimensions()
            }

            VideoDimensions(width, height)
        }
    } catch (e: Exception) {
        Log.e("Dimensions", "Error extracting dimensions", e)
        getDefaultDimensions()
    }
}
```

#### フォールバック値の設定（デフォルト値）

```kotlin
/**
 * デフォルト値を返すフォールバック処理
 *
 * iOS版では明示的なフォールバックがないが、
 * Android版では必須
 */

private fun getDefaultDimensions(): VideoDimensions {
    // ✅ 一般的な縦向き動画サイズ
    return VideoDimensions(
        width = 1080,   // Full HD 縦向き
        height = 1920
    )
}

private fun getDefaultMetadata(): VideoMetadata {
    return VideoMetadata(
        width = 1080,
        height = 1920,
        durationMs = 1000,  // 1秒（ClipFlowのデフォルト）
        rotation = 0,
        bitrate = null
    )
}

/**
 * フォールバック付きメタデータ取得
 */
fun getVideoMetadataWithFallback(
    path: String,
    fallback: VideoMetadata = getDefaultMetadata()
): VideoMetadata {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path)

            VideoMetadata(
                width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: fallback.width,

                height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: fallback.height,

                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: fallback.durationMs,

                rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: fallback.rotation,

                bitrate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE
                )?.toIntOrNull() ?: fallback.bitrate
            )
        }
    } catch (e: Exception) {
        Log.w("Metadata", "Using fallback metadata due to: ${e.message}")
        fallback
    }
}
```

#### 90度回転時の幅・高さ入れ替え処理

```kotlin
/**
 * 回転を考慮した実際の表示サイズを計算
 *
 * iOS版の preferredTransform 処理に相当:
 * 実装箇所: ProjectManager.swift:197-219
 *
 * if isRotated {
 *     composition.naturalSize = CGSize(
 *         width: naturalSize.height,
 *         height: naturalSize.width
 *     )
 * }
 */
fun getActualDimensions(path: String): VideoDimensions {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path)

            val rawWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 1080

            val rawHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 1920

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            // ✅ 90度または270度回転の場合、幅と高さを入れ替え
            val isRotated = rotation == 90 || rotation == 270

            if (isRotated) {
                Log.d("Dimensions", "Rotation detected ($rotation°), swapping dimensions")
                Log.d("Dimensions", "Original: ${rawWidth}x${rawHeight}")
                Log.d("Dimensions", "Actual: ${rawHeight}x${rawWidth}")

                VideoDimensions(
                    width = rawHeight,   // 高さと幅を入れ替え
                    height = rawWidth
                )
            } else {
                VideoDimensions(
                    width = rawWidth,
                    height = rawHeight
                )
            }
        }
    } catch (e: Exception) {
        Log.e("Dimensions", "Error getting actual dimensions", e)
        getDefaultDimensions()
    }
}
```

#### iOS版との比較

```swift
// iOS版: ProjectManager.swift:206-216
let angle = atan2(transform.b, transform.a)
let isRotated = abs(angle) > .pi / 4

if isRotated {
    composition.naturalSize = CGSize(
        width: naturalSize.height,
        height: naturalSize.width
    )
}
```

```kotlin
// Android版: より単純なアプローチ
val rotation = retriever.extractMetadata(
    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
)?.toIntOrNull() ?: 0

val isRotated = rotation == 90 || rotation == 270

if (isRotated) {
    videoDimensions = VideoDimensions(
        width = rawHeight,
        height = rawWidth
    )
}
```

---

### 4.3 完全なセグメント処理の実装例

```kotlin
/**
 * 完全なセグメント処理（メタデータ取得 + Composition作成）
 *
 * iOS版の createComposition() と createCompositionWithProgress() を統合
 * 実装箇所: ProjectManager.swift:143-370
 */
class SegmentProcessor(private val context: Context) {

    data class ProcessingResult(
        val composition: Composition,
        val totalDurationMs: Long,
        val dimensions: VideoDimensions,
        val segmentCount: Int
    )

    suspend fun processAndCreateComposition(
        project: Project,
        onProgress: (Int, Int) -> Unit
    ): Result<ProcessingResult> = withContext(Dispatchers.IO) {
        try {
            val sortedSegments = project.segments.sortedBy { it.order }
            val editedMediaItems = mutableListOf<EditedMediaItem>()
            var totalDurationMs = 0L
            var firstDimensions: VideoDimensions? = null

            sortedSegments.forEachIndexed { index, segment ->
                // 進捗通知（メインスレッド）
                withContext(Dispatchers.Main) {
                    onProgress(index, sortedSegments.size)
                }

                val file = File(context.filesDir, segment.uri)

                // ファイル存在確認
                if (!file.exists()) {
                    Log.w("SegmentProcessor",
                        "Segment file not found: ${file.name}")
                    return@forEachIndexed
                }

                // メタデータ取得（リソース管理付き）
                val metadata = MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)

                    VideoMetadata(
                        width = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull() ?: 1080,
                        height = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull() ?: 1920,
                        durationMs = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 1000,
                        rotation = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                        )?.toIntOrNull() ?: 0,
                        bitrate = null
                    )
                }

                // 最初のセグメントから dimensions を取得
                if (firstDimensions == null) {
                    val isRotated = metadata.rotation == 90 ||
                                    metadata.rotation == 270
                    firstDimensions = if (isRotated) {
                        VideoDimensions(metadata.height, metadata.width)
                    } else {
                        VideoDimensions(metadata.width, metadata.height)
                    }
                    Log.d("SegmentProcessor",
                        "First segment dimensions: $firstDimensions (rotation: ${metadata.rotation}°)")
                }

                // 総再生時間を加算
                totalDurationMs += metadata.durationMs

                // MediaItem を作成
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                editedMediaItems.add(editedMediaItem)

                Log.d("SegmentProcessor",
                    "Added segment ${index + 1}: ${metadata.durationMs}ms, total: ${totalDurationMs}ms")

                // GC促進（15セグメント問題対策）
                if ((index + 1) % 10 == 0) {
                    System.gc()
                    delay(10)
                }
            }

            // 最終進捗
            withContext(Dispatchers.Main) {
                onProgress(sortedSegments.size, sortedSegments.size)
            }

            // Composition を構築
            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(sequence).build()

            Log.d("SegmentProcessor", "Composition created successfully")
            Log.d("SegmentProcessor", "Total duration: ${totalDurationMs}ms (${totalDurationMs / 1000.0}s)")
            Log.d("SegmentProcessor", "Total segments: ${editedMediaItems.size}")

            Result.success(
                ProcessingResult(
                    composition = composition,
                    totalDurationMs = totalDurationMs,
                    dimensions = firstDimensions ?: getDefaultDimensions(),
                    segmentCount = editedMediaItems.size
                )
            )
        } catch (e: Exception) {
            Log.e("SegmentProcessor", "Failed to process segments", e)
            Result.failure(e)
        }
    }
}
```

---

### 4.4 ViewModel との統合

```kotlin
/**
 * ViewModel でのセグメント処理統合
 *
 * iOS版の PlayerView.swift:858-941 に相当
 */
class PlayerViewModel(
    private val context: Context,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val segmentProcessor = SegmentProcessor(context)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _compositionResult = MutableStateFlow<SegmentProcessor.ProcessingResult?>(null)
    val compositionResult: StateFlow<SegmentProcessor.ProcessingResult?> =
        _compositionResult.asStateFlow()

    fun createComposition(project: Project) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadingProgress.value = 0f

            val result = segmentProcessor.processAndCreateComposition(
                project = project,
                onProgress = { current, total ->
                    _loadingProgress.value = current.toFloat() / total
                }
            )

            result.onSuccess { processingResult ->
                _compositionResult.value = processingResult
                Log.d("PlayerViewModel",
                    "Composition ready: ${processingResult.totalDurationMs}ms")
            }.onFailure { error ->
                Log.e("PlayerViewModel", "Composition failed", error)
            }

            _isLoading.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        _compositionResult.value = null
        Log.d("PlayerViewModel", "ViewModel cleared")
    }
}
```

---

## 5. まとめ

### 5.1 iOS版AVCompositionとの完全な対応

| iOS版の処理 | Android版の対応 | 実装ポイント |
|------------|----------------|------------|
| AVMutableComposition 初期化 | Composition.Builder | 依存関係の追加が必要 |
| addMutableTrack() | EditedMediaItemSequence | トラック管理は自動 |
| insertTimeRange() | EditedMediaItem リストへの追加 | 時間管理は自動 |
| CMTime 計算 | Long (マイクロ秒) | 単位変換に注意 |
| AVURLAsset | MediaItem.fromUri() | ファイルアクセス |
| asset.load(.duration) | MediaMetadataRetriever | use {} でリソース管理 |
| preferredTransform | METADATA_KEY_VIDEO_ROTATION | 90/270度で入れ替え |
| AVAssetExportSession | Transformer | ファイル出力必須 |

### 5.2 重要なポイント

1. **リソース管理**: MediaMetadataRetriever は必ず `use {}` で囲む
2. **単位変換**: iOS版の秒はAndroid版ではマイクロ秒またはミリ秒
3. **ファイル出力**: Android版は仮想アセットではなく実ファイル出力が必要
4. **エラーハンドリング**: null安全とフォールバック値の設定が必須
5. **回転処理**: 90度/270度回転時は幅と高さを入れ替え
6. **進捗管理**: 大量セグメント処理時はGC促進が有効

---

*以上がセクション4B-1a「Media3 Composition基本実装ガイド（Android Part 1-a）」です。*
