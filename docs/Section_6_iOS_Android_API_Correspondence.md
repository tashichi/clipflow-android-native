# セクション6: 1対1 API対応表

## 1. クラス・型の対応表

### 1.1 基本データ型

| iOS (Swift) | Android (Kotlin) | 備考 |
|-------------|------------------|------|
| `String` | `String` | 同一 |
| `Int` | `Int` | 同一 |
| `Double` | `Double` | 同一 |
| `Float` | `Float` | 同一 |
| `Bool` | `Boolean` | キーワードが異なる |
| `Date` | `Long` (milliseconds) | `System.currentTimeMillis()` |
| `Date` | `Instant` | Java 8+ / kotlinx-datetime |
| `UUID` | `UUID` | `java.util.UUID` |
| `Array<T>` | `List<T>` | Kotlin は不変リスト推奨 |
| `[T]` | `List<T>` | 同上 |
| `Dictionary<K, V>` | `Map<K, V>` | 同様の使用感 |
| `Optional<T>` / `T?` | `T?` | Kotlin も nullable |
| `enum` | `enum class` / `sealed class` | sealed class 推奨 |
| `struct` | `data class` | 値型として使用 |
| `class` | `class` | 参照型 |

### 1.2 主要クラス

| iOS | Android | 備考 |
|-----|---------|------|
| `Project` (struct) | `Project` (data class) | カスタムモデル |
| `VideoSegment` (struct) | `VideoSegment` (data class) | カスタムモデル |
| `AVPlayer` | `ExoPlayer` | Media3 |
| `AVMutableComposition` | `Composition.Builder` | Media3 Transformer |
| `AVCaptureSession` | `ProcessCameraProvider` | CameraX |
| `AVCaptureDevice` | `Camera` | CameraX |
| `AVCaptureMovieFileOutput` | `VideoCapture<Recorder>` | CameraX |
| `AVAssetExportSession` | `Transformer` | Media3 Transformer |
| `PHPhotoLibrary` | `MediaStore` | Android ContentResolver |
| `FileManager` | `File` / `Context.filesDir` | Java IO |
| `UserDefaults` | `SharedPreferences` | データ永続化 |
| `JSONEncoder` / `JSONDecoder` | `Gson` / `kotlinx.serialization` | JSON処理 |
| `NotificationCenter` | `Flow` / `LiveData` / `BroadcastReceiver` | イベント通知 |

---

## 2. 主要API の対応表

### 2.1 ビデオ関連API

| iOS | Android | 説明 |
|-----|---------|------|
| `AVMutableComposition()` | `Composition.Builder(HDR_MODE_KEEP_HDR)` | Composition 作成 |
| `composition.addMutableTrack(withMediaType:)` | `Composition.Sequence.Builder()` | トラック追加 |
| `track.insertTimeRange(_:of:at:)` | `sequenceBuilder.addMediaItem()` | セグメント挿入 |
| `AVPlayerItem(asset: composition)` | `MediaItem.fromUri(uri)` | プレーヤーアイテム作成 |
| `player.replaceCurrentItem(with:)` | `player.setMediaItem()` | メディアセット |
| `player.play()` | `player.play()` | 再生開始 |
| `player.pause()` | `player.pause()` | 一時停止 |
| `player.seek(to:)` | `player.seekTo(positionMs)` | シーク |
| `player.currentTime()` | `player.currentPosition` | 現在位置取得 |
| `asset.load(.duration)` | `MediaMetadataRetriever.METADATA_KEY_DURATION` | 長さ取得 |
| `AVAssetExportSession(asset:presetName:)` | `Transformer.Builder(context).build()` | エクスポート準備 |
| `exportSession.exportAsynchronously()` | `transformer.start(composition, outputPath)` | エクスポート実行 |

### 2.2 カメラ関連API

