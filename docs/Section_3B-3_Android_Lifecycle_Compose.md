# セクション3B-3: Android版ライフサイクル管理（Compose ライフサイクル）

## 3.2.5 Compose のライフサイクル

### 3.2.5.1 LaunchedEffect vs DisposableEffect の使い分け

#### 基本的な違い

| Effect | 用途 | 実行タイミング | クリーンアップ |
|--------|------|--------------|--------------|
| LaunchedEffect | 非同期処理の実行 | Composable が表示されたとき | コルーチンが自動的にキャンセルされる |
| DisposableEffect | リソースの購読・解放 | Composable が表示されたとき | onDispose {} で明示的にクリーンアップ |

#### LaunchedEffect のパターン

```kotlin
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current

    // ✅ LaunchedEffect: 非同期処理に使用
    LaunchedEffect(Unit) {
        // カメラの初期化（非同期）
        viewModel.initializeCamera(context)
    }

    // ✅ key が変わると再実行される
    LaunchedEffect(viewModel.selectedProjectId) {
        // プロジェクトが変更されたら再セットアップ
        viewModel.setupCameraForProject()
    }

    // UI...
}
```

**使用ケース**:

- データの読み込み（Repository から取得）
- カメラ・プレーヤーの初期化
- API リクエスト
- 定期的なポーリング

**特徴**:

- コルーチンスコープ内で実行
- Composable が破棄されると自動的にキャンセル
- key が変更されると再実行

#### DisposableEffect のパターン

```kotlin
@Composable
fun CameraScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current

    // ✅ DisposableEffect: リソースの購読・解放に使用
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // カメラを再開
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // カメラを一時停止
                }
                else -> {}
            }
        }

        // ✅ リソースの購読
        lifecycleOwner.lifecycle.addObserver(observer)

        // ✅ onDispose: Composable が破棄されるときに実行
        onDispose {
            // リソースの解放
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
```

**使用ケース**:

- Lifecycle Observer の登録・解除
- Broadcast Receiver の登録・解除
- Listener の追加・削除
- カメラ・プレーヤーの解放

**特徴**:

- 必ず `onDispose {}` でクリーンアップを記述
- key が変更されると再実行（古い Effect は破棄）

#### iOS版との対応関係

| iOS版 | Android版 |
|-------|----------|
| onAppear { setupCamera() } | LaunchedEffect(Unit) { setupCamera() } |
| onDisappear { cleanupPlayer() } | DisposableEffect(Unit) { onDispose { cleanup() } } |
| @State の変更監視 | LaunchedEffect(state) { ... } |
| NotificationCenter.addObserver | DisposableEffect { addObserver(); onDispose { removeObserver() } } |

#### 使い分けの判断フロー

```
リソースの初期化が必要？
    │
    ├─ Yes → 非同期処理が必要？
    │         │
    │         ├─ Yes → LaunchedEffect
    │         │
    │         └─ No → リソースの解放が必要？
    │                  │
    │                  ├─ Yes → DisposableEffect
    │                  │
    │                  └─ No → SideEffect or derivedStateOf
    │
    └─ No → 状態の監視のみ？
              │
              └─ Yes → remember + State
```

#### 実例: カメラ初期化

```kotlin
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // ✅ LaunchedEffect: カメラプロバイダーの取得（非同期）
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ LaunchedEffect: カメラのバインド（cameraProvider が準備できたら実行）
    LaunchedEffect(cameraProvider) {
        cameraProvider?.let { provider ->
            viewModel.bindCamera(provider)
        }
    }

    // ✅ DisposableEffect: カメラのアンバインド（Composable 破棄時）
    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindCamera()
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:81-88)
.onAppear {
    setupCamera()  // ← LaunchedEffect に相当
}
.onDisappear {
    if isTorchOn {
        toggleTorch()
    }
    videoManager.stopSession()  // ← DisposableEffect.onDispose に相当
}
```

---

### 3.2.5.2 各Composable画面での初期化・クリーンアップ

#### ProjectListScreen

