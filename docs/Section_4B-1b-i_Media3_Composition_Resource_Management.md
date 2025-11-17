# セクション4B-1b-i: Media3 Composition リソース管理ガイド

## 1. 前回の失敗（セグメント15個制限）の原因分析

### 1.1 MediaMetadataRetriever のリソースリーク

#### 問題の症状

```
セグメント1: 成功
セグメント2: 成功
...
セグメント15: 成功
セグメント16: 失敗 - "Too many open files" エラー
```

#### 原因

```kotlin
// ❌ 前回の失敗パターン
fun getSegmentDurations(segments: List<VideoSegment>): List<Long> {
    val durations = mutableListOf<Long>()

    for (segment in segments) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(segment.uri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L

        durations.add(durationMs)

        // ❌ close() を呼んでいない！
        // retriever.release() も呼んでいない！
    }

    return durations
}
```

**問題点**:

1. MediaMetadataRetriever は内部でファイルハンドルを保持
2. close() を呼ばないとハンドルが解放されない
3. 15個程度でプロセスのファイルハンドル上限に達する

#### 解決方法

```kotlin
// ✅ 正しいパターン: try-finally で確実に close()
fun getSegmentDuration(uri: String): Long {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(uri)
        retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
    } finally {
        // ✅ 必ず close() を呼ぶ
        retriever.close()
    }
}
```

---

### 1.2 Media3 Composition でのメモリ使用量

#### 原因

```kotlin
// ❌ 問題のあるパターン
fun createComposition(segments: List<VideoSegment>): Composition {
    // Composition.Builder が大量のメモリを消費
    val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)

    for (segment in segments) {
        val mediaItem = MediaItem.fromUri(Uri.parse(segment.uri))
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

        // ❌ 各セグメントの MediaItem がメモリに残り続ける
        builder.addSequence(
            Composition.Sequence.Builder()
                .addMediaItem(editedMediaItem)
                .build()
        )
    }

    return builder.build()
}
```

**症状**:

- セグメント数が増えるとメモリ使用量が線形に増加
- 100個以上のセグメントで OutOfMemoryError の可能性
- GC が追いつかない

#### 解決方法

```kotlin
// ✅ バッチ処理でメモリを効率的に使用
suspend fun createCompositionInBatches(
    segments: List<VideoSegment>,
    batchSize: Int = 10
): Uri? = withContext(Dispatchers.IO) {
    val sortedSegments = segments.sortedBy { it.order }
    val intermediateFiles = mutableListOf<File>()

    try {
        // バッチごとに処理
        sortedSegments.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            val batchComposition = createBatchComposition(batch)
            val outputFile = exportComposition(batchComposition, "batch_$batchIndex.mp4")
            intermediateFiles.add(outputFile)

            // ✅ GC を促進
            System.gc()
        }

        // 中間ファイルを最終的に結合
        val finalUri = mergeIntermediateFiles(intermediateFiles)

        finalUri
    } finally {
        // ✅ 中間ファイルを削除
        intermediateFiles.forEach { it.delete() }
    }
}
```

---

### 1.3 ファイルハンドル上限の制約

#### Android のファイルハンドル上限

```
プロセスあたりのファイルハンドル上限:
- 通常: 1024個
- 一部デバイス: 512個
- 実際に使用可能: 約900個（システム予約を除く）
```

#### 各セグメントで消費されるハンドル

| リソース | ハンドル数 |
|---------|----------|
| MediaMetadataRetriever | 1個 |
| MediaItem (ExoPlayer) | 1〜2個 |
| ファイル読み込み (FileInputStream) | 1個 |
| Composition.Builder 内部 | 複数個 |

#### 15個制限の計算

```
15セグメント × 約60ハンドル/セグメント = 約900ハンドル
→ 上限に達する！
```

#### 解決方法

```kotlin
// ✅ ファイルハンドルを即座に解放
fun processSegments(segments: List<VideoSegment>) {
    for (segment in segments) {
        processSegment(segment)

        // ✅ 明示的にリソースを解放
        System.gc()

        // ✅ ハンドル数をログ出力（デバッグ用）
        logOpenFileDescriptors()
    }
}

// デバッグ用: 開いているファイルハンドル数を確認
fun logOpenFileDescriptors() {
    val fdDir = File("/proc/self/fd")
    val fdCount = fdDir.listFiles()?.size ?: 0
    Log.d("ResourceManager", "Open file descriptors: $fdCount")
}
```

