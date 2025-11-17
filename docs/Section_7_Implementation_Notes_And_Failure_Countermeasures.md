# セクション7: 実装注意点と前回の失敗への対策

## 概要

このドキュメントは、ClipFlow Android版実装における重要な注意点、過去の失敗から学んだ教訓、およびプロダクション環境での運用を想定した対策をまとめています。特に、15セグメント制限問題の根本原因と解決策に重点を置いています。

---

## 1. セグメント15個制限への対策（前回の失敗から学んだこと）

### 1.1 失敗の経緯

初期実装では、以下の問題が発生しました：

```kotlin
// 問題のあったコード
fun createComposition(segments: List<VideoSegment>): Uri? {
    val builder = Composition.Builder(/* ... */)

    segments.forEach { segment ->
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, segment.uri)
        val duration = retriever.extractMetadata(METADATA_KEY_DURATION)
        // retriever.release() が呼ばれていない！

        builder.addMediaItem(/* ... */)
    }

    return buildComposition(builder)
}
```

**発生した症状：**
- セグメント15個目以降でクラッシュ
- `java.lang.IllegalStateException: setDataSource failed`
- ファイルハンドル枯渇によるシステムエラー
- メモリ使用量の急激な増加

**根本原因：**
1. **MediaMetadataRetriever のリソースリーク** - `release()` 未呼び出し
2. **ファイルハンドル枯渇** - Linux プロセスあたり1024ハンドル制限
3. **メモリリーク** - ネイティブリソースの解放漏れ

### 1.2 根本原因と対策

#### 対策1: use() 拡張関数による自動リソース管理

```kotlin
// 正しい実装
fun getVideoDuration(context: Context, uri: Uri): Long {
    return MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } // use() ブロックを抜けると自動的に release() が呼ばれる
}
```

#### 対策2: try-finally による明示的解放

```kotlin
// 代替実装（use が使えない場合）
fun getVideoDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        return retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release() // 必ず呼ばれる
    }
}
```

#### 対策3: バッチ処理による段階的処理

```kotlin
// 大量セグメント対応
suspend fun createCompositionSafely(segments: List<VideoSegment>): Uri? {
    val batchSize = 10

    if (segments.size <= batchSize) {
        return createDirectComposition(segments)
    }

    // バッチ処理
    val batches = segments.chunked(batchSize)
    val intermediateFiles = mutableListOf<Uri>()

    batches.forEachIndexed { index, batch ->
        val batchUri = createDirectComposition(batch)
        batchUri?.let { intermediateFiles.add(it) }

        // GC を促進
        System.gc()
        delay(100) // 短い休憩を挟む
    }

    // 中間ファイルを結合
    return mergeIntermediateFiles(intermediateFiles)
}
```

### 1.3 実装チェックリスト

実装時に必ず確認すべき項目：

- [ ] **MediaMetadataRetriever を use() で包んでいる**
  ```kotlin
  MediaMetadataRetriever().use { retriever ->
      // 処理
  }
  ```

- [ ] **getMetadataInt() の失敗時フォールバック**
  ```kotlin
  val width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
  val height = retriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
  ```

- [ ] **Composition.Builder の段階的構築**
  ```kotlin
  val editedMediaItems = mutableListOf<EditedMediaItem>()
  segments.forEach { segment ->
      // use() 内で安全にメタデータ取得
      val item = createEditedMediaItem(segment)
      editedMediaItems.add(item)
  }
  // 最後に一括で Builder に追加
  ```

- [ ] **メモリプロファイラで確認**
  - Android Studio → View → Tool Windows → Profiler
  - Memory タブで Heap Dump を取得
  - MediaMetadataRetriever インスタンスが残っていないことを確認

---

## 2. 大量セグメント対応（100個以上）

### 2.1 メモリ効率化の必須項目

100個以上のセグメントを扱う場合、特別な対策が必要です：

| セグメント数 | 推奨処理方式 | メモリ使用量目安 |
|------------|------------|---------------|
| 1-10個 | 直接処理 | 50-100MB |
| 11-50個 | バッチ処理（10個単位） | 100-200MB |
| 51-100個 | バッチ処理（5個単位）+ GC | 200-400MB |
| 100個以上 | 階層的結合 + ストリーミング | 400MB+ |

### 2.2 実装パターン

#### バッチ処理パターン

```kotlin
class BatchCompositionProcessor(private val context: Context) {

    suspend fun processBatches(
        segments: List<VideoSegment>,
        batchSize: Int = 10,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Uri? = withContext(Dispatchers.IO) {

        val batches = segments.chunked(batchSize)
        val intermediateUris = mutableListOf<Uri>()

        batches.forEachIndexed { index, batch ->
            onProgress(index + 1, batches.size)

            // バッチ処理
            val batchResult = processSingleBatch(batch)
            batchResult?.let { intermediateUris.add(it) }

            // メモリ解放を促進
            forceGarbageCollection()
        }

        // 最終結合
        if (intermediateUris.size == 1) {
            intermediateUris.first()
        } else {
            mergeAllBatches(intermediateUris)
        }
    }

    private fun processSingleBatch(batch: List<VideoSegment>): Uri? {
        val editedMediaItems = batch.map { segment ->
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, segment.uri)

                val duration = retriever.extractMetadata(
                    METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 1000L

                EditedMediaItem.Builder(
                    MediaItem.fromUri(segment.uri)
                ).build()
            }
        }

        // Composition 作成
        val composition = Composition.Builder(
            editedMediaItems.map {
                EditedMediaItemSequence(listOf(it))
            }
        ).build()

        return composition.toUri()
    }

    private fun forceGarbageCollection() {
        System.gc()
        System.runFinalization()
        Thread.sleep(50) // GC に時間を与える
    }
}
```