| iOS | Android | 説明 |
|-----|---------|------|
| `AVCaptureSession()` | `ProcessCameraProvider.getInstance(context)` | セッション/プロバイダー取得 |
| `captureSession.beginConfiguration()` | `cameraProvider.unbindAll()` | 設定開始 |
| `captureSession.addInput(input)` | `cameraProvider.bindToLifecycle()` | デバイス追加 |
| `captureSession.commitConfiguration()` | （bindToLifecycleで完了） | 設定確定 |
| `captureSession.startRunning()` | `cameraProvider.bindToLifecycle()` | セッション開始 |
| `captureSession.stopRunning()` | `cameraProvider.unbind()` | セッション停止 |
| `device.lockForConfiguration()` | （不要） | 設定ロック |
| `device.torchMode = .on` | `camera.cameraControl.enableTorch(true)` | トーチ制御 |
| `device.position == .front` | `CameraSelector.LENS_FACING_FRONT` | カメラ向き |
| `device.position == .back` | `CameraSelector.LENS_FACING_BACK` | カメラ向き |
| `movieOutput.startRecording(to:)` | `videoCapture.output.prepareRecording().start()` | 録画開始 |
| `movieOutput.stopRecording()` | `recording.stop()` | 録画停止 |
| `withAudioEnabled` | `.withAudioEnabled()` | 音声有効化 |

### 2.3 ファイル・ストレージAPI

| iOS | Android | 説明 |
|-----|---------|------|
| `FileManager.default` | `context.filesDir` / `File` | ファイルマネージャー |
| `documentDirectory` | `context.filesDir` | アプリ専用ディレクトリ |
| `cachesDirectory` | `context.cacheDir` | キャッシュディレクトリ |
| `FileManager.default.fileExists(atPath:)` | `file.exists()` | ファイル存在確認 |
| `FileManager.default.removeItem(at:)` | `file.delete()` | ファイル削除 |
| `FileManager.default.copyItem(at:to:)` | `file.copyTo(destination)` | ファイルコピー |
| `FileManager.default.createDirectory(at:)` | `file.mkdirs()` | ディレクトリ作成 |
| `URL.resourceValues(forKeys:)` | `StatFs(path)` | ストレージ情報取得 |
| `PHPhotoLibrary.shared().performChanges()` | `ContentResolver.insert()` | ギャラリー保存 |

### 2.4 データ永続化API

| iOS | Android | 説明 |
|-----|---------|------|
| `UserDefaults.standard` | `context.getSharedPreferences()` | キー値ストレージ |
| `UserDefaults.set(_:forKey:)` | `sharedPrefs.edit().putString().apply()` | 値の保存 |
| `UserDefaults.object(forKey:)` | `sharedPrefs.getString()` | 値の取得 |
| `JSONEncoder().encode(object)` | `Gson().toJson(object)` | JSONエンコード |
| `JSONDecoder().decode(Type.self, from:)` | `Gson().fromJson(json, Type::class.java)` | JSONデコード |
| `Codable` protocol | `@Serializable` / Gson対応クラス | シリアライズ |

---

## 3. 画面遷移・ライフサイクル対応

### 3.1 画面遷移

| iOS (SwiftUI) | Android (Compose) | 説明 |
|---------------|-------------------|------|
| `NavigationStack` | `NavHost` + `NavController` | ナビゲーションコンテナ |
| `NavigationLink(destination:)` | `navController.navigate("route")` | 画面遷移 |
| `NavigationPath` | `NavBackStackEntry` | ナビゲーション履歴 |
| `.fullScreenCover(isPresented:)` | `navController.navigate()` | フルスクリーン遷移 |
| `.sheet(isPresented:)` | `ModalBottomSheet` / `Dialog` | モーダル表示 |
| `presentationMode.wrappedValue.dismiss()` | `navController.popBackStack()` | 画面を閉じる |
| `@State var currentScreen` | `rememberNavController()` | 現在画面の状態 |
| `@Binding` | `State hoisting` | 親子間の状態共有 |

### 3.2 ライフサイクル

