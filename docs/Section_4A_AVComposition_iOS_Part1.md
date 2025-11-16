セクション4A-1: AVComposition実装ガイド（iOS版 Part 1）
4.1 AVMutableComposition の基本
4.1.1 AVMutableComposition の作成方法
基本的な初期化パターン
// ProjectManager.swift:143-159
func createComposition(for project: Project) async -> AVComposition? {
    print("Creating composition for project: \(project.name)")
    print("Total segments: \(project.segments.count)")
    
    // ✅ 1. AVMutableComposition を初期化
    let composition = AVMutableComposition()
    
    guard !project.segments.isEmpty else {
        print("No segments to compose")
        return nil
    }
    
    // ✅ 2. ビデオトラックと音声トラックを追加
    guard let videoTrack = composition.addMutableTrack(
            withMediaType: .video, 
            preferredTrackID: kCMPersistentTrackID_Invalid
          ),
          let audioTrack = composition.addMutableTrack(
            withMediaType: .audio, 
            preferredTrackID: kCMPersistentTrackID_Invalid
          ) else {
        print("Failed to create composition tracks")
        return nil
    }
    
    // セグメント統合処理...
}
実装箇所: ProjectManager.swift:143-159

AVMutableComposition の初期化
// ✅ 空のコンポジションを作成
let composition = AVMutableComposition()
AVMutableComposition の役割: | 役割 | 説明 | |------|------| | 仮想的なアセット | 複数の動画を統合した仮想的なメディアアセット | | メモリ効率 | 実際のファイルをコピーせず、参照のみを保持 | | トラック管理 | ビデオトラック、音声トラックを個別に管理 | | 時間軸管理 | 各セグメントの開始時刻と長さを管理 |

ビデオトラック追加
// ✅ ビデオトラックを追加
guard let videoTrack = composition.addMutableTrack(
    withMediaType: .video,                      // ← メディアタイプ（ビデオ）
    preferredTrackID: kCMPersistentTrackID_Invalid  // ← 自動的にトラックIDを割り当て
) else {
    print("Failed to create video track")
    return nil
}
パラメータ: | パラメータ | 説明 | |----------|------| | withMediaType: .video | ビデオトラックであることを指定 | | preferredTrackID: kCMPersistentTrackID_Invalid | システムが自動的にトラックIDを割り当てる |

実装箇所: ProjectManager.swift:155-156

音声トラック追加
// ✅ 音声トラックを追加
guard let audioTrack = composition.addMutableTrack(
    withMediaType: .audio,                      // ← メディアタイプ（音声）
    preferredTrackID: kCMPersistentTrackID_Invalid  // ← 自動的にトラックIDを割り当て
) else {
    print("Failed to create audio track")
    return nil
}
実装箇所: ProjectManager.swift:156-157

タイムスケール設定
AVMutableComposition では、タイムスケールは自動的に設定されます。各セグメントのタイムスケールを保持します。

// ✅ タイムスケールは自動的に設定される
// 最初に追加されたセグメントのタイムスケールが使用される

// 例: セグメントのタイムスケールを確認
let asset = AVURLAsset(url: fileURL)
let duration = try await asset.load(.duration)

print("Duration: \(duration.seconds)s")
print("Timescale: \(duration.timescale)")  // 例: 600 (一般的な値)
一般的なタイムスケール値: | タイムスケール | 精度 | 用途 | |-------------|------|------| | 600 | 1/600秒 | 一般的な動画（デフォルト） | | 1000 | 1/1000秒 | 高精度な動画 | | 24000 | 1/24000秒 | 映画品質 |

完全な初期化例
func createComposition(for project: Project) async -> AVComposition? {
    // 1. Composition を作成
    let composition = AVMutableComposition()
    
    // 2. セグメントの存在チェック
    guard !project.segments.isEmpty else {
        print("No segments to compose")
        return nil
    }
    
    // 3. ビデオトラックと音声トラックを追加
    guard let videoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
          ),
          let audioTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
          ) else {
        print("Failed to create composition tracks")
        return nil
    }
    
    print("Composition initialized successfully")
    print("Video track ID: \(videoTrack.trackID)")
    print("Audio track ID: \(audioTrack.trackID)")
    
    // 4. セグメント統合処理（次のセクションで説明）
    // ...
    
    return composition
}
実装箇所: ProjectManager.swift:143-159