#### ストリーミング処理パターン

```kotlin
class StreamingCompositionProcessor(private val context: Context) {

    suspend fun processStreaming(
        segments: List<VideoSegment>,
        onSegmentProcessed: (Int) -> Unit = {}
    ): Uri? = withContext(Dispatchers.IO) {

        val tempDir = File(context.cacheDir, "streaming_process")
        tempDir.mkdirs()

        var currentComposition: Uri? = null

        segments.forEachIndexed { index, segment ->
            currentComposition = if (currentComposition == null) {
                // 最初のセグメント
                createSingleSegmentComposition(segment)
            } else {
                // 既存の composition に追加
                appendSegmentToComposition(currentComposition!!, segment)
            }

            onSegmentProcessed(index + 1)

            // 5セグメントごとに GC
            if ((index + 1) % 5 == 0) {
                System.gc()
            }
        }

        currentComposition
    }

    private fun createSingleSegmentComposition(segment: VideoSegment): Uri {
        return MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, segment.uri)
            // ... composition 作成
            segment.uri // 実際には新しい URI を返す
        }
    }

    private fun appendSegmentToComposition(
        existing: Uri,
        newSegment: VideoSegment
    ): Uri {
        // 既存の composition に新しいセグメントを追加
        // Transformer を使用して結合
        return existing // 実際には結合後の URI を返す
    }
}
```

### 2.3 テスト方法

#### 段階的テスト計画

```kotlin
@RunWith(AndroidJUnit4::class)
class LargeSegmentTest {

    @Test
    fun test_1_to_5_segments() {
        // 基本動作確認
        val segments = createTestSegments(5)
        val result = processor.createComposition(segments)
        assertNotNull(result)
        verifyMemoryUsage(maxMB = 100)
    }

    @Test
    fun test_10_to_20_segments() {
        // 通常使用シナリオ
        val segments = createTestSegments(20)
        val result = processor.createComposition(segments)
        assertNotNull(result)
        verifyMemoryUsage(maxMB = 200)
        verifyNoResourceLeaks()
    }

    @Test
    fun test_100_plus_segments() {
        // ストレステスト
        val segments = createTestSegments(150)
        val startTime = System.currentTimeMillis()

        val result = processor.createComposition(segments)

        val duration = System.currentTimeMillis() - startTime
        assertNotNull(result)
        assertTrue("Processing time should be under 60 seconds", duration < 60000)
        verifyNoMemoryLeaks()
        verifyAllFilesReleased()
    }

    private fun verifyMemoryUsage(maxMB: Int) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        assertTrue("Memory usage $usedMemory MB exceeds $maxMB MB", usedMemory <= maxMB)
    }

    private fun verifyNoResourceLeaks() {
        System.gc()
        Thread.sleep(1000)
        // LeakCanary やカスタムリーク検出
    }
}
```

---

## 3. Android固有の制約と対応

### 3.1 ファイルハンドル上限

**問題：**
- Linux デフォルト: 1024 ファイルハンドル/プロセス
- 各セグメントファイル + MediaMetadataRetriever + 一時ファイルで消費
- 15-20セグメントで上限に近づく

**確認方法：**
```kotlin
fun getOpenFileDescriptorCount(): Int {
    val fdDir = File("/proc/self/fd")
    return fdDir.listFiles()?.size ?: 0
}

fun logFileDescriptorUsage() {
    Log.d("FD_Monitor", "Open FDs: ${getOpenFileDescriptorCount()}/1024")
}
```

**対策：**
```kotlin
class FileHandleMonitor {
    private val maxSafeFDs = 800 // 安全マージンを確保

    fun checkBeforeOperation(): Boolean {
        val currentFDs = getOpenFileDescriptorCount()
        if (currentFDs > maxSafeFDs) {
            Log.w("FD_Monitor", "File descriptor count high: $currentFDs")
            // GC を促して解放を促進
            System.gc()
            Thread.sleep(100)
            return getOpenFileDescriptorCount() < maxSafeFDs
        }
        return true
    }

    fun wrapWithFDCheck(operation: () -> Unit) {
        if (!checkBeforeOperation()) {
            throw IllegalStateException("Too many open file descriptors")
        }
        operation()
    }
}
```

### 3.2 メモリ制限

**デバイス別メモリ状況：**

| デバイスクラス | RAM | ヒープ制限 | 推奨最大セグメント数 |
|--------------|-----|---------|-----------------|
| ローエンド | 512MB-1GB | 64-128MB | 20個 |
| ミドルレンジ | 2-4GB | 256-512MB | 100個 |
| ハイエンド | 6-12GB | 512MB-1GB | 200個以上 |

**デバイス能力の検出：**
```kotlin
class DeviceCapabilityDetector(private val context: Context) {

    fun getRecommendedSegmentLimit(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMB = memoryInfo.totalMem / 1024 / 1024
        val availableRamMB = memoryInfo.availMem / 1024 / 1024

        return when {
            totalRamMB < 1024 -> 20  // 1GB未満
            totalRamMB < 3072 -> 50  // 3GB未満
            totalRamMB < 6144 -> 100 // 6GB未満
            else -> 200              // 6GB以上
        }
    }

    fun getMaxHeapSize(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory()
    }

    fun isLowMemoryDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        return activityManager.isLowRamDevice
    }
}
```

