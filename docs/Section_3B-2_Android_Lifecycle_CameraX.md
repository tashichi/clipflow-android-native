# セクション3B-2: Android版ライフサイクル管理（CameraX と ExoPlayer）

## 3.2.3 CameraX のリソース管理

### iOS版との対応関係

| iOS版 (VideoManager.swift) | Android版 (CameraX) |
|---------------------------|---------------------|
| AVCaptureSession | ProcessCameraProvider |
| setupCamera() | cameraProvider.bindToLifecycle() |
| stopSession() | cameraProvider.unbind() (特定のユースケース) |
| toggleCamera() | CameraSelector 切り替え + rebind |
| AVCaptureDevice.torchMode | camera.cameraControl.enableTorch() |
| onDisappear { stopSession() } | DisposableEffect { onDispose { unbind() } } |

---

### 3.2.3.1 CameraProvider の初期化タイミング

#### iOS版のパターン（復習）

```swift
// VideoManager.swift:61-105
func setupCamera() async {
    captureSession = AVCaptureSession()
    captureSession?.beginConfiguration()

    await setupCameraDevice(position: currentCameraPosition)
    await setupAudioDevice()
    setupMovieOutput()

    captureSession?.commitConfiguration()
    setupPreviewLayer()
    await startSession()

    isSetupComplete = true
}

// CameraView.swift:81-82
.onAppear {
    setupCamera()  // ✅ 画面表示時に初期化
}
```

#### Android版のパターン

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    // ✅ Compose State として保持
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // ✅ LaunchedEffect: Composable が最初に表示されたときに1回だけ実行
    LaunchedEffect(Unit) {
        val context = ... // Context を取得

        // ProcessCameraProvider を非同期で取得
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                // ✅ CameraProvider を取得（初期化完了）
                cameraProvider = cameraProviderFuture.get()
                Log.d("CameraScreen", "CameraProvider initialized")
            } catch (e: Exception) {
                Log.e("CameraScreen", "CameraProvider initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // UI コンポーネント...
}
```

**初期化タイミング**:

| タイミング | 処理 |
|----------|------|
| LaunchedEffect(Unit) | ProcessCameraProvider の取得開始（非同期） |
| addListener コールバック | 初期化完了、cameraProvider に保存 |
| その後 | bindToLifecycle() でカメラをバインド |

**iOS版との対応**:

- iOS: `onAppear { setupCamera() }` で同期的に初期化
- Android: `LaunchedEffect(Unit)` で非同期に初期化

---

### 3.2.3.2 bindToLifecycle() の使用方法

#### 基本パターン

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }

    // CameraProvider 初期化
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            isCameraReady = true
        }, ContextCompat.getMainExecutor(context))
    }

    // ✅ CameraProvider が準備できたらカメラをバインド
    LaunchedEffect(isCameraReady) {
        if (isCameraReady && cameraProvider != null) {
            bindCamera(
                cameraProvider = cameraProvider!!,
                lifecycleOwner = lifecycleOwner,
                context = context,
                onCameraBound = { boundCamera, boundVideoCapture ->
                    camera = boundCamera
                    videoCapture = boundVideoCapture
                }
            )
        }
    }

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            // ✅ 画面を離れるときにアンバインド
            videoCapture?.let { cameraProvider?.unbind(it) }
            camera = null
            videoCapture = null
            Log.d("CameraScreen", "Camera unbound")
        }
    }

    // UI...
}

private fun bindCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    context: Context,
    onCameraBound: (Camera, VideoCapture<Recorder>) -> Unit
) {
    // 1. Preview ユースケース
    val preview = Preview.Builder()
        .build()

    // 2. VideoCapture ユースケース
    val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()

    val videoCapture = VideoCapture.withOutput(recorder)

    // 3. CameraSelector（背面カメラ）
    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    try {
        // ✅ 既存のバインドを解除（重要！）
        cameraProvider.unbindAll()

        // ✅ ライフサイクルにバインド
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            videoCapture
        )

        Log.d("CameraScreen", "Camera bound successfully")
        onCameraBound(camera, videoCapture)

    } catch (e: Exception) {
        Log.e("CameraScreen", "Camera binding failed", e)
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (VideoManager.swift:61-105)
func setupCamera() async {
    captureSession = AVCaptureSession()
    captureSession?.beginConfiguration()

    await setupCameraDevice(position: currentCameraPosition)  // ← カメラデバイス
    await setupAudioDevice()                                  // ← 音声デバイス
    setupMovieOutput()                                        // ← 動画出力

    captureSession?.commitConfiguration()
    await startSession()  // ← セッション開始
}
```

**対応関係**:

| iOS版 | Android版 |
|-------|----------|
| setupCameraDevice() | CameraSelector |
| setupAudioDevice() | VideoCapture（音声は自動） |
| setupMovieOutput() | VideoCapture.withOutput(recorder) |
| commitConfiguration() + startSession() | bindToLifecycle() |

---

### 3.2.3.3 カメラセレクター（前面/背面切り替え）

#### iOS版のパターン（復習）

```swift
// VideoManager.swift:252-260
func toggleCamera() async {
    let newPosition: AVCaptureDevice.Position = currentCameraPosition == .back ? .front : .back

    captureSession?.beginConfiguration()
    await setupCameraDevice(position: newPosition)  // ← 古い入力を削除 → 新しい入力を追加
    captureSession?.commitConfiguration()
}
```

#### Android版のパターン

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // ✅ カメラの向き（前面/背面）
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // カメラ初期化...

    // ✅ lensFacing が変更されたら再バインド
    LaunchedEffect(lensFacing) {
        if (cameraProvider != null) {
            rebindCamera(
                cameraProvider = cameraProvider!!,
                lifecycleOwner = lifecycleOwner,
                lensFacing = lensFacing,
                onCameraBound = { boundCamera, boundVideoCapture ->
                    camera = boundCamera
                    videoCapture = boundVideoCapture
                }
            )
        }
    }

    // UI: カメラ切り替えボタン
    IconButton(
        onClick = {
            // ✅ lensFacing を切り替え → LaunchedEffect が再実行される
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        }
    ) {
        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Switch Camera")
    }
}

