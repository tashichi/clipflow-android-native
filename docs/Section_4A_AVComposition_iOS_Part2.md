# セクション4A-2: AVComposition実装ガイド（iOS版 Part 2）

## 4.3 preferredTransform の適用

### 4.3.1 回転情報の取得方法

#### 問題: なぜ preferredTransform が必要か？

```
問題のシナリオ:
1. ユーザーがiPhoneを縦向きで動画を撮影
2. 動画ファイル自体は横向きで保存されている
3. メタデータとして「90度回転」の情報を持っている

preferredTransform を適用しない場合:
→ 動画が横向きで再生されてしまう（90度傾いて表示）

preferredTransform を適用した場合:
→ 動画が正しく縦向きで再生される
```

#### 回転情報の取得

```swift
// ProjectManager.swift:197-219
// ✅ 最初のセグメントから回転情報を取得
if index == 0 {
    // 1. preferredTransform を取得
    let transform = assetVideoTrack.preferredTransform
    let naturalSize = assetVideoTrack.naturalSize

    // 2. Composition のビデオトラックに適用
    videoTrack.preferredTransform = transform

    // 3. 回転角度を計算
    let angle = atan2(transform.b, transform.a)
    let isRotated = abs(angle) > .pi / 4

    // 4. Composition のサイズを調整
    if isRotated {
        // 90度または270度回転の場合、幅と高さを入れ替える
        composition.naturalSize = CGSize(
            width: naturalSize.height,
            height: naturalSize.width
        )
        print("Composition rotated: \(naturalSize) → \(composition.naturalSize)")
    } else {
        composition.naturalSize = naturalSize
        print("Composition normal: \(naturalSize)")
    }

    print("Transform applied: \(transform)")
}
```

**実装箇所**: ProjectManager.swift:197-219

#### CGAffineTransform の理解

```swift
// ✅ CGAffineTransform の取得
let transform = assetVideoTrack.preferredTransform
// 例: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 1920, ty: 0)

// transform の要素:
// ┌       ┐
// │ a  b  │  → 回転・スケール
// │ c  d  │  → 回転・スケール
// │ tx ty │  → 平行移動
// └       ┘
```

**一般的な回転のパターン**:

| 回転 | transform | angle (ラジアン) |
|-----|-----------|----------------|
| 0度（横向き） | (a:1, b:0, c:0, d:1) | 0 |
| 90度（縦向き・右回転） | (a:0, b:1, c:-1, d:0) | π/2 |
| 180度（上下逆） | (a:-1, b:0, c:0, d:-1) | π |
| 270度（縦向き・左回転） | (a:0, b:-1, c:1, d:0) | -π/2 |

#### 回転角度の計算

```swift
// ✅ atan2() で回転角度を計算
let angle = atan2(transform.b, transform.a)

// 例:
// transform.b = 1, transform.a = 0 の場合
// angle = atan2(1, 0) = π/2 (90度)

// ✅ 90度または270度回転かを判定
let isRotated = abs(angle) > .pi / 4  // 45度以上の回転

// abs(angle) > π/4 の意味:
// - 0度: abs(0) = 0 < π/4 → false (回転なし)
// - 90度: abs(π/2) = π/2 > π/4 → true (回転あり)
// - 180度: abs(π) = π > π/4 → true (回転あり)
// - 270度: abs(-π/2) = π/2 > π/4 → true (回転あり)
```

**実装箇所**: ProjectManager.swift:206-207

---

### 4.3.2 Composition に回転情報を適用

#### ビデオトラックへの適用

```swift
// ✅ Composition のビデオトラックに preferredTransform を設定
videoTrack.preferredTransform = transform
```

**実装箇所**: ProjectManager.swift:204

**効果**:

- Composition 全体に回転情報が適用される
- AVPlayer で再生時に自動的に正しい向きで表示される
- エクスポート時も正しい向きで保存される

#### Composition サイズの調整

```swift
// ✅ 回転に応じてサイズを調整
if isRotated {
    // 90度または270度回転の場合
    composition.naturalSize = CGSize(
        width: naturalSize.height,   // ← 幅と高さを入れ替え
        height: naturalSize.width
    )
    print("Composition rotated: \(naturalSize) → \(composition.naturalSize)")
} else {
    // 0度または180度の場合
    composition.naturalSize = naturalSize  // ← そのまま
    print("Composition normal: \(naturalSize)")
}
```