**OOMError 対策：**
```kotlin
class MemorySafeCompositionBuilder(private val context: Context) {

    private val detector = DeviceCapabilityDetector(context)

    suspend fun createCompositionWithMemoryCheck(
        segments: List<VideoSegment>
    ): Result<Uri> = withContext(Dispatchers.IO) {

        val limit = detector.getRecommendedSegmentLimit()

        if (segments.size > limit) {
            // ユーザーに警告
            return@withContext Result.failure(
                TooManySegmentsException(
                    "このデバイスでは最大${limit}個のセグメントまで対応しています。" +
                    "現在${segments.size}個のセグメントがあります。"
                )
            )
        }

        try {
            val uri = createCompositionInternal(segments)
            uri?.let { Result.success(it) }
                ?: Result.failure(Exception("Composition creation failed"))
        } catch (e: OutOfMemoryError) {
            System.gc()
            Result.failure(
                OutOfMemoryException(
                    "メモリ不足です。一部のセグメントを削除してください。"
                )
            )
        }
    }
}
```

### 3.3 API レベルの差異

**Android バージョン別の主要な違い：**

| Android Version | API Level | 主要な変更点 |
|----------------|-----------|------------|
| 10 (Q) | 29 | Scoped Storage 導入 |
| 11 (R) | 30 | MediaStore API 強化 |
| 12 (S) | 31 | 近似位置情報、Bluetooth 権限 |
| 13 (T) | 33 | 通知権限、メディア権限分離 |
| 14 (U) | 34 | 写真・動画部分アクセス |

**バージョン対応コード：**
```kotlin
object VersionCompatibility {

    fun saveToGallery(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGalleryModern(context, sourceUri, fileName)
        } else {
            saveToGalleryLegacy(context, sourceUri, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToGalleryModern(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { output ->
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(it, contentValues, null, null)
        }

        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToGalleryLegacy(
        context: Context,
        sourceUri: Uri,
        fileName: String
    ): Uri? {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val destFile = File(moviesDir, fileName)

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // MediaScanner に通知
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        return Uri.fromFile(destFile)
    }

    fun requestPermissions(activity: Activity) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        }

        ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE)
    }
}
```

---

## 4. iOS版との重要な違い

### 4.1 メモリ管理

**iOS (ARC - Automatic Reference Counting)：**
```swift
class VideoComposer {
    func createComposition(segments: [VideoSegment]) -> AVComposition? {
        let composition = AVMutableComposition()

        for segment in segments {
            // ARC が自動的にメモリ管理
            let asset = AVAsset(url: segment.url)
            // asset は必要なくなると自動解放
        }

        return composition
        // composition も適切に管理される
    }
}
```

**Android (手動管理が必要)：**
```kotlin
class VideoComposer(private val context: Context) {

    fun createComposition(segments: List<VideoSegment>): Uri? {
        // 明示的なリソース解放が必須
        segments.forEach { segment ->
            MediaMetadataRetriever().use { retriever ->
                // use ブロックで必ず release() が呼ばれる
                retriever.setDataSource(context, segment.uri)
            }
        }

        return buildComposition()
    }

    // ViewModel での明示的クリーンアップ
    fun cleanup() {
        // 全てのリソースを明示的に解放
        player?.release()
        player = null

        System.gc() // GC を促進
    }
}
```

**重要な違い：**
- iOS: `deinit` で自動クリーンアップ
- Android: `onCleared()`, `DisposableEffect`, `use()` で明示的にクリーンアップ
- **対策**: 全てのリソース使用箇所で解放処理を記述

### 4.2 ライフサイクル

**iOS (SwiftUI @StateObject)：**
```swift
struct PlayerView: View {
    @StateObject private var viewModel = PlayerViewModel()
    // SwiftUI が自動的にライフサイクル管理

    var body: some View {
        VideoPlayer(player: viewModel.player)
            .onAppear { viewModel.play() }
            .onDisappear { viewModel.pause() }
        // @StateObject により、View の再生成時もインスタンス保持
    }
}
```

**Android (ViewModel + Compose)：**
```kotlin
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = viewModel()) {
    // ViewModel は Configuration Change を超えて生存

    val player by viewModel.player.collectAsState()

    // 明示的なライフサイクル管理が必要
    DisposableEffect(Unit) {
        onDispose {
            // Composable 破棄時の処理
            viewModel.pausePlayback()
        }
    }

    // Activity ライフサイクルの監視
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.pausePlayback()
                Lifecycle.Event.ON_RESUME -> viewModel.resumePlayback()
                Lifecycle.Event.ON_DESTROY -> viewModel.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    PlayerUI(player)
}
```

**重要な違い：**
- iOS: View のライフサイクルに自動連動
- Android: Activity/Fragment のライフサイクルを明示的に監視
- **対策**: LifecycleObserver パターンを必ず実装

### 4.3 スレッド管理

**iOS (@MainActor)：**
```swift
@MainActor
class PlayerViewModel: ObservableObject {
    @Published var isPlaying = false

    func play() {
        // @MainActor により自動的にメインスレッドで実行
        isPlaying = true
        player.play()
    }

    func loadVideo() async {
        // 自動的にバックグラウンドで実行
        let composition = await createComposition()
        // UI 更新は自動的にメインスレッドに切り替え
        isPlaying = true
    }
}
```