private fun rebindCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    lensFacing: Int,
    onCameraBound: (Camera, VideoCapture<Recorder>) -> Unit
) {
    val preview = Preview.Builder().build()
    val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()
    val videoCapture = VideoCapture.withOutput(recorder)

    // ✅ CameraSelector を切り替え
    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing)
        .build()

    try {
        // ✅ 既存のバインドを解除
        cameraProvider.unbindAll()

        // ✅ 新しい CameraSelector でバインド
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            videoCapture
        )

        Log.d("CameraScreen", "Camera switched to ${if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"}")
        onCameraBound(camera, videoCapture)

    } catch (e: Exception) {
        Log.e("CameraScreen", "Camera rebind failed", e)
    }
}
```

**切り替えフロー**:

```
ユーザーがボタンをタップ
    ↓
lensFacing を切り替え（BACK ↔ FRONT）
    ↓
LaunchedEffect(lensFacing) が再実行される
    ↓
cameraProvider.unbindAll() で既存のバインドを解除
    ↓
新しい CameraSelector でバインド
    ↓
カメラプレビューが切り替わる
```

**iOS版との対応**:

- iOS: `beginConfiguration()` → デバイス削除/追加 → `commitConfiguration()`
- Android: `unbindAll()` → 新しい CameraSelector で `bindToLifecycle()`

---

### 3.2.3.4 Torch ON/OFF 時のリソース管理

#### iOS版のパターン（復習）

```swift
// CameraView.swift:308-338
private func toggleTorch() {
    guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                for: .video,
                                                position: .back) else { return }

    guard device.hasTorch else { return }

    do {
        try device.lockForConfiguration()  // ✅ ロック

        if isTorchOn {
            device.torchMode = .off
        } else {
            try device.setTorchModeOn(level: 1.0)
        }

        device.unlockForConfiguration()  // ✅ アンロック
    } catch {
        print("Torch control error: \(error)")
    }
}
```

#### Android版のパターン

```kotlin
@Composable
fun CameraScreen() {
    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    // カメラ初期化...

    // UI: ライトボタン
    IconButton(
        onClick = {
            camera?.let {
                // ✅ CameraControl 経由でトーチを制御
                it.cameraControl.enableTorch(!isTorchOn)
                isTorchOn = !isTorchOn
                Log.d("CameraScreen", "Torch ${if (isTorchOn) "ON" else "OFF"}")
            }
        },
        enabled = camera != null  // ✅ カメラが準備できていない場合は無効化
    ) {
        Icon(
            imageVector = if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
            contentDescription = "Toggle Torch",
            tint = if (isTorchOn) Color.Yellow else Color.Gray
        )
    }

    // ✅ 画面を離れるときにトーチをOFFにする
    DisposableEffect(Unit) {
        onDispose {
            if (isTorchOn) {
                camera?.cameraControl?.enableTorch(false)
                Log.d("CameraScreen", "Torch turned off on dispose")
            }
        }
    }
}
```

**リソース管理のポイント**:

| ポイント | iOS版 | Android版 |
|---------|-------|----------|
| ロック | lockForConfiguration() | 不要（CameraControl が内部で処理） |
| アンロック | unlockForConfiguration() | 不要 |
| トーチ制御 | device.torchMode = .off | camera.cameraControl.enableTorch(false) |
| 画面離脱時 | onDisappear { toggleTorch() } | DisposableEffect { onDispose { enableTorch(false) } } |

**iOS版との対応**:

- iOS: デバイスレベルの制御（ロック/アンロック必須）
- Android: Camera インスタンス経由の制御（ロック不要）

---

### 3.2.3.5 unbindAll() の正しい使用方法（前回の失敗を踏まえて）

#### 前回の失敗: PlayerScreen で unbindAll() を呼んでカメラが使えなくなった

```kotlin
// ❌ 間違った実装（前回のAndroid版）
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val cameraProvider = ProcessCameraProvider.getInstance(context).get()

    DisposableEffect(Unit) {
        onDispose {
            // ❌ これはグローバルにすべてのカメラをアンバインドしてしまう！
            cameraProvider.unbindAll()
        }
    }
}
```

**問題点**:

1. ProcessCameraProvider.getInstance() はシングルトン
2. unbindAll() はアプリ全体のすべてのカメラバインドを解除する
3. PlayerScreen を離れると、CameraScreen のカメラも解除されてしまう

#### 正しい実装パターン1: 特定のユースケースのみをアンバインド

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }

    // カメラ初期化...
    LaunchedEffect(Unit) {
        // CameraProvider を取得
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
    }

    // カメラバインド...
    LaunchedEffect(cameraProvider) {
        if (cameraProvider != null) {
            val newPreview = Preview.Builder().build()
            val recorder = Recorder.Builder().build()
            val newVideoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // ✅ 再バインド前に既存のバインドを解除
                cameraProvider!!.unbindAll()

                // バインド
                cameraProvider!!.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    newPreview,
                    newVideoCapture
                )

                preview = newPreview
                videoCapture = newVideoCapture
            } catch (e: Exception) {
                Log.e("CameraScreen", "Binding failed", e)
            }
        }
    }

    // ✅ 正しいクリーンアップ: 特定のユースケースのみをアンバインド
    DisposableEffect(Unit) {
        onDispose {
            // ❌ unbindAll() は使わない！
            // ✅ 代わりに、このスクリーンで使用したユースケースのみをアンバインド
            preview?.let { cameraProvider?.unbind(it) }
            videoCapture?.let { cameraProvider?.unbind(it) }

            Log.d("CameraScreen", "Camera use cases unbound")
        }
    }
}
```