```kotlin
@Composable
fun ProjectListScreen(
    navController: NavController,
    viewModel: ProjectViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsState()

    // ✅ 初期化: プロジェクト一覧を読み込む
    LaunchedEffect(Unit) {
        viewModel.loadProjects()
    }

    // ✅ クリーンアップ不要
    // - ViewModel は NavBackStackEntry に紐づいているため、
    //   画面が破棄されるまで保持される
    // - StateFlow の監視は自動的に解除される

    LazyColumn {
        items(projects) { project ->
            ProjectItem(
                project = project,
                onNavigateToCamera = {
                    navController.navigate("camera/${project.id}")
                },
                onNavigateToPlayer = {
                    navController.navigate("player/${project.id}")
                }
            )
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:6-7, ProjectManager.swift:14-16)
@StateObject private var projectManager = ProjectManager()

init() {
    loadProjects()  // ← LaunchedEffect(Unit) に相当
}

// クリーンアップ不要（@StateObject が自動管理）
```

#### CameraScreen

```kotlin
@Composable
fun CameraScreen(
    projectId: String,
    navController: NavController,
    viewModel: CameraViewModel = viewModel(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // ✅ 初期化1: カメラプロバイダーの取得
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ 初期化2: カメラのバインド
    LaunchedEffect(cameraProvider) {
        cameraProvider?.let { provider ->
            camera = bindCamera(provider, lifecycleOwner)
        }
    }

    // ✅ クリーンアップ: カメラのアンバインド
    DisposableEffect(Unit) {
        onDispose {
            // 特定のユースケースのみをアンバインド
            // （unbindAll() は使わない！）
            cameraProvider?.unbind(camera?.cameraInfo?.cameraSelector)
            camera = null

            Log.d("CameraScreen", "Camera unbound")
        }
    }

    // UI...
}
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:81-88)
.onAppear {
    setupCamera()  // ← LaunchedEffect に相当
}
.onDisappear {
    if isTorchOn {
        toggleTorch()
    }
    videoManager.stopSession()  // ← DisposableEffect.onDispose に相当
}
```

#### PlayerScreen

```kotlin
@Composable
fun PlayerScreen(
    projectId: String,
    navController: NavController,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsState()

    // ✅ ExoPlayer を remember で作成
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // ✅ 初期化1: プロジェクトを読み込む
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    // ✅ 初期化2: Composition を作成してプレーヤーにセット
    LaunchedEffect(project) {
        project?.let { proj ->
            val composition = viewModel.createComposition(proj, context)
            composition?.let {
                player.setMediaItem(MediaItem.fromUri(it))
                player.prepare()
            }
        }
    }

    // ✅ 初期化3: プレーヤーイベントの監視
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        player.seekTo(0)
                        player.pause()
                    }
                    else -> {}
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // ✅ クリーンアップ: プレーヤーを解放
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            Log.d("PlayerScreen", "ExoPlayer released")
        }
    }

    // UI: プレーヤービュー
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize()
    )
}
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift:93-99)
.onAppear {
    setupPlayer()  // ← LaunchedEffect に相当
}
.onDisappear {
    cleanupPlayer()  // ← DisposableEffect.onDispose に相当
}

// PlayerView.swift:1228-1236
private func cleanupPlayer() {
    player.pause()
    removeTimeObserver()
    NotificationCenter.default.removeObserver(...)
    player.replaceCurrentItem(with: nil)
    composition = nil
}
```

---

### 3.2.5.3 画面遷移時のリソース管理

#### 基本原則

| 原則 | 説明 |
|------|------|
| LaunchedEffect で初期化 | 画面表示時に非同期でリソースを初期化 |
| DisposableEffect で解放 | 画面破棄時に確実にリソースを解放 |
| ViewModel で状態保持 | 画面回転や一時的な破棄では状態を保持 |
| Navigation で管理 | バックスタック管理により、適切なタイミングで破棄 |

#### 遷移パターン1: ProjectListScreen → CameraScreen

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            // ✅ ProjectViewModel は "projects" に紐づく
            val viewModel: ProjectViewModel = viewModel(backStackEntry)

            ProjectListScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            // ✅ CameraViewModel は "camera/{projectId}" に紐づく
            val viewModel: CameraViewModel = viewModel(backStackEntry)

            CameraScreen(
                projectId = backStackEntry.arguments?.getString("projectId") ?: "",
                navController = navController,
                viewModel = viewModel
            )
        }
    }
}
```

**遷移時のリソース状態**:

```
ProjectListScreen 表示中
    ↓