**実装箇所**: ProjectManager.swift:209-216

**例**:

```
元の動画（横向きで保存）:
naturalSize = (width: 1920, height: 1080)
transform = 90度回転

Composition サイズ:
width = naturalSize.height = 1080  ← 縦向きの幅
height = naturalSize.width = 1920  ← 縦向きの高さ

結果: (1080 × 1920) の縦向き動画として認識される
```

#### 完全な適用処理

```swift
// セグメント統合のループ内で
for (index, segment) in sortedSegments.enumerated() {
    let asset = AVURLAsset(url: fileURL)

    do {
        let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
        let assetDuration = try await asset.load(.duration)

        if let assetVideoTrack = assetVideoTracks.first {
            let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
            try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)

            // ✅ 最初のセグメントのみで回転情報を適用
            if index == 0 {
                // 回転情報を取得
                let transform = assetVideoTrack.preferredTransform
                let naturalSize = assetVideoTrack.naturalSize

                // Composition に適用
                videoTrack.preferredTransform = transform

                // 回転角度を計算
                let angle = atan2(transform.b, transform.a)
                let isRotated = abs(angle) > .pi / 4

                // サイズを調整
                if isRotated {
                    composition.naturalSize = CGSize(
                        width: naturalSize.height,
                        height: naturalSize.width
                    )
                } else {
                    composition.naturalSize = naturalSize
                }

                print("Transform applied: \(transform)")
                print("Natural size: \(composition.naturalSize)")
            }
        }

        // currentTime を更新...
    }
}
```

**実装箇所**: ProjectManager.swift:191-219

---

### 4.3.3 異なる向き（縦/横）のセグメント対応

#### 現在の実装の制限

```swift
// ✅ 現在の実装: 最初のセグメントの向きを全体に適用
if index == 0 {
    videoTrack.preferredTransform = assetVideoTrack.preferredTransform
    // ...
}
```

**制限**:

- 最初のセグメントの向きがすべてのセグメントに適用される
- 途中で向きが変わるセグメントがあっても無視される

#### 想定されるシナリオ

**シナリオ1: すべて同じ向き（推奨）**

```
セグメント1: 縦向き (90度)
セグメント2: 縦向き (90度)
セグメント3: 縦向き (90度)
→ 問題なし ✅
```

**シナリオ2: 途中で向きが変わる（現在の実装では非推奨）**

```
セグメント1: 縦向き (90度)  ← これが適用される
セグメント2: 横向き (0度)   ← 無視される
セグメント3: 縦向き (90度)  ← 無視される
→ セグメント2が90度回転して表示される ⚠️
```

#### 異なる向きへの対応方法（将来の拡張）

##### 方法1: 各セグメントに個別の transform を適用（複雑）

```swift
// ⚠️ 注意: AVMutableVideoComposition が必要（現在の実装にはない）

let videoComposition = AVMutableVideoComposition()
videoComposition.renderSize = composition.naturalSize

// 各セグメントごとに transform を設定
for (index, segment) in sortedSegments.enumerated() {
    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = segmentTimeRanges[index]

    let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
    layerInstruction.setTransform(segment.transform, at: .zero)

    instruction.layerInstructions = [layerInstruction]
    videoComposition.instructions.append(instruction)
}

// プレーヤーに設定
let playerItem = AVPlayerItem(asset: composition)
playerItem.videoComposition = videoComposition
player.replaceCurrentItem(with: playerItem)
```

##### 方法2: 統一された向きで撮影することを強制（推奨）

```swift
// CameraView でカメラの向きを固定
class CameraViewController: UIViewController {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return .portrait  // ✅ 縦向きに固定
    }

    override var shouldAutorotate: Bool {
        return false  // ✅ 回転を無効化
    }
}
```

**現在の ClipFlow の方針**:

- すべてのセグメントを同じ向きで撮影することを前提
- カメラの向きは固定しない（ユーザーが自由に回転可能）
- 最初のセグメントの向きが全体の向きとなる

---

## 4.4 継ぎ目のない再生実現方法

### 4.4.1 AVPlayer で Composition を再生

#### 基本的な再生フロー

