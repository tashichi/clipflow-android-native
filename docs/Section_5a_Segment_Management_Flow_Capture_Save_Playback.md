# セクション5a: セグメント管理フロー（撮影・保存・再生）

## 1. 撮影 → 保存フロー

### 1.1 CameraView での1秒録画

#### CameraX による録画開始・停止

```kotlin
@Composable
fun CameraScreen(
    projectId: String,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    // カメラ初期化
    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()

        // Recorder の設定
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        // カメラをバインド
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build()

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            videoCapture
        )
    }

    // 録画ボタン
    Button(
        onClick = {
            if (!isRecording) {
                startRecording(
                    context = context,
                    videoCapture = videoCapture,
                    projectId = projectId,
                    onRecordingStarted = { rec ->
                        recording = rec
                        isRecording = true
                    },
                    onRecordingCompleted = { segment ->
                        isRecording = false
                        recording = null
                        viewModel.addSegment(projectId, segment)
                    }
                )
            }
        },
        enabled = !isRecording && videoCapture != null
    ) {
        Text(if (isRecording) "Recording..." else "Record")
    }

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
        }
    }
}
```

#### 自動1秒タイマー実装

```kotlin
private fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>?,
    projectId: String,
    onRecordingStarted: (Recording) -> Unit,
    onRecordingCompleted: (VideoSegment) -> Unit
) {
    videoCapture ?: return

    val timestamp = System.currentTimeMillis()
    val fileName = "segment_${timestamp}.mp4"
    val outputFile = File(context.filesDir, fileName)

    val outputOptions = FileOutputOptions.Builder(outputFile).build()

    // 録画開始
    val recording = videoCapture.output
        .prepareRecording(context, outputOptions)
        .withAudioEnabled()  // ✅ 音声録音を有効化
        .start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Log.d("CameraScreen", "Recording started: $fileName")
                }

                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Log.e("CameraScreen", "Recording error: ${event.error}")
                        outputFile.delete()
                    } else {
                        Log.d("CameraScreen", "Recording completed: $fileName")

                        // ファイルサイズを確認
                        val fileSizeKB = outputFile.length() / 1024
                        Log.d("CameraScreen", "File size: ${fileSizeKB}KB")

                        // VideoSegment を作成
                        val segment = VideoSegment(
                            id = UUID.randomUUID().toString(),
                            uri = fileName,  // 相対パス
                            order = 0,  // 後で設定
                            durationMs = 1000L  // 1秒
                        )

                        onRecordingCompleted(segment)
                    }
                }
            }
        }

    onRecordingStarted(recording)

    // ✅ 1秒後に自動停止
    CoroutineScope(Dispatchers.Main).launch {
        delay(1000)  // 1秒待機
        recording.stop()
        Log.d("CameraScreen", "Recording stopped after 1 second")
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:129-170)
func startRecording() {
    // 1秒録画を開始
    videoManager.startRecording()

    // 1秒後に自動停止
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
        self.videoManager.stopRecording { ... }
    }
}
```

#### 音声録音の有効化

```kotlin
val recording = videoCapture.output
    .prepareRecording(context, outputOptions)
    .withAudioEnabled()  // ✅ これが重要！
    .start(executor) { event -> ... }
```

**必要な権限**:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
                 android:maxSdkVersion="28" />
```

---

### 1.2 セグメント保存

#### ファイルシステムへの保存パス

```kotlin
// アプリ専用ディレクトリに保存
val outputFile = File(context.filesDir, fileName)
// パス: /data/data/com.example.clipflow/files/segment_1234567890.mp4
```

**ディレクトリ構造**:

```
/data/data/com.example.clipflow/
├── files/
│   ├── segment_1234567890.mp4
│   ├── segment_1234567891.mp4
│   ├── segment_1234567892.mp4
│   └── ...
├── cache/
│   ├── composition_1234567890.mp4  (一時ファイル)
│   └── ...
└── shared_prefs/
    └── clipflow_prefs.xml  (プロジェクトデータ)