4.2 セグメント統合ロジック
4.2.1 複数の 1秒動画ファイルを順序通り統合
基本的な統合フロー
// ProjectManager.swift:161-246
var currentTime = CMTime.zero
let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

// ✅ セグメントを順序でソート
let sortedSegments = project.segments.sorted { $0.order < $1.order }

// ✅ セグメントを順次処理
for (index, segment) in sortedSegments.enumerated() {
    // 1. ファイルURLを構築
    let fileURL: URL
    if !segment.uri.hasPrefix("/") {
        fileURL = documentsPath.appendingPathComponent(segment.uri)
    } else {
        fileURL = URL(fileURLWithPath: segment.uri)
    }
    
    // 2. ファイルの存在確認
    guard FileManager.default.fileExists(atPath: fileURL.path) else {
        print("Segment file not found: \(fileURL.lastPathComponent)")
        continue
    }
    
    // 3. AVURLAsset を作成
    let asset = AVURLAsset(url: fileURL)
    
    do {
        // 4. トラック情報を読み込む
        let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
        let assetAudioTracks = try await asset.loadTracks(withMediaType: .audio)
        let assetDuration = try await asset.load(.duration)
        
        // 5. ビデオトラックを追加
        if let assetVideoTrack = assetVideoTracks.first {
            let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
            try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
            
            print("Video track added: Segment \(segment.order)")
        }
        
        // 6. 音声トラックを追加
        if let assetAudioTrack = assetAudioTracks.first {
            let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
            try audioTrack.insertTimeRange(timeRange, of: assetAudioTrack, at: currentTime)
            
            print("Audio track added: Segment \(segment.order)")
        }
        
        // 7. 次のセグメントの開始時刻を更新
        currentTime = CMTimeAdd(currentTime, assetDuration)
        
    } catch {
        print("Failed to add segment \(segment.order): \(error)")
    }
}
実装箇所: ProjectManager.swift:161-238

セグメントのソート
// ✅ セグメントを order フィールドで昇順にソート
let sortedSegments = project.segments.sorted { $0.order < $1.order }
理由:

セグメントは撮影順に order フィールドが割り当てられている
統合時は必ず順序通りに処理する必要がある
データベースやメモリ上での順序は保証されないため、明示的にソート
実装箇所: ProjectManager.swift:165

ファイルURL構築
// ✅ パターン1: 相対パス（ファイル名のみ）
if !segment.uri.hasPrefix("/") {
    // 例: segment.uri = "segment_1234567890.mov"
    fileURL = documentsPath.appendingPathComponent(segment.uri)
    // 結果: /path/to/Documents/segment_1234567890.mov
}

// ✅ パターン2: 絶対パス（レガシー形式）
else {
    // 例: segment.uri = "/path/to/Documents/segment_1234567890.mov"
    fileURL = URL(fileURLWithPath: segment.uri)
}
実装箇所: ProjectManager.swift:168-174

パスの形式: | 形式 | 例 | 説明 | |------|---|------| | 相対パス | segment_1234567890.mov | 新しい形式（推奨） | | 絶対パス | /path/to/Documents/segment_1234567890.mov | 古い形式（互換性のため残存） |

ファイル存在確認
// ✅ ファイルが存在しない場合はスキップ
guard FileManager.default.fileExists(atPath: fileURL.path) else {
    print("Segment file not found: \(fileURL.lastPathComponent)")
    continue  // ← 次のセグメントへ
}
実装箇所: ProjectManager.swift:176-180

重要性:

ファイルが削除されている可能性がある
ファイルが存在しない場合にクラッシュを防ぐ
スキップして次のセグメントを処理
AVURLAsset の作成
// ✅ AVURLAsset を作成（iOS 18 互換）
let asset = AVURLAsset(url: fileURL)
AVURLAsset の役割:

ファイルから動画メタデータを読み込む
トラック情報（ビデオ、音声）を取得
デュレーション（長さ）を取得
メモリ効率的（ファイル全体を読み込まない）
実装箇所: ProjectManager.swift:183

4.2.2 各セグメントの時間範囲（CMTimeRange）を計算
時間範囲の基本概念
セグメント1    セグメント2    セグメント3
[0.0 - 1.0]   [1.0 - 2.0]   [2.0 - 3.0]
    ↑             ↑             ↑
currentTime   currentTime   currentTime
   = 0.0         = 1.0         = 2.0
時間範囲の計算
// ✅ 各セグメントの duration を取得
let assetDuration = try await asset.load(.duration)

// ✅ CMTimeRange を作成
let timeRange = CMTimeRange(
    start: .zero,           // ← セグメント内の開始位置（常に0）
    duration: assetDuration // ← セグメントの長さ
)

print("Time range: start=\(timeRange.start.seconds)s, duration=\(timeRange.duration.seconds)s")
実装箇所: ProjectManager.swift:189, 193

CMTimeRange の構成: | フィールド | 説明 | 例 | |-----------|------|---| | start | セグメント内の開始位置 | 0.0 （常にセグメントの先頭から） | | duration | セグメントの長さ | 1.0 （1秒間） |

時間範囲の可視化
// セグメント1のファイル: 0.0s から 1.0s
let timeRange1 = CMTimeRange(start: .zero, duration: CMTime(seconds: 1.0, preferredTimescale: 600))
// start: 0.0s, duration: 1.0s

// セグメント2のファイル: 0.0s から 1.0s
let timeRange2 = CMTimeRange(start: .zero, duration: CMTime(seconds: 1.0, preferredTimescale: 600))
// start: 0.0s, duration: 1.0s

// Composition 上での配置:
// セグメント1: currentTime = 0.0 で挿入 → [0.0 - 1.0]
// セグメント2: currentTime = 1.0 で挿入 → [1.0 - 2.0]
4.2.3 insertTimeRange() でトラックに追加
ビデオトラックの追加
// ✅ ビデオトラックが存在する場合
if let assetVideoTrack = assetVideoTracks.first {
    // 1. 時間範囲を作成
    let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
    
    // 2. Composition のビデオトラックに挿入
    try videoTrack.insertTimeRange(
        timeRange,              // ← セグメント内の範囲（0.0 - 1.0）
        of: assetVideoTrack,    // ← 元のビデオトラック
        at: currentTime         // ← Composition 上の挿入位置（0.0, 1.0, 2.0, ...）
    )
    
    print("Video track added: Segment \(segment.order)")
}
実装箇所: ProjectManager.swift:191-196

パラメータ: | パラメータ | 説明 | 例 | |----------|------|---| | timeRange | セグメント内の範囲 | [0.0 - 1.0] | | of: assetVideoTrack | 元のビデオトラック | セグメントファイルのビデオトラック | | at: currentTime | Composition 上の挿入位置 | 0.0, 1.0, 2.0, ... |

音声トラックの追加
// ✅ 音声トラックが存在する場合
if let assetAudioTrack = assetAudioTracks.first {
    // 1. 時間範囲を作成（ビデオと同じ）
    let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
    
    // 2. Composition の音声トラックに挿入
    try audioTrack.insertTimeRange(
        timeRange,              // ← セグメント内の範囲
        of: assetAudioTrack,    // ← 元の音声トラック
        at: currentTime         // ← Composition 上の挿入位置
    )
    
    print("Audio track added: Segment \(segment.order)")
}
実装箇所: ProjectManager.swift:224-229

insertTimeRange() の動作
元のセグメントファイル（1秒間）:
[0.0 ──────────────── 1.0]
 ↑                    ↑
start               end

insertTimeRange(timeRange, of: track, at: currentTime)
                                       ↓
Composition 上に配置:
currentTime = 0.0 の場合
[0.0 ──────────────── 1.0]

currentTime = 1.0 の場合
            [1.0 ──────────────── 2.0]

currentTime = 2.0 の場合
                        [2.0 ──────────────── 3.0]
4.2.4 タイムスタンプの正確な計算方法
currentTime の更新
// ✅ 初期値は 0
var currentTime = CMTime.zero

