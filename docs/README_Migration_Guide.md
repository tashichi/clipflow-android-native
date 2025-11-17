# ClipFlow Android 移植指示書 - 完全ガイド

## 📚 このドキュメント群について

このドキュメント群は、**iOS版ClipFlow（App Store公開済み）をAndroid Nativeに完全に移植するための完全な指示書**です。

セクション1～7で、iOS版の設計・実装パターンを詳細に解説し、Androidでの完全な対応方法を示します。

**前回の失敗を踏まえた対策**も完全に含まれており、本番環境での安定動作を想定した内容になっています。

---

## 📖 セクション構成と推奨読書順

### 基礎知識

1. **Section_1_Application_Overview.md** - アプリケーション概要
   - アプリの目的・機能
   - iOS版技術スタック
   - Android版技術スタック

2. **Section_2_UI_UX_Layout.md** - UI/UXレイアウト詳細
   - 各画面のレイアウト
   - UI要素の配置
   - 画面遷移フロー

### ライフサイクル管理

3. **Section_3A_iOS_Lifecycle.md** - iOS版ライフサイクル管理
   - 参考：iOS版の状態管理パターン
   - リソース管理方法

4. **Section_3B-1_Android_Lifecycle_ViewModel.md** - Android版ViewModel管理
   - @StateObject → ViewModel 対応
   - @Published → StateFlow 対応

5. **Section_3B-2_Android_Lifecycle_CameraX.md** - Android版CameraX管理
   - ProcessCameraProvider の初期化・破棄
   - リソース管理パターン

6. **Section_3B-3_Android_Lifecycle_Compose.md** - Android版Compose管理
   - LaunchedEffect / DisposableEffect
   - ライフサイクル統合

### ギャップレス再生実装（最重要）

7. **Section_4B-1a_Media3_Composition_Android_Part1a.md** - Media3 Composition基本
   - Composition.Builder の使用方法
   - セグメント統合ロジック
   - メタデータ取得

8. **Section_4B-1b-i_Media3_Composition_Resource_Management.md** - リソース管理
   - セクション15個制限への対策
   - MediaMetadataRetriever のリソースリーク防止
   - 100個以上セグメント対応

9. **Section_4B-1b-ii_ViewModel_Compose_Lifecycle_Integration.md** - ライフサイクル統合
   - viewModelScope での実行
   - DisposableEffect でのリソース破棄

10. **Section_4B-1b-iii_Complete_VideoComposer_Implementation.md** - 完全実装例
    - VideoComposer.kt 全体実装
    - エラーハンドリング
    - テスト方法

### フロー実装

11. **Section_5a_Segment_Management_Flow_Capture_Save_Playback.md** - 撮影・保存・再生
    - 1秒録画フロー
    - セグメント保存
    - ギャップレス再生

12. **Section_5b_Segment_Management_Flow_Delete_Export_Storage.md** - 削除・エクスポート
    - セグメント削除フロー
    - エクスポートフロー
    - ストレージ管理

### API対応とトラブルシューティング

13. **Section_6_iOS_Android_API_Correspondence.md** - 1対1 API対応表
    - クラス・型の対応
    - API の対応
    - 処理フローの対応

14. **Section_7_Implementation_Notes_And_Failure_Countermeasures.md** - 実装注意点
    - 前回の失敗への対策
    - Android 固有の制約
    - デバッグ・トラブルシューティング
    - テスト方針

---

## 🚀 使用方法

### 推奨される読み方

**1. 全体把握（1-2時間）**
- Section_1, 2 を読む
- アプリの全体像を理解

**2. ライフサイクル理解（2-3時間）**
- Section_3A を読む
- Section_3B-1, 3B-2, 3B-3 を読む
- iOS と Android の違いを理解

**3. ギャップレス再生実装（最重要、3-4時間）**
- Section_4B-1a, 1b-i, 1b-ii, 1b-iii を順に読む
- **この部分が前回の失敗点です。必ず熟読してください。**

**4. フロー実装（2-3時間）**
- Section_5a, 5b を読む
- 全体フローを理解

**5. 実装準備（1時間）**
- Section_6, 7 を読む
- トラブルシューティングを把握

### 実装時の参照方法

