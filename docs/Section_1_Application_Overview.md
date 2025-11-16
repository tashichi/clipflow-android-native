# セクション1: アプリケーション概要

## 1.1 アプリの目的・機能

**ClipFlow（旧名：JourneyMoments）**は、1秒単位の短尺ビデオセグメントを撮影・管理し、シームレスに連結して1本のビデオとして再生・エクスポートできるビデオ撮影アプリです。

### 主要機能

#### プロジェクト管理
- 複数のプロジェクトを作成・管理
- プロジェクト名の編集
- プロジェクトの削除
- プロジェクト作成日時の記録

#### ビデオ撮影
- 1秒間の自動録画
- 前面/背面カメラの切り替え
- フラッシュライト（トーチ）のON/OFF
- 撮影済みセグメント数のリアルタイム表示

#### ビデオ再生
- シームレスな連続再生（AVComposition統合）
- セグメント単位のナビゲーション（前/次）
- シークバーによる任意位置へのジャンプ
- 個別セグメントの削除
- 再生進捗表示

#### エクスポート機能
- フォトライブラリへの保存
- 高画質エクスポート（AVAssetExportPresetHighestQuality）
- エクスポート進捗のリアルタイム表示
- スリープ防止機能

#### 課金機能
- 無料版：プロジェクト3個まで、エクスポート不可
- フルバージョン：無制限プロジェクト、エクスポート可能
- StoreKit による買い切り課金

---

## 1.2 iOS版の技術スタック

### フレームワーク・言語
- **言語**: Swift 5.x
- **UIフレームワーク**: SwiftUI
- **最小バージョン**: iOS 14.0以上（推定）

### ビデオ処理

#### AVFoundation
- **AVCaptureSession**: カメラセッション管理
- **AVCaptureDevice**: カメラデバイス制御
- **AVCaptureMovieFileOutput**: ビデオ録画
- **AVCaptureVideoPreviewLayer**: カメラプレビュー
- **AVPlayer / AVPlayerItem**: ビデオ再生
- **AVMutableComposition**: セグメント統合
- **AVAssetExportSession**: ビデオエクスポート

### データ永続化
- **UserDefaults**: プロジェクトデータの保存（JSON形式）
- **FileManager**: ビデオファイルの保存（Documentsディレクトリ）

### 権限管理
- **AVCaptureDevice.requestAccess**: カメラ・マイク権限
- **PHPhotoLibrary**: フォトライブラリアクセス権限

### 課金
- **StoreKit**: アプリ内課金
- **SKProductsRequest**: 商品情報取得
- **SKPaymentQueue**: 購入処理
- **SKPaymentTransaction**: トランザクション管理

### 状態管理
- **@StateObject**: ViewModel的な状態管理
- **@Published**: リアクティブな状態更新
- **@ObservedObject**: 親から渡されるObservableオブジェクト

---

## 1.3 Android版の技術スタック候補

### 言語・UIフレームワーク
- **言語**: Kotlin
- **UIフレームワーク**: Jetpack Compose
- **最小バージョン**: Android 8.0 (API 26) 以上推奨

### ビデオ処理

#### CameraX: カメラ撮影（AVCaptureSession相当）
- **CameraSelector**: カメラ選択
- **Preview**: プレビュー表示
- **VideoCapture**: ビデオ録画
- **ImageCapture**: 静止画撮影（未使用）

#### Media3 ExoPlayer: ビデオ再生（AVPlayer相当）
- **ExoPlayer**: プレーヤーコア
- **MediaItem**: 再生アイテム
- **ConcatenatingMediaSource**: 連続再生

#### Media3 Transformer: セグメント統合とエクスポート（AVComposition相当）
- **Composition**: セグメント統合
- **ExportResult**: エクスポート結果
- **EditedMediaItem**: 編集済みメディアアイテム

### データ永続化
- **SharedPreferences**: プロジェクトデータ保存
- **Gson / Kotlinx Serialization**: JSON シリアライズ
- **File API**: ビデオファイル保存（内部ストレージ）

### 権限管理
- **ActivityCompat.requestPermissions**:
  - CAMERA: カメラ権限
  - RECORD_AUDIO: マイク権限
  - READ_MEDIA_VIDEO / WRITE_EXTERNAL_STORAGE: ストレージ権限

