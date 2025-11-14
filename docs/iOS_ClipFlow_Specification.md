📱 ClipFlow iOS版 完全機能仕様書・Android移植ガイド
目次
アプリケーション概要
データモデル仕様
機能仕様書
UI/UX設計書
Kotlin移植チェックリスト
Android版実装ガイド
1. アプリケーション概要
1.1 アプリの目的
ClipFlowは、1秒単位の短い動画セグメントを連続撮影し、それらをシームレスに統合して1本の動画として再生・エクスポートできる動画編集アプリです。

1.2 主要機能
プロジェクト管理: 複数のプロジェクトを作成・管理
1秒撮影: カメラで1秒間の動画を連続撮影
シームレス再生: AVCompositionを使用した複数セグメントの統合再生
セグメント管理: 個別セグメントの削除・再生
エクスポート機能: フォトライブラリへの書き出し
ライト機能: カメラのトーチ（フラッシュライト）制御
課金機能: 無料版（3プロジェクト制限）と有料版（無制限）
1.3 技術スタック
UI: SwiftUI
動画処理: AVFoundation (AVPlayer, AVComposition, AVAssetExportSession)
カメラ: AVCaptureSession, AVCaptureDevice
データ永続化: UserDefaults (JSON encode/decode)
課金: StoreKit 2
2. データモデル仕様
2.1 VideoSegment (Models.swift:6-29)
struct VideoSegment: Codable, Identifiable {
    let id: Int                  // Unix timestamp (ms)
    let uri: String              // ファイル名（例: "segment_1234567890.mov"）
    let timestamp: Date          // 撮影日時
    let facing: String           // カメラ向き: "back" | "front"
    var order: Int               // 再生順序（1から開始）
}
Android対応:

@Serializable
data class VideoSegment(
    val id: Long,                // Unix timestamp (ms)
    val uri: String,             // ファイル名
    val timestamp: Long,         // Unix timestamp
    val facing: String,          // "back" | "front"
    var order: Int               // 再生順序
)
2.2 Project (Models.swift:33-58)
struct Project: Codable, Identifiable {
    let id: Int                  // プロジェクトID
    var name: String             // プロジェクト名
    var segments: [VideoSegment] // セグメントリスト
    let createdAt: Date          // 作成日時
    var lastModified: Date       // 最終更新日時
    
    var segmentCount: Int { segments.count }
}
Android対応:

@Serializable
data class Project(
    val id: Long,
    var name: String,
    var segments: List<VideoSegment>,
    val createdAt: Long,
    var lastModified: Long
) {
    val segmentCount: Int get() = segments.size
}
2.3 AppScreen (Models.swift:62-66)
enum AppScreen {
    case projects    // プロジェクト一覧
    case camera      // カメラ撮影
    case player      // 動画再生
}
Android対応: Sealed classまたはenum class

enum class AppScreen {
    PROJECTS, CAMERA, PLAYER
}
3. 機能仕様書
3.1 ProjectManager (ProjectManager.swift)
3.1.1 プロジェクト管理
createNewProject() (ProjectManager.swift:21-30)

新規プロジェクト作成
命名規則: "Project {count + 1}"
UserDefaultsへ自動保存
updateProject() (ProjectManager.swift:33-39)

プロジェクトの状態更新
セグメント追加時に呼び出される
renameProject() (ProjectManager.swift:42-55)

プロジェクト名変更
deleteProject() (ProjectManager.swift:435-449)

プロジェクト削除
関連する動画ファイルも物理削除
3.1.2 セグメント管理
deleteSegment() (ProjectManager.swift:58-94)

セグメント削除
重要: 最後の1セグメントは削除不可
削除後、orderを自動リナンバリング（連番を維持）
3.1.3 AVComposition作成（最重要機能）
createComposition() (ProjectManager.swift:143-246)

処理フロー:

AVMutableComposition作成
videoTrack、audioTrackを追加
セグメントをorder順にソート
各セグメントに対して:
ファイルURLを構築（documentsPath + filename）
AVURLAsset作成
asset.loadTracks(withMediaType:) でトラック取得（iOS 18対応）
videoTrack/audioTrackに時間範囲を追加
最初のセグメントから動画の向き情報を取得（重要）
assetVideoTrack.preferredTransform を取得
回転角度を計算し、90°/270°の場合は幅と高さを入れ替え
currentTimeを累積
動画の向き補正 (ProjectManager.swift:198-219):