**重要なポイント**:

```kotlin
// ❌ 間違い: すべてのカメラをアンバインド
cameraProvider.unbindAll()

// ✅ 正しい: このスクリーンで使用したユースケースのみをアンバインド
preview?.let { cameraProvider.unbind(it) }
videoCapture?.let { cameraProvider.unbind(it) }
```

#### 正しい実装パターン2: ライフサイクルに自動的に連動させる

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // カメラ初期化...

    // カメラバインド
    LaunchedEffect(cameraProvider) {
        if (cameraProvider != null) {
            val preview = Preview.Builder().build()
            val videoCapture = VideoCapture.withOutput(Recorder.Builder().build())
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // ✅ bindToLifecycle() はライフサイクルに自動的に連動
                // lifecycleOwner が DESTROYED になると自動的にアンバインドされる
                cameraProvider!!.bindToLifecycle(
                    lifecycleOwner,  // ← ここがポイント
                    cameraSelector,
                    preview,
                    videoCapture
                )

                Log.d("CameraScreen", "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e("CameraScreen", "Binding failed", e)
            }
        }
    }

    // ✅ DisposableEffect は不要！
    // ライフサイクルが DESTROYED になると自動的にアンバインドされる
}
```

**ライフサイクル連動の仕組み**:

```
CameraScreen 表示
    ↓