| iOS (SwiftUI) | Android (Compose) | 説明 |
|---------------|-------------------|------|
| `@StateObject` | `viewModel()` | 状態オブジェクト保持 |
| `@ObservedObject` | `collectAsState()` | 監視オブジェクト |
| `@Published` | `MutableStateFlow` | 公開プロパティ |
| `@State` | `remember { mutableStateOf() }` | ローカル状態 |
| `@EnvironmentObject` | `CompositionLocalProvider` | 環境オブジェクト |
| `ObservableObject` | `ViewModel` | 監視可能オブジェクト |
| `.onAppear { }` | `LaunchedEffect(Unit) { }` | 画面表示時 |
| `.onDisappear { }` | `DisposableEffect { onDispose { } }` | 画面非表示時 |
| `init()` | `init { }` ブロック | 初期化 |
| `deinit` | `onCleared()` (ViewModel) | 破棄時 |
| `.onChange(of:)` | `LaunchedEffect(key) { }` | 値変化時 |
| `.task { }` | `LaunchedEffect(Unit) { }` | 非同期タスク |

---

## 4. 非同期処理・スレッド対応

### 4.1 非同期処理

| iOS (Swift) | Android (Kotlin) | 説明 |
|-------------|------------------|------|
| `async { }` | `launch { }` / `async { }` | 非同期ブロック |
| `await` | `suspend fun` / `.await()` | 非同期待機 |
| `Task { }` | `viewModelScope.launch { }` | タスク起動 |
| `Task.detached { }` | `GlobalScope.launch { }` | デタッチドタスク |
| `task.cancel()` | `job.cancel()` | タスクキャンセル |
| `Task.isCancelled` | `isActive` / `ensureActive()` | キャンセル確認 |
| `withCheckedContinuation` | `suspendCancellableCoroutine` | コールバックをasyncに変換 |
| `@escaping` クロージャ | `suspend` 関数 | 非同期コールバック |
| `Result<Success, Error>` | `Result<T>` / try-catch | 結果型 |

### 4.2 スレッド管理

| iOS | Android | 説明 |
|-----|---------|------|
| `DispatchQueue.main.async { }` | `withContext(Dispatchers.Main) { }` | メインスレッド |
| `DispatchQueue.global().async { }` | `withContext(Dispatchers.IO) { }` | バックグラウンド |
| `@MainActor` | `Dispatchers.Main` | メインアクター |
| `DispatchQueue.main.asyncAfter(deadline:)` | `delay(ms); ...` | 遅延実行 |
| `OperationQueue` | `CoroutineScope` | 操作キュー |
| `dispatchPrecondition(condition:)` | （デバッグアサーション） | スレッド確認 |

---

## 5. UI フレームワーク対応

### 5.1 ビュー

| iOS (SwiftUI) | Android (Compose) | 説明 |
|---------------|-------------------|------|
| `Text("...")` | `Text("...")` | テキスト表示 |
| `Image(systemName:)` | `Icon(imageVector = Icons.Default.XXX)` | アイコン |
| `Image(uiImage:)` | `Image(bitmap:)` | 画像表示 |
| `Button(action:) { }` | `Button(onClick = { }) { }` | ボタン |
| `TextField($text)` | `TextField(value = text, onValueChange = { })` | テキスト入力 |
| `Toggle(isOn:)` | `Switch(checked = , onCheckedChange = )` | スイッチ |
| `Slider(value:)` | `Slider(value = , onValueChange = )` | スライダー |
| `List { }` | `LazyColumn { items() { } }` | リスト |
| `ScrollView { }` | `Column(modifier = Modifier.verticalScroll())` | スクロールビュー |
| `TabView` | `TabRow` + `HorizontalPager` | タブ |
| `Alert(title:)` | `AlertDialog()` | アラート |
| `ProgressView()` | `CircularProgressIndicator()` | インジケーター |

### 5.2 レイアウト

