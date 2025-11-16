ClipFlow Android移植 - Stage 2: 分析結果文書
分析結果1: 前回の4つの問題への対策計画
1. VideoComposer - MediaMetadataRetrieverのリソースリーク対策
iOS版パターン（ProjectManager.swift:167-237）:

for (index, segment) in sortedSegments.enumerated() {
    let asset = AVURLAsset(url: fileURL)
    
    do {
        // 非同期でメタデータ取得（自動解放）
        let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
        let assetAudioTracks = try await asset.loadTracks(withMediaType: .audio)
        let assetDuration = try await asset.load(.duration)
        
        // 取得後すぐに使用、保持しない
        if let assetVideoTrack = assetVideoTracks.first {
            try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
        }
        // assetは自動解放される
    } catch {
        print("Failed to add segment \(segment.order): \(error)")
    }
}
Android対策:

// NG: リソースリーク
val retriever = MediaMetadataRetriever()
retriever.setDataSource(path)
val width = retriever.extractMetadata(...)
// release()忘れ

// OK: use {}ブロックで確実解放
MediaMetadataRetriever().use { retriever ->
    retriever.setDataSource(path)
    val width = retriever.extractMetadata(...)?.toIntOrNull() ?: throw Exception("Invalid width")
} // 自動release

// OK: 大量セグメント処理時
sortedSegments.forEach { segment ->
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(segment.uri)
        // 処理
    } // 各セグメント後に即解放
}
2. PlayerScreen - cameraProvider.unbindAll()問題への対策
iOS版パターン（VideoManager.swift:229-248）:

func stopSession() {
    guard let captureSession = captureSession else { return }
    
    Task {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                if captureSession.isRunning {
                    captureSession.stopRunning()  // 自分のセッションのみ停止
                }
                
                DispatchQueue.main.async {
                    self.isSessionRunning = false
                    self.isSetupComplete = false
                    continuation.resume()
                }
            }
        }
    }
}
Android対策:

// NG: 全カメラをアンバインド（PlayerScreenで使っていた）
DisposableEffect(Unit) {
    onDispose {
        cameraProvider.unbindAll()  // 他の画面のカメラも解放してしまう
    }
}

// OK: 自分のUseCaseのみアンバインド
@Composable
fun PlayerScreen(...) {
    // カメラを使わない画面ではCameraProviderを触らない
    
    DisposableEffect(Unit) {
        onDispose {
            // ExoPlayerのみ解放
            exoPlayer.release()
        }
    }
}

// OK: CameraScreenでのみカメラ管理
@Composable
fun CameraScreen(...) {
    val cameraProvider = remember { ... }
    val preview = remember { Preview.Builder().build() }
    val videoCapture = remember { VideoCapture.Builder().build() }
    
    DisposableEffect(lifecycleOwner) {
        // バインド
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            videoCapture
        )
        
        onDispose {
            // 自分のUseCaseのみアンバインド
            cameraProvider.unbind(preview, videoCapture)
            // または画面離脱時のみunbindAll()
        }
    }
}
3. ProjectListView - REC/Play/Exportボタン実装
iOS版パターン（ProjectListView.swift:216-282）:

// 3ボタン横並び（等幅、高さ40）
HStack(spacing: 0) {
    // Record button - 赤
    Button {
        onOpenProject(project)
    } label: {
        HStack(spacing: 6) {
            Image(systemName: "camera.fill")
            Text("Rec")
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .background(Color.red)
        .foregroundColor(.white)
    }
    
    // Play button - 青
    Button {
        onPlayProject(project)
    } label: {
        HStack(spacing: 6) {
            Image(systemName: "play.fill")
            Text("Play")
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .background(Color.blue)
        .foregroundColor(.white)
    }
    .disabled(project.segmentCount == 0)
    .opacity(project.segmentCount == 0 ? 0.5 : 1.0)
    
    // Export button - オレンジ
    Button {
        handleExportProject(project)
    } label: {
        HStack(spacing: 6) {
            Image(systemName: "square.and.arrow.up")
            Text("Export")
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .background(Color.orange)
        .foregroundColor(.white)
    }
    .disabled(project.segmentCount == 0)
    .opacity(project.segmentCount == 0 ? 0.5 : 1.0)
}
.cornerRadius(8)
Android実装確認ポイント:

// Jetpack Compose
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly
) {
    // REC - 赤 (#FF0000)
    Button(
        onClick = { onOpenProject(project) },
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
        modifier = Modifier.weight(1f).height(40.dp)
    ) {
        Icon(Icons.Default.Videocam, "Record")
        Text("Rec")
    }
    
    // Play - 青 (#0000FF)
    Button(
        onClick = { onPlayProject(project) },
        enabled = project.segments.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
        modifier = Modifier.weight(1f).height(40.dp)
    ) {
        Icon(Icons.Default.PlayArrow, "Play")
        Text("Play")
    }
    
    // Export - オレンジ (#FFA500)
    Button(
        onClick = { onExportProject(project) },
        enabled = project.segments.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)),
        modifier = Modifier.weight(1f).height(40.dp)
    ) {
        Icon(Icons.Default.Share, "Export")
        Text("Export")
    }
}
4. セグメント15個制限への対策
iOS版パターン（ProjectManager.swift:249-370）:

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
Android対策:

suspend fun createCompositionWithProgress(
    project: Project,
    onProgress: (processed: Int, total: Int) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    
    val segments = project.segments.sortedBy { it.order }
    val totalSegments = segments.size
    
    segments.forEachIndexed { index, segment ->
        // 進捗通知
        withContext(Dispatchers.Main) {
            onProgress(index, totalSegments)
        }
        
        // 各セグメント処理時にリソース確実解放
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(segment.uri)
            // 処理
        }
        
        // GC促進（15セグメント問題対策）
        if (index % 10 == 0) {
            System.gc()
            delay(10) // 0.01秒待機
        }
    }
    
    onProgress(totalSegments, totalSegments)
    Result.success(outputFile)
}
分析結果2: iOS版の重要な実装パターン
1. 時間管理パターン（ProjectManager.swift:143-246）
CMTime基本操作:

// 初期化
var currentTime = CMTime.zero  // 0秒

// 時間範囲作成
let timeRange = CMTimeRange(start: .zero, duration: assetDuration)

// 時間加算
currentTime = CMTimeAdd(currentTime, assetDuration)

// 秒数取得
let totalDuration = currentTime.seconds  // Double型

// 時間範囲チェック
if CMTimeRangeContainsTime(timeRange, time: currentPlayerTime) {
    // 範囲内
}
セグメント統合時の時刻計算:

func createComposition(for project: Project) async -> AVComposition? {
    let composition = AVMutableComposition()
    var currentTime = CMTime.zero  // 開始時刻
    
    let sortedSegments = project.segments.sorted { $0.order < $1.order }
    
    for segment in sortedSegments {
        let asset = AVURLAsset(url: fileURL)
        let assetDuration = try await asset.load(.duration)
        
        // 現在位置にセグメントを挿入
        let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
        try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
        
        // 次のセグメントの開始位置を更新
        currentTime = CMTimeAdd(currentTime, assetDuration)
        // currentTime: 0s → 1s → 2s → 3s ...
    }
    
    return composition
}
Android対応:

// MediaMuxer時間管理
var currentTimeUs = 0L  // マイクロ秒

segments.forEach { segment ->
    val extractor = MediaExtractor()
    extractor.setDataSource(segment.uri)
    
    // セグメント時間取得
    val duration = extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
    
    // 現在位置に追加
    // presentationTimeUs = currentTimeUs + sampleTimeUs
    
    currentTimeUs += duration
}
2. リソース管理パターン（VideoManager.swift:61-105）
AVCaptureSession初期化:

func setupCamera() async {
    // 1. セッション作成
    captureSession = AVCaptureSession()
    
    // 2. 設定開始
    captureSession.beginConfiguration()
    
    // 3. プリセット設定
    captureSession.sessionPreset = .high
    
    // 4. デバイス追加
    await setupCameraDevice(position: currentCameraPosition)
    await setupAudioDevice()
    
    // 5. 出力設定
    setupMovieOutput()
    
    // 6. 設定コミット
    captureSession.commitConfiguration()
    
    // 7. プレビュー作成
    setupPreviewLayer()
    
    // 8. セッション開始
    await startSession()
    
    // 9. 完了フラグ
    isSetupComplete = true
}
破棄タイミング（CameraView.swift:83-88）:

.onDisappear {
    if isTorchOn {
        toggleTorch()  // ライトOFF
    }
    videoManager.stopSession()  // セッション停止
}
@MainActorの役割:

@MainActor
class VideoManager: NSObject, ObservableObject {
    // UI状態はメインスレッドで更新
    @Published var isSetupComplete = false
    @Published var currentCameraPosition: AVCaptureDevice.Position = .back
    