lifecycleOwner.lifecycle.currentState == STARTED
    ↓
bindToLifecycle() でカメラをバインド
    ↓
CameraScreen 非表示
    ↓
lifecycleOwner.lifecycle.currentState == DESTROYED
    ↓
✅ 自動的にアンバインド（明示的な unbind() 不要）
```

#### 正しい実装パターン3: ViewModel でカメラ状態を管理

```kotlin
class CameraViewModel : ViewModel() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    fun initializeCamera(context: Context) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        cameraProvider?.let { provider ->
            val newPreview = Preview.Builder().build()
            val recorder = Recorder.Builder().build()
            val newVideoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // ✅ 再バインド前に既存のバインドを解除
                provider.unbindAll()

                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    newPreview,
                    newVideoCapture
                )

                preview = newPreview
                videoCapture = newVideoCapture
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Binding failed", e)
            }
        }
    }

    // ✅ ViewModel が破棄されるときにクリーンアップ
    override fun onCleared() {
        super.onCleared()

        // 特定のユースケースのみをアンバインド
        preview?.let { cameraProvider?.unbind(it) }
        videoCapture?.let { cameraProvider?.unbind(it) }

        cameraProvider = null
        Log.d("CameraViewModel", "Camera resources cleaned up")
    }
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.initializeCamera(context)
        viewModel.bindCamera(lifecycleOwner)
    }

    // ✅ DisposableEffect 不要（ViewModel.onCleared() でクリーンアップ）
}
```

#### unbindAll() の使用ガイドライン

| ケース | 使用すべきメソッド | 理由 |
|--------|-----------------|------|
| カメラ切り替え時 | unbindAll() | 古いバインドを完全に解除してから新しいバインドを作成する |
| 画面遷移時（離脱） | unbind(useCase) | 他の画面のカメラに影響を与えないため |
| ViewModel.onCleared() | unbind(useCase) | 特定のユースケースのみを解放 |
| アプリ終了時 | 何もしない | システムが自動的にリソースを解放 |

**iOS版との対応**:

```swift
// iOS版: stopSession() はセッションを停止するが、入力/出力は保持
func stopSession() {
    captureSession?.stopRunning()
    isSetupComplete = false
}
↓

