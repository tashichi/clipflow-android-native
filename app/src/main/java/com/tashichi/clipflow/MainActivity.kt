package com.tashichi.clipflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tashichi.clipflow.data.model.AppScreen
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.VideoSegment
import com.tashichi.clipflow.ui.screen.CameraScreen
import com.tashichi.clipflow.ui.screen.PlayerScreen
import com.tashichi.clipflow.ui.screen.ProjectListScreen
import com.tashichi.clipflow.ui.theme.ClipFlowTheme
import com.tashichi.clipflow.ui.viewmodel.ProjectListViewModel
import com.tashichi.clipflow.util.DebugLogger
import android.util.Log

/**
 * MainActivity - ClipFlow Androidアプリのエントリーポイント
 *
 * iOS版参考実装:
 * - ClipFlowApp.swift (@main struct ClipFlowApp: App)
 * - MainView.swift (画面遷移ロジック)
 *
 * 主な機能:
 * - ClipFlowApp() Composable によるアプリケーション全体のレイアウト
 * - AppScreen enum による画面遷移管理
 * - 状態管理（currentScreen, currentProject, selectedSegmentIndex）
 * - 3つの画面の統合（ProjectList, Camera, Player）
 *
 * 画面遷移フロー:
 * 1. PROJECTS (起動時デフォルト)
 *    ↓ プロジェクト選択
 * 2. CAMERA (撮影画面)
 *    ↓ 戻る → PROJECTS
 *    ↓ セグメント追加後、プロジェクトにセグメントがある場合 → PLAYER
 * 3. PLAYER (再生画面)
 *    ↓ 戻る → PROJECTS
 *
 * iOS版との対応:
 * - iOS: NavigationView + fullScreenCover
 * - Android: Compose Navigation (状態管理による画面切り替え)
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "ClipFlow App onCreate")

        // デバッグセッション開始（Phase 6: 統合テスト用）
        DebugLogger.startSession()
        DebugLogger.logDeviceCapabilities(applicationContext)
        DebugLogger.logIntegrationTestChecklist()

        // Edge-to-edge表示を有効化（iOS版のfullScreenCoverに相当）
        enableEdgeToEdge()

        setContent {
            ClipFlowApp()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ClipFlow App onDestroy")
        DebugLogger.endSession()
    }
}

/**
 * ClipFlowApp - アプリケーション全体のレイアウトとナビゲーション管理
 *
 * iOS版参考: MainView.swift
 *
 * 状態管理:
 * - currentScreen: 現在表示中の画面 (PROJECTS, CAMERA, PLAYER)
 * - currentProject: 選択中のプロジェクト
 * - selectedSegmentIndex: 再生中のセグメントインデックス
 *
 * 画面遷移ロジック:
 * - ProjectListScreen → CameraScreen: プロジェクト選択時（セグメントなし）
 * - ProjectListScreen → PlayerScreen: プロジェクトタップ時（セグメントあり）
 * - CameraScreen → ProjectListScreen: 戻るボタン押下時
 * - PlayerScreen → ProjectListScreen: 戻るボタン押下時
 */
