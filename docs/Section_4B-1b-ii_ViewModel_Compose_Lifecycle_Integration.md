# セクション4B-1b-ii: ViewModel・Compose ライフサイクル統合ガイド

## 1. ViewModel での Composition 作成

### 1.1 viewModelScope での非同期実行

#### 基本パターン

```kotlin
class PlayerViewModel(
    private val compositionBuilder: CompositionBuilder,
    private val repository: ProjectRepository
) : ViewModel() {

    // 状態管理
    private val _compositionState = MutableStateFlow<CompositionState>(CompositionState.Idle)
    val compositionState: StateFlow<CompositionState> = _compositionState.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    // 一時ファイルの管理
    private val temporaryFiles = mutableListOf<File>()

    // 現在のジョブ（キャンセル用）
    private var compositionJob: Job? = null

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            try {
                val loadedProject = repository.getProject(projectId)
                _project.value = loadedProject
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to load project", e)
            }
        }
    }

    fun createComposition(project: Project, context: Context) {
        // ✅ 前のジョブがあればキャンセル
        compositionJob?.cancel()

        compositionJob = viewModelScope.launch {
            _compositionState.value = CompositionState.Loading(0f)

            try {
                val uri = compositionBuilder.createCompositionWithMemoryOptimization(
                    segments = project.segments,
                    onProgress = { progress ->
                        // ✅ 進捗を StateFlow で公開
                        _compositionState.value = CompositionState.Loading(progress)
                    }
                )

                if (uri != null) {
                    _compositionState.value = CompositionState.Success(uri)
                    Log.d("PlayerViewModel", "Composition created: $uri")
                } else {
                    _compositionState.value = CompositionState.Error("Failed to create composition")
                }
            } catch (e: CancellationException) {
                // ✅ キャンセルされた場合
                Log.d("PlayerViewModel", "Composition creation cancelled")
                _compositionState.value = CompositionState.Idle
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Composition creation failed", e)
                _compositionState.value = CompositionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ✅ ViewModel 破棄時のクリーンアップ
    override fun onCleared() {
        super.onCleared()

        // 進行中のジョブをキャンセル
        compositionJob?.cancel()

        // 一時ファイルを削除
        temporaryFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
                Log.d("PlayerViewModel", "Deleted temp file: ${file.name}")
            }
        }
        temporaryFiles.clear()

        Log.d("PlayerViewModel", "ViewModel cleared, all resources released")
    }
}

// 状態を表す sealed class
sealed class CompositionState {
    object Idle : CompositionState()
    data class Loading(val progress: Float) : CompositionState()
    data class Success(val uri: Uri) : CompositionState()
    data class Error(val message: String) : CompositionState()
}
```

#### キャンセレーション処理

```kotlin
class PlayerViewModel : ViewModel() {

    private var compositionJob: Job? = null

    fun createComposition(project: Project, context: Context) {
        // ✅ 前のジョブをキャンセル（重要！）
        compositionJob?.cancel()

        compositionJob = viewModelScope.launch {
            try {
                // 長時間の処理
                val result = withContext(Dispatchers.IO) {
                    createCompositionInternal(project, context)
                }

                // ✅ キャンセルされていないか確認
                ensureActive()

                // 結果を処理
                handleResult(result)

            } catch (e: CancellationException) {
                // ✅ キャンセル時のクリーンアップ
                Log.d("PlayerViewModel", "Job cancelled, cleaning up")
                cleanupPartialWork()
                throw e // 再スロー必須
            }
        }
    }

    // 画面遷移前に呼び出す
    fun cancelCompositionCreation() {
        compositionJob?.let { job ->
            if (job.isActive) {
                job.cancel()
                Log.d("PlayerViewModel", "Composition creation cancelled by user")
            }
        }
    }

    private suspend fun createCompositionInternal(
        project: Project,
        context: Context
    ): Uri? = withContext(Dispatchers.IO) {

        val segments = project.segments.sortedBy { it.order }

        for ((index, segment) in segments.withIndex()) {
            // ✅ 各ステップでキャンセルをチェック
            ensureActive()

            processSegment(segment)

            Log.d("PlayerViewModel", "Processed segment ${index + 1}/${segments.size}")
        }

        // 最終的な Composition を作成
        buildFinalComposition()
    }

    private fun cleanupPartialWork() {
        // 部分的に作成された一時ファイルを削除
        temporaryFiles.forEach { it.delete() }
        temporaryFiles.clear()
    }
}
```