// Android版: unbind(useCase) は特定のユースケースのみをアンバインド
preview?.let { cameraProvider.unbind(it) }
videoCapture?.let { cameraProvider.unbind(it) }
```

---

### 3.2.3.6 onDestroy() または Lifecycle イベント時のクリーンアップ

#### パターン1: DisposableEffect でのクリーンアップ

```kotlin
@Composable
fun CameraScreen() {
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    // カメラ初期化 & バインド...

    // ✅ DisposableEffect: Composable が破棄されるときにクリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            Log.d("CameraScreen", "Cleaning up camera resources")

            // 1. 特定のユースケースをアンバインド
            preview?.let { cameraProvider?.unbind(it) }
            videoCapture?.let { cameraProvider?.unbind(it) }

            // 2. 参照をクリア
            preview = null
            videoCapture = null

            Log.d("CameraScreen", "Camera cleanup completed")
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (CameraView.swift:83-88)
.onDisappear {
    if isTorchOn {
        toggleTorch()
    }
    videoManager.stopSession()
}
```

#### パターン2: ViewModel でのクリーンアップ

```kotlin
class CameraViewModel : ViewModel() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    // カメラ操作...

    // ✅ ViewModel が破棄されるときに自動的に呼ばれる
    override fun onCleared() {
        super.onCleared()

        Log.d("CameraViewModel", "Cleaning up camera resources")

        // 1. 特定のユースケースをアンバインド
        preview?.let { cameraProvider?.unbind(it) }
        videoCapture?.let { cameraProvider?.unbind(it) }

        // 2. 参照をクリア
        cameraProvider = null
        preview = null
        videoCapture = null

        Log.d("CameraViewModel", "Camera cleanup completed")
    }
}
```

#### パターン3: Lifecycle イベントの監視

```kotlin
@Composable
fun CameraScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // ✅ Lifecycle イベントを監視
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    Log.d("CameraScreen", "Lifecycle: ON_START")
                    // カメラを再開（必要に応じて）
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.d("CameraScreen", "Lifecycle: ON_STOP")
                    // カメラを一時停止（必要に応じて）
                }
                Lifecycle.Event.ON_DESTROY -> {
                    Log.d("CameraScreen", "Lifecycle: ON_DESTROY")
                    // カメラをクリーンアップ
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
```

---

## 3.2.4 ExoPlayer のリソース管理

### iOS版との対応関係

| iOS版 (PlayerView.swift) | Android版 (ExoPlayer) |
|-------------------------|----------------------|
| @State private var player = AVPlayer() | val player = remember { ExoPlayer.Builder(context).build() } |
| AVMutableComposition | Media3 Composition |
| player.replaceCurrentItem(with:) | player.setMediaItem() |
| player.pause() + replaceCurrentItem(with: nil) | player.release() |
| cleanupPlayer() in onDisappear | DisposableEffect { onDispose { player.release() } } |
| removeTimeObserver() | player.removeListener() |

---

### 3.2.4.1 ExoPlayer の初期化タイミング

#### iOS版のパターン（復習）

```swift
// PlayerView.swift:16
@State private var player = AVPlayer()

// PlayerView.swift:93-99
.onAppear {
    setupPlayer()
}
.onDisappear {
    cleanupPlayer()
}
```

#### Android版のパターン

```kotlin
@Composable
fun PlayerScreen(
    project: Project,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val context = LocalContext.current

    // ✅ remember: Composable が破棄されるまで同じインスタンスを保持
    val player = remember {
        ExoPlayer.Builder(context)
            .build()
            .also {
                Log.d("PlayerScreen", "ExoPlayer created")
            }
    }

    // プレーヤーセットアップ
    LaunchedEffect(project) {
        setupPlayer(player, project, context)
    }

    // ✅ DisposableEffect: Composable が破棄されるときにクリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Cleaning up ExoPlayer")
            player.release()  // ✅ リソース解放
        }
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

private suspend fun setupPlayer(
    player: ExoPlayer,
    project: Project,
    context: Context
) {
    // Composition 作成（Media3 Transformer 使用）
    val composition = createComposition(project, context)

    if (composition != null) {
        // プレーヤーにセット
        player.setMediaItem(MediaItem.fromUri(composition))
        player.prepare()

        Log.d("PlayerScreen", "Player setup completed")
    } else {
        Log.e("PlayerScreen", "Failed to create composition")
    }
}
```

**初期化タイミング**:

| タイミング | 処理 |
|----------|------|
| remember { ExoPlayer.Builder().build() } | ExoPlayer インスタンス作成（1回のみ） |
| LaunchedEffect(project) | Composition 作成 & プレーヤーセットアップ |
| DisposableEffect { onDispose } | プレーヤー解放 |

**iOS版との対応**:

- iOS: `@State private var player = AVPlayer()` で初期化
- Android: `remember { ExoPlayer.Builder().build() }` で初期化

---

### 3.2.4.2 Media3 Composition の作成時のリソース確保

#### iOS版のパターン（復習）

```swift
// PlayerView.swift:858-941
private func loadComposition() {
    isLoadingComposition = true

    Task {
        // ✅ Composition を非同期で作成
        guard let newComposition = await createCompositionWithProgress() else {
            // フォールバック
            useSeamlessPlayback = false
            loadCurrentSegment()
            return
        }

        // セグメント時間範囲を取得
        segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

        await MainActor.run {
            // 新しいプレーヤーアイテムを作成
            let newPlayerItem = AVPlayerItem(asset: newComposition)

            // プレーヤーにセット
            composition = newComposition
            player.replaceCurrentItem(with: newPlayerItem)
            playerItem = newPlayerItem

            // 時間監視開始
            startTimeObserver()

            isLoadingComposition = false
        }
    }
}
```

#### Android版のパターン

```kotlin
@Composable
fun PlayerScreen(
    project: Project
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableStateOf(0f) }

    // ✅ Composition 作成
    LaunchedEffect(project) {
        isLoading = true
        loadingProgress = 0f

        try {
            // Media3 Composition を作成
            val composition = createCompositionWithProgress(
                project = project,
                context = context,
                onProgress = { progress ->
                    loadingProgress = progress
                }
            )

            if (composition != null) {
                // ✅ ExoPlayer にセット
                player.setMediaItem(MediaItem.fromUri(composition))
                player.prepare()

                Log.d("PlayerScreen", "Composition loaded successfully")
            } else {
                Log.e("PlayerScreen", "Failed to create composition")
            }
        } catch (e: Exception) {
            Log.e("PlayerScreen", "Composition creation failed", e)
        } finally {
            isLoading = false
        }
    }

    // ローディング表示
    if (isLoading) {
        LoadingOverlay(progress = loadingProgress)
    }

    // プレーヤーUI
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
            }
        }
    )

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }
}