let transform = assetVideoTrack.preferredTransform
let angle = atan2(transform.b, transform.a)
let isRotated = abs(angle) > .pi / 4

if isRotated {
    composition.naturalSize = CGSize(
        width: naturalSize.height, 
        height: naturalSize.width
    )
}
videoTrack.preferredTransform = transform
Android対応:

// Media3のTransformationを使用
val transformation = Composition.Builder()
    .setVideoCompositorSettings(
        VideoCompositorSettings.Builder()
            .setRotationDegrees(rotationDegrees)
            .build()
    )
createCompositionWithProgress() (ProjectManager.swift:249-370)

上記と同じ処理 + プログレスコールバック
10ms毎にTask.sleepでプログレス更新を可視化
UIのローディング表示と連携
getSegmentTimeRanges() (ProjectManager.swift:373-407)

各セグメントのComposition内での開始時刻と長さを計算
シーク機能で使用（タップした位置から対応セグメントを特定）
3.1.4 データ永続化
saveProjects() (ProjectManager.swift:490-498)

let data = try JSONEncoder().encode(projects)
userDefaults.set(data, forKey: "JourneyMoments_Projects")
loadProjects() (ProjectManager.swift:501-514)

projects = try JSONDecoder().decode([Project].self, from: data)
Android対応: SharedPreferences + Kotlinx.serialization

val json = Json.encodeToString(projects)
sharedPreferences.edit().putString("projects", json).apply()
3.2 PlayerView (PlayerView.swift)
3.2.1 プレイヤー初期化
setupPlayer() (PlayerView.swift:847-855)

useSeamlessPlayback = true がデフォルト
シームレス再生の場合: loadComposition()
個別再生の場合: loadCurrentSegment()
loadComposition() (PlayerView.swift:858-941)

処理フロー:

ローディング状態を開始（isLoadingComposition = true）
createCompositionWithProgress() を呼び出し
プログレスコールバックで進捗更新
loadingProgress = processed / total * 0.8 (最大80%)
getSegmentTimeRanges() でセグメント時間範囲を取得
AVPlayerItemを作成し、AVPlayerにセット
再生終了の監視: AVPlayerItemDidPlayToEndTime
タイムオブザーバーを開始（0.1秒間隔）
ローディング完了（0.5秒後に非表示）
loadCurrentSegment() (PlayerView.swift:968-1012)

個別セグメント再生（フォールバック用）
セグメント終了時に自動で次セグメントへ遷移
3.2.2 シーク機能
seekableProgressBar (PlayerView.swift:352-420)

実装詳細:

GeometryReaderでプログレスバーの幅を取得
DragGestureとonTapGestureでシーク位置を取得
セグメント境界に黄色の縦線を表示（視覚的フィードバック）
handleSeekGesture() (PlayerView.swift:423-473)

let tapProgress = location.x / geometryWidth
let targetTime = tapProgress * duration

// タップ位置に対応するセグメントを特定
for (index, (_, timeRange)) in segmentTimeRanges.enumerated() {
    if targetTime >= timeRange.start.seconds && targetTime < segmentEndTime {
        targetSegmentIndex = index
        break
    }
}

// プレイヤーをシーク
player.seek(to: targetCMTime)
Android対応:

// ExoPlayerのseekTo()を使用
player.seekTo(segmentIndex, positionMs)
3.2.3 エクスポート機能
exportVideo() (PlayerView.swift:723-801)

処理フロー:

既存のcompositionを使用（なければ新規作成）
出力URLを生成（documentsPath + projectName_timestamp.mp4）
AVAssetExportSession作成
Preset: AVAssetExportPresetHighestQuality
FileType: .mp4
進捗監視（0.1秒間隔のTimer）
exportAsynchronously() で非同期エクスポート
完了後、saveToPhotoLibrary() でフォトライブラリに保存
一時ファイル削除
saveToPhotoLibrary() (PlayerView.swift:827-843)

PHPhotoLibrary.shared().performChanges({
    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
})
Android対応:

// MediaStoreを使用
val values = ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
}
val uri = contentResolver.insert(
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 
    values
)
3.2.4 セグメント削除
handleSegmentDeletion() (PlayerView.swift:1125-1186)

処理フロー:

シームレス再生中の場合、個別再生に切り替え
onDeleteSegment(project, segment) を呼び出し（MainViewに委譲）
0.1秒後、プロジェクトの更新を確認
currentSegmentIndexを調整（範囲外にならないように）
loadCurrentSegment() で再ロード
元がシームレス再生なら、0.3秒後に再度シームレス再生に戻る
3.2.5 タイムオブザーバー
startTimeObserver() (PlayerView.swift:1195-1207)

let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
    self.updateCurrentTime()
    if self.useSeamlessPlayback {
        self.updateCurrentSegmentIndex()  // 現在のセグメントインデックスを更新
    }
}
updateCurrentSegmentIndex() (PlayerView.swift:944-956)

現在の再生時刻から、どのセグメントを再生中かを判定
segmentTimeRangesをループし、CMTimeRangeContainsTime() で判定
3.3 CameraView (CameraView.swift)
3.3.1 カメラセットアップ
setupCamera() (CameraView.swift:237-252)

await videoManager.requestCameraPermission()
if videoManager.cameraPermissionGranted {
    await videoManager.setupCamera()
}
VideoManager.setupCamera()の内部処理 (推定):

AVCaptureSession作成
AVCaptureDeviceを取得（.back/.front）
AVCaptureDeviceInputを追加
AVCaptureMovieFileOutputを追加
sessionPreset設定（例: .high）
session.startRunning()
3.3.2 1秒録画機能
recordOneSecondVideo() (CameraView.swift:260-306)

処理フロー:

isRecording = true で録画状態を表示
videoManager.recordOneSecond() を呼び出し
内部で1秒タイマーを起動し、自動停止
録画完了後、VideoSegmentを作成
uri: ファイル名のみ（lastPathComponent）
facing: videoManager.currentCameraPosition
order: 現在のセグメント数 + 1
onRecordingComplete(newSegment) でMainViewに通知
成功トーストを1.5秒間表示
Android対応:

// Camera2 + MediaRecorderを使用
mediaRecorder.start()
handler.postDelayed({ mediaRecorder.stop() }, 1000L)
3.3.3 ライト機能
toggleTorch() (CameraView.swift:308-338)

実装詳細:

guard let device = AVCaptureDevice.default(
    .builtInWideAngleCamera, 
    for: .video, 
    position: .back
) else { return }

try device.lockForConfiguration()
if isTorchOn {
    device.torchMode = .off
} else {
    try device.setTorchModeOn(level: 1.0)  // 最大輝度
}
device.unlockForConfiguration()
UI実装 (CameraView.swift:159-168):

アイコン: flashlight.on.fill / flashlight.off.fill
色: ON時は黄色、OFF時はグレー
位置: 録画ボタンの左側
Android対応:

val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
cameraManager.setTorchMode(cameraId, true)
3.3.4 カメラ切り替え
toggleCamera() (CameraView.swift:254-258)

await videoManager.toggleCamera()
VideoManager内部で:

session.stopRunning()
現在の入力デバイスを削除
反対のカメラデバイスを取得（.back ↔ .front）
新しい入力デバイスを追加
session.startRunning()
3.4 MainView (MainView.swift)
3.4.1 画面遷移
fullScreenCover (MainView.swift:74-108)

.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    CameraView(...)
}

.fullScreenCover(isPresented: .constant(currentScreen == .player)) {
    PlayerView(...)
}
currentScreenの変更で自動的に画面遷移
fullScreenCoverを使用することで、ステータスバーも非表示
3.4.2 セグメント追加フロー
onRecordingComplete (MainView.swift:78-87)

guard let currentProject = projectManager.projects.first(where: { $0.id == project.id })
var updatedProject = currentProject
updatedProject.segments.append(videoSegment)
projectManager.updateProject(updatedProject)
selectedProject = updatedProject  // 重要: 選択中プロジェクトも更新
注意点: Projectはstructなので、値渡し。必ず最新のプロジェクト状態を取得してから更新する。

3.4.3 課金機能統合
プロジェクト作成制限 (MainView.swift:220-224)

if !purchaseManager.canCreateNewProject(currentProjectCount: projectManager.projects.count) {
    showPurchaseView = true
    return
}
エクスポート制限 (MainView.swift:268-272)