---

### 1.2 StateFlow による状態管理

#### Composition 作成状態の公開

```kotlin
class PlayerViewModel : ViewModel() {

    // ✅ Private: 内部でのみ変更可能
    private val _compositionState = MutableStateFlow<CompositionState>(CompositionState.Idle)

    // ✅ Public: 外部からは読み取り専用
    val compositionState: StateFlow<CompositionState> = _compositionState.asStateFlow()

    // ✅ 詳細な状態
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun createComposition(project: Project, context: Context) {
        viewModelScope.launch {
            // ローディング開始
            _compositionState.value = CompositionState.Loading(0f)
            _playerState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val uri = buildComposition(project, context) { progress ->
                    // ✅ 進捗を逐次更新
                    _compositionState.value = CompositionState.Loading(progress)
                    _playerState.update { it.copy(loadingProgress = progress) }
                }

                // 成功
                _compositionState.value = CompositionState.Success(uri)
                _playerState.update {
                    it.copy(
                        isLoading = false,
                        compositionUri = uri,
                        totalDuration = calculateTotalDuration(project)
                    )
                }

            } catch (e: Exception) {
                // エラー
                _compositionState.value = CompositionState.Error(e.message ?: "Unknown error")
                _playerState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message
                    )
                }
            }
        }
    }
}

// 詳細な状態を保持するデータクラス
data class PlayerState(
    val isLoading: Boolean = false,
    val loadingProgress: Float = 0f,
    val compositionUri: Uri? = null,
    val currentPosition: Long = 0L,
    val totalDuration: Long = 0L,
    val isPlaying: Boolean = false,
    val currentSegmentIndex: Int = 0,
    val errorMessage: String? = null
)
```

#### エラー状態の通知

```kotlin
class PlayerViewModel : ViewModel() {

    private val _compositionState = MutableStateFlow<CompositionState>(CompositionState.Idle)
    val compositionState: StateFlow<CompositionState> = _compositionState.asStateFlow()

    // ✅ エラーイベント（一度だけ処理される）
    private val _errorEvent = MutableSharedFlow<ErrorEvent>()
    val errorEvent: SharedFlow<ErrorEvent> = _errorEvent.asSharedFlow()

    fun createComposition(project: Project, context: Context) {
        viewModelScope.launch {
            try {
                // Composition 作成処理...

            } catch (e: OutOfMemoryError) {
                // ✅ メモリ不足エラー
                val event = ErrorEvent.OutOfMemory(
                    segmentCount = project.segments.size,
                    message = "セグメント数が多すぎます。分割して処理してください。"
                )
                _errorEvent.emit(event)
                _compositionState.value = CompositionState.Error(event.message)

            } catch (e: FileNotFoundException) {
                // ✅ ファイルが見つからないエラー
                val event = ErrorEvent.FileNotFound(
                    fileName = e.message ?: "Unknown",
                    message = "セグメントファイルが見つかりません。"
                )
                _errorEvent.emit(event)
                _compositionState.value = CompositionState.Error(event.message)

            } catch (e: Exception) {
                // ✅ その他のエラー
                val event = ErrorEvent.General(
                    message = e.message ?: "不明なエラーが発生しました。"
                )
                _errorEvent.emit(event)
                _compositionState.value = CompositionState.Error(event.message)
            }
        }
    }

    // エラーをクリア
    fun clearError() {
        _compositionState.value = CompositionState.Idle
    }
}

// エラーイベント
sealed class ErrorEvent {
    abstract val message: String

    data class OutOfMemory(
        val segmentCount: Int,
        override val message: String
    ) : ErrorEvent()

    data class FileNotFound(
        val fileName: String,
        override val message: String
    ) : ErrorEvent()

    data class General(
        override val message: String
    ) : ErrorEvent()
}
```