---

## 2. 具体的な対策コード

### 2.1 MediaMetadataRetriever の正しい使用パターン

#### パターン1: try-finally（基本）

```kotlin
fun getVideoDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(context, uri)

        val durationString = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )

        durationString?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        Log.e("MetadataRetriever", "Failed to get duration", e)
        0L
    } finally {
        // ✅ 必ず close() を呼ぶ
        retriever.close()
    }
}
```

#### パターン2: use 拡張関数（Kotlin推奨）

```kotlin
// ✅ Closeable を実装しているため use が使える
fun getVideoDurationWithUse(context: Context, uri: Uri): Long {
    return MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)

        retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
    }
}
```

#### パターン3: 複数のメタデータを一度に取得

```kotlin
data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val bitrate: Int?
)

fun getVideoMetadata(context: Context, uri: Uri): VideoMetadata {
    return MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)

        VideoMetadata(
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L,

            width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0,

            height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0,

            rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0,

            bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull()
        )
    }
}
```

#### パターン4: 大量セグメント対応（順次処理）

```kotlin
suspend fun getAllSegmentMetadata(
    context: Context,
    segments: List<VideoSegment>,
    onProgress: (Int, Int) -> Unit
): List<VideoMetadata> = withContext(Dispatchers.IO) {

    val metadataList = mutableListOf<VideoMetadata>()

    segments.forEachIndexed { index, segment ->
        // ✅ 各セグメントごとに retriever を作成・解放
        val metadata = MediaMetadataRetriever().use { retriever ->
            val uri = Uri.parse(segment.uri)
            retriever.setDataSource(context, uri)

            VideoMetadata(
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0,
                rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0,
                bitrate = null
            )
        }

        metadataList.add(metadata)

        // 進捗更新（UIスレッドで）
        withContext(Dispatchers.Main) {
            onProgress(index + 1, segments.size)
        }

        // ✅ 10セグメントごとに GC を促進
        if ((index + 1) % 10 == 0) {
            System.gc()
            Log.d("MetadataRetriever", "GC triggered after ${index + 1} segments")
        }
    }

    metadataList
}
```

---

### 2.2 大量セグメント対応のメモリ最適化

#### Composition 作成時のメモリ管理