```

#### ファイル命名規則（timestamp ベース）

```kotlin
// 命名規則: segment_${timestamp}.mp4
val timestamp = System.currentTimeMillis()
val fileName = "segment_${timestamp}.mp4"

// 例:
// segment_1699123456789.mp4
// segment_1699123457890.mp4
```

**利点**:
- 一意性が保証される
- 作成順序が分かる
- ソートが容易

**iOS版との対応**:

```swift
// iOS版 (VideoManager.swift:188)
let fileName = "segment_\(Date().timeIntervalSince1970).mp4"
```

#### ファイルサイズの確認（1.0-1.1MB の正常範囲）

```kotlin
private fun validateSegmentFile(file: File): Boolean {
    if (!file.exists()) {
        Log.e("Validation", "File does not exist: ${file.name}")
        return false
    }

    val fileSizeBytes = file.length()
    val fileSizeKB = fileSizeBytes / 1024
    val fileSizeMB = fileSizeKB / 1024.0

    Log.d("Validation", "File: ${file.name}")
    Log.d("Validation", "Size: ${fileSizeMB}MB (${fileSizeKB}KB)")

    // ✅ 正常範囲: 0.5MB ~ 2.0MB（1秒の動画）
    val isValidSize = fileSizeMB in 0.5..2.0

    if (!isValidSize) {
        Log.w("Validation", "Unusual file size: ${fileSizeMB}MB")
    }

    // ✅ メタデータも確認
    val metadata = getVideoMetadata(file)
    if (metadata == null) {
        Log.e("Validation", "Cannot read metadata")
        return false
    }

    val durationMs = metadata.durationMs
    Log.d("Validation", "Duration: ${durationMs}ms")

    // 1秒（±100ms）の範囲内か
    val isValidDuration = durationMs in 900..1100

    return isValidSize && isValidDuration
}

private fun getVideoMetadata(file: File): VideoMetadata? {
    return MediaMetadataRetriever().use { retriever ->
        try {
            retriever.setDataSource(file.absolutePath)

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
                bitrate = null,
                frameRate = null
            )
        } catch (e: Exception) {
            Log.e("Validation", "Metadata retrieval failed", e)
            null
        }
    }
}
```

---

### 1.3 Project データモデルへの追加

#### VideoSegment オブジェクト作成

```kotlin
// 録画完了時に作成
val segment = VideoSegment(
    id = UUID.randomUUID().toString(),
    uri = fileName,  // "segment_1234567890.mp4"
    order = project.segments.size,  // 次の順番
    durationMs = 1000L  // 1秒
)
```

#### Project.segments リストに追加

```kotlin
class CameraViewModel(
    private val repository: ProjectRepository = ProjectRepository()
) : ViewModel() {

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            _project.value = repository.getProject(projectId)
        }
    }

    fun addSegment(projectId: String, segment: VideoSegment) {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch

            // ✅ order を設定
            val newSegment = segment.copy(
                order = currentProject.segments.size
            )

            // ✅ 新しいセグメントリストを作成
            val updatedSegments = currentProject.segments + newSegment

            // ✅ プロジェクトを更新
            val updatedProject = currentProject.copy(
                segments = updatedSegments,
                lastModified = Date()
            )

            // ✅ Repository に保存
            repository.updateProject(updatedProject)

            // ✅ 状態を更新
            _project.value = updatedProject

            Log.d("CameraViewModel", "Segment added: ${newSegment.id}")
            Log.d("CameraViewModel", "Total segments: ${updatedSegments.size}")
        }
    }

    fun removeLastSegment(projectId: String) {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch

            if (currentProject.segments.isEmpty()) {
                Log.w("CameraViewModel", "No segments to remove")
                return@launch
            }

            // 最後のセグメントを取得
            val lastSegment = currentProject.segments.last()

            // ファイルを削除
            val file = File(context.filesDir, lastSegment.uri)
            if (file.exists()) {
                file.delete()
                Log.d("CameraViewModel", "Deleted file: ${lastSegment.uri}")
            }

            // セグメントリストを更新
            val updatedSegments = currentProject.segments.dropLast(1)
            val updatedProject = currentProject.copy(
                segments = updatedSegments,
                lastModified = Date()
            )

            repository.updateProject(updatedProject)
            _project.value = updatedProject

            Log.d("CameraViewModel", "Last segment removed")
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:180-200)
let newSegment = VideoSegment(
    id: videoSegment.id,
    uri: videoSegment.uri,
    timestamp: videoSegment.timestamp,
    cameraPosition: videoSegment.cameraPosition,
    order: updatedProject.segments.count
)