---

## 2. Compose DisposableEffect での破棄

### 2.1 Composition リソースの適切な解放

#### onDispose での処理

```kotlin
@Composable
fun PlayerScreen(
    projectId: String,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val compositionState by viewModel.compositionState.collectAsState()

    // ✅ ExoPlayer を remember で作成
    val player = remember {
        ExoPlayer.Builder(context).build().also {
            Log.d("PlayerScreen", "ExoPlayer created")
        }
    }

    // Composition が成功したらプレーヤーにセット
    LaunchedEffect(compositionState) {
        if (compositionState is CompositionState.Success) {
            val uri = (compositionState as CompositionState.Success).uri
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    // ✅ プレーヤーリスナーの管理
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        player.seekTo(0)
                        player.pause()
                    }
                }
            }
        }

        player.addListener(listener)
        Log.d("PlayerScreen", "Player listener added")

        onDispose {
            // ✅ リスナーを削除
            player.removeListener(listener)
            Log.d("PlayerScreen", "Player listener removed")
        }
    }

    // ✅ プレーヤーの解放（最も重要）
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Releasing ExoPlayer")

            // 1. 再生を停止
            player.stop()

            // 2. プレーヤーを解放
            player.release()

            // 3. 一時ファイルを削除
            cleanupTemporaryFiles(context)

            Log.d("PlayerScreen", "ExoPlayer released and cleanup completed")
        }
    }

    // UI...
}

private fun cleanupTemporaryFiles(context: Context) {
    try {
        val cacheDir = context.cacheDir
        val compositionFiles = cacheDir.listFiles { file ->
            file.name.startsWith("composition_") && file.extension == "mp4"
        }

        compositionFiles?.forEach { file ->
            if (file.delete()) {
                Log.d("PlayerScreen", "Deleted: ${file.name}")
            }
        }
    } catch (e: Exception) {
        Log.e("PlayerScreen", "Cleanup failed", e)
    }
}
```

#### Recomposition 時の安全な再初期化

```kotlin
@Composable
fun PlayerScreen(
    projectId: String,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()

    // ✅ remember: Composable が破棄されるまで同じインスタンスを保持
    // Recomposition では再作成されない
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // ✅ project が変更されたときのみ再実行
    LaunchedEffect(project) {
        project?.let { proj ->
            Log.d("PlayerScreen", "Project changed, reloading composition")

            // 古いメディアをクリア
            player.clearMediaItems()

            // 新しい Composition を作成
            viewModel.createComposition(proj, context)
        }
    }

    // ✅ key を projectId にすることで、プロジェクトが変わったら再初期化
    key(projectId) {
        PlayerContent(
            player = player,
            viewModel = viewModel
        )
    }

    // クリーンアップは Unit key なので、Composable 破棄時のみ実行
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }
}

@Composable
private fun PlayerContent(
    player: ExoPlayer,
    viewModel: PlayerViewModel
) {
    val compositionState by viewModel.compositionState.collectAsState()

    // この Composable は Recomposition されても、
    // player インスタンスは保持される

    when (val state = compositionState) {
        is CompositionState.Loading -> {
            LoadingIndicator(progress = state.progress)
        }
        is CompositionState.Success -> {
            PlayerView(player = player)
        }
        is CompositionState.Error -> {
            ErrorMessage(message = state.message)
        }
        else -> {
            // Idle state
        }
    }
}
```

---

### 2.2 LaunchedEffect との使い分け

#### いつ LaunchedEffect を使うか