| iOS (SwiftUI) | Android (Compose) | 説明 |
|---------------|-------------------|------|
| `VStack { }` | `Column { }` | 縦方向配置 |
| `HStack { }` | `Row { }` | 横方向配置 |
| `ZStack { }` | `Box { }` | 重ね合わせ |
| `Spacer()` | `Spacer()` | スペーサー |
| `GeometryReader { }` | `BoxWithConstraints { }` | サイズ取得 |
| `.frame(width:height:)` | `Modifier.size()` | サイズ指定 |
| `.padding()` | `Modifier.padding()` | パディング |
| `.background()` | `Modifier.background()` | 背景 |
| `.foregroundColor()` | `color = ` パラメータ | 前景色 |
| `.cornerRadius()` | `Modifier.clip(RoundedCornerShape())` | 角丸 |
| `.opacity()` | `Modifier.alpha()` | 透明度 |
| `.offset()` | `Modifier.offset()` | オフセット |
| `.onTapGesture { }` | `Modifier.clickable { }` | タップジェスチャー |

---

## 6. 処理フロー対応表

### 6.1 撮影フロー

**iOS (CameraView)**

```swift
// 1. セッション開始
captureSession.startRunning()

// 2. 録画開始
movieOutput.startRecording(to: outputURL, recordingDelegate: self)

// 3. 1秒後に停止
DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
    self.movieOutput.stopRecording()
}

// 4. デリゲートでファイル取得
func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo: URL, ...) {
    // 録画完了処理
}
```

**Android (CameraScreen)**

```kotlin
// 1. プロバイダーをバインド
cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)

// 2. 録画開始
val recording = videoCapture.output
    .prepareRecording(context, outputOptions)
    .withAudioEnabled()
    .start(executor) { event ->
        when (event) {
            is VideoRecordEvent.Finalize -> {
                // 4. 録画完了処理
            }
        }
    }

// 3. 1秒後に停止
delay(1000)
recording.stop()
```

### 6.2 再生フロー

**iOS (PlayerView)**

```swift
// 1. Composition 作成
let composition = AVMutableComposition()
for segment in segments {
    videoTrack.insertTimeRange(...)
}

// 2. プレーヤーにセット
let playerItem = AVPlayerItem(asset: composition)
player.replaceCurrentItem(with: playerItem)

// 3. 再生
player.play()

// 4. 進捗監視
timeObserver = player.addPeriodicTimeObserver(...) { time in
    self.updateCurrentTime()
}
```

**Android (PlayerScreen)**

```kotlin
// 1. Composition 作成
val builder = Composition.Builder(HDR_MODE_KEEP_HDR)
for (segment in segments) {
    sequenceBuilder.addMediaItem(editedMediaItem)
}
val composition = builder.build()

// 2. エクスポート後プレーヤーにセット
val uri = exportComposition(composition)
player.setMediaItem(MediaItem.fromUri(uri))
player.prepare()

// 3. 再生
player.play()

// 4. 進捗監視
player.addListener(object : Player.Listener {
    override fun onPositionDiscontinuity(...) { }
})
```

### 6.3 エクスポートフロー

**iOS (PlayerView)**

```swift
// 1. エクスポートセッション作成
let exportSession = AVAssetExportSession(
    asset: composition,
    presetName: AVAssetExportPresetHighestQuality
)
exportSession?.outputURL = outputURL
exportSession?.outputFileType = .mp4

// 2. エクスポート実行
exportSession?.exportAsynchronously {
    switch exportSession?.status {
    case .completed:
        // 3. PHPhotoLibrary に保存
        PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: outputURL)
        }
    case .failed:
        // エラー処理
    }
}
```

**Android (ExportViewModel)**

```kotlin
// 1. Transformer 作成
val transformer = Transformer.Builder(context).build()

// 2. エクスポート実行
transformer.addListener(object : Transformer.Listener {
    override fun onCompleted(composition, result) {
        // 3. MediaStore に保存
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
    }
    override fun onError(...) {
        // エラー処理
    }
})
transformer.start(composition, outputPath)
```

---

## 7. メモリ・リソース管理対応

### 7.1 リソース解放