updatedProject.segments.append(newSegment)
projectManager.updateProject(updatedProject)
```

#### SharedPreferences への永続化

```kotlin
class ProjectRepository(
    private val context: Context
) {
    private val sharedPrefs = context.getSharedPreferences(
        "clipflow_prefs",
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    fun saveProject(project: Project) {
        val projects = loadAllProjects().toMutableList()

        val index = projects.indexOfFirst { it.id == project.id }
        if (index != -1) {
            projects[index] = project
        } else {
            projects.add(project)
        }

        val json = gson.toJson(projects)
        sharedPrefs.edit().putString("projects", json).apply()

        Log.d("ProjectRepository", "Project saved: ${project.id}")
    }

    fun updateProject(project: Project) {
        saveProject(project)
    }

    fun loadAllProjects(): List<Project> {
        val json = sharedPrefs.getString("projects", "[]")
        val type = object : TypeToken<List<Project>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun getProject(projectId: String): Project? {
        return loadAllProjects().find { it.id == projectId }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:33-39, 88-95)
func updateProject(_ updatedProject: Project) {
    if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
        projects[index] = updatedProject
        saveProjects()  // UserDefaults に保存
    }
}

private func saveProjects() {
    if let encoded = try? JSONEncoder().encode(projects) {
        UserDefaults.standard.set(encoded, forKey: "projects")
    }
}
```

---

## 2. 再生フロー

### 2.1 セグメント一覧の取得

#### Project から segments を取得

```kotlin
@Composable
fun PlayerScreen(
    projectId: String,
    viewModel: PlayerViewModel = viewModel()
) {
    val project by viewModel.project.collectAsState()

    // プロジェクト読み込み
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    // セグメント一覧を表示
    project?.let { proj ->
        Column {
            Text("Project: ${proj.name}")
            Text("Segments: ${proj.segments.size}")

            LazyColumn {
                items(proj.segments.sortedBy { it.order }) { segment ->
                    SegmentItem(segment)
                }
            }
        }
    }
}

@Composable
fun SegmentItem(segment: VideoSegment) {
    Row {
        Text("${segment.order + 1}. ")
        Text(segment.uri)
        Text(" (${segment.durationMs}ms)")
    }
}
```

#### 再生順序の確認（order フィールド）

```kotlin
// ✅ order でソートして再生順序を保証
val sortedSegments = project.segments.sortedBy { it.order }

// 確認ログ
sortedSegments.forEachIndexed { index, segment ->
    Log.d("PlayerScreen", "Segment ${index + 1}: order=${segment.order}, uri=${segment.uri}")
}

// order が連続していることを確認
sortedSegments.forEachIndexed { index, segment ->
    if (segment.order != index) {
        Log.w("PlayerScreen", "Order mismatch: expected $index, got ${segment.order}")
    }
}
```

**order の整合性を修正**:

```kotlin
fun reorderSegments(project: Project): Project {
    val reorderedSegments = project.segments
        .sortedBy { it.order }
        .mapIndexed { index, segment ->
            segment.copy(order = index)
        }

    return project.copy(
        segments = reorderedSegments,
        lastModified = Date()
    )
}
```

---

### 2.2 Composition 作成

#### VideoComposer.createComposition() の呼び出し

```kotlin
@Composable
fun PlayerScreen(
    projectId: String,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()
    val compositionState by viewModel.compositionState.collectAsState()

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // Composition 作成
    LaunchedEffect(project) {
        project?.let { proj ->
            if (proj.segments.isNotEmpty()) {
                viewModel.createComposition(proj, context)
            }
        }
    }

    // Composition が完成したらプレーヤーにセット
    LaunchedEffect(compositionState) {
        if (compositionState is CompositionState.Success) {
            val uri = (compositionState as CompositionState.Success).uri
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    // UI
    Column {
        when (val state = compositionState) {
            is CompositionState.Idle -> {
                Text("Ready to create composition")
            }
            is CompositionState.Loading -> {
                LinearProgressIndicator(progress = state.progress)
                Text("Creating composition... ${(state.progress * 100).toInt()}%")
            }
            is CompositionState.Success -> {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.weight(1f)
                )
            }
            is CompositionState.Error -> {
                Text("Error: ${state.message}")
            }
        }
    }

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            viewModel.cleanup()
        }
    }
}
```

**PlayerViewModel での実装**:

```kotlin
class PlayerViewModel : ViewModel() {
    private val composer = VideoComposer(context)

    private val _compositionState = MutableStateFlow<CompositionState>(CompositionState.Idle)
    val compositionState: StateFlow<CompositionState> = _compositionState.asStateFlow()

    fun createComposition(project: Project, context: Context) {
        viewModelScope.launch {
            _compositionState.value = CompositionState.Loading(0f)

            try {
                val uri = composer.createComposition(
                    segments = project.segments,
                    onProgress = { progress ->
                        _compositionState.value = CompositionState.Loading(progress)
                    }
                )

                if (uri != null) {
                    _compositionState.value = CompositionState.Success(uri)
                } else {
                    _compositionState.value = CompositionState.Error("Failed to create composition")
                }
            } catch (e: Exception) {
                _compositionState.value = CompositionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun cleanup() {
        composer.cleanup()
    }
}
```

#### セグメント15個制限の回避方法

```kotlin
// VideoComposer 内部で自動的に処理される
suspend fun createComposition(segments: List<VideoSegment>): Uri? {
    val sortedSegments = segments.sortedBy { it.order }

    return when {
        // ✅ 20個以下: 直接統合
        sortedSegments.size <= 20 -> {
            createDirectComposition(sortedSegments)
        }

        // ✅ 50個以下: バッチサイズ10
        sortedSegments.size <= 50 -> {
            createBatchComposition(sortedSegments, batchSize = 10)
        }

        // ✅ 50個超: バッチサイズ5
        else -> {
            createBatchComposition(sortedSegments, batchSize = 5)
        }
    }
}
```

**なぜ15個で制限が発生したか**:

1. MediaMetadataRetriever の close() 漏れ
2. ファイルハンドルの枯渇
3. メモリリーク

**解決策**:

1. `use {}` で必ずリソースを解放
2. バッチ処理でメモリを制御
3. 定期的に GC を促進

#### 大量セグメント対応（100個以上）

```kotlin
// 100個のセグメントの場合
// バッチサイズ5 → 20バッチに分割
// 各バッチを処理 → 中間ファイル作成
// 中間ファイルを階層的に結合

suspend fun createBatchComposition(
    segments: List<VideoSegment>,
    batchSize: Int
): Uri? {
    val batches = segments.chunked(batchSize)
    val intermediateFiles = mutableListOf<File>()

    try {
        // Phase 1: バッチ処理 (0% ~ 70%)
        batches.forEachIndexed { index, batch ->
            val batchFile = processBatch(batch, index)
            intermediateFiles.add(batchFile)

            // メモリ管理
            if ((index + 1) % 3 == 0) {
                System.gc()
            }
        }

        // Phase 2: 階層的結合 (70% ~ 100%)
        return mergeFilesHierarchically(intermediateFiles)

    } finally {
        // 中間ファイルをクリーンアップ
        intermediateFiles.forEach { it.delete() }
    }
}
```

---

### 2.3 ExoPlayer での再生

#### MediaSource の設定

```kotlin
@Composable
fun PlayerScreen() {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(1000)  // 1秒戻る
            .setSeekForwardIncrementMs(1000)  // 1秒進む
            .build()
    }

    // Composition URI が取得できたら設定
    LaunchedEffect(compositionUri) {
        compositionUri?.let { uri ->
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()

            Log.d("PlayerScreen", "MediaItem set: $uri")
        }
    }

    // プレーヤービュー
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true  // コントローラーを表示
                showBuffering = PlayerView.SHOW_BUFFERING_ALWAYS
            }
        }
    )
}
```

#### ギャップレス再生の確認

```kotlin
@Composable
fun PlayerScreen() {
    val player = remember { ExoPlayer.Builder(context).build() }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var currentSegmentIndex by remember { mutableStateOf(0) }

    // 再生位置の監視
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // ✅ 位置が不連続に変化した場合（ギャップの可能性）
                val jump = newPosition.positionMs - oldPosition.positionMs
                if (abs(jump) > 100) {  // 100ms以上のジャンプ
                    Log.w("PlayerScreen", "Position jump detected: ${jump}ms")
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // 定期的な位置更新
    LaunchedEffect(player) {
        while (true) {
            currentPositionMs = player.currentPosition

            // 現在のセグメントを計算
            val segmentIndex = (currentPositionMs / 1000).toInt()
            if (segmentIndex != currentSegmentIndex) {
                currentSegmentIndex = segmentIndex
                Log.d("PlayerScreen", "Segment ${segmentIndex + 1} playing")
            }

            delay(100)  // 100msごとに更新
        }
    }

    // UI
    Column {
        Text("Position: ${currentPositionMs}ms")
        Text("Segment: ${currentSegmentIndex + 1}")
    }
}
```

**ギャップレス再生の確認方法**:

1. Logcat で "Position jump detected" がないか確認
2. 再生中にセグメント番号が滑らかに増加するか確認
3. 視覚的に再生が途切れないか確認

#### プログレスバーの表示

```kotlin
@Composable
fun PlayerScreen() {
    val player = remember { ExoPlayer.Builder(context).build() }

    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    // プレーヤー状態の監視
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    totalDuration = player.duration
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // 定期的な位置更新
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = player.currentPosition
            delay(100)
        }
    }

    Column {
        // プログレスバー
        Slider(
            value = if (totalDuration > 0) {
                currentPosition.toFloat() / totalDuration
            } else 0f,
            onValueChange = { value ->
                val newPosition = (value * totalDuration).toLong()
                player.seekTo(newPosition)
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 時間表示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(currentPosition))
            Text(formatTime(totalDuration))
        }

        // 再生/一時停止ボタン
        Button(
            onClick = {
                if (isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        ) {
            Text(if (isPlaying) "Pause" else "Play")
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%02d:%02d".format(minutes, remainingSeconds)
}
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift:352-420)
private var seekableProgressBar: some View {
    GeometryReader { geometry in
        ZStack(alignment: .leading) {
            // 背景
            Rectangle().fill(Color.gray.opacity(0.3))

            // 進捗
            Rectangle()
                .fill(Color.white)
                .frame(width: geometry.size.width * (currentTime / duration))
        }
    }
}
```

---

## 3. データ構造の詳細（基本）

### 3.1 Project の構造

```kotlin
data class Project(
    val id: String,
    var name: String,
    var segments: List<VideoSegment>,
    val createdAt: Long,  // timestamp
    var lastModified: Long  // timestamp
)
```

**使用例**:

```kotlin
val project = Project(
    id = UUID.randomUUID().toString(),
    name = "My First Project",
    segments = emptyList(),
    createdAt = System.currentTimeMillis(),
    lastModified = System.currentTimeMillis()
)
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift)
struct Project: Codable, Identifiable {
    let id: Int
    var name: String
    var segments: [VideoSegment]
    let createdAt: Date
    var lastModified: Date
}
```

### 3.2 VideoSegment の構造

```kotlin
data class VideoSegment(
    val id: String,
    val uri: String,  // ファイル名（相対パス）
    val order: Int,  // 再生順序
    val durationMs: Long  // 長さ（ミリ秒）
)
```

**使用例**:

```kotlin
val segment = VideoSegment(
    id = UUID.randomUUID().toString(),
    uri = "segment_1699123456789.mp4",
    order = 0,
    durationMs = 1000L
)
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift)
struct VideoSegment: Codable, Identifiable {
    let id: Int
    let uri: String
    let timestamp: Date
    let cameraPosition: String
    var order: Int
}
```

**注意**: Android版は `cameraPosition` と `timestamp` を省略していますが、必要に応じて追加可能です。

---

## 4. ファイルシステム管理（基本）

### 4.1 ストレージパス

#### アプリ専用ディレクトリ

```kotlin
// 内部ストレージ（アプリ専用）
val filesDir = context.filesDir
// パス: /data/data/com.example.clipflow/files/

