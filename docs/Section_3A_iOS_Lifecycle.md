# セクション3A: iOS版のライフサイクル管理

## 3.1 iOS版のライフサイクル管理パターン

### 3.1.1 MainView.swift の @StateObject の役割

#### 初期化パターン

```swift
struct MainView: View {
    @StateObject private var projectManager = ProjectManager()
    @StateObject private var purchaseManager = PurchaseManager()

    @State private var selectedProject: Project?
    @State private var currentScreen: AppScreen = .projects

    var body: some View {
        // ...
    }
}
```

**実装箇所**: MainView.swift:6-7

#### @StateObject の役割と特性

| 特性 | 説明 |
|------|------|
| 初期化タイミング | MainView が最初に生成された時に1回だけ初期化 |
| ライフサイクル | MainView が存在する限り保持される（画面遷移しても破棄されない） |
| 状態保持 | @Published プロパティの変更を監視し、UI を自動更新 |
| メモリ管理 | MainView が破棄された時に自動的に破棄される |
| 子Viewへの伝達 | @ObservedObject として子Viewに渡せる |

#### 画面遷移時の状態保持パターン

```swift
// ProjectListView → CameraView 遷移
.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    if let project = selectedProject {
        CameraView(
            currentProject: project,
            onRecordingComplete: { videoSegment in
                // ✅ projectManager は MainView に保持されているため、
                // 遷移後も状態が維持される
                guard let currentProject = projectManager.projects.first(where: { $0.id == project.id }) else { return }

                var updatedProject = currentProject
                updatedProject.segments.append(videoSegment)
                projectManager.updateProject(updatedProject)  // ← 状態更新

                selectedProject = updatedProject
            },
            onBackToProjects: {
                currentScreen = .projects  // ← 画面遷移
            }
        )
    }
}
```

**実装箇所**: MainView.swift:74-94

#### アプリ終了時の処理

iOS版では、アプリ終了時に明示的なクリーンアップは行っていません。理由：

- **自動保存**: プロジェクト作成・更新時に即座に UserDefaults に保存
- **メモリ解放**: @StateObject は MainView 破棄時に自動的に解放される
- **リソース解放**: CameraView / PlayerView の onDisappear で個別に処理

```swift
// プロジェクト更新時に自動保存
func updateProject(_ updatedProject: Project) {
    if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
        projects[index] = updatedProject
        saveProjects()  // ✅ 即座にUserDefaultsに保存
    }
}
```

**実装箇所**: ProjectManager.swift:33-39

---

### 3.1.2 ProjectManager.swift のライフサイクル

#### ObservableObject としての実装

```swift
class ProjectManager: ObservableObject {
    @Published var projects: [Project] = []

    private let userDefaults = UserDefaults.standard
    private let projectsKey = "JourneyMoments_Projects"

    init() {
        loadProjects()  // ✅ 初期化時にUserDefaultsから読み込み
    }
}
```

**実装箇所**: ProjectManager.swift:8-16

#### @Published プロパティの役割

```swift
@Published var projects: [Project] = []
```

**動作フロー**:

1. `projects` が変更される
2. SwiftUI が自動的に変更を検知
3. `projects` を参照しているすべての View が再描画される

**具体例**:

```swift
// ProjectListView.swift
ProjectListView(
    projects: projectManager.projects,  // ← @Published を監視
    onCreateProject: { ... }
)

// projectManager.projects が変更されると、
// ProjectListView が自動的に再描画される
```

**実装箇所**: ProjectManager.swift:9, MainView.swift:41

#### プロジェクト操作時の処理フロー

##### 作成時

```swift
func createNewProject() -> Project {
    let projectName = "Project \(projects.count + 1)"
    let newProject = Project(name: projectName)

    projects.append(newProject)  // ← @Published が変更を通知
    saveProjects()               // ← UserDefaultsに即座に保存

    return newProject
}
```

**実装箇所**: ProjectManager.swift:21-30

##### 更新時

```swift
func updateProject(_ updatedProject: Project) {
    if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
        projects[index] = updatedProject  // ← @Published が変更を通知
        saveProjects()                    // ← UserDefaultsに即座に保存
    }
}
```

**実装箇所**: ProjectManager.swift:33-39

##### 削除時

```swift
func deleteProject(_ project: Project) {
    deleteVideoFiles(for: project)  // 1. 物理ファイルを削除
    projects.removeAll { $0.id == project.id }  // 2. @Published が変更を通知
    saveProjects()  // 3. UserDefaultsに即座に保存
}
```

**実装箇所**: ProjectManager.swift:435-449

#### ファイルシステムとの同期タイミング