navController.navigate("camera/123")
    ↓
ProjectListScreen は非表示だが破棄されない
ProjectViewModel は保持される
    ↓
CameraScreen 表示
CameraViewModel 作成
LaunchedEffect でカメラ初期化
    ↓
（カメラで撮影）
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:74-94)
.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    CameraView(...)
}

// MainView が projectManager を保持（@StateObject）
// CameraView が破棄されても projectManager は保持される
```

#### 遷移パターン2: CameraScreen → ProjectListScreen（戻る）

```kotlin
@Composable
fun CameraScreen(
    navController: NavController
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // カメラ初期化...

    // ✅ DisposableEffect: 画面破棄時にクリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            // カメラリソースを解放
            cameraProvider?.unbind(...)
            cameraProvider = null

            Log.d("CameraScreen", "Camera resources released")
        }
    }

    // UI: 戻るボタン
    IconButton(onClick = { navController.popBackStack() }) {
        Icon(Icons.Default.ArrowBack, "Back")
    }
}
```

**戻るときのリソース状態**:

```
CameraScreen 表示中
    ↓
navController.popBackStack()
    ↓
CameraScreen の DisposableEffect.onDispose が実行される
カメラリソースが解放される
CameraViewModel.onCleared() が実行される
    ↓
ProjectListScreen が再表示される
ProjectViewModel は保持されていたのでそのまま使用
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:83-88, 101)
.onDisappear {
    if isTorchOn {
        toggleTorch()
    }
    videoManager.stopSession()  // ← カメラセッション停止
}

Button(action: onBackToProjects) {
    HStack {
        Image(systemName: "chevron.left")
        Text("Projects")
    }
}
```

---

### 3.2.5.4 Navigation コンポーネント使用時のリソース管理

#### ViewModel のスコープ管理

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            // ✅ パターン1: このスクリーン専用の ViewModel
            val viewModel: ProjectViewModel = viewModel(backStackEntry)

            ProjectListScreen(viewModel = viewModel)
        }

        composable("camera/{projectId}") { backStackEntry ->
            // ✅ パターン2: 親スクリーンの ViewModel を共有
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry("projects")
            }
            val projectViewModel: ProjectViewModel = viewModel(parentEntry)
            val cameraViewModel: CameraViewModel = viewModel(backStackEntry)

            CameraScreen(
                projectViewModel = projectViewModel,  // ← 共有
                cameraViewModel = cameraViewModel      // ← 専用
            )
        }
    }
}
```

**ViewModel のライフサイクル**:

| スコープ | 破棄タイミング |
|---------|--------------|
| viewModel(backStackEntry) | その画面がバックスタックから削除されたとき |
| viewModel(parentEntry) | 親画面がバックスタックから削除されたとき |
| viewModel() (Activity) | Activity が破棄されたとき |

#### バックスタック管理のパターン

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onNavigateToCamera = { projectId ->
                    navController.navigate("camera/$projectId")
                }
            )
        }

        composable("camera/{projectId}") {
            CameraScreen(
                onBack = {
                    // ✅ パターン1: 通常の戻る
                    navController.popBackStack()
                },
                onNavigateToPlayer = { projectId ->
                    // ✅ パターン2: CameraScreen を削除して PlayerScreen へ
                    navController.navigate("player/$projectId") {
                        popUpTo("camera/{projectId}") { inclusive = true }
                    }
                }
            )
        }

        composable("player/{projectId}") {
            PlayerScreen(
                onBack = {
                    // ✅ パターン3: ProjectListScreen まで戻る
                    navController.popBackStack("projects", inclusive = false)
                }
            )
        }
    }
}
```

**バックスタックの状態遷移**:

```kotlin
// 初期状態
[projects]

// camera へ遷移
[projects, camera]

// player へ遷移（camera を削除）
[projects, player]