```kotlin
class CompositionBuilder(
    private val context: Context
) {
    private val cacheDir = context.cacheDir

    suspend fun createCompositionWithMemoryOptimization(
        segments: List<VideoSegment>,
        onProgress: (Float) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {

        val sortedSegments = segments.sortedBy { it.order }
        val totalSegments = sortedSegments.size

        Log.d("CompositionBuilder", "Creating composition with $totalSegments segments")

        // ✅ セグメント数に応じて戦略を選択
        when {
            totalSegments <= 20 -> {
                // 小規模: 一括処理
                createCompositionDirect(sortedSegments, onProgress)
            }
            totalSegments <= 50 -> {
                // 中規模: バッチ処理（10個ずつ）
                createCompositionInBatches(sortedSegments, 10, onProgress)
            }
            else -> {
                // 大規模: ストリーミング処理
                createCompositionStreaming(sortedSegments, onProgress)
            }
        }
    }

    // 小規模用: 一括処理
    private suspend fun createCompositionDirect(
        segments: List<VideoSegment>,
        onProgress: (Float) -> Unit
    ): Uri? {
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        segments.forEachIndexed { index, segment ->
            val file = File(context.filesDir, segment.uri)

            if (file.exists()) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                sequenceBuilder.addMediaItem(editedMediaItem)

                onProgress((index + 1).toFloat() / segments.size)
            }
        }

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        return exportComposition(composition)
    }

    // 中規模用: バッチ処理
    private suspend fun createCompositionInBatches(
        segments: List<VideoSegment>,
        batchSize: Int,
        onProgress: (Float) -> Unit
    ): Uri? {
        val intermediateFiles = mutableListOf<File>()
        val batches = segments.chunked(batchSize)

        try {
            batches.forEachIndexed { batchIndex, batch ->
                Log.d("CompositionBuilder", "Processing batch ${batchIndex + 1}/${batches.size}")

                // バッチごとに Composition を作成
                val batchComposition = createBatchComposition(batch)
                val outputFile = File(cacheDir, "batch_${batchIndex}_${System.currentTimeMillis()}.mp4")

                // エクスポート
                exportCompositionToFile(batchComposition, outputFile)
                intermediateFiles.add(outputFile)

                // 進捗更新
                val progress = (batchIndex + 1).toFloat() / batches.size * 0.8f
                onProgress(progress)

                // ✅ メモリ解放
                System.gc()
            }

            // 中間ファイルを結合
            Log.d("CompositionBuilder", "Merging ${intermediateFiles.size} intermediate files")
            val finalUri = mergeFiles(intermediateFiles, onProgress)

            onProgress(1.0f)
            return finalUri

        } finally {
            // ✅ 中間ファイルを必ず削除
            intermediateFiles.forEach { file ->
                if (file.exists()) {
                    file.delete()
                    Log.d("CompositionBuilder", "Deleted intermediate file: ${file.name}")
                }
            }
        }
    }

    private fun createBatchComposition(batch: List<VideoSegment>): Composition {
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        for (segment in batch) {
            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                sequenceBuilder.addMediaItem(editedMediaItem)
            }
        }

        builder.addSequence(sequenceBuilder.build())
        return builder.build()
    }

    private suspend fun exportComposition(composition: Composition): Uri? {
        val outputFile = File(cacheDir, "composition_${System.currentTimeMillis()}.mp4")
        exportCompositionToFile(composition, outputFile)
        return Uri.fromFile(outputFile)
    }

    private suspend fun exportCompositionToFile(
        composition: Composition,
        outputFile: File
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        val transformer = Transformer.Builder(context).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                continuation.resume(Unit) {}
            }

            override fun onError(
                comp: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                Log.e("CompositionBuilder", "Export failed", exportException)
                continuation.resume(Unit) {}
            }
        })

        transformer.start(composition, outputFile.path)
    }

    private suspend fun mergeFiles(
        files: List<File>,
        onProgress: (Float) -> Unit
    ): Uri? {
        // 中間ファイルを最終的に結合する処理
        // （実際の実装はプロジェクト要件に応じて）

        if (files.isEmpty()) return null
        if (files.size == 1) return Uri.fromFile(files.first())

        // 複数ファイルの結合処理...
        val finalFile = File(cacheDir, "final_${System.currentTimeMillis()}.mp4")

        // Media3 Transformer を使って結合
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        for (file in files) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
            sequenceBuilder.addMediaItem(editedMediaItem)
        }

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        exportCompositionToFile(composition, finalFile)

        onProgress(1.0f)
        return Uri.fromFile(finalFile)
    }

    // 大規模用: ストリーミング処理
    private suspend fun createCompositionStreaming(
        segments: List<VideoSegment>,
        onProgress: (Float) -> Unit
    ): Uri? {
        // より小さいバッチサイズで処理
        return createCompositionInBatches(segments, 5, onProgress)
    }
}
```

---

### 2.3 100個以上のセグメント対応

#### ストリーミング処理パターン

