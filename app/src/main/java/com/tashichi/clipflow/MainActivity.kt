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
import com.tashichi.clipflow.data.model.AppScreen
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.VideoSegment
import com.tashichi.clipflow.ui.screen.CameraScreen
import com.tashichi.clipflow.ui.screen.PlayerScreen
import com.tashichi.clipflow.ui.theme.ClipFlowTheme

/**
 * MainActivity - ClipFlowアプリのメインActivity
 *
 * iOS版の参考実装:
 * - MainView.swift (画面遷移管理)
 * - AppScreen enum (PROJECTS, CAMERA, PLAYER)
 *
 * 画面遷移:
 * - プロジェクト一覧 (未実装)
 * - カメラ撮影画面 (CameraScreen)
 * - 動画再生画面 (PlayerScreen)
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipFlowTheme {
                ClipFlowApp()
            }
        }
    }
}

/**
 * ClipFlowアプリのメインComposable
 *
 * 画面遷移とプロジェクト管理を行います
 */
@Composable
fun ClipFlowApp() {
    // 現在の画面
    var currentScreen by remember { mutableStateOf(AppScreen.PROJECTS) }

    // サンプルプロジェクト（テスト用）
    var currentProject by remember {
        mutableStateOf(
            Project(
                id = System.currentTimeMillis(),
                name = "Sample Project",
                segments = emptyList(),
                createdAt = System.currentTimeMillis(),
                lastModified = System.currentTimeMillis()
            )
        )
    }

    // iOS版参考: MainView.swift:74-108 (fullScreenCover)
    when (currentScreen) {
        AppScreen.PROJECTS -> {
            // プロジェクト一覧画面（仮実装）
            ProjectListPlaceholder(
                onNavigateToCamera = { project ->
                    currentProject = project
                    currentScreen = AppScreen.CAMERA
                },
                onNavigateToPlayer = { project ->
                    currentProject = project
                    currentScreen = AppScreen.PLAYER
                }
            )
        }

        AppScreen.CAMERA -> {
            // カメラ撮影画面
            CameraScreen(
                project = currentProject,
                onBack = {
                    currentScreen = AppScreen.PROJECTS
                },
                onSegmentRecorded = { segment ->
                    // セグメントを追加（iOS版参考: MainView.swift:78-87）
                    currentProject = currentProject.addSegment(segment)
                }
            )
        }

        AppScreen.PLAYER -> {
            // 動画再生画面
            PlayerScreen(
                project = currentProject,
                onBack = {
                    currentScreen = AppScreen.PROJECTS
                },
                onExport = {
                    // エクスポート処理（未実装）
                    // TODO: エクスポート機能の実装
                },
                onProjectUpdated = { updatedProject ->
                    // プロジェクト更新（セグメント削除時など）
                    currentProject = updatedProject
                }
            )
        }
    }
}

/**
 * プロジェクト一覧画面のプレースホルダー
 *
 * 実際のプロジェクト一覧画面は未実装のため、
 * テスト用の簡易画面を表示します
 *
 * @param onNavigateToCamera カメラ画面への遷移コールバック
 * @param onNavigateToPlayer 再生画面への遷移コールバック
 */
@Composable
fun ProjectListPlaceholder(
    onNavigateToCamera: (Project) -> Unit,
    onNavigateToPlayer: (Project) -> Unit
) {
    // サンプルプロジェクト
    val sampleProject = remember {
        Project(
            id = System.currentTimeMillis(),
            name = "Test Project",
            segments = listOf(
                VideoSegment(
                    id = 1L,
                    uri = "segment_1.mp4",
                    timestamp = System.currentTimeMillis(),
                    facing = "back",
                    order = 1
                ),
                VideoSegment(
                    id = 2L,
                    uri = "segment_2.mp4",
                    timestamp = System.currentTimeMillis(),
                    facing = "back",
                    order = 2
                ),
                VideoSegment(
                    id = 3L,
                    uri = "segment_3.mp4",
                    timestamp = System.currentTimeMillis(),
                    facing = "back",
                    order = 3
                )
            ),
            createdAt = System.currentTimeMillis(),
            lastModified = System.currentTimeMillis()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "ClipFlow",
                color = Color.White,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Android Native",
                color = Color.Gray,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(40.dp))

            // カメラ画面へ遷移
            Button(
                onClick = {
                    onNavigateToCamera(
                        Project(
                            id = System.currentTimeMillis(),
                            name = "New Project",
                            segments = emptyList(),
                            createdAt = System.currentTimeMillis(),
                            lastModified = System.currentTimeMillis()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                ),
                modifier = Modifier
                    .width(250.dp)
                    .height(60.dp)
            ) {
                Text(
                    text = "Open Camera",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 再生画面へ遷移（サンプルプロジェクト）
            Button(
                onClick = { onNavigateToPlayer(sampleProject) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                ),
                modifier = Modifier
                    .width(250.dp)
                    .height(60.dp)
            ) {
                Text(
                    text = "Open Player (Sample)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "テスト用画面",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "プロジェクト一覧画面は未実装",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}