if !purchaseManager.canExportVideo() {
    showPurchaseView = true
    return
}
4. UI/UX設計書
4.1 カラースキーム
| 要素 | 色 | 説明 | |------|------|------| | 背景 | Color.black | 全画面共通 | | ボタン背景 | Color.black.opacity(0.7) | 半透明黒 | | アクセント | Color.orange | エクスポート関連 | | 警告 | Color.red | 削除、録画中 | | 成功 | Color.green | 成功トースト | | セグメント情報 | Color.yellow | セグメント番号、境界線 | | プログレス | LinearGradient([.blue, .purple]) | ローディングバー |

4.2 レイアウト構造
4.2.1 PlayerView レイアウト
┌─────────────────────────────────────────┐
│ ┌─────────────────────────────────────┐ │ ← headerView
│ │ [← Back]          [1 / 5]           │ │   (top: 60)
│ │        Project Name                 │ │
│ └─────────────────────────────────────┘ │
│                                         │
│         📹 Video Preview                │ ← customPlayerView
│                                         │
│ ┌─────────────────────────────────────┐ │ ← playbackControls
│ │  0:00 ─────────●─────────── 5:00   │ │   (progressView)
│ │                                     │ │
│ │    [⏮]    [▶️/⏸]    [⏭]           │ │   (mainControls)
│ │                                     │ │
│ │       Segment 1                    │ │   (segmentInfo)
│ │    [🗑 Delete Segment]              │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
4.2.2 CameraView レイアウト
┌─────────────────────────────────────────┐
│ ┌─────────────────────────────────────┐ │ ← headerView
│ │ [← Projects]        [🔄]           │ │   (top: 60)
│ │     Project Name                   │ │
│ │      5s recorded                   │ │
│ └─────────────────────────────────────┘ │
│                                         │
│      🎥 Camera Preview                 │ ← CameraPreview
│                                         │
│ ┌─────────────────────────────────────┐ │ ← controlsView
│ │  [🔦]           ⭕                  │ │   (bottom: 50)
│ │             REC                     │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
4.3 アニメーション
4.3.1 ローディングオーバーレイ (PlayerView.swift:133-225)
回転アニメーション:

Image(systemName: "gearshape.2")
    .rotationEffect(.degrees(loadingProgress * 360))
    .animation(.linear(duration: 2).repeatForever(autoreverses: false), value: loadingProgress)
プログレスバー:

Rectangle()
    .fill(LinearGradient(colors: [.blue, .purple], ...))
    .frame(width: geometry.size.width * loadingProgress, ...)
    .animation(.easeInOut(duration: 0.3), value: loadingProgress)
4.3.2 成功トースト (CameraView.swift:71-78)
if showSuccessToast {
    successToastView
        .transition(.scale.combined(with: .opacity))
}

withAnimation(.easeInOut(duration: 0.3)) {
    showSuccessToast = true
}
表示時間: 1.5秒

4.3.3 エクスポート進捗オーバーレイ (MainView.swift:152-212)
半透明黒背景（opacity: 0.85）
プログレスバー: オレンジ色
更新頻度: 0.05秒（50ms）
4.4 フォント・タイポグラフィ
| 要素 | フォント | |------|----------| | プロジェクト名 | .title2, .fontWeight(.bold) | | セグメント情報 | .caption, .fontWeight(.semibold) | | 時間表示 | .caption, .monospacedDigit() | | ボタンテキスト | .body / .caption | | ローディングメッセージ | .title2, .fontWeight(.semibold) |

4.5 シャドウ・エフェクト
// テキストシャドウ
.shadow(color: .black, radius: 2, x: 1, y: 1)

// ボックスシャドウ
.shadow(color: .black.opacity(0.5), radius: 20, x: 0, y: 10)

// ボタンシャドウ
.shadow(radius: 5)
5. Kotlin移植チェックリスト
5.1 言語・フレームワーク対応表
| iOS | Android | 備考 | |-----|---------|------| | SwiftUI | Jetpack Compose | 宣言的UI | | @State | remember { mutableStateOf() } | ローカル状態 | | @ObservedObject | ViewModel + collectAsState() | 共有状態 | | @Published | StateFlow / MutableStateFlow | リアクティブ | | Codable | kotlinx.serialization | シリアライゼーション | | UserDefaults | SharedPreferences / DataStore | データ永続化 | | async/await | suspend fun + coroutines | 非同期処理 | | Task { } | viewModelScope.launch { } | コルーチンスコープ |