// セグメント1を追加
// currentTime = 0.0
try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)

// ✅ 次のセグメントの開始時刻を更新
currentTime = CMTimeAdd(currentTime, assetDuration)
// currentTime = 0.0 + 1.0 = 1.0

// セグメント2を追加
// currentTime = 1.0
try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)

// ✅ 次のセグメントの開始時刻を更新
currentTime = CMTimeAdd(currentTime, assetDuration)
// currentTime = 1.0 + 1.0 = 2.0
実装箇所: ProjectManager.swift:161, 232

CMTime の正確な計算
// ❌ 間違った方法（浮動小数点演算）
var currentTimeSeconds = 0.0
currentTimeSeconds += assetDuration.seconds  // ← 精度が失われる可能性

// ✅ 正しい方法（CMTime 演算）
var currentTime = CMTime.zero
currentTime = CMTimeAdd(currentTime, assetDuration)  // ← 精度を保持
CMTimeAdd の利点:

タイムスケールを考慮した正確な計算
浮動小数点誤差を回避
フレーム境界に正確に配置
タイムスタンプ計算の可視化
// 3つのセグメントを統合する例

// 初期状態
var currentTime = CMTime.zero  // 0.0s

// セグメント1（1.0s）を挿入
let duration1 = CMTime(seconds: 1.0, preferredTimescale: 600)
// insertTimeRange(..., at: currentTime)  ← at: 0.0s
currentTime = CMTimeAdd(currentTime, duration1)
// currentTime = 1.0s

// セグメント2（1.0s）を挿入
let duration2 = CMTime(seconds: 1.0, preferredTimescale: 600)
// insertTimeRange(..., at: currentTime)  ← at: 1.0s
currentTime = CMTimeAdd(currentTime, duration2)
// currentTime = 2.0s

// セグメント3（1.0s）を挿入
let duration3 = CMTime(seconds: 1.0, preferredTimescale: 600)
// insertTimeRange(..., at: currentTime)  ← at: 2.0s
currentTime = CMTimeAdd(currentTime, duration3)
// currentTime = 3.0s

// 最終的な Composition:
// [Seg1: 0.0-1.0][Seg2: 1.0-2.0][Seg3: 2.0-3.0]
// 総再生時間: 3.0s
完全な統合処理の例
func createComposition(for project: Project) async -> AVComposition? {
    let composition = AVMutableComposition()
    
    guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
          let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
        return nil
    }
    
    // ✅ 1. 初期時刻を設定
    var currentTime = CMTime.zero
    let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    
    // ✅ 2. セグメントをソート
    let sortedSegments = project.segments.sorted { $0.order < $1.order }
    
    // ✅ 3. 各セグメントを処理
    for (index, segment) in sortedSegments.enumerated() {
        // ファイルURL構築
        let fileURL: URL
        if !segment.uri.hasPrefix("/") {
            fileURL = documentsPath.appendingPathComponent(segment.uri)
        } else {
            fileURL = URL(fileURLWithPath: segment.uri)
        }
        
        // ファイル存在確認
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            print("Segment file not found: \(fileURL.lastPathComponent)")
            continue
        }
        
        // アセット作成
        let asset = AVURLAsset(url: fileURL)
        
        do {
            // トラック情報を読み込む
            let assetVideoTracks = try await asset.loadTracks(withMediaType: .video)
            let assetAudioTracks = try await asset.loadTracks(withMediaType: .audio)
            let assetDuration = try await asset.load(.duration)
            
            // ✅ 4. 時間範囲を計算
            let timeRange = CMTimeRange(start: .zero, duration: assetDuration)
            
            // ✅ 5. ビデオトラックを挿入
            if let assetVideoTrack = assetVideoTracks.first {
                try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
                print("Video track added: Segment \(segment.order) at \(currentTime.seconds)s")
            }
            
            // ✅ 6. 音声トラックを挿入
            if let assetAudioTrack = assetAudioTracks.first {
                try audioTrack.insertTimeRange(timeRange, of: assetAudioTrack, at: currentTime)
                print("Audio track added: Segment \(segment.order)")
            }
            
            // ✅ 7. タイムスタンプを更新
            currentTime = CMTimeAdd(currentTime, assetDuration)
            print("Current composition time: \(currentTime.seconds)s")
            
        } catch {
            print("Failed to add segment \(segment.order): \(error)")
        }
    }
    
    // ✅ 8. 統合完了
    let totalDuration = currentTime.seconds
    print("Composition created successfully")
    print("Total duration: \(totalDuration)s")
    print("Total segments processed: \(sortedSegments.count)")
    
    return composition
}
実装箇所: ProjectManager.swift:143-246