// projects へ戻る
[projects]
```

---

## 3.2.6 画面遷移時のリソース解放パターン（Android版）

### 3.2.6.1 ProjectListScreen → CameraScreen 遷移時

#### 完全な実装例

```kotlin
// AppNavigation.kt
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            val viewModel: ProjectViewModel = viewModel(backStackEntry)

            ProjectListScreen(
                viewModel = viewModel,
                onNavigateToCamera = { projectId ->
                    navController.navigate("camera/$projectId")
                }
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""

            // ✅ 親の ViewModel を共有（プロジェクト状態を保持）
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry("projects")
            }
            val projectViewModel: ProjectViewModel = viewModel(parentEntry)

            // ✅ カメラ専用の ViewModel
            val cameraViewModel: CameraViewModel = viewModel(backStackEntry)

            CameraScreen(
                projectId = projectId,
                projectViewModel = projectViewModel,
                cameraViewModel = cameraViewModel,
                navController = navController
            )
        }
    }
}

// CameraScreen.kt
@Composable
fun CameraScreen(
    projectId: String,
    projectViewModel: ProjectViewModel,
    cameraViewModel: CameraViewModel,
    navController: NavController,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // ✅ 初期化1: カメラプロバイダーの取得
    LaunchedEffect(Unit) {
        Log.d("CameraScreen", "Initializing camera provider")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            Log.d("CameraScreen", "Camera provider initialized")
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ 初期化2: カメラのバインド
    LaunchedEffect(cameraProvider) {
        cameraProvider?.let { provider ->
            Log.d("CameraScreen", "Binding camera to lifecycle")

            val newPreview = Preview.Builder().build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            val newVideoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 既存のバインドを解除
                provider.unbindAll()

                // ライフサイクルにバインド
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    newPreview,
                    newVideoCapture
                )

                camera = boundCamera
                preview = newPreview
                videoCapture = newVideoCapture

                Log.d("CameraScreen", "Camera bound successfully")
            } catch (e: Exception) {
                Log.e("CameraScreen", "Camera binding failed", e)
            }
        }
    }

    // ✅ クリーンアップ: 画面破棄時
    DisposableEffect(Unit) {
        onDispose {
            Log.d("CameraScreen", "Disposing camera resources")

            // 特定のユースケースのみをアンバインド
            preview?.let { cameraProvider?.unbind(it) }
            videoCapture?.let { cameraProvider?.unbind(it) }

            camera = null
            preview = null
            videoCapture = null

            Log.d("CameraScreen", "Camera resources disposed")
        }
    }

    // UI...
}
```

**リソース状態の変化**:

```
ProjectListScreen 表示中
    ├─ ProjectViewModel: 保持
    └─ リソース: なし
        ↓
navController.navigate("camera/123")
        ↓
CameraScreen 表示開始
    ├─ ProjectViewModel: 共有（保持）
    ├─ CameraViewModel: 新規作成
    └─ リソース:
        ├─ ProcessCameraProvider: 取得中...
        └─ Camera: バインド中...
        ↓
CameraScreen 完全表示
    ├─ ProjectViewModel: 保持
    ├─ CameraViewModel: 保持
    └─ リソース:
        ├─ ProcessCameraProvider: 取得完了
        ├─ Camera: バインド完了
        ├─ Preview: バインド完了
        └─ VideoCapture: バインド完了
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:74-94)
.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    if let project = selectedProject {
        CameraView(
            currentProject: project,
            onRecordingComplete: { videoSegment in
                // MainView の projectManager を直接参照
                projectManager.updateProject(updatedProject)
            },
            onBackToProjects: {
                currentScreen = .projects
            }
        )
    }
}
```

**対応関係**:

| iOS版 | Android版 |
|-------|----------|
| selectedProject | projectId (Navigation 引数) |
| projectManager (MainView が保持) | projectViewModel (親 ViewModel を共有) |
| VideoManager (@StateObject) | CameraViewModel + CameraX |
| onAppear { setupCamera() } | LaunchedEffect { bindCamera() } |

---

### 3.2.6.2 CameraScreen → ProjectListScreen 戻る時

#### 完全な実装例

```kotlin
@Composable
fun CameraScreen(
    projectViewModel: ProjectViewModel,
    navController: NavController
) {
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    // カメラ初期化...

    // ✅ クリーンアップ: 戻るときに実行される
    DisposableEffect(Unit) {
        onDispose {
            Log.d("CameraScreen", "Cleaning up before going back")

            // 1. トーチを消す
            if (isTorchOn) {
                camera?.cameraControl?.enableTorch(false)
                Log.d("CameraScreen", "Torch turned off")
            }

            // 2. カメラリソースをアンバインド
            preview?.let { cameraProvider?.unbind(it) }
            videoCapture?.let { cameraProvider?.unbind(it) }

            Log.d("CameraScreen", "Camera resources released")
        }
    }

    // UI: 戻るボタン
    TopAppBar(
        title = { Text("Camera") },
        navigationIcon = {
            IconButton(onClick = {
                Log.d("CameraScreen", "Back button pressed")
                navController.popBackStack()  // ✅ ProjectListScreen へ戻る
            }) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
        }
    )
}
```

**戻るときのリソース状態の変化**:

```
CameraScreen 表示中
    ├─ ProjectViewModel: 保持
    ├─ CameraViewModel: 保持
    └─ リソース:
        ├─ ProcessCameraProvider: 取得済み
        ├─ Camera: バインド済み
        ├─ Preview: バインド済み
        └─ VideoCapture: バインド済み
        ↓