// キャッシュディレクトリ
val cacheDir = context.cacheDir
// パス: /data/data/com.example.clipflow/cache/
```

**利点**:
- ルート権限不要
- アプリアンインストール時に自動削除
- 他のアプリからアクセス不可

#### ファイル命名規則

```kotlin
// セグメントファイル
val segmentFileName = "segment_${System.currentTimeMillis()}.mp4"
// 例: segment_1699123456789.mp4

// Composition 一時ファイル
val compositionFileName = "composition_${System.currentTimeMillis()}.mp4"
// 例: composition_1699123457890.mp4

// バッチ処理一時ファイル
val batchFileName = "batch_${index}_${System.currentTimeMillis()}.mp4"
// 例: batch_0_1699123458901.mp4
```

### 4.2 ストレージ容量管理

#### 空き容量の確認方法

```kotlin
fun getAvailableStorageSpace(context: Context): Long {
    val filesDir = context.filesDir
    val statFs = StatFs(filesDir.absolutePath)

    val availableBytes = statFs.availableBytes
    val availableMB = availableBytes / 1024 / 1024
    val availableGB = availableMB / 1024.0

    Log.d("Storage", "Available: ${availableGB}GB ($availableMB MB)")

    return availableBytes
}

fun checkStorageBeforeRecording(context: Context): Boolean {
    val availableBytes = getAvailableStorageSpace(context)

    // 1セグメント ≈ 1-2MB、余裕を持って10MB必要
    val requiredBytes = 10 * 1024 * 1024L  // 10MB

    if (availableBytes < requiredBytes) {
        Log.w("Storage", "Low storage space: ${availableBytes / 1024 / 1024}MB")
        return false
    }

    return true
}