    // バックグラウンド処理後、メインスレッドで状態更新
    private func startSession() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                captureSession.startRunning()  // バックグラウンド
                
                DispatchQueue.main.async {
                    self.isSessionRunning = true  // メイン
                    continuation.resume()
                }
            }
        }
    }
}
Android対応:

// ViewModel（メインスレッド安全）
class CameraViewModel : ViewModel() {
    private val _isSetupComplete = MutableStateFlow(false)
    val isSetupComplete: StateFlow<Boolean> = _isSetupComplete
    
    fun setupCamera(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            // バックグラウンド処理
            withContext(Dispatchers.IO) {
                // カメラ初期化
            }
            // UI更新（自動的にメインスレッド）
            _isSetupComplete.value = true
        }
    }
}
3. プレイヤー管理パターン（PlayerView.swift:846-966）
AVComposition作成から再生までのフロー:

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
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
            
            // 5. PlayerItem作成
            let newPlayerItem = AVPlayerItem(asset: newComposition)
            
            // 6. 再生完了監視登録
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: newPlayerItem,
                queue: .main
            ) { _ in
                self.handleCompositionEnd()
            }
            
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
時間監視（TimeObserver）実装:

private func startTimeObserver() {
    removeTimeObserver()
    
    // 0.1秒間隔で監視
    let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    
    timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
        self.updateCurrentTime()
        
        // シームレス再生時は現在セグメントも更新
        if self.useSeamlessPlayback {
            self.updateCurrentSegmentIndex()
        }
    }
}

private func updateCurrentTime() {
    let current = player.currentTime().seconds
    let total = playerItem.duration.seconds
    
    if current.isFinite && total.isFinite {
        currentTime = current
        duration = total
    }
}

private func updateCurrentSegmentIndex() {
    let currentPlayerTime = player.currentTime()
    
    for (index, (_, timeRange)) in segmentTimeRanges.enumerated() {
        if CMTimeRangeContainsTime(timeRange, time: currentPlayerTime) {
            if currentSegmentIndex != index {
                currentSegmentIndex = index
            }
            break
        }
    }
}
シーク機能実装（PlayerView.swift:423-473）:

private func handleSeekGesture(location: CGPoint, geometryWidth: CGFloat, isDragging: Bool) {
    guard useSeamlessPlayback, !segmentTimeRanges.isEmpty else { return }
    
    // 1. タップ位置から時間計算
    let tapProgress = max(0, min(1, location.x / geometryWidth))
    let targetTime = tapProgress * duration
    
    // 2. 対象セグメント特定
    var targetSegmentIndex = 0
    for (index, (_, timeRange)) in segmentTimeRanges.enumerated() {
        let segmentStartTime = timeRange.start.seconds
        let segmentEndTime = (timeRange.start + timeRange.duration).seconds
        
        if targetTime >= segmentStartTime && targetTime < segmentEndTime {
            targetSegmentIndex = index
            break
        }
    }
    
    // 3. セグメントインデックス更新
    currentSegmentIndex = targetSegmentIndex
    
    // 4. プレイヤーシーク実行
    let targetCMTime = segmentTimeRanges[targetSegmentIndex].timeRange.start
    player.seek(to: targetCMTime) { _ in
        print("Seek completed")
    }
}
Android対応:

// ExoPlayer時間監視
class PlayerViewModel : ViewModel() {
    private val _currentTime = MutableStateFlow(0L)
    val currentTime: StateFlow<Long> = _currentTime
    
    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex
    
    private var segmentTimeRanges: List<Pair<VideoSegment, LongRange>> = emptyList()
    
    fun setupPlayer(exoPlayer: ExoPlayer) {
        // 時間監視
        exoPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(...) {
                updateCurrentSegment(exoPlayer.currentPosition)
            }
        })
        
        // 定期更新（0.1秒間隔）
        viewModelScope.launch {
            while (true) {
                _currentTime.value = exoPlayer.currentPosition
                updateCurrentSegment(exoPlayer.currentPosition)
                delay(100)
            }
        }
    }
    
    private fun updateCurrentSegment(positionMs: Long) {
        segmentTimeRanges.forEachIndexed { index, (_, range) ->
            if (positionMs in range) {
                _currentSegmentIndex.value = index
                return
            }
        }
    }
    
    // シーク
    fun seekToSegment(index: Int, exoPlayer: ExoPlayer) {
        val targetTime = segmentTimeRanges[index].second.first
        exoPlayer.seekTo(targetTime)
        _currentSegmentIndex.value = index
    }
}
文書化完了。