**Android (明示的 Dispatcher 指定)：**
```kotlin
class PlayerViewModel : ViewModel() {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun play() {
        viewModelScope.launch(Dispatchers.Main) {
            // 明示的にメインスレッドを指定
            _isPlaying.value = true
            player?.play()
        }
    }

    fun loadVideo() {
        viewModelScope.launch {
            // デフォルトは Dispatchers.Main
            val composition = withContext(Dispatchers.IO) {
                // 重い処理は IO スレッドで
                createComposition()
            }
            // Dispatchers.Main に戻る
            _isPlaying.value = true
        }
    }

    // 間違った例（クラッシュの原因）
    fun wrongPlay() {
        viewModelScope.launch(Dispatchers.IO) {
            // IO スレッドから UI 更新は禁止！
            _isPlaying.value = true // これは動くが...
            player?.play() // これがクラッシュする可能性
        }
    }
}
```

**重要な違い：**
- iOS: `@MainActor` アノテーションで自動管理
- Android: `Dispatchers.Main`, `Dispatchers.IO` を明示的に指定
- **対策**: UI 更新は必ず `Dispatchers.Main` で実行

---

## 5. デバッグ・トラブルシューティング

### 5.1 よくあるエラーと対策

#### エラー1: `width -1 must be positive`

**原因：** MediaMetadataRetriever がメタデータ取得に失敗

```kotlin
// 問題のあるコード
val width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: -1
// width が -1 のまま使用されてクラッシュ

// 対策コード
fun getVideoMetadataSafely(context: Context, uri: Uri): VideoMetadata {
    return MediaMetadataRetriever().use { retriever ->
        try {
            retriever.setDataSource(context, uri)

            val width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 1920 // フォールバック値

            val height = retriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 1080 // フォールバック値

            val duration = retriever.extractMetadata(METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 1000L

            VideoMetadata(width, height, duration)
        } catch (e: Exception) {
            Log.e("Metadata", "Failed to extract metadata: ${e.message}")
            VideoMetadata(1920, 1080, 1000L) // デフォルト値
        }
    }
}
```

**追加の確認事項：**
- ファイルが存在するか確認
- ファイルが破損していないか確認
- URI が正しいスキーマか確認（`content://` vs `file://`）

#### エラー2: `FileNotFoundException`

**原因：** ファイルパスの誤り、または権限不足

```kotlin
// 問題のあるコード
val file = File("/storage/emulated/0/segment.mp4")
// 外部ストレージへの直接アクセスは Android 10 以降で制限

// 対策コード
class SafeFileAccess(private val context: Context) {

    fun getSegmentFile(fileName: String): File {
        // 内部ストレージを使用
        return File(context.filesDir, fileName).also { file ->
            if (!file.exists()) {
                throw FileNotFoundException("Segment file not found: $fileName")
            }
        }
    }

    fun getSegmentUri(fileName: String): Uri {
        val file = getSegmentFile(fileName)

        // Android 7 以降は FileProvider を使用
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        } else {
            Uri.fromFile(file)
        }
    }

    fun ensureFileExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.close()
            true
        } catch (e: Exception) {
            Log.e("FileAccess", "File not accessible: $uri")
            false
        }
    }
}
```

#### エラー3: `OutOfMemoryError`

**原因：** メモリ不足

```kotlin
// 対策：段階的処理とメモリ監視
class MemoryAwareProcessor {

    fun processWithMemoryCheck(
        segments: List<VideoSegment>,
        onMemoryWarning: () -> Unit
    ) {
        val runtime = Runtime.getRuntime()

        segments.forEachIndexed { index, segment ->
            // メモリ使用率を確認
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val usagePercent = (usedMemory.toFloat() / maxMemory) * 100

            if (usagePercent > 80) {
                Log.w("Memory", "High memory usage: ${usagePercent.toInt()}%")
                onMemoryWarning()

                // GC を強制
                System.gc()
                System.runFinalization()
                Thread.sleep(100)
            }

            processSegment(segment)

            // 10セグメントごとに GC
            if ((index + 1) % 10 == 0) {
                System.gc()
            }
        }
    }

    private fun processSegment(segment: VideoSegment) {
        // 処理
    }
}
```

### 5.2 Logcat での確認方法

#### 重要なログポイントの設定

```kotlin
object AppLogger {
    private const val TAG = "ClipFlow"

    fun logCompositionStart(segmentCount: Int) {
        Log.i(TAG, "=== Composition Creation Started ===")
        Log.i(TAG, "Segment count: $segmentCount")
        Log.i(TAG, "Available memory: ${getAvailableMemoryMB()} MB")
        Log.i(TAG, "Open FDs: ${getOpenFDCount()}")
    }

    fun logMetadataExtraction(segmentIndex: Int, uri: Uri, metadata: VideoMetadata) {
        Log.d(TAG, "Segment[$segmentIndex] Metadata:")
        Log.d(TAG, "  URI: $uri")
        Log.d(TAG, "  Width: ${metadata.width}")
        Log.d(TAG, "  Height: ${metadata.height}")
        Log.d(TAG, "  Duration: ${metadata.duration}ms")
    }

    fun logTrackAdded(index: Int, success: Boolean) {
        if (success) {
            Log.d(TAG, "Track[$index] added successfully")
        } else {
            Log.e(TAG, "Track[$index] failed to add")
        }
    }

    fun logPlaybackStart(totalDuration: Long) {
        Log.i(TAG, "=== Playback Started ===")
        Log.i(TAG, "Total duration: ${totalDuration}ms")
    }

    fun logError(operation: String, error: Exception) {
        Log.e(TAG, "Error in $operation: ${error.message}")
        Log.e(TAG, "Stack trace: ${error.stackTraceToString()}")
    }

    private fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / 1024 / 1024
    }

    private fun getOpenFDCount(): Int {
        return File("/proc/self/fd").listFiles()?.size ?: -1
    }
}
```