| iOS | Android | 説明 |
|-----|---------|------|
| `deinit { }` | `onCleared()` (ViewModel) | オブジェクト破棄時 |
| ARC 自動解放 | `DisposableEffect { onDispose { } }` | スコープ終了時 |
| `player.pause()` + `replaceCurrentItem(nil)` | `player.release()` | プレーヤー解放 |
| `captureSession.stopRunning()` | `cameraProvider.unbindAll()` | カメラ解放 |
| `removeTimeObserver()` | `player.removeListener()` | リスナー解除 |
| `NotificationCenter.removeObserver()` | （Compose で自動） | オブザーバー解除 |
| `FileManager.removeItem()` | `file.delete()` | ファイル削除 |

### 7.2 メモリリーク防止

| iOS | Android | 説明 |
|-----|---------|------|
| `[weak self]` | `WeakReference<T>` | 弱参照 |
| `[unowned self]` | （使用非推奨） | 非所有参照 |
| クロージャのキャプチャリスト | `viewModelScope` 自動キャンセル | 循環参照防止 |
| `@autoreleasepool { }` | `System.gc()` | メモリプール解放 |
| `task.cancel()` | `job.cancel()` | タスクキャンセル |

**iOS の weak self パターン**:

```swift
someAsyncOperation { [weak self] result in
    guard let self = self else { return }
    self.updateUI(result)
}
```

**Android の対応パターン**:

```kotlin
// viewModelScope が自動的に ViewModel の破棄時にキャンセル
viewModelScope.launch {
    val result = someAsyncOperation()
    // ViewModel が生きている間のみ実行される
    updateUI(result)
}
```

---

## 8. エラーハンドリング対応

### 8.1 例外処理

| iOS (Swift) | Android (Kotlin) | 説明 |
|-------------|------------------|------|
| `do { try ... } catch { }` | `try { ... } catch (e: Exception) { }` | 例外処理 |
| `throw error` | `throw Exception()` | 例外スロー |
| `throws` | `@Throws` / `suspend fun` | 例外宣言 |
| `try?` | `runCatching { }.getOrNull()` | 失敗時nil |
| `try!` | `!!` | 強制アンラップ（非推奨） |
| `guard let ... else { return }` | `?: return` | 早期リターン |
| `Result<Success, Failure>` | `Result<T>` / `sealed class` | 結果型 |
| `fatalError()` | `error()` / `throw IllegalStateException()` | 致命的エラー |

### 8.2 エラー通知

| iOS | Android | 説明 |
|-----|---------|------|
| `Alert(title:message:)` | `AlertDialog()` | アラートダイアログ |
| UIKit の `Toast` | `Toast.makeText().show()` | トースト |
| `print()` | `Log.d() / Log.e()` | ログ出力 |
| `os_log()` | `android.util.Log` | システムログ |
| `assertionFailure()` | `assert()` / `check()` | アサーション |
| `precondition()` | `require()` | 前提条件 |

---

## 9. パーミッション対応

### 9.1 権限要求

| iOS | Android | 説明 |
|-----|---------|------|
| `AVCaptureDevice.requestAccess(for: .video)` | `ActivityCompat.requestPermissions([CAMERA])` | カメラ権限 |
| `AVCaptureDevice.requestAccess(for: .audio)` | `ActivityCompat.requestPermissions([RECORD_AUDIO])` | マイク権限 |
| `PHPhotoLibrary.requestAuthorization()` | `ActivityCompat.requestPermissions([WRITE_EXTERNAL_STORAGE])` | ストレージ権限 |
| `AVAuthorizationStatus` | `PackageManager.PERMISSION_GRANTED` | 権限状態 |
| Info.plist の説明文 | AndroidManifest.xml の `<uses-permission>` | 権限宣言 |

**iOS**:

```swift
AVCaptureDevice.requestAccess(for: .video) { granted in
    if granted {
        // 権限付与
    }
}
```

**Android**:

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        // 権限付与
    }
}