5.2 AVFoundation → Media3/ExoPlayer 対応表
| iOS (AVFoundation) | Android (Media3) | |-------------------|------------------| | AVPlayer | ExoPlayer | | AVPlayerItem | MediaItem | | AVComposition | Composition (Media3 Transformer) | | AVMutableComposition | Composition.Builder() | | AVAssetExportSession | Transformer.start() | | AVCaptureSession | Camera2 / CameraX | | AVCaptureDevice | CameraDevice | | AVCaptureMovieFileOutput | MediaRecorder / CameraX VideoCapture | | AVPlayerLayer | PlayerView (AndroidView) | | CMTime | Long (milliseconds) | | CMTimeRange | Range<Long> |

5.3 UI Components 対応表
| SwiftUI | Jetpack Compose | |---------|-----------------| | VStack | Column | | HStack | Row | | ZStack | Box | | Spacer() | Spacer() | | Button | Button | | Text | Text | | Image(systemName:) | Icon (Material Icons) | | ProgressView | LinearProgressIndicator / CircularProgressIndicator | | alert() | AlertDialog | | sheet() | ModalBottomSheet / Dialog | | fullScreenCover() | Navigation + Fullscreen Composable | | GeometryReader | BoxWithConstraints | | Color.black | Color.Black | | .padding() | Modifier.padding() | | .cornerRadius() | Modifier.clip(RoundedCornerShape()) | | .opacity() | Modifier.alpha() | | .shadow() | Modifier.shadow() |

5.4 Permission対応表
| iOS | Android | |-----|---------| | AVCaptureDevice.requestAccess | Manifest.permission.CAMERA + ActivityCompat.requestPermissions | | PHPhotoLibrary.requestAuthorization | Manifest.permission.WRITE_EXTERNAL_STORAGE (API 28以下) |

6. Android版実装ガイド
6.1 アーキテクチャ
推奨構成:

app/
├── data/
│   ├── model/
│   │   ├── VideoSegment.kt
│   │   └── Project.kt
│   ├── repository/
│   │   └── ProjectRepository.kt
│   └── local/
│       └── PreferencesDataStore.kt
├── domain/
│   ├── usecase/
│   │   ├── CreateCompositionUseCase.kt
│   │   └── ExportVideoUseCase.kt
├── ui/
│   ├── screen/
│   │   ├── ProjectListScreen.kt
│   │   ├── CameraScreen.kt
│   │   └── PlayerScreen.kt
│   ├── viewmodel/
│   │   ├── ProjectListViewModel.kt
│   │   ├── CameraViewModel.kt
│   │   └── PlayerViewModel.kt
│   └── component/
│       └── VideoPlayer.kt
└── util/
    ├── VideoComposer.kt
    └── PermissionManager.kt
6.2 データモデル実装
VideoSegment.kt:

import kotlinx.serialization.Serializable

@Serializable
data class VideoSegment(
    val id: Long = System.currentTimeMillis(),
    val uri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val facing: String,  // "back" or "front"
    var order: Int
)
Project.kt:

@Serializable
data class Project(
    val id: Long = System.currentTimeMillis(),
    var name: String,
    var segments: List<VideoSegment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastModified: Long = System.currentTimeMillis()
) {
    val segmentCount: Int get() = segments.size
    
    fun addSegment(segment: VideoSegment): Project {
        return copy(
            segments = segments + segment,
            lastModified = System.currentTimeMillis()
        )
    }
}
6.3 ProjectRepository実装
class ProjectRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val projectsKey = stringPreferencesKey("projects")
    
    val projectsFlow: Flow<List<Project>> = dataStore.data
        .map { preferences ->
            val json = preferences[projectsKey] ?: "[]"
            Json.decodeFromString<List<Project>>(json)
        }
    
    suspend fun saveProjects(projects: List<Project>) {
        dataStore.edit { preferences ->
            preferences[projectsKey] = Json.encodeToString(projects)
        }
    }
    
    suspend fun createNewProject(): Project {
        val projects = projectsFlow.first()
        val newProject = Project(name = "Project ${projects.size + 1}")
        saveProjects(projects + newProject)
        return newProject
    }
    
    suspend fun deleteSegment(project: Project, segment: VideoSegment) {
        if (project.segments.size <= 1) {
            throw IllegalStateException("Cannot delete last segment")
        }
        
        // ファイル削除
        val file = File(context.filesDir, segment.uri)
        file.delete()
        
        // プロジェクト更新
        val updatedSegments = project.segments
            .filter { it.id != segment.id }
            .mapIndexed { index, seg -> seg.copy(order = index + 1) }
        
        val updatedProject = project.copy(segments = updatedSegments)
        updateProject(updatedProject)
    }
}
6.4 VideoComposer実装（最重要）
class VideoComposer(private val context: Context) {
    