### 課金
- **Google Play Billing Library**
  - BillingClient: 課金クライアント
  - ProductDetails: 商品情報
  - Purchase: 購入情報

### 状態管理
- **ViewModel**: UI状態管理
- **StateFlow / MutableStateFlow**: リアクティブな状態管理
- **Compose State**: UI状態のホイスティング

---

## 1.4 主要な機能要件

### 1.4.1 プロジェクト管理要件

| 要件ID | 内容 | iOS実装 | Android実装方針 |
|--------|------|---------|-----------------|
| PM-01 | プロジェクト一覧表示 | ProjectListView | LazyColumn |
| PM-02 | プロジェクト作成 | ProjectManager.createNewProject() | ProjectRepository.createProject() |
| PM-03 | プロジェクト削除（動画ファイル含む） | ProjectManager.deleteProject() | ProjectRepository.deleteProject() |
| PM-04 | プロジェクト名編集 | ProjectManager.renameProject() | ProjectRepository.renameProject() |
| PM-05 | データ永続化 | UserDefaults + JSON | SharedPreferences + JSON |

### 1.4.2 撮影要件

| 要件ID | 内容 | iOS実装 | Android実装方針 |
|--------|------|---------|-----------------|
| REC-01 | 1秒自動録画 | VideoManager.recordOneSecond() | VideoCapture.startRecording() + 1秒タイマー |
| REC-02 | 前面/背面切り替え | VideoManager.toggleCamera() | CameraSelector切り替え |
| REC-03 | フラッシュライト制御 | AVCaptureDevice.torchMode | Camera.CameraControl.enableTorch() |
| REC-04 | カメラプレビュー表示 | AVCaptureVideoPreviewLayer | PreviewView (CameraX) |
| REC-05 | セグメント数表示 | @Published segmentCount | MutableStateFlow<Int> |

### 1.4.3 再生要件

| 要件ID | 内容 | iOS実装 | Android実装方針 |
|--------|------|---------|-----------------|
| PLAY-01 | シームレス連続再生 | AVMutableComposition | Media3 Composition |
| PLAY-02 | セグメント間ナビゲーション | previousSegment() / nextSegment() | player.seekToNextMediaItem() |
| PLAY-03 | シークバー操作 | CMTime seek() | player.seekTo(positionMs) |
| PLAY-04 | 再生進捗表示 | TimeObserver + @State | player.currentPosition監視 |
| PLAY-05 | セグメント削除 | ProjectManager.deleteSegment() | ProjectRepository.deleteSegment() |

### 1.4.4 エクスポート要件

| 要件ID | 内容 | iOS実装 | Android実装方針 |
|--------|------|---------|-----------------|
| EXP-01 | フォトライブラリ保存 | PHPhotoLibrary.shared() | MediaStore.Video |
| EXP-02 | 高画質エクスポート | AVAssetExportPresetHighestQuality | Media3 Transformer (高品質設定) |
| EXP-03 | 進捗表示 | exportSession.progress | Transformer.Listener.onProgress() |
| EXP-04 | スリープ防止 | UIApplication.isIdleTimerDisabled | WindowManager.FLAG_KEEP_SCREEN_ON |

### 1.4.5 課金要件

| 要件ID | 内容 | iOS実装 | Android実装方針 |
|--------|------|---------|-----------------|
| IAP-01 | プロジェクト数制限（無料版3個） | PurchaseManager.canCreateNewProject() | BillingRepository.canCreateProject() |
| IAP-02 | エクスポート制限（無料版不可） | PurchaseManager.canExportVideo() | BillingRepository.canExport() |
| IAP-03 | 買い切り課金 | StoreKit SKProduct | BillingClient INAPP |
| IAP-04 | 購入状態復元 | restorePurchases() | queryPurchasesAsync() |

---

## 1.5 非機能要件

### パフォーマンス
- **セグメント処理**: 100個以上のセグメントでも安定動作
- **エクスポート時間**: 1分間の動画を30秒以内にエクスポート
- **メモリ使用量**: バックグラウンド時のメモリリーク防止

### セキュリティ・プライバシー
- カメラ・マイク権限の適切な要求タイミング
- ユーザーデータの端末内保存（クラウド同期なし）
- 課金情報の安全な管理

### 互換性
- **iOS版**: iOS 14.0以上
- **Android版**: Android 8.0 (API 26) 以上推奨

---

*以上がセクション1「アプリケーション概要」です。*