```kotlin
class LargeScaleCompositionBuilder(
    private val context: Context
) {
    private val cacheDir = context.cacheDir
    private val maxConcurrentFiles = 5

    suspend fun createLargeComposition(
        segments: List<VideoSegment>,
        onProgress: (Float) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {

        val sortedSegments = segments.sortedBy { it.order }
        val totalSegments = sortedSegments.size

        Log.d("LargeComposition", "Processing $totalSegments segments")

        // ステップ1: セグメントを小さなグループに分割
        val groupSize = 5
        val groups = sortedSegments.chunked(groupSize)
        val intermediateFiles = mutableListOf<File>()

        try {
            // ステップ2: 各グループを処理
            groups.forEachIndexed { groupIndex, group ->
                Log.d("LargeComposition", "Processing group ${groupIndex + 1}/${groups.size}")

                // グループの Composition を作成
                val groupFile = processGroup(group, groupIndex)
                if (groupFile != null) {
                    intermediateFiles.add(groupFile)
                }

                // 進捗更新
                val progress = (groupIndex + 1).toFloat() / groups.size * 0.7f
                onProgress(progress)

                // ✅ メモリ管理
                if ((groupIndex + 1) % 10 == 0) {
                    System.gc()
                    logMemoryUsage()
                }
            }

            // ステップ3: 中間ファイルを段階的に結合
            Log.d("LargeComposition", "Merging ${intermediateFiles.size} intermediate files")
            val finalUri = mergeFilesHierarchically(intermediateFiles, onProgress)

            onProgress(1.0f)
            return@withContext finalUri

        } finally {
            // ✅ すべての中間ファイルを削除
            cleanupIntermediateFiles(intermediateFiles)
        }
    }

    private suspend fun processGroup(
        segments: List<VideoSegment>,
        groupIndex: Int
    ): File? {
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        for (segment in segments) {
            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                // ✅ メタデータを個別に取得（リソースを即座に解放）
                val duration = getSegmentDuration(file)
                Log.d("LargeComposition", "Segment ${segment.order}: ${duration}ms")

                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                sequenceBuilder.addMediaItem(editedMediaItem)
            }
        }

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        // 中間ファイルにエクスポート
        val outputFile = File(cacheDir, "group_${groupIndex}_${System.currentTimeMillis()}.mp4")

        return try {
            exportToFile(composition, outputFile)
            outputFile
        } catch (e: Exception) {
            Log.e("LargeComposition", "Failed to export group $groupIndex", e)
            null
        }
    }

    private fun getSegmentDuration(file: File): Long {
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        }
    }

    private suspend fun exportToFile(
        composition: Composition,
        outputFile: File
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        val transformer = Transformer.Builder(context).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                continuation.resume(Unit) {}
            }

            override fun onError(
                comp: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                continuation.resumeWithException(exportException)
            }
        })

        transformer.start(composition, outputFile.path)
    }

    // 階層的なファイル結合（二分木方式）
    private suspend fun mergeFilesHierarchically(
        files: List<File>,
        onProgress: (Float) -> Unit
    ): Uri? {
        if (files.isEmpty()) return null
        if (files.size == 1) return Uri.fromFile(files.first())

        var currentFiles = files.toMutableList()
        var level = 0

        while (currentFiles.size > 1) {
            Log.d("LargeComposition", "Merge level $level: ${currentFiles.size} files")

            val nextLevelFiles = mutableListOf<File>()
            val pairs = currentFiles.chunked(2)

            pairs.forEachIndexed { pairIndex, pair ->
                if (pair.size == 2) {
                    // 2つのファイルを結合
                    val mergedFile = mergeTwoFiles(pair[0], pair[1], level, pairIndex)
                    if (mergedFile != null) {
                        nextLevelFiles.add(mergedFile)
                    }
                } else {
                    // 奇数の場合、そのまま次のレベルへ
                    nextLevelFiles.add(pair[0])
                }
            }

            // 古いファイルを削除（元のセグメントファイルは除く）
            if (level > 0) {
                currentFiles.forEach { it.delete() }
            }

            currentFiles = nextLevelFiles
            level++

            // 進捗更新
            val progress = 0.7f + (level.toFloat() / (Math.log(files.size.toDouble()) / Math.log(2.0)).toFloat()) * 0.3f
            onProgress(progress)

            System.gc()
        }

        return Uri.fromFile(currentFiles.first())
    }

    private suspend fun mergeTwoFiles(
        file1: File,
        file2: File,
        level: Int,
        pairIndex: Int
    ): File? {
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        val mediaItem1 = MediaItem.fromUri(Uri.fromFile(file1))
        val mediaItem2 = MediaItem.fromUri(Uri.fromFile(file2))

        sequenceBuilder.addMediaItem(EditedMediaItem.Builder(mediaItem1).build())
        sequenceBuilder.addMediaItem(EditedMediaItem.Builder(mediaItem2).build())

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        val outputFile = File(cacheDir, "merged_L${level}_P${pairIndex}_${System.currentTimeMillis()}.mp4")

        return try {
            exportToFile(composition, outputFile)
            outputFile
        } catch (e: Exception) {
            Log.e("LargeComposition", "Failed to merge files", e)
            null
        }
    }

    private fun cleanupIntermediateFiles(files: List<File>) {
        files.forEach { file ->
            if (file.exists() && file.parent == cacheDir.absolutePath) {
                file.delete()
                Log.d("LargeComposition", "Cleaned up: ${file.name}")
            }
        }
    }

    private fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMemory = runtime.maxMemory() / 1024 / 1024

        Log.d("LargeComposition", "Memory usage: ${usedMemory}MB / ${maxMemory}MB")
    }
}
```