| 操作 | データ保存 | ビデオファイル保存 |
|------|-----------|-------------------|
| プロジェクト作成 | UserDefaults に即座に保存 | なし（セグメントがまだない） |
| セグメント追加 | UserDefaults に即座に保存 | Documents ディレクトリに保存済み |
| プロジェクト削除 | UserDefaults から削除 | Documents ディレクトリから物理削除 |
| セグメント削除 | UserDefaults に即座に保存 | Documents ディレクトリから物理削除 |

**データ保存の実装**:

```swift
private func saveProjects() {
    do {
        let data = try JSONEncoder().encode(projects)
        userDefaults.set(data, forKey: projectsKey)
        print("Projects saved successfully: \(projects.count) items")
    } catch {
        print("Project save error: \(error)")
    }
}
```

**実装箇所**: ProjectManager.swift:490-498

#### メモリ管理

ProjectManager は @StateObject として MainView に保持されるため：

- **初期化**: MainView 初回表示時に1回だけ
- **保持**: アプリが起動している限り保持
- **解放**: アプリ終了時に自動的に解放

**メモリリーク防止策**:

- `projects` 配列は値型（struct）のため、循環参照なし
- AVComposition 作成時は都度生成・破棄（保持しない）
- ビデオファイルはディスクに保存（メモリ上に保持しない）

---

### 3.1.3 VideoManager.swift のリソース管理（カメラセッション）

#### AVCaptureSession の初期化

```swift
@MainActor
class VideoManager: NSObject, ObservableObject {
    private var captureSession: AVCaptureSession?
    private var videoDeviceInput: AVCaptureDeviceInput?
    private var audioDeviceInput: AVCaptureDeviceInput?
    private var movieOutput: AVCaptureMovieFileOutput?

    @Published var isSetupComplete = false  // ✅ セットアップ完了フラグ

    var previewLayer: AVCaptureVideoPreviewLayer?
}
```

**実装箇所**: VideoManager.swift:13-27

#### setupCamera() のタイミングと処理

```swift
func setupCamera() async {
    print("🔧 setupCamera() 開始")

    guard cameraPermissionGranted else {
        print("❌ カメラ権限が許可されていません")
        return
    }

    // 1. マイク権限もリクエスト
    await requestMicrophonePermission()

    // 2. CaptureSession 作成
    captureSession = AVCaptureSession()
    captureSession?.beginConfiguration()

    // 3. カメラデバイス設定
    await setupCameraDevice(position: currentCameraPosition)

    // 4. 音声デバイス設定
    await setupAudioDevice()

    // 5. 動画出力設定
    setupMovieOutput()

    captureSession?.commitConfiguration()

    // 6. プレビューレイヤー作成
    setupPreviewLayer()

    // 7. セッション開始（バックグラウンドスレッド）
    await startSession()

    // ✅ セットアップ完了を明示的にマーク
    isSetupComplete = true
    print("✅ カメラセットアップ完全完了")
}
```

**実装箇所**: VideoManager.swift:61-105

**呼び出しタイミング**:

```swift
// CameraView.swift
.onAppear {
    setupCamera()  // ✅ 画面表示時に初期化
}
```

**実装箇所**: CameraView.swift:81-82, 237-252

#### startSession() / stopSession() のタイミング

##### 開始パターン

```swift
private func startSession() async {
    guard let captureSession = captureSession else { return }

    await withCheckedContinuation { continuation in
        // ✅ バックグラウンドスレッドで実行（メインスレッドをブロックしない）
        DispatchQueue.global(qos: .userInitiated).async {
            if !captureSession.isRunning {
                captureSession.startRunning()
                print("✅ カメラセッション開始完了")
            }

            DispatchQueue.main.async {
                self.isSessionRunning = captureSession.isRunning
                continuation.resume()
            }
        }
    }
}
```

**実装箇所**: VideoManager.swift:211-227

##### 停止パターン

```swift
func stopSession() {
    guard let captureSession = captureSession else { return }

    Task {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                if captureSession.isRunning {
                    captureSession.stopRunning()
                    print("🛑 カメラセッション停止")
                }

                DispatchQueue.main.async {
                    self.isSessionRunning = false
                    self.isSetupComplete = false  // ✅ セットアップ状態をリセット
                    continuation.resume()
                }
            }
        }
    }
}
```

**実装箇所**: VideoManager.swift:229-248

**呼び出しタイミング**:

```swift
// CameraView.swift
.onDisappear {
    if isTorchOn {
        toggleTorch()  // ✅ ライトを消す
    }
    videoManager.stopSession()  // ✅ カメラセッションを停止
}
```