    suspend fun createComposition(
        project: Project,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Composition? = withContext(Dispatchers.IO) {
        
        val compositionBuilder = Composition.Builder(
            Composition.Builder.SequenceList()
        )
        
        var currentTimeMs = 0L
        val sortedSegments = project.segments.sortedBy { it.order }
        
        sortedSegments.forEachIndexed { index, segment ->
            onProgress(index, sortedSegments.size)
            
            val file = File(context.filesDir, segment.uri)
            if (!file.exists()) return@forEachIndexed
            
            val mediaItem = MediaItem.fromUri(file.toURI().toString())
            
            // 動画の長さを取得
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLong() ?: 0L
            
            // 回転情報を取得（最初のセグメントのみ）
            if (index == 0) {
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toInt() ?: 0
                
                // Compositionに回転情報を設定
                compositionBuilder.setVideoCompositorSettings(
                    VideoCompositorSettings.Builder()
                        .setRotationDegrees(rotation)
                        .build()
                )
            }
            
            retriever.release()
            
            // MediaItemを追加
            compositionBuilder.experimentalAddMediaItem(mediaItem)
            
            currentTimeMs += durationMs
        }
        
        onProgress(sortedSegments.size, sortedSegments.size)
        
        try {
            compositionBuilder.build()
        } catch (e: Exception) {
            Log.e("VideoComposer", "Failed to create composition", e)
            null
        }
    }
    
    suspend fun exportComposition(
        composition: Composition,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onProgress(1f)
                }
                
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e("VideoComposer", "Export failed", exportException)
                }
            })
            .build()
        
        // プログレス監視
        val progressJob = launch {
            while (isActive) {
                val progress = transformer.progressState.value
                onProgress(progress.progressPercent / 100f)
                delay(50)
            }
        }
        
        try {
            transformer.start(composition, outputFile.absolutePath)
            transformer.awaitCompletion()
            progressJob.cancel()
            true
        } catch (e: Exception) {
            progressJob.cancel()
            false
        }
    }
}
6.5 PlayerScreen実装
@Composable
fun PlayerScreen(
    projectId: Long,
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val project by viewModel.project.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentTime by viewModel.currentTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val currentSegmentIndex by viewModel.currentSegmentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()
    
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
        viewModel.setupPlayer()
    }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video Player
        if (project != null && project!!.segments.isNotEmpty()) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = viewModel.exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            EmptyStateView()
        }
        
        // Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        ) {
            HeaderView(
                project = project,
                currentSegmentIndex = currentSegmentIndex,
                onBack = onBack
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            if (project?.segments?.isNotEmpty() == true) {
                PlaybackControls(
                    currentTime = currentTime,
                    duration = duration,
                    isPlaying = isPlaying,
                    onPlayPause = { viewModel.togglePlayback() },
                    onPrevious = { viewModel.previousSegment() },
                    onNext = { viewModel.nextSegment() },
                    onSeek = { position -> viewModel.seekTo(position) }
                )
            }
        }
        
        // Loading Overlay
        if (isLoading) {
            LoadingOverlay(
                progress = loadingProgress,
                processedSegments = viewModel.processedSegments.collectAsState().value,
                totalSegments = project?.segments?.size ?: 0
            )
        }
    }
}
6.6 CameraScreen実装
@Composable
fun CameraScreen(
    projectId: Long,
    viewModel: CameraViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val project by viewModel.project.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val cameraSelector by viewModel.cameraSelector.collectAsState()
    
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(surfaceProvider)
                    
                    val videoCapture = VideoCapture.Builder()
                        .setVideoFrameRate(30)
                        .build()
                    
                    viewModel.setupCamera(cameraProvider, preview, videoCapture, lifecycleOwner)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Controls
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderView(
                project = project,
                onBack = onBack,
                onToggleCamera = { viewModel.toggleCamera() }
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            CameraControls(
                isRecording = isRecording,
                isTorchOn = isTorchOn,
                onRecord = { viewModel.recordOneSecond() },
                onToggleTorch = { viewModel.toggleTorch() }
            )
        }
    }
}
6.7 CameraViewModel実装
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    
    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn
    
    private var camera: Camera? = null
    private var videoCapture: VideoCapture? = null
    
    fun recordOneSecond() {
        viewModelScope.launch {
            _isRecording.value = true
            
            val outputFile = File(
                context.filesDir,
                "segment_${System.currentTimeMillis()}.mp4"
            )
            
            val outputOptions = VideoCapture.OutputFileOptions.Builder(outputFile).build()
            
            videoCapture?.startRecording(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : VideoCapture.OnVideoSavedCallback {
                    override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                        val segment = VideoSegment(
                            uri = outputFile.name,
                            facing = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front",
                            order = project.value?.segments?.size?.plus(1) ?: 1
                        )
                        
                        viewModelScope.launch {
                            repository.addSegmentToProject(project.value!!.id, segment)
                        }
                        
                        _isRecording.value = false
                    }
                    
                    override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                        Log.e("CameraViewModel", "Recording failed: $message", cause)
                        _isRecording.value = false
                    }
                }
            )
            
            // 1秒後に停止
            delay(1000)
            videoCapture?.stopRecording()
        }
    }
    
    fun toggleTorch() {
        camera?.cameraControl?.enableTorch(!_isTorchOn.value)
        _isTorchOn.value = !_isTorchOn.value
    }
}
6.8 必要なライブラリ（build.gradle.kts）
dependencies {
    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-transformer:1.2.0")
    
    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-video:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    
    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
}
6.9 AndroidManifest.xml
<manifest>
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    
    <uses-feature android:name="android.hardware.camera" android:required="true" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
    
    <application
        android:name=".ClipFlowApplication"
        ...>
        <activity
            android:name=".MainActivity"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.ClipFlow.NoActionBar"
            ...>
        </activity>
    </application>