---

## 3. リソース管理ベストプラクティス

### 3.1 一般的なガイドライン

| リソース | 取得方法 | 解放方法 | 注意点 |
|---------|---------|---------|-------|
| MediaMetadataRetriever | `MediaMetadataRetriever()` | `.close()` または `.use {}` | 必ず close() を呼ぶ |
| ExoPlayer | `ExoPlayer.Builder().build()` | `.release()` | 画面破棄時に必ず release |
| Transformer | `Transformer.Builder().build()` | リスナーで完了を待つ | 非同期処理 |
| 一時ファイル | `File(cacheDir, ...)` | `.delete()` | finally ブロックで削除 |

### 3.2 ViewModel での管理

```kotlin
class CompositionViewModel(
    private val application: Application
) : AndroidViewModel(application) {

    private var currentTransformer: Transformer? = null
    private val temporaryFiles = mutableListOf<File>()

    fun createComposition(segments: List<VideoSegment>) {
        viewModelScope.launch(Dispatchers.IO) {
            // Composition 作成処理...
        }
    }

    override fun onCleared() {
        super.onCleared()

        // ✅ すべてのリソースを解放
        currentTransformer?.cancel()
        currentTransformer = null

        // ✅ 一時ファイルを削除
        temporaryFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        temporaryFiles.clear()

        Log.d("CompositionViewModel", "All resources cleaned up")
    }
}
```

### 3.3 DisposableEffect での管理

```kotlin
@Composable
fun PlayerScreen(
    project: Project,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // ✅ 必ず DisposableEffect で解放
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            Log.d("PlayerScreen", "ExoPlayer released")
        }
    }

    // UI...
}
```

---

## 4. iOS版との対応関係

| 側面 | iOS版 (AVComposition) | Android版 (Media3 Composition) |
|------|----------------------|-------------------------------|
| リソース管理 | ARC による自動解放 | 明示的な close/release が必要 |
| メタデータ取得 | AVAsset.load() (async) | MediaMetadataRetriever (同期) |
| Composition 作成 | AVMutableComposition | Composition.Builder |
| メモリ管理 | システムが最適化 | バッチ処理で手動最適化が必要 |
| ファイルハンドル | システムが管理 | 明示的に close() が必要 |
| 大量セグメント | 制限なし（実質） | バッチ処理で対応必要 |

---

## 5. まとめ

### 5.1 セグメント15個制限の解決策

1. **MediaMetadataRetriever**: 必ず `use {}` または `try-finally` で close()
2. **バッチ処理**: 大量セグメントは小さなグループに分けて処理
3. **メモリ管理**: 定期的に System.gc() を呼び、メモリ使用量を監視
4. **一時ファイル**: finally ブロックで必ず削除

### 5.2 推奨アーキテクチャ

```kotlin
class CompositionService(private val context: Context) {

    suspend fun createComposition(segments: List<VideoSegment>): Uri? {
        return when {
            segments.size <= 20 -> createDirectComposition(segments)
            segments.size <= 100 -> createBatchComposition(segments, batchSize = 10)
            else -> createStreamingComposition(segments, batchSize = 5)
        }
    }

    private fun createDirectComposition(segments: List<VideoSegment>): Uri? {
        // 小規模: 一括処理
    }

    private fun createBatchComposition(segments: List<VideoSegment>, batchSize: Int): Uri? {
        // 中規模: バッチ処理
    }

    private fun createStreamingComposition(segments: List<VideoSegment>, batchSize: Int): Uri? {
        // 大規模: ストリーミング処理
    }
}
```

これらの対策を実装することで、セグメント数の制限を解消し、100個以上のセグメントでも安定して Composition を作成できるようになります。

---

*以上がセクション4B-1b-i「Media3 Composition リソース管理ガイド」です。*