private suspend fun createCompositionWithProgress(
    project: Project,
    context: Context,
    onProgress: (Float) -> Unit
): Uri? = withContext(Dispatchers.IO) {
    try {
        val segments = project.segments.sortedBy { it.order }
        val totalSegments = segments.size

        // ✅ Media3 Composition.Builder を使用
        val compositionBuilder = Composition.Builder(
            Composition.HDR_MODE_KEEP_HDR
        )

        // セグメントを順次追加
        segments.forEachIndexed { index, segment ->
            val file = File(context.filesDir, segment.uri)

            if (file.exists()) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

                compositionBuilder.addSequence(
                    Composition.Sequence.Builder()
                        .addMediaItem(editedMediaItem)
                        .build()
                )

                // 進捗更新
                onProgress((index + 1).toFloat() / totalSegments)

                Log.d("Composition", "Added segment ${index + 1}/$totalSegments")
            } else {
                Log.w("Composition", "Segment file not found: ${segment.uri}")
            }
        }

        val composition = compositionBuilder.build()

        // ✅ Composition を一時ファイルとして書き出し
        val outputFile = File(context.cacheDir, "composition_${System.currentTimeMillis()}.mp4")

        val transformer = Transformer.Builder(context).build()

        // Composition を変換（同期的に待機）
        val result = suspendCancellableCoroutine<Uri?> { continuation ->
            transformer.start(composition, outputFile.path)

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    continuation.resume(Uri.fromFile(outputFile), null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e("Composition", "Transformation failed", exportException)
                    continuation.resume(null, null)
                }
            })
        }

        result

    } catch (e: Exception) {
        Log.e("Composition", "Failed to create composition", e)
        null
    }
}
```

**リソース確保のポイント**:

| リソース | iOS版 | Android版 |
|---------|-------|----------|
| Composition | AVMutableComposition | Media3 Composition |
| セグメント統合 | videoTrack.insertTimeRange() | compositionBuilder.addSequence() |
| 一時ファイル | 不要（仮想的なアセット） | 必要（Transformer で書き出し） |
| 進捗監視 | タイマーで手動監視 | Transformer.Listener |

---

### 3.2.4.3 再生完了時のリソース解放

#### iOS版のパターン（復習）

```swift
// PlayerView.swift:959-965
private func handleCompositionEnd() {
    print("Composition playback completed - Returning to start")

    // ✅ 再生位置をリセット（リソースは保持）
    player.seek(to: .zero)
    currentSegmentIndex = 0
    isPlaying = false
}
```

#### Android版のパターン

```kotlin
@Composable
fun PlayerScreen(
    project: Project
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    // ✅ プレーヤーイベントリスナー
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        // ✅ 再生完了時
                        Log.d("PlayerScreen", "Playback completed")

                        // 再生位置をリセット（リソースは保持）
                        player.seekTo(0)
                        player.pause()
                    }
                    Player.STATE_READY -> {
                        Log.d("PlayerScreen", "Player ready")
                    }
                    Player.STATE_BUFFERING -> {
                        Log.d("PlayerScreen", "Buffering...")
                    }
                    Player.STATE_IDLE -> {
                        Log.d("PlayerScreen", "Player idle")
                    }
                }
            }
        }

        player.addListener(listener)

        onDispose {
            // ✅ リスナーを削除
            player.removeListener(listener)
        }
    }

    // UI...
}
```

**iOS版との対応**:

- iOS: `NotificationCenter.addObserver(name: .AVPlayerItemDidPlayToEndTime)` で監視
- Android: `Player.Listener.onPlaybackStateChanged()` で監視

---

### 3.2.4.4 player.release() の正確な使用タイミング

#### iOS版のパターン（復習）

```swift
// PlayerView.swift:1228-1236
private func cleanupPlayer() {
    // 1. 再生停止
    player.pause()

    // 2. タイムオブザーバーを削除
    removeTimeObserver()

    // 3. 通知監視を解除
    NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)

    // 4. プレーヤーアイテムを削除
    player.replaceCurrentItem(with: nil)

    // 5. Composition をクリア
    composition = nil
    segmentTimeRanges = []
}
```

#### Android版のパターン

```kotlin
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    // プレーヤーセットアップ...

    // ✅ DisposableEffect: 画面を離れるときに必ず呼ばれる
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PlayerScreen", "Cleaning up ExoPlayer")

            // ✅ player.release() を呼ぶ
            // これにより以下が自動的に実行される：
            // - 再生停止
            // - すべてのリスナー削除
            // - メディアアイテム削除
            // - 内部リソース解放
            player.release()

            Log.d("PlayerScreen", "ExoPlayer released")
        }
    }
}
```

**release() が行うこと**:

| 処理 | 内容 |
|------|------|
| stop() | 再生を停止 |
| clearMediaItems() | すべてのメディアアイテムを削除 |
| removeAllListeners() | すべてのリスナーを削除 |
| releaseInternal() | 内部リソースを解放（デコーダー、レンダラーなど） |

**iOS版との対応**:

```swift
// iOS版: 個別にクリーンアップ
player.pause()
removeTimeObserver()
NotificationCenter.removeObserver()
player.replaceCurrentItem(with: nil)
↓