```swift
// PlayerView.swift:858-941
private func loadComposition() {
    print("Loading composition for seamless playback")

    // 1. ローディング状態を開始
    isLoadingComposition = true
    loadingProgress = 0.0

    Task {
        // 2. Composition を作成
        guard let newComposition = await createCompositionWithProgress() else {
            // 失敗時は個別再生にフォールバック
            await MainActor.run {
                isLoadingComposition = false
                useSeamlessPlayback = false
                loadCurrentSegment()
            }
            return
        }

        // 3. セグメント時間範囲を取得
        segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

        // 4. UI更新（メインスレッド）
        await MainActor.run {
            // ✅ 既存のオブザーバーを削除
            removeTimeObserver()
            NotificationCenter.default.removeObserver(
                self,
                name: .AVPlayerItemDidPlayToEndTime,
                object: nil
            )

            // ✅ 新しいプレーヤーアイテムを作成
            let newPlayerItem = AVPlayerItem(asset: newComposition)

            // ✅ 再生完了通知を監視
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: newPlayerItem,
                queue: .main
            ) { _ in
                self.handleCompositionEnd()
            }

            // ✅ Composition を保存
            composition = newComposition

            // ✅ プレーヤーにセット
            player.replaceCurrentItem(with: newPlayerItem)
            playerItem = newPlayerItem

            // ✅ 再生準備
            player.pause()
            isPlaying = false
            currentTime = 0
            duration = newComposition.duration.seconds

            print("Composition loaded successfully")
            print("Total duration: \(duration)s")

            // ✅ 時間監視を開始
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

#### AVPlayerItem の作成

```swift
// ✅ Composition から AVPlayerItem を作成
let newPlayerItem = AVPlayerItem(asset: newComposition)
```

**AVPlayerItem の役割**:

| 役割 | 説明 |
|------|------|
| 再生可能なラッパー | AVComposition を AVPlayer で再生可能にする |
| バッファリング管理 | 再生前にデータをバッファリング |
| ステータス管理 | 準備完了、再生中、エラーなどの状態を管理 |
| タイムベース制御 | 再生速度、シーク位置などを制御 |

**実装箇所**: PlayerView.swift:899

#### プレーヤーへのセット

```swift
// ✅ 既存のアイテムを置き換え
player.replaceCurrentItem(with: newPlayerItem)
playerItem = newPlayerItem
```

**replaceCurrentItem の動作**:

1. 既存のプレーヤーアイテムを停止
2. 既存のアイテムをクリーンアップ
3. 新しいアイテムをセット
4. 新しいアイテムの準備を開始

**実装箇所**: PlayerView.swift:912-913

#### 再生準備

```swift
// ✅ 初期状態に設定
player.pause()          // ← 一時停止状態
isPlaying = false       // ← UI状態を更新
currentTime = 0         // ← 再生位置を先頭に
duration = newComposition.duration.seconds  // ← 総再生時間を取得
```

**実装箇所**: PlayerView.swift:916-919

---

### 4.4.2 セグメント間のギャップを最小化

#### ギャップが発生する原因

```
原因1: セグメントのタイムスタンプが不正確
[Seg1: 0.0-1.0][gap][Seg2: 1.1-2.1]
              ↑ 0.1秒のギャップ

原因2: セグメントのデュレーションが不正確
[Seg1: 0.0-0.9][gap][Seg2: 1.0-2.0]
              ↑ 0.1秒のギャップ

原因3: フレーム境界の不一致
[Seg1: 0.0-1.0333...][gap][Seg2: 1.0-2.0]
                     ↑ フレーム境界のずれ
```

#### ギャップを最小化する方法

##### 方法1: CMTime を使用した正確な計算

```swift
// ❌ 間違った方法（浮動小数点演算）
var currentTimeSeconds = 0.0
for segment in segments {
    currentTimeSeconds += 1.0  // ← 累積誤差が発生
}

// ✅ 正しい方法（CMTime 演算）
var currentTime = CMTime.zero
for segment in segments {
    let assetDuration = try await asset.load(.duration)

    // セグメントを挿入
    try videoTrack.insertTimeRange(
        CMTimeRange(start: .zero, duration: assetDuration),
        of: assetVideoTrack,
        at: currentTime
    )

    // ✅ CMTimeAdd で正確に加算
    currentTime = CMTimeAdd(currentTime, assetDuration)
}
```

**実装箇所**: ProjectManager.swift:232

**利点**:

- タイムスケールを考慮した正確な計算
- 浮動小数点誤差を回避
- フレーム境界に正確に配置

##### 方法2: セグメントの実際のデュレーションを使用

```swift
// ✅ セグメントの実際のデュレーションを取得
let assetDuration = try await asset.load(.duration)