navController.popBackStack()
        ↓
DisposableEffect.onDispose 実行
    ├─ トーチ OFF
    ├─ Preview アンバインド
    └─ VideoCapture アンバインド
        ↓
CameraScreen 破棄
    ├─ CameraViewModel.onCleared() 実行
    └─ リソース: すべて解放
        ↓
ProjectListScreen 再表示
    ├─ ProjectViewModel: そのまま保持（状態は保存されている）
    └─ リソース: なし
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:83-88, 101)
.onDisappear {
    if isTorchOn {
        toggleTorch()  // ✅ トーチを消す
    }
    videoManager.stopSession()  // ✅ カメラセッション停止
}

Button(action: onBackToProjects) {
    HStack {
        Image(systemName: "chevron.left")
        Text("Projects")
    }
}
```

**対応関係**:

| iOS版 | Android版 |
|-------|----------|
| onBackToProjects() | navController.popBackStack() |
| onDisappear { ... } | DisposableEffect { onDispose { ... } } |
| videoManager.stopSession() | cameraProvider.unbind(...) |
| VideoManager 破棄 | CameraViewModel.onCleared() |

---

### 3.2.6.3 ProjectListScreen → PlayerScreen 遷移時

#### 完全な実装例

```kotlin
// AppNavigation.kt
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            val viewModel: ProjectViewModel = viewModel(backStackEntry)

            ProjectListScreen(
                viewModel = viewModel,
                onNavigateToPlayer = { projectId ->
                    navController.navigate("player/$projectId")
                }
            )
        }

        composable("player/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""

            // ✅ 親の ViewModel を共有
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry("projects")
            }
            val projectViewModel: ProjectViewModel = viewModel(parentEntry)

            // ✅ プレーヤー専用の ViewModel
            val playerViewModel: PlayerViewModel = viewModel(backStackEntry)

            PlayerScreen(
                projectId = projectId,
                projectViewModel = projectViewModel,
                playerViewModel = playerViewModel,
                navController = navController
            )
        }
    }
}

// PlayerScreen.kt
@Composable
fun PlayerScreen(
    projectId: String,
    projectViewModel: ProjectViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val project by projectViewModel.getProject(projectId).collectAsState(initial = null)

    // ✅ ExoPlayer を remember で作成
    val player = remember {
        ExoPlayer.Builder(context).build().also {
            Log.d("PlayerScreen", "ExoPlayer created")
        }
    }

    var isLoading by remember { mutableStateOf(false) }

    // ✅ 初期化: Composition 作成 & プレーヤーセットアップ
    LaunchedEffect(project) {
        project?.let { proj ->
            isLoading = true
            Log.d("PlayerScreen", "Creating composition for project: ${proj.name}")

            try {
                val compositionUri = playerViewModel.createComposition(proj, context)

                compositionUri?.let { uri ->
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                    Log.d("PlayerScreen", "Player setup completed")
                } ?: run {
                    Log.e("PlayerScreen", "Failed to create composition")
                }
            } catch (e: Exception) {
                Log.e("PlayerScreen", "Composition creation failed", e)
            } finally {
                isLoading = false
            }
        }
    }

    // ✅ プレーヤーイベント監視
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        Log.d("PlayerScreen", "Playback completed")
                        player.seekTo(0)
                        player.pause()
                    }
                    else -> {}
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    // ✅ クリーンアップ: プレーヤー解放
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Releasing ExoPlayer")
            player.release()
            Log.d("PlayerScreen", "ExoPlayer released")
        }
    }

    // UI: ローディング表示
    if (isLoading) {
        CircularProgressIndicator()
    }

    // UI: プレーヤービュー
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
```

**リソース状態の変化**:

```
ProjectListScreen 表示中
    ├─ ProjectViewModel: 保持
    └─ リソース: なし
        ↓