| 実装内容 | 参照セクション |
|---------|--------------|
| **撮影機能を実装する時** | Section_5a |
| **再生機能を実装する時** | Section_4B-1b-iii, 5a |
| **エクスポートを実装する時** | Section_5b |
| **APIの対応を確認する時** | Section_6 |
| **トラブル発生時** | Section_7 のトラブルシューティング |

---

## ⚠️ 最重要な注意点

### セクション15個制限への対策（必須）

前回の実装でセグメント15個以降にクラッシュが発生しました。

**原因：** MediaMetadataRetriever のリソースリーク
**結果：** ファイルハンドル枯渇（Linux上限 1024）

> ⚠️ **Section_4B-1b-i を必ず読んでから実装してください**

```kotlin
// 正しい実装パターン
MediaMetadataRetriever().use { retriever ->
    retriever.setDataSource(context, uri)
    // ... 処理
} // 自動的に release() が呼ばれる
```

### 大量セグメント対応（100個以上）

**Section_4B-1b-i のバッチ処理パターンを必ず実装してください**

```kotlin
// バッチ処理パターン
val batchSize = 10
segments.chunked(batchSize).forEach { batch ->
    processBatch(batch)
    System.gc() // GC を促進
}
```

**テストは段階的に実施：**
1. 1-5個: 基本動作確認
2. 10-20個: 通常使用シナリオ
3. 100個以上: ストレステスト

### メモリリーク防止

**Section_4B-1b-ii のライフサイクル統合を必ず実装してください**

- `viewModelScope` で非同期処理を管理
- `DisposableEffect` でリソース破棄を確実に実行
- `onCleared()` でプレイヤーを解放

### Android 固有の制約

**Section_7 の「Android固有の制約と対応」を読んでください**

- **ファイルハンドル上限**: 1024/プロセス
- **メモリ制限**: デバイス依存（512MB-8GB+）
- **APIレベル差異**: Android 10以降のScoped Storage

---

## 📊 実装進捗管理

### チェックリスト

```markdown
- [ ] Section_1-3 の読了（全体理解）
- [ ] Section_4B-1a-iii の実装（ギャップレス再生基本）
- [ ] Section_5a の実装（撮影→保存→再生）
- [ ] Section_5b の実装（削除→エクスポート）
- [ ] Section_7 のテスト項目実施
- [ ] 本番環境テスト完了
```

### マイルストーン

| # | マイルストーン | 完了基準 | 参照セクション |
|---|--------------|---------|--------------|
| 1 | シングルセグメント再生 | 1つの動画ファイルを再生できる | Section_4B-1a |
| 2 | 複数セグメント再生 | 5つのセグメントをギャップレス再生 | Section_4B-1b-iii |
| 3 | 撮影→再生フロー | 1秒撮影→保存→一覧表示→再生 | Section_5a |
| 4 | 削除→エクスポート | セグメント削除とギャラリーエクスポート | Section_5b |
| 5 | 100セグメント対応 | 100個のセグメントを安定処理 | Section_4B-1b-i |
| 6 | 本番環境対応 | 低スペックデバイスでも動作 | Section_7 |

---

## 📂 ファイル構成

```
docs/
├── README_Migration_Guide.md          # 本ファイル（全体ガイド）
├── Section_1_Application_Overview.md  # アプリ概要
├── Section_2_UI_UX_Layout.md          # UI/UXレイアウト
├── Section_3A_iOS_Lifecycle.md        # iOS版ライフサイクル
├── Section_3B-1_Android_Lifecycle_ViewModel.md    # Android ViewModel
├── Section_3B-2_Android_Lifecycle_CameraX.md      # Android CameraX
├── Section_3B-3_Android_Lifecycle_Compose.md      # Android Compose
├── Section_4A-1_AVComposition_iOS_Part1.md        # iOS AVComposition
├── Section_4A-2_AVComposition_iOS_Part2.md        # iOS AVComposition続き
├── Section_4B-1a_Media3_Composition_Android_Part1a.md           # Media3基本
├── Section_4B-1b-i_Media3_Composition_Resource_Management.md   # リソース管理
├── Section_4B-1b-ii_ViewModel_Compose_Lifecycle_Integration.md # ライフサイクル統合
├── Section_4B-1b-iii_Complete_VideoComposer_Implementation.md  # 完全実装
├── Section_5a_Segment_Management_Flow_Capture_Save_Playback.md # 撮影・再生フロー
├── Section_5b_Segment_Management_Flow_Delete_Export_Storage.md # 削除・エクスポート
├── Section_6_iOS_Android_API_Correspondence.md                  # API対応表
└── Section_7_Implementation_Notes_And_Failure_Countermeasures.md # 注意点・対策
```