// ❌ 固定値を使用しない
// let fixedDuration = CMTime(seconds: 1.0, preferredTimescale: 600)
```

**理由**:

- 実際のセグメントは正確に1.0秒でない可能性がある
- 例: 0.999秒、1.001秒、1.033秒（30fpsの場合）
- 実際のデュレーションを使用することでギャップを防ぐ

**実装箇所**: ProjectManager.swift:189

##### 方法3: insertTimeRange の正確な使用

```swift
// ✅ 正しい方法
try videoTrack.insertTimeRange(
    CMTimeRange(start: .zero, duration: assetDuration),  // ← セグメント全体
    of: assetVideoTrack,
    at: currentTime  // ← 正確な挿入位置
)

// ❌ 間違った方法
try videoTrack.insertTimeRange(
    CMTimeRange(start: .zero, duration: CMTime(seconds: 1.0, preferredTimescale: 600)),  // ← 固定値
    of: assetVideoTrack,
    at: CMTime(seconds: Double(index), preferredTimescale: 600)  // ← 浮動小数点から変換
)
```

**実装箇所**: ProjectManager.swift:194, 227

#### ギャップ検証のデバッグ方法

```swift
// セグメント追加後にギャップをチェック
for (index, segment) in sortedSegments.enumerated() {
    let asset = AVURLAsset(url: fileURL)
    let assetDuration = try await asset.load(.duration)

    // セグメントを追加
    try videoTrack.insertTimeRange(
        CMTimeRange(start: .zero, duration: assetDuration),
        of: assetVideoTrack,
        at: currentTime
    )

    let previousTime = currentTime
    currentTime = CMTimeAdd(currentTime, assetDuration)

    // ✅ デバッグ出力
    print("Segment \(index + 1):")
    print("  Start: \(previousTime.seconds)s")
    print("  Duration: \(assetDuration.seconds)s")
    print("  End: \(currentTime.seconds)s")

    // ✅ ギャップチェック
    if index > 0 {
        let expectedStart = previousTime
        let actualStart = previousTime
        let gap = CMTimeSubtract(actualStart, expectedStart).seconds

        if abs(gap) > 0.001 {  // 1ミリ秒以上のギャップ
            print("  ⚠️ Gap detected: \(gap)s")
        }
    }
}
```

---

### 4.4.3 完全なシームレス再生の確認方法

#### 確認方法1: 時間監視での確認

```swift
// PlayerView.swift:1195-1207
private func startTimeObserver() {
    removeTimeObserver()

    let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))

    timeObserver = player.addPeriodicTimeObserver(
        forInterval: interval,
        queue: .main
    ) { time in
        self.updateCurrentTime()

        // ✅ セグメントインデックスも更新
        if self.useSeamlessPlayback {
            self.updateCurrentSegmentIndex()
        }
    }
}
```

**実装箇所**: PlayerView.swift:1195-1207

**動作**:

- 0.1秒ごとに現在の再生位置を更新
- シームレスモードでは現在のセグメントインデックスも更新
- ギャップがあれば時間がジャンプするため視覚的に確認可能

#### 確認方法2: セグメント境界での確認

```swift
// PlayerView.swift:944-957
private func updateCurrentSegmentIndex() {
    let currentPlayerTime = player.currentTime()

    // ✅ 現在の再生位置がどのセグメントに該当するか判定
    for (index, (_, timeRange)) in segmentTimeRanges.enumerated() {
        if CMTimeRangeContainsTime(timeRange, time: currentPlayerTime) {
            if currentSegmentIndex != index {
                currentSegmentIndex = index
                print("Current segment updated to: \(index + 1)")
            }
            break
        }
    }
}
```

**実装箇所**: PlayerView.swift:944-957

**確認ポイント**:

```
セグメント1 → セグメント2 の境界:
時刻0.999: セグメント1
時刻1.000: セグメント2  ← ここでスムーズに切り替わるか確認
時刻1.001: セグメント2

ギャップがある場合:
時刻0.999: セグメント1
時刻1.000: （ギャップ・何も表示されない）
時刻1.100: セグメント2  ← 0.1秒のギャップ
```

#### 確認方法3: セグメント時間範囲の検証

```swift
// PlayerView.swift:885-890
segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