```kotlin
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = viewModel()) {
    val projectId = "..."
    val compositionState by viewModel.compositionState.collectAsState()

    // ✅ 1. 初期化処理（非同期）
    LaunchedEffect(Unit) {
        viewModel.loadProject(projectId)
    }

    // ✅ 2. 依存する値が変わったときに再実行
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    // ✅ 3. 状態変化に応じた処理
    LaunchedEffect(compositionState) {
        when (compositionState) {
            is CompositionState.Success -> {
                Log.d("PlayerScreen", "Composition ready")
            }
            is CompositionState.Error -> {
                Log.e("PlayerScreen", "Error occurred")
            }
            else -> {}
        }
    }

    // ✅ 4. 定期的なポーリング
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updatePlaybackPosition()
            delay(100) // 100ms ごとに更新
        }
    }

    // ✅ 5. API リクエスト
    LaunchedEffect(Unit) {
        val result = viewModel.fetchRemoteConfig()
        // 結果を処理
    }
}
```

**LaunchedEffect の特徴**:

- コルーチンスコープ内で実行
- Composable が破棄されると自動的にキャンセル
- key が変更されると古いコルーチンがキャンセルされ、新しいものが開始
- 明示的なクリーンアップは不要（自動キャンセル）

#### いつ DisposableEffect を使うか

```kotlin
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember { ExoPlayer.Builder(context).build() }

    // ✅ 1. リソースの取得と解放
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    // ✅ 2. Listener の登録と解除
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // ...
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // ✅ 3. Lifecycle Observer の登録と解除
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ✅ 4. BroadcastReceiver の登録と解除
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Handle broadcast
            }
        }

        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        context.registerReceiver(receiver, filter)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}
```

**DisposableEffect の特徴**:

- onDispose {} で明示的なクリーンアップが必須
- リソースの取得と解放をペアで管理
- key が変更されると古い Effect が破棄され、新しいものが開始
- 非同期処理には向かない（コルーチンスコープなし）

#### 使い分け判断表

| シナリオ | 使用する Effect | 理由 |
|---------|----------------|------|
| データの読み込み | LaunchedEffect | 非同期処理、自動キャンセル |
| プレーヤーの初期化 | remember + DisposableEffect | リソースの解放が必要 |
| API リクエスト | LaunchedEffect | 非同期処理 |
| Listener の登録 | DisposableEffect | 登録と解除のペア管理 |
| 定期的なポーリング | LaunchedEffect | 無限ループ、自動キャンセル |
| Lifecycle の監視 | DisposableEffect | Observer の登録と解除 |
| 一時ファイルのクリーンアップ | DisposableEffect | 明示的な解放が必要 |

---

## 3. 画面遷移時のライフサイクル

### 3.1 PlayerScreen → CameraScreen への遷移

#### リソースの適切な破棄

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("player/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val viewModel: PlayerViewModel = viewModel(backStackEntry)

            PlayerScreen(
                projectId = projectId,
                viewModel = viewModel,
                onNavigateToCamera = {
                    // ✅ 画面遷移前にリソースをクリーンアップ
                    viewModel.cancelCompositionCreation()
                    navController.navigate("camera/$projectId")
                }
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val viewModel: CameraViewModel = viewModel(backStackEntry)

            CameraScreen(
                projectId = projectId,
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun PlayerScreen(
    projectId: String,
    viewModel: PlayerViewModel,
    onNavigateToCamera: () -> Unit
) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // ✅ 画面破棄時のクリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Screen disposing, releasing resources")

            // 1. プレーヤーを解放
            player.release()

            // 2. 一時ファイルを削除
            cleanupTemporaryFiles(context)

            // 3. ViewModel の状態をリセット
            viewModel.resetState()

            Log.d("PlayerScreen", "All resources released")
        }
    }

    // UI: カメラ画面への遷移ボタン
    Button(onClick = onNavigateToCamera) {
        Text("Go to Camera")
    }
}
```

#### 新しい Composition の初期化

```kotlin
@Composable
fun CameraScreen(
    projectId: String,
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // ✅ 新しい画面でのリソース初期化
    LaunchedEffect(Unit) {
        Log.d("CameraScreen", "Initializing camera resources")

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            Log.d("CameraScreen", "Camera provider initialized")
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ カメラのバインド
    LaunchedEffect(cameraProvider) {
        cameraProvider?.let { provider ->
            viewModel.bindCamera(provider, lifecycleOwner)
        }
    }

    // ✅ 画面破棄時のクリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindCamera()
            Log.d("CameraScreen", "Camera resources released")
        }
    }
}
```

---

### 3.2 メモリリーク防止

#### 前のセッションのリソース完全破棄

```kotlin
class PlayerViewModel : ViewModel() {