navController.navigate("player/123")
        ↓
PlayerScreen 表示開始
    ├─ ProjectViewModel: 共有（保持）
    ├─ PlayerViewModel: 新規作成
    └─ リソース:
        ├─ ExoPlayer: 作成完了
        └─ Composition: 作成中...
        ↓
PlayerScreen 完全表示
    ├─ ProjectViewModel: 保持
    ├─ PlayerViewModel: 保持
    └─ リソース:
        ├─ ExoPlayer: 準備完了
        ├─ Composition: 作成完了
        └─ Player.Listener: 監視中
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:95-108)
.fullScreenCover(isPresented: .constant(currentScreen == .player)) {
    if let project = selectedProject {
        PlayerView(
            projectManager: projectManager,  // ← 親の Manager を渡す
            initialProject: project,
            onBack: {
                currentScreen = .projects
            },
            onDeleteSegment: { project, segment in
                projectManager.deleteSegment(from: project, segment: segment)
            }
        )
    }
}
```

**対応関係**:

| iOS版 | Android版 |
|-------|----------|
| selectedProject | projectId (Navigation 引数) |
| projectManager (MainView が保持) | projectViewModel (親 ViewModel を共有) |
| AVPlayer (@State) | ExoPlayer (remember) |
| onAppear { setupPlayer() } | LaunchedEffect { setupPlayer() } |

---

### 3.2.6.4 PlayerScreen → ProjectListScreen 戻る時

#### 完全な実装例

```kotlin
@Composable
fun PlayerScreen(
    projectViewModel: ProjectViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    // プレーヤーセットアップ...

    // ✅ クリーンアップ: 戻るときに実行される
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Cleaning up before going back")

            // 1. プレーヤーを解放
            player.release()

            // 2. 一時ファイルを削除
            cleanupTemporaryFiles(context)

            Log.d("PlayerScreen", "Player resources released")
        }
    }

    // UI: 戻るボタン
    TopAppBar(
        title = { Text("Player") },
        navigationIcon = {
            IconButton(onClick = {
                Log.d("PlayerScreen", "Back button pressed")
                navController.popBackStack()  // ✅ ProjectListScreen へ戻る
            }) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
        }
    )
}

private fun cleanupTemporaryFiles(context: Context) {
    try {
        val cacheDir = context.cacheDir
        val compositionFiles = cacheDir.listFiles { file ->
            file.name.startsWith("composition_") && file.extension == "mp4"
        }

        compositionFiles?.forEach { file ->
            if (file.delete()) {
                Log.d("PlayerScreen", "Deleted temp file: ${file.name}")
            }
        }
    } catch (e: Exception) {
        Log.e("PlayerScreen", "Failed to cleanup temp files", e)
    }
}
```

**戻るときのリソース状態の変化**:

```
PlayerScreen 表示中
    ├─ ProjectViewModel: 保持
    ├─ PlayerViewModel: 保持
    └─ リソース:
        ├─ ExoPlayer: 再生中
        ├─ Composition: メモリ上
        └─ 一時ファイル: キャッシュディレクトリ
        ↓
navController.popBackStack()
        ↓
DisposableEffect.onDispose 実行
    ├─ player.release() → ExoPlayer 解放
    └─ cleanupTemporaryFiles() → 一時ファイル削除
        ↓
PlayerScreen 破棄
    ├─ PlayerViewModel.onCleared() 実行
    └─ リソース: すべて解放
        ↓
ProjectListScreen 再表示
    ├─ ProjectViewModel: そのまま保持
    └─ リソース: なし
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift:97-99, 1228-1236)
.onDisappear {
    cleanupPlayer()
}