**実装箇所**: CameraView.swift:83-88

#### カメラ切り替え時のリソース管理

```swift
func toggleCamera() async {
    let newPosition: AVCaptureDevice.Position = currentCameraPosition == .back ? .front : .back

    guard let captureSession = captureSession else { return }

    // ✅ トランザクション内で安全に切り替え
    captureSession.beginConfiguration()
    await setupCameraDevice(position: newPosition)  // ← 古い入力を削除 → 新しい入力を追加
    captureSession.commitConfiguration()
}

private func setupCameraDevice(position: AVCaptureDevice.Position) async {
    guard let captureSession = captureSession else { return }

    // 1. 既存の入力を削除
    if let currentInput = videoDeviceInput {
        captureSession.removeInput(currentInput)
    }

    // 2. 新しいカメラデバイスを取得
    guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
        return
    }

    // 3. 新しい入力を追加
    do {
        let deviceInput = try AVCaptureDeviceInput(device: camera)

        if captureSession.canAddInput(deviceInput) {
            captureSession.addInput(deviceInput)
            videoDeviceInput = deviceInput
            currentCameraPosition = position
        }
    } catch {
        print("❌ カメラデバイス作成エラー: \(error)")
    }
}
```

**実装箇所**: VideoManager.swift:252-260, 107-135

**重要ポイント**:

- `beginConfiguration()` / `commitConfiguration()` でアトミックに切り替え
- 古い入力を削除してから新しい入力を追加（リソースリーク防止）
- カメラ切り替え中もセッションは実行中（プレビューが途切れない）

#### トーチ ON/OFF 時のリソース管理

```swift
private func toggleTorch() {
    guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                for: .video,
                                                position: .back) else {
        return
    }

    guard device.hasTorch else {
        return
    }

    do {
        // ✅ ロックを取得してから設定変更
        try device.lockForConfiguration()

        if isTorchOn {
            device.torchMode = .off
            isTorchOn = false
        } else {
            try device.setTorchModeOn(level: 1.0)
            isTorchOn = true
        }

        // ✅ 設定完了後にアンロック
        device.unlockForConfiguration()
    } catch {
        print("Torch control error: \(error)")
    }
}
```

**実装箇所**: CameraView.swift:308-338

**重要ポイント**:

- `lockForConfiguration()` でデバイスをロック
- 設定変更後に必ず `unlockForConfiguration()`
- エラー処理でロック漏れを防止

#### onDisappear での自動停止処理

```swift
// CameraView.swift
.onDisappear {
    // 1. ライトが点いていれば消す
    if isTorchOn {
        toggleTorch()
    }

    // 2. カメラセッションを停止
    videoManager.stopSession()
}
```

**実装箇所**: CameraView.swift:83-88

**停止処理の内容**:

- トーチを OFF にする（バッテリー節約）
- `captureSession.stopRunning()` でセッション停止
- `isSetupComplete = false` でセットアップ状態をリセット
- リソース解放（入力・出力は保持したまま、次回の onAppear で再利用可能）

---

### 3.1.4 PlayerView.swift のリソース管理（AVPlayer）

#### AVPlayer の初期化タイミング

```swift
struct PlayerView: View {
    @State private var player = AVPlayer()  // ✅ View作成時に初期化
    @State private var playerItem: AVPlayerItem?
    @State private var composition: AVComposition?

    @State private var useSeamlessPlayback = true  // シームレス再生モード

    var body: some View {
        // ...
    }

    .onAppear {
        setupPlayer()  // ✅ 画面表示時にプレーヤーセットアップ
    }
    .onDisappear {
        cleanupPlayer()  // ✅ 画面非表示時にリソース完全解放
    }
}
```

**実装箇所**: PlayerView.swift:16-17, 33, 93-99

#### setupPlayer() の処理フロー

```swift
private func setupPlayer() {
    print("PlayerView setup started - Mode: \(useSeamlessPlayback ? "Seamless" : "Individual")")

    if useSeamlessPlayback {
        loadComposition()  // ✅ AVComposition 統合モード
    } else {
        loadCurrentSegment()  // 個別セグメント再生モード（互換性用）
    }
}
```

**実装箇所**: PlayerView.swift:847-855

#### AVComposition 作成時のリソース確保