#### Logcat フィルタリング

```bash
# ClipFlow アプリのログのみ表示
adb logcat -s ClipFlow:*

# エラーのみ表示
adb logcat ClipFlow:E *:S

# メモリ関連のログ
adb logcat | grep -E "(Memory|OutOfMemory|GC)"

# MediaMetadataRetriever 関連
adb logcat | grep MediaMetadataRetriever
```

### 5.3 Android Profiler での確認

#### Memory Profiler

**確認項目：**
1. **Heap Dump の取得**
   - Android Studio → View → Tool Windows → Profiler
   - Memory タブを選択
   - "Dump Java Heap" ボタンをクリック

2. **確認すべきクラス：**
   ```
   MediaMetadataRetriever - インスタンス数が0であること
   ExoPlayer - 1つ以下であること
   Composition - 必要最小限であること
   ```

3. **メモリリークの検出：**
   ```kotlin
   // テストコード
   @Test
   fun detectMemoryLeaks() {
       val initialMemory = getUsedMemory()

       repeat(10) {
           createAndDestroyComposition()
       }

       System.gc()
       Thread.sleep(1000)

       val finalMemory = getUsedMemory()
       val leak = finalMemory - initialMemory

       assertTrue("Memory leak detected: $leak bytes", leak < 1_000_000) // 1MB以下
   }
   ```

#### CPU Profiler

**確認項目：**
1. **処理時間の計測**
   ```kotlin
   fun measureProcessingTime() {
       val startTime = SystemClock.elapsedRealtime()

       createComposition(segments)

       val endTime = SystemClock.elapsedRealtime()
       Log.i("Performance", "Composition took ${endTime - startTime}ms")
   }
   ```

2. **ホットスポットの特定**
   - メタデータ取得が遅い場合 → キャッシュを検討
   - ファイル I/O が遅い場合 → バッファサイズを調整
   - GC が頻繁な場合 → オブジェクト生成を削減

---

## 6. パフォーマンス最適化

### 6.1 Composition 作成時間の短縮

#### メタデータキャッシュ

```kotlin
class MetadataCache {
    private val cache = mutableMapOf<Uri, VideoMetadata>()

    fun getMetadata(context: Context, uri: Uri): VideoMetadata {
        return cache.getOrPut(uri) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                extractMetadata(retriever)
            }
        }
    }

    fun clearCache() {
        cache.clear()
    }

    fun invalidate(uri: Uri) {
        cache.remove(uri)
    }
}
```

#### 並列メタデータ取得

```kotlin
suspend fun getMetadataParallel(
    context: Context,
    segments: List<VideoSegment>
): List<VideoMetadata> = coroutineScope {
    segments.map { segment ->
        async(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, segment.uri)
                extractMetadata(retriever)
            }
        }
    }.awaitAll()
}
```

### 6.2 再生の滑らかさ確認

```kotlin
class PlaybackQualityMonitor(private val player: ExoPlayer) {

    private var droppedFrames = 0
    private var totalFrames = 0

    fun startMonitoring() {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                this@PlaybackQualityMonitor.droppedFrames += droppedFrames
                Log.w("Playback", "Dropped $droppedFrames frames in ${elapsedMs}ms")
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                Log.i("Playback", "First frame rendered in ${renderTimeMs}ms")
            }
        })
    }

    fun getDroppedFramePercentage(): Float {
        if (totalFrames == 0) return 0f
        return (droppedFrames.toFloat() / totalFrames) * 100
    }

    fun reportQuality() {
        val dropPercent = getDroppedFramePercentage()
        when {
            dropPercent < 1 -> Log.i("Quality", "Excellent playback quality")
            dropPercent < 5 -> Log.i("Quality", "Good playback quality")
            dropPercent < 10 -> Log.w("Quality", "Fair playback quality, some jank")
            else -> Log.e("Quality", "Poor playback quality: ${dropPercent}% frames dropped")
        }
    }
}
```

### 6.3 メモリ使用量の監視

```kotlin
class MemoryMonitor(private val context: Context) {

    private val initialMemory = getUsedMemory()
    private val memoryHistory = mutableListOf<Long>()

    fun recordMemoryUsage(operation: String) {
        val currentMemory = getUsedMemory()
        val delta = currentMemory - initialMemory
        memoryHistory.add(currentMemory)

        Log.i("Memory", "$operation: ${currentMemory / 1024 / 1024}MB (+${delta / 1024 / 1024}MB)")
    }

    fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    fun checkThresholds(): MemoryStatus {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return when {
            memoryInfo.lowMemory -> MemoryStatus.CRITICAL
            memoryInfo.availMem < memoryInfo.threshold * 2 -> MemoryStatus.WARNING
            else -> MemoryStatus.NORMAL
        }
    }

    enum class MemoryStatus {
        NORMAL, WARNING, CRITICAL
    }
}
```