// ✅ セグメント時間範囲をログ出力
for (index, (segment, timeRange)) in segmentTimeRanges.enumerated() {
    print("Segment \(index + 1):")
    print("  Start: \(timeRange.start.seconds)s")
    print("  Duration: \(timeRange.duration.seconds)s")
    print("  End: \((timeRange.start + timeRange.duration).seconds)s")
}
```

**実装箇所**: PlayerView.swift:890

**期待される出力**:

```
Segment 1:
  Start: 0.0s
  Duration: 1.0s
  End: 1.0s

Segment 2:
  Start: 1.0s    ← 前のセグメントの End と一致
  Duration: 1.0s
  End: 2.0s

Segment 3:
  Start: 2.0s    ← 前のセグメントの End と一致
  Duration: 1.0s
  End: 3.0s
```

#### 確認方法4: 再生完了イベントの監視

```swift
// PlayerView.swift:902-909
NotificationCenter.default.addObserver(
    forName: .AVPlayerItemDidPlayToEndTime,
    object: newPlayerItem,
    queue: .main
) { _ in
    print("Composition playback completed")
    self.handleCompositionEnd()
}

// PlayerView.swift:959-965
private func handleCompositionEnd() {
    print("Composition playback completed - Returning to start")

    // ✅ 最後まで再生できたか確認
    player.seek(to: .zero)
    currentSegmentIndex = 0
    isPlaying = false

    print("Stopped - Press play button to replay")
}
```

**実装箇所**: PlayerView.swift:902-909, 959-965

**確認ポイント**:

- 最後のセグメントまでスムーズに再生されるか
- 途中でエラーが発生しないか
- 再生完了通知が正しく受信されるか

#### 確認方法5: シークバーでの視覚的確認

```swift
// PlayerView.swift:352-420
private var seekableProgressBar: some View {
    GeometryReader { geometry in
        ZStack(alignment: .leading) {
            // 背景バー
            Rectangle()
                .fill(Color.gray.opacity(0.3))
                .frame(height: 4)

            // 進捗バー
            Rectangle()
                .fill(Color.white)
                .frame(width: geometry.size.width * (currentTime / duration), height: 4)

            // ✅ セグメント区切り線（ギャップがあれば視覚的にわかる）
            ForEach(0..<segmentTimeRanges.count, id: \.self) { index in
                if index > 0 {
                    let segmentStartTime = segmentTimeRanges[index].timeRange.start.seconds
                    let xPosition = geometry.size.width * (segmentStartTime / duration)

                    Rectangle()
                        .fill(Color.yellow.opacity(0.6))
                        .frame(width: 1, height: 8)
                        .position(x: xPosition, y: 4)
                }
            }
        }
    }
}
```

**実装箇所**: PlayerView.swift:352-420

**視覚的確認**:

```
シークバー:
[████████████|████████████|████████████]
            ↑            ↑
         境界1         境界2

ギャップがある場合:
[████████████ gap |████████████ gap |████████████]
            ↑    ↑             ↑    ↑
         境界1  ギャップ      境界2  ギャップ
```

---

### 4.4.4 シームレス再生の最適化

#### プレーヤーのプリロード設定

```swift
// ✅ プレーヤーアイテムの準備
let newPlayerItem = AVPlayerItem(asset: newComposition)

// オプション: バッファリングを最適化
newPlayerItem.preferredForwardBufferDuration = 5.0  // 5秒先までバッファリング

player.replaceCurrentItem(with: newPlayerItem)
```

**効果**:

- 再生開始時のバッファリング時間を短縮
- セグメント境界でのスタッターを防ぐ

#### 再生速度の設定

```swift
// ✅ 通常速度で再生
player.rate = 1.0

// オプション: スムーズな再生開始
player.playImmediately(atRate: 1.0)
```

#### セグメント統合の最適化

```swift
// ✅ セグメントのプリロード（進捗表示付き）
func createCompositionWithProgress(
    for project: Project,
    progressCallback: @escaping (Int, Int) -> Void
) async -> AVComposition? {

    let composition = AVMutableComposition()
    let sortedSegments = project.segments.sorted { $0.order < $1.order }

    // ✅ 各セグメントを非同期で処理
    for (index, segment) in sortedSegments.enumerated() {
        progressCallback(index, sortedSegments.count)

        // セグメント追加処理...

        // ✅ 少し待機してUIを更新
        try await Task.sleep(nanoseconds: 10_000_000)  // 0.01秒
    }

    progressCallback(sortedSegments.count, sortedSegments.count)
    return composition
}
```

**実装箇所**: ProjectManager.swift:248-370

---

### 4.4.5 シームレス再生のトラブルシューティング

#### 問題1: セグメント間にブラックフレームが表示される

**原因**:

- セグメントのデュレーションが不正確
- タイムスタンプの計算ミス

**解決策**:

```swift
// ✅ 実際のデュレーションを使用
let assetDuration = try await asset.load(.duration)