private func cleanupPlayer() {
    player.pause()
    removeTimeObserver()
    NotificationCenter.default.removeObserver(...)
    player.replaceCurrentItem(with: nil)
    composition = nil
    segmentTimeRanges = []
}
```

**対応関係**:

| iOS版 | Android版 |
|-------|----------|
| onBack() | navController.popBackStack() |
| onDisappear { cleanupPlayer() } | DisposableEffect { onDispose { ... } } |
| player.replaceCurrentItem(with: nil) | player.release() |
| removeTimeObserver() | player.removeListener() |
| composition = nil | Composition 一時ファイル削除 |

---

### 3.2.6.5 Navigation バックスタックの管理

#### 基本的なバックスタック操作

```kotlin
// ✅ パターン1: 通常の遷移（バックスタックに追加）
navController.navigate("camera/123")
// バックスタック: [projects, camera]

// ✅ パターン2: 戻る（バックスタックから削除）
navController.popBackStack()
// バックスタック: [projects]

// ✅ パターン3: 特定の画面まで戻る
navController.popBackStack("projects", inclusive = false)
// バックスタック: [projects]

// ✅ パターン4: 特定の画面を削除して遷移
navController.navigate("player/123") {
    popUpTo("camera/123") { inclusive = true }
}
// バックスタック: [projects, player]

// ✅ パターン5: ルートまで戻る
navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
// バックスタック: [projects]
```

#### 実践的なバックスタック管理

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            ProjectListScreen(
                onNavigateToCamera = { projectId ->
                    // ✅ 通常の遷移
                    navController.navigate("camera/$projectId")
                },
                onNavigateToPlayer = { projectId ->
                    // ✅ 通常の遷移
                    navController.navigate("player/$projectId")
                }
            )
        }

        composable("camera/{projectId}") {
            CameraScreen(
                onBack = {
                    // ✅ 前の画面へ戻る
                    navController.popBackStack()
                },
                onRecordingComplete = {
                    // ✅ 録画完了後もそのまま（戻らない）
                },
                onNavigateToPlayer = { projectId ->
                    // ✅ Camera を削除して Player へ
                    navController.navigate("player/$projectId") {
                        popUpTo("camera/{projectId}") { inclusive = true }
                    }
                    // バックスタック: [projects, player]
                }
            )
        }

        composable("player/{projectId}") {
            PlayerScreen(
                onBack = {
                    // ✅ ProjectListScreen まで戻る
                    navController.popBackStack("projects", inclusive = false)
                },
                onDeleteSegment = { projectId, segmentId ->
                    // ✅ セグメント削除後もそのまま（戻らない）
                }
            )
        }
    }
}
```

**バックスタック状態のログ出力**:

```kotlin
// デバッグ用: バックスタックの状態を確認
fun logBackStack(navController: NavController) {
    val backStack = navController.currentBackStack.value
    Log.d("Navigation", "Current back stack:")
    backStack.forEach { entry ->
        Log.d("Navigation", "  - ${entry.destination.route}")
    }
}

// 使用例
IconButton(onClick = {
    logBackStack(navController)
    navController.popBackStack()
}) {
    Icon(Icons.Default.ArrowBack, "Back")
}
```

---

### 3.2.6.6 iOS版 vs Android版の画面遷移比較表

| 側面 | iOS版 | Android版 |
|------|-------|----------|
| 画面遷移方法 | @State currentScreen + fullScreenCover | NavController.navigate() |
| 戻る操作 | currentScreen = .projects | navController.popBackStack() |
| 状態保持 | @StateObject (MainView が保持) | ViewModel (NavBackStackEntry が保持) |
| リソース初期化 | onAppear { } | LaunchedEffect { } |
| リソース解放 | onDisappear { } | DisposableEffect { onDispose { } } |
| ViewModel 共有 | クロージャー経由でアクセス | 親 NavBackStackEntry から取得 |
| バックスタック管理 | 手動（State で管理） | Navigation が自動管理 |
| 画面回転時 | State 保持（自動） | ViewModel 保持（自動） |

---

*以上がセクション3B-3「Android版ライフサイクル管理（Compose ライフサイクル）」です。*