    private var player: ExoPlayer? = null
    private val temporaryFiles = mutableListOf<File>()
    private var compositionJob: Job? = null

    // ✅ 状態をリセット
    fun resetState() {
        viewModelScope.launch {
            // 進行中のジョブをキャンセル
            compositionJob?.cancel()
            compositionJob = null

            // 状態をリセット
            _compositionState.value = CompositionState.Idle
            _playerState.value = PlayerState()

            // 一時ファイルを削除
            temporaryFiles.forEach { it.delete() }
            temporaryFiles.clear()

            Log.d("PlayerViewModel", "State reset completed")
        }
    }

    // ✅ ViewModel 破棄時の完全クリーンアップ
    override fun onCleared() {
        super.onCleared()

        Log.d("PlayerViewModel", "ViewModel being cleared")

        // 1. 進行中のジョブをキャンセル
        compositionJob?.cancel()

        // 2. プレーヤーを解放（もし ViewModel が保持している場合）
        player?.release()
        player = null

        // 3. 一時ファイルを削除
        temporaryFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        temporaryFiles.clear()

        // 4. メモリを解放
        System.gc()

        Log.d("PlayerViewModel", "All resources cleaned up")
    }
}
```

#### Coroutine のキャンセル

```kotlin
class PlayerViewModel : ViewModel() {

    private var compositionJob: Job? = null
    private var exportJob: Job? = null
    private var progressUpdateJob: Job? = null

    fun createComposition(project: Project, context: Context) {
        // ✅ 前のジョブをすべてキャンセル
        cancelAllJobs()

        compositionJob = viewModelScope.launch {
            try {
                // Composition 作成処理...

                // ✅ 定期的にキャンセルをチェック
                ensureActive()

            } catch (e: CancellationException) {
                Log.d("PlayerViewModel", "Composition job cancelled")
                cleanupPartialWork()
                throw e
            }
        }
    }

    fun startProgressUpdates() {
        progressUpdateJob = viewModelScope.launch {
            while (isActive) { // ✅ isActive でキャンセルをチェック
                updateProgress()
                delay(100)
            }
        }
    }