// Android版: release() で一括クリーンアップ
player.release()
```

#### release() のタイミング一覧

| シナリオ | release() を呼ぶ？ | 理由 |
|---------|------------------|------|
| 画面を離れる | ✅ Yes | リソース解放のため |
| 画面回転 | ❌ No | ViewModel が保持するため |
| 一時停止 | ❌ No | pause() で十分 |
| 別の動画に切り替え | ❌ No | setMediaItem() で上書き |
| アプリ終了 | ✅ Yes | システムが呼ぶ（明示的に不要） |

---

### 3.2.4.5 DisposableEffect での完全なクリーンアップ

#### 基本パターン

```kotlin
@Composable
fun PlayerScreen(
    project: Project
) {
    val context = LocalContext.current

    // ✅ ExoPlayer を remember で作成
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // プレーヤーセットアップ
    LaunchedEffect(project) {
        setupPlayer(player, project, context)
    }

    // ✅ DisposableEffect: 完全なクリーンアップ
    DisposableEffect(Unit) {
        // onActive: Composable が表示されたとき（オプション）

        onDispose {
            // onDispose: Composable が破棄されるとき
            Log.d("PlayerScreen", "Disposing ExoPlayer resources")

            // 1. プレーヤーを解放
            player.release()

            // 2. 一時ファイルを削除（Composition の出力ファイル）
            cleanupTemporaryFiles(context)

            Log.d("PlayerScreen", "ExoPlayer cleanup completed")
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
                Log.d("PlayerScreen", "Deleted temp file: ${file.name}")
            }
        }
    } catch (e: Exception) {
        Log.e("PlayerScreen", "Failed to cleanup temp files", e)
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift:97-99)
.onDisappear {
    cleanupPlayer()
}
```

#### ViewModel を使用したパターン

```kotlin
class PlayerViewModel : ViewModel() {
    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    fun initializePlayer(context: Context) {
        if (_player == null) {
            _player = ExoPlayer.Builder(context).build()
            Log.d("PlayerViewModel", "ExoPlayer initialized")
        }
    }