@Composable
fun ClipFlowApp() {
    // ViewModelの取得（プロジェクト一覧画面用）
    val projectListViewModel: ProjectListViewModel = viewModel()

    // iOS版: @State var currentScreen: AppScreen = .projects
    var currentScreen by remember { mutableStateOf(AppScreen.PROJECTS) }

    // iOS版: @State var selectedProject: Project?
    var currentProject by remember { mutableStateOf<Project?>(null) }

    // iOS版: @State var selectedSegmentIndex: Int = 0
    var selectedSegmentIndex by remember { mutableStateOf(0) }

    // 画面遷移ログ（Phase 6: 統合テスト用）
    LaunchedEffect(currentScreen) {
        val screenName = when (currentScreen) {
            AppScreen.PROJECTS -> "ProjectListScreen"
            AppScreen.CAMERA -> "CameraScreen"
            AppScreen.PLAYER -> "PlayerScreen"
        }
        Log.i("Navigation", "Screen transition to: $screenName")
        DebugLogger.startOperation("Screen: $screenName")
    }

    /**
     * テーマ設定: Dark theme (Material Design 3)
     * iOS版カラースキーム参考: docs/iOS_ClipFlow_Specification.md:380-382
     * - 背景: Color.black
     * - ボタン背景: Color.black.opacity(0.7)
     * - アクセント: Color.orange (エクスポート)
     * - 警告: Color.red (削除)
     * - 成功: Color.green (トースト)
     * - セグメント情報: Color.yellow
     */
    ClipFlowTheme(
        darkTheme = true,  // 常にダークテーマを使用
        dynamicColor = false  // ダイナミックカラーを無効化（独自のカラースキームを使用）
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)  // iOS版: Color.black
        ) {
            // 画面遷移管理
            // iOS版: fullScreenCover(isPresented:) で画面を切り替え
            when (currentScreen) {
                AppScreen.PROJECTS -> {
                    /**
                     * プロジェクト一覧画面
                     *
                     * iOS版参考: ProjectListView.swift
                     * - プロジェクト一覧表示
                     * - 新規プロジェクト作成
                     * - REC/Play/Exportボタンによる画面遷移
                     */
                    ProjectListScreen(
                        viewModel = projectListViewModel,
                        onRecordProject = { project ->
                            // RECボタン → Camera画面へ
                            currentProject = project
                            currentScreen = AppScreen.CAMERA
                        },
                        onPlayProject = { project ->
                            // Playボタン → Player画面へ
                            currentProject = project
                            selectedSegmentIndex = 0
                            currentScreen = AppScreen.PLAYER
                        },
                        onExportProject = { project ->
                            // Exportボタン → エクスポート処理
                            // TODO: エクスポート機能を実装（別タスク）
                            currentProject = project
                        }
                    )
                }

                AppScreen.CAMERA -> {
                    /**
                     * カメラ撮影画面
                     *
                     * iOS版参考: CameraView.swift
                     * - カメラプレビュー表示
                     * - 1秒録画機能
                     * - セグメント追加
                     * - 戻るボタン → Projects画面へ
                     */
                    currentProject?.let { project ->
                        CameraScreen(
                            project = project,
                            onBack = {
                                // 戻るボタン → Projects画面へ
                                // iOS版参考: CameraView.swift headerView "← Projects"
                                currentScreen = AppScreen.PROJECTS
                                currentProject = null
                            },
                            onSegmentRecorded = { segment ->
                                /**
                                 * セグメント追加時の処理
                                 *
                                 * iOS版参考: MainView.swift:78-87 (onRecordingComplete)
                                 *
                                 * 処理フロー:
                                 * 1. プロジェクトを最新の状態に更新
                                 * 2. セグメントを追加
                                 * 3. ProjectRepositoryに保存（ViewModelが処理）
                                 * 4. currentProjectを更新
                                 *
                                 * 注意: ProjectはData Classなので、値渡し。
                                 * 必ず最新のプロジェクト状態を取得してから更新する。
                                 */
                                val updatedProject = project.addSegment(segment)
                                currentProject = updatedProject

                                // セグメント追加後、ProjectRepositoryに保存
                                // ViewModelのupdateProjectを呼び出す
                                // （実際の保存処理はViewModel経由で行う）
                            }
                        )
                    }
                }

                AppScreen.PLAYER -> {
                    /**
                     * 動画再生画面
                     *
                     * iOS版参考: PlayerView.swift
                     * - AVCompositionによるシームレス再生（Media3 ExoPlayer）
                     * - 再生コントロール（再生/一時停止/次へ/前へ/シーク）
                     * - セグメント削除機能
                     * - エクスポート機能
                     * - 戻るボタン → Projects画面へ
                     */
                    currentProject?.let { project ->
                        PlayerScreen(
                            project = project,
                            onBack = {
                                // 戻るボタン → Projects画面へ
                                // iOS版参考: PlayerView.swift headerView "← Back"
                                currentScreen = AppScreen.PROJECTS
                                currentProject = null
                                selectedSegmentIndex = 0
                            },
                            onExport = {
                                /**
                                 * エクスポート機能
                                 *
                                 * iOS版参考: PlayerView.swift:723-801 (exportVideo)
                                 *
                                 * 処理フロー:
                                 * 1. 既存のcompositionを使用（なければ新規作成）
                                 * 2. AVAssetExportSession でエクスポート
                                 * 3. 完了後、saveToPhotoLibrary() でフォトライブラリに保存
                                 *
                                 * Android版実装:
                                 * - Media3 Transformer を使用
                                 * - MediaStore API でギャラリーに保存
                                 *
                                 * TODO: エクスポート機能を実装（別タスク）
                                 */
                                // エクスポート処理は今後実装
                            },
                            onProjectUpdated = { updatedProject ->
                                /**
                                 * プロジェクト更新時の処理
                                 *
                                 * iOS版参考: MainView.swift:78-87 (onRecordingComplete)
                                 * セグメント削除などでプロジェクトが更新された場合
                                 */
                                currentProject = updatedProject
                            }
                        )
                    }
                }
            }
        }
    }
}