```swift
private func loadComposition() {
    print("Loading composition for seamless playback")

    // 1. ローディング状態を開始
    isLoadingComposition = true
    loadingProgress = 0.0
    loadingMessage = "Preparing seamless playback..."
    processedSegments = 0
    loadingStartTime = Date()

    Task {
        // 2. Composition を非同期で作成（進捗付き）
        guard let newComposition = await createCompositionWithProgress() else {
            print("Failed to create composition")

            await MainActor.run {
                isLoadingComposition = false
                useSeamlessPlayback = false
                loadCurrentSegment()  // ✅ 失敗時は個別再生にフォールバック
            }
            return
        }

        // 3. セグメント時間範囲を取得
        segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

        // 4. UI更新（メインスレッド）
        await MainActor.run {
            // 既存のオブザーバーを削除
            removeTimeObserver()
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)

            // 新しいプレーヤーアイテムを作成
            let newPlayerItem = AVPlayerItem(asset: newComposition)

            // 再生完了通知を監視
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: newPlayerItem,
                queue: .main
            ) { _ in
                self.handleCompositionEnd()
            }

            // プレーヤーにセット
            composition = newComposition
            player.replaceCurrentItem(with: newPlayerItem)
            playerItem = newPlayerItem

            // 再生準備
            player.pause()
            isPlaying = false
            currentTime = 0
            duration = newComposition.duration.seconds

            // 時間監視開始
            startTimeObserver()
            updateCurrentSegmentIndex()

            // ローディング終了
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.isLoadingComposition = false
            }
        }
    }
}
```

**実装箇所**: PlayerView.swift:858-941

**リソース確保のポイント**:

- **AVComposition**: 全セグメントを統合した仮想的なアセット（メモリ効率的）
- **AVPlayerItem**: Composition をラップした再生可能アイテム
- **TimeObserver**: 再生進捗を監視するタイマー
- **NotificationCenter**: 再生完了を監視

#### 再生完了時のリソース解放

```swift
private func handleCompositionEnd() {
    print("Composition playback completed - Returning to start")

    // ✅ 再生位置をリセット（リソースは保持）
    player.seek(to: .zero)
    currentSegmentIndex = 0
    isPlaying = false

    print("Stopped - Press play button to replay")
}
```

**実装箇所**: PlayerView.swift:959-965

**ポイント**:

- 再生完了時は `seek(to: .zero)` で先頭に戻る
- AVPlayer や Composition は破棄しない（再再生可能）
- 画面を離れる時に初めて完全解放（下記参照）

#### onDisappear での完全なリソース解放

```swift
private func cleanupPlayer() {
    // 1. 再生停止
    player.pause()

    // 2. タイムオブザーバーを削除
    removeTimeObserver()

    // 3. 通知監視を解除
    NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)

    // 4. プレーヤーアイテムを削除
    player.replaceCurrentItem(with: nil)

    // 5. Composition とセグメント時間範囲をクリア
    composition = nil
    segmentTimeRanges = []

    print("PlayerView cleanup completed")
}

private func removeTimeObserver() {
    if let observer = timeObserver {
        player.removeTimeObserver(observer)
        timeObserver = nil
    }
}
```

**実装箇所**: PlayerView.swift:1228-1236, 1209-1214

**呼び出しタイミング**:

```swift
.onDisappear {
    cleanupPlayer()  // ✅ 画面非表示時に完全解放
}
```

**実装箇所**: PlayerView.swift:97-99

#### メモリリーク防止パターン

**問題: 循環参照の可能性**

```swift
// ❌ 悪い例（iOS版にはない）
NotificationCenter.default.addObserver(
    self,  // ← self を強参照
    selector: #selector(handleEnd),
    name: .AVPlayerItemDidPlayToEndTime,
    object: playerItem
)
```

**解決策: クロージャベースの監視**

```swift
// ✅ 良い例（iOS版の実装）
NotificationCenter.default.addObserver(
    forName: .AVPlayerItemDidPlayToEndTime,
    object: newPlayerItem,
    queue: .main
) { _ in
    self.handleCompositionEnd()  // ← SwiftUI の @State なので問題なし
}
```

**実装箇所**: PlayerView.swift:902-909

**ポイント**:

- SwiftUI の @State は値型のため、循環参照が発生しない
- `onDisappear` で確実に `removeObserver()` を呼ぶ
- `player.replaceCurrentItem(with: nil)` でプレーヤーアイテムを明示的に解放

---

### 3.1.5 画面遷移時のリソース解放パターン

#### パターン1: ProjectListView → CameraView 遷移時

```swift
// MainView.swift
.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    if let project = selectedProject {
        CameraView(
            currentProject: project,
            onRecordingComplete: { ... },
            onBackToProjects: {
                currentScreen = .projects  // ← 遷移トリガー
            }
        )
    }
}
```

**実装箇所**: MainView.swift:74-94

**リソース状態**:

- **ProjectListView**: 非表示になるが破棄されない（MainView が保持）
- **ProjectManager**: MainView の @StateObject なので保持される
- **CameraView**: 新規作成
- **VideoManager**: CameraView の @StateObject として新規作成

#### パターン2: CameraView → ProjectListView 戻る時

```swift
// CameraView.swift
.onDisappear {
    // 1. ライトを消す
    if isTorchOn {
        toggleTorch()
    }

    // 2. カメラセッションを停止
    videoManager.stopSession()
}
```

**実装箇所**: CameraView.swift:83-88

**リソース解放**:

| リソース | 状態 |
|---------|------|
| AVCaptureSession | stopRunning() で停止（インスタンスは保持） |
| AVCaptureDevice | 入力を削除せず保持（次回の表示で再利用可能） |
| AVCaptureVideoPreviewLayer | 保持（レイアウト時に再利用） |
| VideoManager | CameraView 破棄時に自動的に解放 |
| Torch（ライト） | OFF にする |

**戻る処理**:

```swift
Button(action: onBackToProjects) {
    HStack(spacing: 4) {
        Image(systemName: "chevron.left")
        Text("Projects")
    }
}
```

**実装箇所**: CameraView.swift:101

#### パターン3: ProjectListView → PlayerView 遷移時

```swift
// MainView.swift
.fullScreenCover(isPresented: .constant(currentScreen == .player)) {
    if let project = selectedProject {
        PlayerView(
            projectManager: projectManager,  // ← MainViewのインスタンスを渡す
            initialProject: project,
            onBack: {
                currentScreen = .projects  // ← 遷移トリガー
            },
            onDeleteSegment: { project, segment in
                projectManager.deleteSegment(from: project, segment: segment)
            }
        )
    }
}
```

**実装箇所**: MainView.swift:95-108

**リソース状態**:

- **ProjectListView**: 非表示になるが破棄されない
- **ProjectManager**: PlayerView に @ObservedObject として渡される（MainView が所有）
- **PlayerView**: 新規作成
- **AVPlayer**: PlayerView の @State として新規作成

#### パターン4: PlayerView → ProjectListView 戻る時

```swift
// PlayerView.swift
.onDisappear {
    cleanupPlayer()
}

private func cleanupPlayer() {
    // 1. 再生停止
    player.pause()

    // 2. タイムオブザーバーを削除
    removeTimeObserver()

    // 3. 通知監視を解除
    NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)

    // 4. プレーヤーアイテムを削除
    player.replaceCurrentItem(with: nil)

    // 5. Composition とセグメント時間範囲をクリア
    composition = nil
    segmentTimeRanges = []

    print("PlayerView cleanup completed")
}
```

**実装箇所**: PlayerView.swift:97-99, 1228-1236

**リソース解放**:

| リソース | 状態 |
|---------|------|
| AVPlayer | pause() で停止 → replaceCurrentItem(with: nil) で解放 |
| AVComposition | nil 代入で解放 |
| AVPlayerItem | replaceCurrentItem(with: nil) で解放 |
| TimeObserver | removeTimeObserver() で削除 |
| NotificationCenter | removeObserver() で監視解除 |
| PlayerView | 自動的に破棄 |

**戻る処理**:

```swift
Button(action: {
    print("Back button tapped")
    onBack()
}) {
    HStack(spacing: 4) {
        Image(systemName: "chevron.left")
        Text("Back")
    }
}
```

**実装箇所**: PlayerView.swift:256-269

#### 画面遷移時のクリーンアップまとめ

| 遷移 | 解放するリソース | 保持するリソース |
|------|----------------|----------------|
| ProjectList → Camera | なし | ProjectManager, PurchaseManager |
| Camera → ProjectList | AVCaptureSession（停止）, Torch（OFF） | なし（VideoManager は破棄） |
| ProjectList → Player | なし | ProjectManager, PurchaseManager |
| Player → ProjectList | AVPlayer, AVComposition, TimeObserver, NotificationCenter | なし（PlayerView は破棄） |

---

## iOS版のリソース管理の基本原則

1. **@StateObject**: 親View（MainView）で生成し、画面遷移しても保持
2. **onAppear**: リソースの初期化（カメラセッション、プレーヤー）
3. **onDisappear**: リソースの解放（確実にクリーンアップ）
4. **即座に保存**: データ変更時に UserDefaults へ即座に保存（アプリ終了時の特別処理不要）
5. **メモリリーク防止**: NotificationCenter の監視解除、TimeObserver の削除、プレーヤーアイテムの nil 化

---

*以上がセクション3A「iOS版のライフサイクル管理」です。*