launcher.launch(Manifest.permission.CAMERA)
```

---

## 10. デバッグ・ログ対応

### 10.1 ログ出力

| iOS | Android | 説明 |
|-----|---------|------|
| `print("message")` | `Log.d(TAG, "message")` | デバッグログ |
| `debugPrint(object)` | `Log.v(TAG, object.toString())` | 詳細ログ |
| `print("Error: \(error)")` | `Log.e(TAG, "Error", exception)` | エラーログ |
| `#if DEBUG` | `BuildConfig.DEBUG` | デバッグビルド判定 |
| `os_log(.info, "message")` | `Log.i(TAG, "message")` | 情報ログ |
| `os_log(.error, "message")` | `Log.e(TAG, "message")` | エラーログ |
| `fatalError("message")` | `throw IllegalStateException("message")` | 致命的エラー |

**ログレベル対応表**:

| iOS (os_log) | Android (Log) | 用途 |
|--------------|---------------|------|
| `.debug` | `Log.v()` | 冗長（Verbose） |
| `.info` | `Log.d()` | デバッグ |
| `.default` | `Log.i()` | 情報 |
| `.error` | `Log.w()` | 警告 |
| `.fault` | `Log.e()` | エラー |

### 10.2 デバッガ

| iOS | Android | 説明 |
|-----|---------|------|
| Xcode Debugger | Android Studio Debugger | 統合デバッガ |
| Instruments | Android Profiler | パフォーマンス分析 |
| Memory Graph | Memory Profiler | メモリ分析 |
| Time Profiler | CPU Profiler | CPU分析 |
| Network Inspector | Network Inspector | ネットワーク分析 |
| View Hierarchy | Layout Inspector | レイアウト分析 |
| `po object` (LLDB) | Evaluate Expression | オブジェクト検査 |
| Breakpoint | Breakpoint | ブレークポイント |
| Conditional Breakpoint | Conditional Breakpoint | 条件付きブレークポイント |

---

## 11. 完全な対応サマリー

### 11.1 アーキテクチャ対応

| レイヤー | iOS | Android |
|---------|-----|---------|
| UI | SwiftUI | Jetpack Compose |
| 状態管理 | ObservableObject + @Published | ViewModel + StateFlow |
| ナビゲーション | NavigationStack | Navigation Compose |
| データ永続化 | UserDefaults | SharedPreferences |
| ネットワーク | URLSession | Retrofit / OkHttp |
| 非同期 | async/await | Coroutines |
| DI | 手動 / Swinject | Hilt / Koin |

### 11.2 ClipFlow 固有の対応

| 機能 | iOS実装 | Android実装 |
|------|---------|------------|
| プロジェクト管理 | ProjectManager + @Published | ProjectViewModel + StateFlow |
| カメラ | AVCaptureSession | CameraX ProcessCameraProvider |
| 録画 | AVCaptureMovieFileOutput | VideoCapture<Recorder> |
| Composition | AVMutableComposition | Media3 Composition.Builder |
| 再生 | AVPlayer | ExoPlayer |
| エクスポート | AVAssetExportSession | Media3 Transformer |
| ギャラリー保存 | PHPhotoLibrary | MediaStore |
| 画面遷移 | @State currentScreen | NavController |
| リソース管理 | ARC 自動 | close() / release() 手動 |

### 11.3 重要な違いと注意点

1. **リソース管理**
   - iOS: ARC で自動解放
   - Android: 明示的な close() / release() が必須

2. **Composition 再生**
   - iOS: AVPlayer が直接 AVComposition を再生
   - Android: Transformer で MP4 にエクスポート後に ExoPlayer で再生

3. **権限要求**
   - iOS: Info.plist + requestAccess()
   - Android: AndroidManifest.xml + requestPermissions()

4. **ファイルパス**
   - iOS: Documents/ (FileManager)
   - Android: context.filesDir (File)

5. **メモリ管理**
   - iOS: システムが自動最適化
   - Android: バッチ処理で手動最適化が必要

この対応表を参考にすることで、iOS版の実装をAndroid版に正確に移植できます。

---

*以上がセクション6「1対1 API対応表」です。*