</manifest>
7. 重要な実装ポイント
7.1 AVComposition → Media3 Composition移行の注意点
時間単位の違い:

iOS: CMTime (秒 + timescale)
Android: Long (ミリ秒)
動画の向き補正:

iOS: preferredTransform + naturalSize の調整
Android: VideoCompositorSettings.setRotationDegrees()
非同期処理:

iOS: async/await
Android: suspend fun + Coroutines
7.2 パフォーマンス最適化
ローディング表示: Composition作成は時間がかかるため、必ずプログレス表示を実装
バックグラウンド処理: IO処理は必ず Dispatchers.IO で実行
メモリ管理: ExoPlayerは使用後必ず release() を呼ぶ
7.3 テスト項目チェックリスト

1秒録画が正確に1秒で停止するか

セグメントの順序が正しく保持されるか

AVComposition/Compositionの作成が成功するか

シーク機能が正しく動作するか（タップ位置 → セグメント判定）

セグメント削除後、orderが正しくリナンバリングされるか

最後の1セグメントは削除できないか

エクスポートが正常に完了するか

ライト機能が正常に動作するか（バックカメラのみ）

カメラ切り替えが正常に動作するか

動画の向きが正しく保持されるか（縦・横）

データの永続化が正常に動作するか

課金制限が正しく機能するか
8. まとめ
ClipFlow iOS版は、以下の技術要素で構成されています：

AVComposition統合再生: 複数動画セグメントをシームレスに統合
1秒録画: AVCaptureSessionによる正確な1秒録画
シーク機能: タップ位置から対応セグメントを特定
エクスポート: AVAssetExportSessionによる高品質書き出し
ライト制御: AVCaptureDevice.torchMode
Android移植では、以下の対応が必要です：

| iOS | Android | |-----|---------| | AVComposition | Media3 Composition + Transformer | | AVPlayer | ExoPlayer | | AVCaptureSession | CameraX VideoCapture | | UserDefaults | DataStore Preferences | | SwiftUI | Jetpack Compose |

最も重要なのは、AVCompositionの完全な再現です。ProjectManager.swift:143-246 の createComposition() の処理フローを、Media3のComposition.Builderで正確に実装する必要があります。

特に動画の向き補正（回転情報の取得と適用）は、エクスポート時の品質に直結するため、注意深く実装してください。

以上が、iOS版ClipFlowの完全な機能仕様・デザイン分析と、Android移植ガイドです。