タイムスタンプ計算のデバッグ出力例
Creating composition for project: My Project
Total segments: 3

Video track added: Segment 1 at 0.0s
Audio track added: Segment 1
Current composition time: 1.0s

Video track added: Segment 2 at 1.0s
Audio track added: Segment 2
Current composition time: 2.0s

Video track added: Segment 3 at 2.0s
Audio track added: Segment 3
Current composition time: 3.0s

Composition created successfully
Total duration: 3.0s
Total segments processed: 3
4.2.5 エラーハンドリング
ファイルが見つからない場合
guard FileManager.default.fileExists(atPath: fileURL.path) else {
    print("Segment file not found: \(fileURL.lastPathComponent)")
    continue  // ← スキップして次のセグメントへ
}
動作:

エラーを出さずに次のセグメントへ進む
一部のセグメントが欠けていても Composition は作成される
トラック追加に失敗した場合
do {
    try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentTime)
} catch {
    print("Failed to add segment \(segment.order): \(error)")
    // ← スキップして次のセグメントへ
}
考えられるエラー:

ファイルが破損している
コーデックがサポートされていない
メモリ不足
4.2.6 セグメント統合の最適化
進捗表示付きの統合
// ProjectManager.swift:248-370
func createCompositionWithProgress(
    for project: Project,
    progressCallback: @escaping (Int, Int) -> Void
) async -> AVComposition? {
    
    let composition = AVMutableComposition()
    
    // トラック作成...
    
    let sortedSegments = project.segments.sorted { $0.order < $1.order }
    let totalSegments = sortedSegments.count
    
    // ✅ 各セグメントを処理
    for (index, segment) in sortedSegments.enumerated() {
        // ✅ 進捗コールバックを呼ぶ
        progressCallback(index, totalSegments)
        
        // セグメント追加処理...
        
        // ✅ デバッグログ（50個ごと）
        if (index + 1) % 50 == 0 || index == totalSegments - 1 {
            print("Processed \(index + 1)/\(totalSegments) segments")
        }
    }
    
    // ✅ 最終進捗
    progressCallback(totalSegments, totalSegments)
    
    return composition
}
実装箇所: ProjectManager.swift:248-370

使用例:

// PlayerView.swift:633-652
let composition = await projectManager.createCompositionWithProgress(
    for: project,
    progressCallback: { processed, total in
        DispatchQueue.main.async {
            self.processedSegments = processed
            self.loadingProgress = Double(processed) / Double(total) * 0.8
            
            if processed % 10 == 0 || processed == total {
                print("Composition progress: \(processed)/\(total)")
            }
        }
    }
)
4.2.7 セグメント統合のまとめ
| ステップ | 処理 | 実装箇所 | |---------|------|---------| | 1 | AVMutableComposition 初期化 | Line 147 | | 2 | ビデオ・音声トラック追加 | Line 155-159 | | 3 | セグメントをソート | Line 165 | | 4 | currentTime 初期化（0） | Line 161 | | 5 | 各セグメントのファイルURL構築 | Line 168-174 | | 6 | ファイル存在確認 | Line 176-180 | | 7 | AVURLAsset 作成 | Line 183 | | 8 | トラック情報読み込み | Line 187-189 | | 9 | CMTimeRange 計算 | Line 193 | | 10 | insertTimeRange() でトラック追加 | Line 194, 227 | | 11 | currentTime 更新 | Line 232 | | 12 | 次のセグメントへ | ループ継続 |

以上がセクション4A-1「AVComposition実装ガイド（iOS版 Part 1）」です。