---

## 7. テスト方針

### 7.1 単体テスト

```kotlin
@RunWith(AndroidJUnit4::class)
class VideoComposerUnitTest {

    private lateinit var context: Context
    private lateinit var composer: VideoComposer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        composer = VideoComposer(context)
    }

    @After
    fun teardown() {
        composer.cleanup()
    }

    @Test
    fun mediaMetadataRetriever_resourcesReleased() {
        val uri = createTestVideoFile()
        val initialFDs = getOpenFDCount()

        repeat(20) {
            composer.getVideoMetadata(uri)
        }

        System.gc()
        Thread.sleep(100)

        val finalFDs = getOpenFDCount()
        assertEquals("File descriptors leaked", initialFDs, finalFDs)
    }

    @Test
    fun composition_creationSuccess() {
        val segments = createTestSegments(5)
        val result = runBlocking {
            composer.createComposition(segments)
        }
        assertNotNull(result)
    }

    @Test
    fun composition_creationWithInvalidSegment() {
        val segments = listOf(
            createTestSegment(),
            createInvalidSegment(), // 存在しないファイル
            createTestSegment()
        )

        val result = runBlocking {
            composer.createComposition(segments)
        }

        // エラーハンドリングを確認
        assertNotNull(result) // 有効なセグメントのみで作成される
    }

    @Test
    fun fileDeletion_success() {
        val segment = createTestSegment()
        assertTrue(segment.file.exists())

        val deleted = segment.file.delete()

        assertTrue(deleted)
        assertFalse(segment.file.exists())
    }

    @Test
    fun fileDeletion_orderRecalculation() {
        val project = createTestProject(segments = 5)
        val originalOrder = project.segments.map { it.order }

        project.deleteSegment(2) // 3番目のセグメントを削除

        val newOrder = project.segments.map { it.order }
        assertEquals(listOf(0, 1, 2, 3), newOrder) // 順序が再計算される
    }
}
```

### 7.2 統合テスト

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class VideoWorkflowIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun completeFlow_capture_play_export() = runTest {
        // 1. 撮影
        val segment = recordTestSegment(duration = 1000)
        assertNotNull(segment)

        // 2. 再生
        val player = createPlayer(listOf(segment))
        player.prepare()
        player.play()
        delay(2000)
        assertTrue(player.currentPosition > 0)

        // 3. エクスポート
        val exportUri = exportToGallery(listOf(segment))
        assertNotNull(exportUri)

        // クリーンアップ
        player.release()
    }

    @Test
    fun multiplePlaybackCycles() = runTest {
        val segments = createTestSegments(10)
        val player = createPlayer(segments)

        repeat(5) { cycle ->
            player.prepare()
            player.play()
            delay(1000)
            player.stop()

            // メモリリークがないことを確認
            System.gc()
            delay(100)
        }

        player.release()
        verifyNoMemoryLeaks()
    }

    @Test
    fun segmentDeletionDuringPlayback() = runTest {
        val segments = createTestSegments(5).toMutableList()
        val player = createPlayer(segments)

        player.prepare()
        player.play()
        delay(500)

        // 再生中にセグメント削除
        segments.removeAt(2)

        // 再生が継続されることを確認（または適切にエラーハンドリング）
        delay(1000)
        assertTrue(player.isPlaying || player.playbackState == Player.STATE_ENDED)

        player.release()
    }
}
```

### 7.3 ストレステスト

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class StressTest {

    @Test(timeout = 300000) // 5分タイムアウト
    fun rapidSegmentCapture() = runTest {
        val segments = mutableListOf<VideoSegment>()

        // 1秒間に複数セグメント（極端なケース）
        repeat(10) {
            val segment = recordTestSegment(duration = 100)
            segments.add(segment)
            delay(200) // 実際にはあり得ないが限界試験
        }

        assertEquals(10, segments.size)

        // 全セグメントの composition 作成
        val composition = createComposition(segments)
        assertNotNull(composition)
    }

    @Test(timeout = 600000) // 10分タイムアウト
    fun hundredPlusSegments() = runTest {
        val segments = createTestSegments(150)

        val startMemory = getUsedMemory()
        val composition = createComposition(segments)
        val endMemory = getUsedMemory()

        assertNotNull(composition)

        val memoryIncrease = endMemory - startMemory
        assertTrue("Memory increase too high: $memoryIncrease bytes",
            memoryIncrease < 500_000_000) // 500MB以下
    }

    @Test(timeout = 1800000) // 30分タイムアウト
    fun memoryLeakDetection_longPlayback() = runTest {
        val segments = createTestSegments(20)
        val player = createPlayer(segments)

        val initialMemory = getUsedMemory()
        val memorySnapshots = mutableListOf<Long>()

        player.prepare()

        // 30分間の連続再生
        repeat(30) { minute ->
            player.seekTo(0)
            player.play()
            delay(60000) // 1分

            memorySnapshots.add(getUsedMemory())
            Log.i("StressTest", "Minute $minute: ${memorySnapshots.last() / 1024 / 1024}MB")
        }

        player.release()
        System.gc()
        delay(1000)

        val finalMemory = getUsedMemory()
        val leakedMemory = finalMemory - initialMemory

        assertTrue("Memory leak detected: $leakedMemory bytes",
            leakedMemory < 50_000_000) // 50MB以下
    }
}
```