---

## 🔗 関連ドキュメント

- **iOS版仕様書**: 参考用（iOS版の詳細仕様）
- **Stage2分析結果**: 参考用（要件分析）

---

## 💡 よくある質問

### Q: どのセクションから始めるべき？

**A:** Section_1 → Section_2 → Section_3A の順に読んでから、Section_4B-1a から実装を始めてください。全体像を把握してから詳細に入ることで、効率的に実装できます。

### Q: Section_3A（iOS版）を読む必要がある？

**A:** はい、強く推奨します。Android版の設計の参考になります。「iOS では こうなっているから、Android では こう対応する」という理解が重要です。これにより、機能パリティを保ちやすくなります。

### Q: セクション15個制限が怖い。どうすればいい？

**A:** 以下の手順で対応してください：
1. Section_4B-1b-i を熟読
2. `use()` パターンを必ず使用
3. 段階的テストを実施（1-5個 → 10-20個 → 100個+）
4. Section_7 のチェックリストで確認

### Q: 実装中にトラブルが発生した。

**A:** Section_7 のトラブルシューティングを確認してください。主なエラーと解決策が記載されています：
- `width -1 must be positive` → メタデータ取得失敗、フォールバック値を設定
- `FileNotFoundException` → ファイルパスの確認、context.filesDir 使用
- `OutOfMemoryError` → バッチ処理、GC促進、セグメント数制限

### Q: iOS版と完全に同じ動作にする必要がある？

**A:** 機能面では同等を目指しますが、実装詳細はプラットフォームに合わせて最適化してください。Section_6 のAPI対応表を参考に、Androidネイティブの方法で実装することが重要です。

### Q: 古いAndroidバージョン（API 26-29）もサポートする必要がある？

**A:** はい。Section_7 の「APIレベルの差異」を参照し、条件分岐で対応してください。特にScoped Storage（Android 10+）の扱いに注意が必要です。

---

## 📝 今後の改善項目

このドキュメント群を使用した実装を通じて、改善点が見つかれば随時更新してください：

### 短期改善項目
- 新しいエラーパターンの追加
- パフォーマンス最適化の知見
- テストケースの追加

### 中期改善項目
- Android 15+ での新しい制約への対応
- Kotlin 2.0 / Compose 最新版への対応
- Media3 ライブラリのアップデート対応

### 長期改善項目
- ユーザーからのフィードバック反映
- 新機能追加時のドキュメント拡張
- クロスプラットフォーム共通化の検討

---

## 🎯 最終目標

このドキュメント群を使用して、**iOS版と完全に同等の機能・品質を持つAndroid版ClipFlow**の実装を実現することです。

### 品質目標

1. **機能パリティ**
   - 1秒自動録画
   - ギャップレス再生
   - セグメント管理（追加・削除・並べ替え）
   - ギャラリーエクスポート

2. **安定性**
   - 15セグメント制限の完全克服
   - 100個以上のセグメントを安定処理
   - メモリリークなし
   - クラッシュなし

3. **パフォーマンス**
   - スムーズな60fps再生
   - フレームドロップ1%未満
   - エクスポート処理がリアルタイム以下

4. **ユーザー体験**
   - iOS版と同等の操作感
   - 直感的なUI
   - 適切なエラーメッセージ

---

## 🏁 実装開始の準備

### 開発環境セットアップ

```gradle
// build.gradle.kts (Module: app)

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Media3
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-transformer:1.2.1")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-video:1.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### 最初のステップ

1. **このREADMEを読了** ✅
2. **Section_1-2を読んで全体像を把握**
3. **Section_3を読んでライフサイクル管理を理解**
4. **Section_4B-1aから実装開始**
5. **各マイルストーンごとにSection_7でテスト確認**

---

**セクション15個制限を完全に克服し、100個以上のセグメントを安定して扱える実装を目指してください。**

このドキュメント群があなたの実装を成功に導くことを願っています。

---

**ドキュメント作成日**: 2025年11月17日
**iOS版バージョン**: v2.7（ライト機能・課金ボタン見える化対応）
**対象Android バージョン**: API 26（Android 8.0）以上
**ドキュメントバージョン**: 1.0