    // ✅ すべてのジョブをキャンセル
    private fun cancelAllJobs() {
        compositionJob?.cancel()
        exportJob?.cancel()
        progressUpdateJob?.cancel()

        compositionJob = null
        exportJob = null
        progressUpdateJob = null

        Log.d("PlayerViewModel", "All jobs cancelled")
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllJobs()
    }
}
```

#### Compose でのリーク防止パターン

```kotlin
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = viewModel()) {
    val context = LocalContext.current

    // ✅ remember で作成したリソースは必ず DisposableEffect で解放
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // ✅ StateFlow の収集は自動的にスコープに紐づく
    val compositionState by viewModel.compositionState.collectAsState()

    // ✅ エラーイベントの収集（一度だけ処理）
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { event ->
            // エラーを処理
            showErrorDialog(event)
        }
    }

    // ✅ Lifecycle に応じたリソース管理
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // バックグラウンドに移行したときにリソースを一時停止
                    player.pause()
                    Log.d("PlayerScreen", "App went to background, player paused")
                }
                Lifecycle.Event.ON_START -> {
                    // フォアグラウンドに戻ったときに再開
                    Log.d("PlayerScreen", "App came to foreground")
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ✅ 必ず解放
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    // UI...
}
```

---

## 4. 実装例：PlayerViewModel.kt

### 完全な実装

```kotlin
package com.example.clipflow.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.clipflow.data.Project
import com.example.clipflow.data.ProjectRepository
import com.example.clipflow.data.VideoSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class PlayerViewModel(
    private val repository: ProjectRepository = ProjectRepository()
) : ViewModel() {

    // ========== 状態管理 ==========

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _compositionState = MutableStateFlow<CompositionState>(CompositionState.Idle)
    val compositionState: StateFlow<CompositionState> = _compositionState.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _errorEvent = MutableSharedFlow<ErrorEvent>()
    val errorEvent: SharedFlow<ErrorEvent> = _errorEvent.asSharedFlow()

    // ========== リソース管理 ==========

    private var compositionJob: Job? = null
    private val temporaryFiles = mutableListOf<File>()
    private var cacheDir: File? = null

    // ========== 公開メソッド ==========

    fun initialize(context: Context) {
        cacheDir = context.cacheDir
        Log.d(TAG, "PlayerViewModel initialized")
    }

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading project: $projectId")
                val loadedProject = repository.getProject(projectId)
                _project.value = loadedProject
                Log.d(TAG, "Project loaded: ${loadedProject?.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load project", e)
                _errorEvent.emit(ErrorEvent.General("プロジェクトの読み込みに失敗しました"))
            }
        }
    }

    fun createComposition(project: Project, context: Context) {
        // 前のジョブをキャンセル
        compositionJob?.cancel()

        compositionJob = viewModelScope.launch {
            Log.d(TAG, "Creating composition for ${project.segments.size} segments")

            _compositionState.value = CompositionState.Loading(0f)
            _playerState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val uri = createCompositionInternal(project, context)

                if (uri != null) {
                    _compositionState.value = CompositionState.Success(uri)
                    _playerState.update {
                        it.copy(
                            isLoading = false,
                            compositionUri = uri,
                            totalDuration = calculateTotalDuration(project)
                        )
                    }
                    Log.d(TAG, "Composition created successfully: $uri")
                } else {
                    throw Exception("Composition creation returned null")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Composition creation cancelled")
                _compositionState.value = CompositionState.Idle
                _playerState.update { it.copy(isLoading = false) }
                cleanupPartialWork()
                throw e

            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory", e)
                val event = ErrorEvent.OutOfMemory(
                    segmentCount = project.segments.size,
                    message = "メモリ不足です。セグメント数を減らしてください。"
                )
                _errorEvent.emit(event)
                _compositionState.value = CompositionState.Error(event.message)
                _playerState.update { it.copy(isLoading = false, errorMessage = event.message) }

            } catch (e: Exception) {
                Log.e(TAG, "Composition creation failed", e)
                val event = ErrorEvent.General(e.message ?: "不明なエラーが発生しました")
                _errorEvent.emit(event)
                _compositionState.value = CompositionState.Error(event.message)
                _playerState.update { it.copy(isLoading = false, errorMessage = event.message) }
            }
        }
    }

    fun cancelCompositionCreation() {
        compositionJob?.let { job ->
            if (job.isActive) {
                job.cancel()
                Log.d(TAG, "Composition creation cancelled by user")
            }
        }
    }

    fun updatePlaybackPosition(positionMs: Long) {
        _playerState.update { it.copy(currentPosition = positionMs) }
    }

    fun setPlaying(isPlaying: Boolean) {
        _playerState.update { it.copy(isPlaying = isPlaying) }
    }

    fun setCurrentSegmentIndex(index: Int) {
        _playerState.update { it.copy(currentSegmentIndex = index) }
    }

    fun resetState() {
        viewModelScope.launch {
            compositionJob?.cancel()
            compositionJob = null

            _compositionState.value = CompositionState.Idle
            _playerState.value = PlayerState()

            cleanupTemporaryFiles()

            Log.d(TAG, "State reset completed")
        }
    }

    fun clearError() {
        _compositionState.value = CompositionState.Idle
        _playerState.update { it.copy(errorMessage = null) }
    }

    // ========== 内部メソッド ==========

    private suspend fun createCompositionInternal(
        project: Project,
        context: Context
    ): Uri? = withContext(Dispatchers.IO) {

        val segments = project.segments.sortedBy { it.order }
        val totalSegments = segments.size

        Log.d(TAG, "Processing $totalSegments segments")

        // セグメント数に応じて処理方法を選択
        when {
            totalSegments <= 20 -> createDirectComposition(segments, context)
            else -> createBatchComposition(segments, context, batchSize = 10)
        }
    }

    private suspend fun createDirectComposition(
        segments: List<VideoSegment>,
        context: Context
    ): Uri? {
        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        segments.forEachIndexed { index, segment ->
            ensureActive() // キャンセルチェック

            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                sequenceBuilder.addMediaItem(editedMediaItem)

                // 進捗更新
                val progress = (index + 1).toFloat() / segments.size
                _compositionState.value = CompositionState.Loading(progress)
                _playerState.update { it.copy(loadingProgress = progress) }

                Log.d(TAG, "Added segment ${index + 1}/${segments.size}")
            } else {
                Log.w(TAG, "Segment file not found: ${segment.uri}")
            }
        }

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        return exportComposition(composition, context)
    }

    private suspend fun createBatchComposition(
        segments: List<VideoSegment>,
        context: Context,
        batchSize: Int
    ): Uri? {
        val batches = segments.chunked(batchSize)
        val intermediateFiles = mutableListOf<File>()

        try {
            batches.forEachIndexed { batchIndex, batch ->
                ensureActive() // キャンセルチェック

                Log.d(TAG, "Processing batch ${batchIndex + 1}/${batches.size}")

                val batchFile = createBatchFile(batch, batchIndex, context)
                if (batchFile != null) {
                    intermediateFiles.add(batchFile)
                    temporaryFiles.add(batchFile)
                }

                // 進捗更新
                val progress = (batchIndex + 1).toFloat() / batches.size * 0.8f
                _compositionState.value = CompositionState.Loading(progress)
                _playerState.update { it.copy(loadingProgress = progress) }

                // メモリ解放
                if ((batchIndex + 1) % 5 == 0) {
                    System.gc()
                }
            }

            // 中間ファイルを結合
            Log.d(TAG, "Merging ${intermediateFiles.size} intermediate files")
            return mergeFiles(intermediateFiles, context)

        } finally {
            // 中間ファイルを削除
            intermediateFiles.forEach { file ->
                if (file.exists()) {
                    file.delete()
                    temporaryFiles.remove(file)
                }
            }
        }
    }

    private suspend fun createBatchFile(
        batch: List<VideoSegment>,
        batchIndex: Int,
        context: Context
    ): File? {
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
        val composition = builder.build()

        val outputFile = File(context.cacheDir, "batch_${batchIndex}_${System.currentTimeMillis()}.mp4")
        exportCompositionToFile(composition, outputFile, context)

        return if (outputFile.exists()) outputFile else null
    }

    private suspend fun exportComposition(
        composition: Composition,
        context: Context
    ): Uri? {
        val outputFile = File(context.cacheDir, "composition_${System.currentTimeMillis()}.mp4")
        temporaryFiles.add(outputFile)

        exportCompositionToFile(composition, outputFile, context)

        return if (outputFile.exists()) Uri.fromFile(outputFile) else null
    }

    private suspend fun exportCompositionToFile(
        composition: Composition,
        outputFile: File,
        context: Context
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        val transformer = Transformer.Builder(context).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                Log.d(TAG, "Export completed: ${outputFile.name}")
                continuation.resume(Unit) {}
            }

            override fun onError(
                comp: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                Log.e(TAG, "Export failed", exportException)
                continuation.resumeWithException(exportException)
            }
        })

        continuation.invokeOnCancellation {
            transformer.cancel()
            Log.d(TAG, "Transformer cancelled")
        }

        transformer.start(composition, outputFile.path)
    }

    private suspend fun mergeFiles(files: List<File>, context: Context): Uri? {
        if (files.isEmpty()) return null
        if (files.size == 1) return Uri.fromFile(files.first())

        val builder = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
        val sequenceBuilder = Composition.Sequence.Builder()

        for (file in files) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
            sequenceBuilder.addMediaItem(editedMediaItem)
        }

        builder.addSequence(sequenceBuilder.build())
        val composition = builder.build()

        return exportComposition(composition, context)
    }

    private fun calculateTotalDuration(project: Project): Long {
        return project.segments.sumOf { it.durationMs }
    }

    private fun cleanupPartialWork() {
        temporaryFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        temporaryFiles.clear()
        Log.d(TAG, "Partial work cleaned up")
    }

    private fun cleanupTemporaryFiles() {
        cacheDir?.let { dir ->
            val compositionFiles = dir.listFiles { file ->
                file.name.startsWith("composition_") || file.name.startsWith("batch_")
            }

            compositionFiles?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Deleted: ${file.name}")
                }
            }
        }

        temporaryFiles.clear()
    }

    // ========== ViewModel 破棄時 ==========

    override fun onCleared() {
        super.onCleared()

        Log.d(TAG, "ViewModel being cleared")

        // 1. 進行中のジョブをキャンセル
        compositionJob?.cancel()

        // 2. 一時ファイルを削除
        cleanupTemporaryFiles()

        // 3. メモリを解放
        System.gc()

        Log.d(TAG, "All resources cleaned up")
    }

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}