---

## 8. 本番環境での対応

### 8.1 ユーザーデバイスの多様性

#### 低メモリデバイス対応

```kotlin
class AdaptiveVideoProcessor(private val context: Context) {

    private val deviceCapability = DeviceCapabilityDetector(context)

    fun processAdaptively(segments: List<VideoSegment>): ProcessingResult {
        return when {
            deviceCapability.isLowMemoryDevice() -> {
                processForLowMemoryDevice(segments)
            }
            segments.size > 50 -> {
                processInBatches(segments)
            }
            else -> {
                processDirectly(segments)
            }
        }
    }

    private fun processForLowMemoryDevice(segments: List<VideoSegment>): ProcessingResult {
        // 1. セグメント数を制限
        val maxSegments = 20
        if (segments.size > maxSegments) {
            return ProcessingResult.Error(
                "このデバイスでは最大${maxSegments}個のセグメントまで対応しています"
            )
        }

        // 2. バッチサイズを小さく
        val batchSize = 3

        // 3. 積極的な GC
        val result = processInSmallBatches(segments, batchSize)

        return result
    }
}
```

#### 古い Android バージョン対応

```kotlin
object LegacySupport {

    fun getStorageDirectory(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: アプリ専用ディレクトリ
            context.filesDir
        } else {
            // Android 9以下: 外部ストレージも可
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
        }
    }

    fun createVideoOutput(context: Context, fileName: String): FileOutputOptions {
        val outputDir = getStorageDirectory(context)
        val file = File(outputDir, fileName)

        return FileOutputOptions.Builder(file).build()
    }
}
```

### 8.2 エラー通知

#### ユーザーフレンドリーなエラーメッセージ

```kotlin
object ErrorMessages {

    fun getUserFriendlyMessage(error: Throwable): String {
        return when (error) {
            is OutOfMemoryError ->
                "メモリが不足しています。一部の動画を削除してから再試行してください。"

            is FileNotFoundException ->
                "ファイルが見つかりません。アプリを再起動してください。"

            is IllegalStateException -> {
                if (error.message?.contains("setDataSource") == true) {
                    "動画ファイルが破損している可能性があります。該当のセグメントを削除してください。"
                } else {
                    "予期しないエラーが発生しました。アプリを再起動してください。"
                }
            }

            is TooManySegmentsException ->
                "セグメント数が多すぎます。${error.maxAllowed}個以下に減らしてください。"

            else ->
                "エラーが発生しました。問題が続く場合はサポートにお問い合わせください。"
        }
    }

    fun showErrorDialog(context: Context, error: Throwable) {
        AlertDialog.Builder(context)
            .setTitle("エラー")
            .setMessage(getUserFriendlyMessage(error))
            .setPositiveButton("OK", null)
            .setNeutralButton("詳細を送信") { _, _ ->
                sendErrorReport(context, error)
            }
            .show()
    }
}
```

#### クラッシュレポート自動送信

```kotlin
class CrashReporter : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // クラッシュ情報を記録
        val report = buildCrashReport(throwable)
        saveCrashReportLocally(report)

        // 次回起動時に送信フラグを設定
        markPendingCrashReport()

        // デフォルトのハンドラに処理を委譲
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildCrashReport(throwable: Throwable): CrashReport {
        return CrashReport(
            timestamp = System.currentTimeMillis(),
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            stackTrace = throwable.stackTraceToString(),
            memoryInfo = getMemoryInfo(),
            openFileDescriptors = getOpenFDCount()
        )
    }
}
```

### 8.3 アップデート計画

```kotlin
object VersionHistory {

    val releases = listOf(
        Release(
            version = "1.0",
            features = listOf("基本的な撮影・再生機能"),
            knownIssues = listOf("15セグメント制限あり")
        ),
        Release(
            version = "1.1",
            features = listOf(
                "15セグメント制限の回避",
                "リソースリーク修正",
                "エラーハンドリング改善"
            ),
            knownIssues = listOf("100個以上のセグメントで遅延可能性あり")
        ),
        Release(
            version = "1.2",
            features = listOf(
                "100個以上のセグメント対応",
                "バッチ処理の最適化",
                "メモリ使用量削減"
            ),
            knownIssues = emptyList()
        ),
        Release(
            version = "1.3",
            features = listOf(
                "パフォーマンス最適化",
                "UI/UX改善",
                "低スペックデバイス対応強化"
            ),
            knownIssues = emptyList()
        )
    )

    data class Release(
        val version: String,
        val features: List<String>,
        val knownIssues: List<String>
    )
}
```

---

## 9. iOS版との機能パリティ確保

### 9.1 チェックリスト

#### 撮影品質

- [ ] **1秒正確性**
  ```kotlin
  // テスト
  @Test
  fun recordingDurationAccuracy() {
      val segment = recordSegment(targetDuration = 1000)
      val actualDuration = getSegmentDuration(segment)

      // 許容誤差 ±100ms
      assertTrue(actualDuration in 900..1100)
  }
  ```

- [ ] **音声品質**
  - サンプルレート: 44.1kHz または 48kHz
  - ビット深度: 16bit
  - チャンネル: ステレオまたはモノラル

#### 再生体験