    fun setupPlayer(project: Project, context: Context) {
        viewModelScope.launch {
            val composition = createComposition(project, context)

            composition?.let {
                _player?.setMediaItem(MediaItem.fromUri(it))
                _player?.prepare()
            }
        }
    }

    // ✅ ViewModel が破棄されるときに自動的に呼ばれる
    override fun onCleared() {
        super.onCleared()

        Log.d("PlayerViewModel", "Cleaning up ExoPlayer")

        // プレーヤーを解放
        _player?.release()
        _player = null

        Log.d("PlayerViewModel", "ExoPlayer cleanup completed")
    }
}

@Composable
fun PlayerScreen(
    project: Project,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.initializePlayer(context)
        viewModel.setupPlayer(project, context)
    }

    // ✅ DisposableEffect 不要（ViewModel.onCleared() でクリーンアップ）

    val player = viewModel.player

    if (player != null) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                }
            }
        )
    }
}
```

---

## 3.2.5 iOS版 vs Android版のリソース管理比較表

| 側面 | iOS版 | Android版 |
|------|-------|----------|
| カメラセッション | AVCaptureSession | ProcessCameraProvider |
| カメラ初期化 | setupCamera() in onAppear | LaunchedEffect(Unit) |
| カメラバインド | startSession() | bindToLifecycle() |
| カメラアンバインド | stopSession() | unbind(useCase) または自動 |
| カメラ切り替え | beginConfiguration() + デバイス切り替え | unbindAll() + rebind |
| トーチ制御 | device.lockForConfiguration() | camera.cameraControl.enableTorch() |
| クリーンアップタイミング | onDisappear | DisposableEffect { onDispose } |
| プレーヤー | AVPlayer | ExoPlayer |
| プレーヤー初期化 | @State private var player | remember { ExoPlayer.Builder().build() } |
| Composition | AVMutableComposition | Media3 Composition + Transformer |
| 再生完了監視 | NotificationCenter | Player.Listener |
| プレーヤー解放 | pause() + replaceCurrentItem(nil) | player.release() |
| ライフサイクル連動 | View のライフサイクル | Lifecycle または ViewModel |

---

*以上がセクション3B-2「Android版ライフサイクル管理（CameraX と ExoPlayer）」です。*