// ========== 状態クラス ==========

sealed class CompositionState {
    object Idle : CompositionState()
    data class Loading(val progress: Float) : CompositionState()
    data class Success(val uri: Uri) : CompositionState()
    data class Error(val message: String) : CompositionState()
}

data class PlayerState(
    val isLoading: Boolean = false,
    val loadingProgress: Float = 0f,
    val compositionUri: Uri? = null,
    val currentPosition: Long = 0L,
    val totalDuration: Long = 0L,
    val isPlaying: Boolean = false,
    val currentSegmentIndex: Int = 0,
    val errorMessage: String? = null
)

sealed class ErrorEvent {
    abstract val message: String

    data class OutOfMemory(
        val segmentCount: Int,
        override val message: String
    ) : ErrorEvent()

    data class FileNotFound(
        val fileName: String,
        override val message: String
    ) : ErrorEvent()

    data class General(
        override val message: String
    ) : ErrorEvent()
}
```

---

## 5. まとめ

### 5.1 ベストプラクティス

| 項目 | 推奨パターン |
|------|------------|
| 非同期処理 | viewModelScope.launch { } |
| 状態公開 | private MutableStateFlow + public StateFlow |
| キャンセル | Job 変数を保持し、必要時にキャンセル |
| リソース初期化 | LaunchedEffect |
| リソース解放 | DisposableEffect + onDispose |
| エラー通知 | SharedFlow でイベントとして発行 |
| 画面遷移 | DisposableEffect で確実にクリーンアップ |

### 5.2 iOS版との対応関係

| iOS版 | Android版 |
|-------|----------|
| @StateObject | ViewModel |
| @Published | MutableStateFlow |
| ObservableObject | ViewModel + StateFlow |
| .sink { } | .collect { } |
| onAppear | LaunchedEffect |
| onDisappear | DisposableEffect + onDispose |
| Task { } | viewModelScope.launch { } |
| task.cancel() | job.cancel() |

これらのパターンを適用することで、メモリリークを防ぎ、安全なリソース管理を実現できます。

---

*以上がセクション4B-1b-ii「ViewModel・Compose ライフサイクル統合ガイド」です。*