fun getProjectStorageUsage(project: Project, context: Context): Long {
    var totalSize = 0L

    project.segments.forEach { segment ->
        val file = File(context.filesDir, segment.uri)
        if (file.exists()) {
            totalSize += file.length()
        }
    }

    val totalMB = totalSize / 1024 / 1024.0
    Log.d("Storage", "Project ${project.name} uses ${totalMB}MB")

    return totalSize
}
```

**警告表示**:

```kotlin
@Composable
fun StorageWarning(context: Context) {
    val availableMB = getAvailableStorageSpace(context) / 1024 / 1024

    if (availableMB < 100) {  // 100MB未満
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Red.copy(alpha = 0.2f)
            )
        ) {
            Text(
                "Storage space is low: ${availableMB}MB",
                color = Color.Red
            )
        }
    }
}
```

---

## 5. iOS版との対応関係

### MainView での画面遷移フロー

**iOS版**:

```swift
// MainView.swift
@State private var currentScreen: ScreenType = .projects
@StateObject private var projectManager = ProjectManager()

var body: some View {
    switch currentScreen {
    case .projects:
        ProjectListView(
            onNavigateToCamera: { project in
                selectedProject = project
                currentScreen = .camera
            }
        )
    case .camera:
        CameraView(
            onBackToProjects: {
                currentScreen = .projects
            },
            onRecordingComplete: { segment in
                projectManager.updateProject(...)
            }
        )
    case .player:
        PlayerView(
            onBack: {
                currentScreen = .projects
            }
        )
    }
}
```

**Android版**:

```kotlin
// AppNavigation.kt
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "projects") {
        composable("projects") {
            val viewModel: ProjectViewModel = viewModel()

            ProjectListScreen(
                onNavigateToCamera = { projectId ->
                    navController.navigate("camera/$projectId")
                },
                onNavigateToPlayer = { projectId ->
                    navController.navigate("player/$projectId")
                }
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val viewModel: CameraViewModel = viewModel()

            CameraScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
                onRecordingComplete = { segment ->
                    viewModel.addSegment(projectId, segment)
                }
            )
        }

        composable("player/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val viewModel: PlayerViewModel = viewModel()

            PlayerScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

### ProjectManager での状態管理

**iOS版**:

```swift
// ProjectManager.swift
class ProjectManager: ObservableObject {
    @Published var projects: [Project] = []

    init() {
        loadProjects()
    }

    func updateProject(_ updatedProject: Project) {
        if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
            projects[index] = updatedProject
            saveProjects()
        }
    }

    private func saveProjects() {
        if let encoded = try? JSONEncoder().encode(projects) {
            UserDefaults.standard.set(encoded, forKey: "projects")
        }
    }
}
```

**Android版**:

```kotlin
// ProjectViewModel.kt
class ProjectViewModel(
    private val repository: ProjectRepository = ProjectRepository()
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        loadProjects()
    }

    fun updateProject(updatedProject: Project) {
        viewModelScope.launch {
            repository.updateProject(updatedProject)

            _projects.value = _projects.value.map { project ->
                if (project.id == updatedProject.id) updatedProject else project
            }
        }
    }
}

// ProjectRepository.kt
class ProjectRepository(context: Context) {
    private val sharedPrefs = context.getSharedPreferences("clipflow_prefs", MODE_PRIVATE)
    private val gson = Gson()

    fun updateProject(project: Project) {
        val projects = loadAllProjects().toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }

        if (index != -1) {
            projects[index] = project
            saveProjects(projects)
        }
    }

    private fun saveProjects(projects: List<Project>) {
        val json = gson.toJson(projects)
        sharedPrefs.edit().putString("projects", json).apply()
    }
}
```

### 対応関係表

| 機能 | iOS版 | Android版 |
|------|-------|----------|
| 画面遷移 | @State currentScreen | NavController |
| 状態管理 | @StateObject + ObservableObject | ViewModel + StateFlow |
| データ永続化 | UserDefaults + JSONEncoder | SharedPreferences + Gson |
| 録画 | AVCaptureSession | CameraX VideoCapture |
| 再生 | AVPlayer + AVComposition | ExoPlayer + Media3 Composition |
| ファイル保存 | FileManager (Documents) | context.filesDir |
| 非同期処理 | async/await + Task | suspend + Coroutines |
| リソース解放 | ARC (自動) | close() / release() (手動) |

---

## まとめ

### フロー全体図

```
[撮影フロー]
1. ユーザーが録画ボタンをタップ
   ↓
2. CameraX で1秒録画開始
   ↓
3. 1秒後に自動停止
   ↓
4. ファイルが context.filesDir に保存
   ↓
5. VideoSegment オブジェクト作成
   ↓
6. Project.segments に追加
   ↓
7. SharedPreferences に永続化

[再生フロー]
1. PlayerScreen 表示
   ↓
2. Project をロード
   ↓
3. segments を order でソート
   ↓
4. VideoComposer.createComposition() 呼び出し
   ↓
5. (バッチ処理でセグメントを統合)
   ↓
6. Composition URI を取得
   ↓
7. ExoPlayer に MediaItem をセット
   ↓
8. player.prepare() & player.play()
   ↓
9. ギャップレス再生
```

### 重要なチェックポイント

1. **録画時**: 音声が有効か？ファイルサイズは正常か？
2. **保存時**: ファイルパスは正しいか？order は連続しているか？
3. **再生時**: Composition 作成は成功したか？ギャップはないか？
4. **クリーンアップ**: 一時ファイルは削除されているか？メモリリークはないか？

これらのフローを正しく実装することで、iOS版と同等の機能を Android で実現できます。

---

*以上がセクション5a「セグメント管理フロー（撮影・保存・再生）」です。*