// ✅ CMTimeAdd で正確に加算
currentTime = CMTimeAdd(currentTime, assetDuration)
```

#### 問題2: 音声がずれる

**原因**:

- ビデオトラックと音声トラックの同期ミス

**解決策**:

```swift
// ✅ ビデオと音声を同じタイムスタンプで追加
let timeRange = CMTimeRange(start: .zero, duration: assetDuration)

try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
try audioTrack.insertTimeRange(timeRange, of: assetAudioTrack, at: currentTime)
// ← 同じ currentTime を使用
```

#### 問題3: 最後のセグメントが再生されない

**原因**:

- セグメントファイルが存在しない
- Composition の作成に失敗

**解決策**:

```swift
// ✅ ファイル存在確認
guard FileManager.default.fileExists(atPath: fileURL.path) else {
    print("Segment file not found: \(fileURL.lastPathComponent)")
    continue
}

// ✅ エラーハンドリング
do {
    try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
} catch {
    print("Failed to add segment \(segment.order): \(error)")
}
```

---

### 4.4.6 シームレス再生の完全な実装例

```swift
// PlayerView.swift: 完全なシームレス再生の実装
private func loadComposition() {
    print("Loading composition for seamless playback")

    isLoadingComposition = true
    loadingProgress = 0.0

    Task {
        // 1. Composition を作成
        guard let newComposition = await createCompositionWithProgress() else {
            print("Failed to create composition")

            await MainActor.run {
                isLoadingComposition = false
                useSeamlessPlayback = false
                loadCurrentSegment()
            }
            return
        }

        // 2. セグメント時間範囲を取得
        segmentTimeRanges = await projectManager.getSegmentTimeRanges(for: project)

        // 3. UI更新
        await MainActor.run {
            // オブザーバーをクリア
            removeTimeObserver()
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)

            // プレーヤーアイテムを作成
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

            print("Composition loaded successfully")
            print("Total duration: \(duration)s")
            print("Segment time ranges: \(segmentTimeRanges.count)")

            // 時間監視を開始
            startTimeObserver()
            updateCurrentSegmentIndex()

            // ローディング終了
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.isLoadingComposition = false
            }
        }
    }
}

// 時間監視
private func startTimeObserver() {
    removeTimeObserver()

    let interval = CMTime(seconds: 0.1, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
    timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
        self.updateCurrentTime()

        if self.useSeamlessPlayback {
            self.updateCurrentSegmentIndex()
        }
    }
}

// 現在のセグメントインデックスを更新
private func updateCurrentSegmentIndex() {
    let currentPlayerTime = player.currentTime()

    for (index, (_, timeRange)) in segmentTimeRanges.enumerated() {
        if CMTimeRangeContainsTime(timeRange, time: currentPlayerTime) {
            if currentSegmentIndex != index {
                currentSegmentIndex = index
                print("Current segment updated to: \(index + 1)")
            }
            break
        }
    }
}

// 再生完了処理
private func handleCompositionEnd() {
    print("Composition playback completed - Returning to start")
    player.seek(to: .zero)
    currentSegmentIndex = 0
    isPlaying = false
    print("Stopped - Press play button to replay")
}
```

---

### 4.4.7 シームレス再生のまとめ

| ステップ | 処理 | 実装箇所 |
|---------|------|---------|
| 1 | Composition 作成 | ProjectManager.swift:143-246 |
| 2 | セグメント時間範囲を取得 | ProjectManager.swift:373-407 |
| 3 | AVPlayerItem 作成 | PlayerView.swift:899 |
| 4 | 再生完了通知を監視 | PlayerView.swift:902-909 |
| 5 | プレーヤーにセット | PlayerView.swift:912-913 |
| 6 | 時間監視を開始 | PlayerView.swift:930 |
| 7 | セグメントインデックス更新 | PlayerView.swift:933 |
| 8 | 再生開始 | ユーザーが Play ボタンをタップ |

---

*以上がセクション4A-2「AVComposition実装ガイド（iOS版 Part 2）」です。*