- [ ] **ギャップレス再生**
  ```kotlin
  @Test
  fun gaplessPlayback() {
      val segments = createTestSegments(5)
      val player = createPlayer(segments)

      player.prepare()
      player.play()

      // フレームドロップ検出
      val monitor = PlaybackQualityMonitor(player)
      monitor.startMonitoring()

      delay(5000)

      val dropPercent = monitor.getDroppedFramePercentage()
      assertTrue("Frame drops: $dropPercent%", dropPercent < 1)
  }
  ```

- [ ] **スムーズさ**
  - 60fps 維持
  - ジャンク（カクつき）なし
  - シーク操作の即時レスポンス

#### エクスポート機能

- [ ] **品質**
  - 元の解像度を維持
  - ビットレート適切
  - 音声品質維持

- [ ] **速度**
  ```kotlin
  @Test
  fun exportSpeed() {
      val segments = createTestSegments(10) // 10秒分
      val startTime = System.currentTimeMillis()

      val exported = exportVideo(segments)

      val elapsed = System.currentTimeMillis() - startTime
      // リアルタイム以下で完了すること
      assertTrue("Export too slow: ${elapsed}ms", elapsed < 10000)
  }
  ```

#### UI/UX

- [ ] **操作感**
  - タップレスポンス < 100ms
  - アニメーションスムーズ
  - 直感的なナビゲーション

- [ ] **レスポンス**
  - 画面遷移 < 300ms
  - ボタン反応即時
  - 進捗表示正確

### 9.2 ユーザーテスト

```kotlin
class ParityTestSuite {

    @Test
    fun compareWithiOSVersion() {
        val testProject = createStandardTestProject()

        // Android での結果
        val androidResult = TestResult(
            recordingAccuracy = measureRecordingAccuracy(),
            playbackSmoothness = measurePlaybackSmoothness(),
            exportQuality = measureExportQuality(),
            uiResponsiveness = measureUIResponsiveness()
        )

        // iOS での結果（事前に取得）
        val iOSResult = loadiOSBaselineResults()

        // 比較
        compareResults(androidResult, iOSResult)
    }

    private fun compareResults(android: TestResult, iOS: TestResult) {
        // 許容範囲内であることを確認
        assertTrue(
            "Recording accuracy differs too much",
            abs(android.recordingAccuracy - iOS.recordingAccuracy) < 50 // ms
        )

        assertTrue(
            "Playback smoothness differs",
            android.playbackSmoothness >= iOS.playbackSmoothness * 0.95
        )
    }
}
```

---

## 10. ドキュメント化と引き継ぎ

### 10.1 このドキュメント群の活用

#### セクション一覧

| セクション | 内容 | 対象者 |
|----------|------|-------|
| 1-3 | iOS版の基本設計とSwift/SwiftUIの基礎 | 全実装者 |
| 4A | AVComposition の詳細（iOS） | iOS理解が必要な場合 |
| 4B | Media3 Composition の詳細（Android） | メイン実装者 |
| 5a-5b | セグメント管理フロー | 機能実装者 |
| 6 | API対応表 | リファレンスとして常時参照 |
| **7（本文書）** | **実装注意点と失敗対策** | **必読** |

#### 実装者向けクイックスタート

1. **まずセクション7（本文書）を熟読**
   - 15セグメント制限の原因と対策を理解
   - Android固有の制約を把握

2. **セクション4B-1b-i～iiiで詳細実装を参照**
   - VideoComposer の完全な実装
   - ViewModel/Compose 統合

3. **セクション6をリファレンスとして活用**
   - iOS APIのAndroid対応を確認

4. **実装後、本文書のチェックリストで確認**
   - リソースリーク対策
   - メモリ使用量
   - パフォーマンス

### 10.2 今後の改善項目

#### 優先度高

1. **継ぎ目問題のさらなる最適化**
   - 現状：セグメント間で微細なギャップが発生する可能性
   - 対策：フレーム単位での正確なカット、オーディオクロスフェード

2. **UI/UXのさらなる改善**
   - より直感的なセグメント管理
   - リアルタイムプレビュー機能
   - 編集機能（トリミング、並べ替え）

#### 優先度中

3. **クラウドバックアップ機能**
   - Firebase Storage 連携
   - 自動同期
   - マルチデバイス対応

4. **ソーシャル共有機能**
   - SNS直接投稿
   - 共有リンク生成
   - コラボレーション機能

#### 優先度低（将来構想）

5. **AI機能統合**
   - 自動ハイライト生成
   - 音声認識による字幕
   - 顔認識によるベストショット選択

6. **プロ向け機能**
   - マルチトラック編集
   - カラーグレーディング
   - 音声エフェクト

---

## まとめ

### 最重要ポイント

1. **リソースリークを絶対に防ぐ**
   - `MediaMetadataRetriever` は必ず `use()` で包む
   - 全てのリソースに解放処理を明示

2. **Android固有の制約を理解**
   - ファイルハンドル上限（1024）
   - メモリ制限（デバイス依存）
   - APIレベルの違い

3. **iOS版との違いを把握**
   - メモリ管理：手動 vs 自動
   - ライフサイクル：明示的管理が必要
   - スレッド：Dispatcher を明示指定

4. **テストを徹底**
   - 単体テスト、統合テスト、ストレステスト
   - メモリリーク検出
   - パフォーマンス測定

5. **本番環境を想定**
   - 多様なデバイスへの対応
   - ユーザーフレンドリーなエラー処理
   - 段階的なバージョンアップ計画

これらの注意点を守ることで、iOS版と同等の品質を持つ、堅牢なAndroid版ClipFlowを実装できます。
