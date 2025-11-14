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
import com.tashichi.clipflow.ui.screen.ProjectListScreen
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

    // 選択中のプロジェクト
    var currentProject by remember {
        mutableStateOf<Project?>(null)
    }

    // iOS版参考: MainView.swift:74-108 (fullScreenCover)
    when (currentScreen) {
        AppScreen.PROJECTS -> {
            // プロジェクト一覧画面
            ProjectListScreen(
                onProjectSelected = { project ->
                    currentProject = project
                    // セグメントがある場合は再生画面へ、ない場合はカメラ画面へ
                    currentScreen = if (project.segments.isNotEmpty()) {
                        AppScreen.PLAYER
                    } else {
                        AppScreen.CAMERA
                    }
                }
            )
        }

        AppScreen.CAMERA -> {
            // カメラ撮影画面
            currentProject?.let { project ->
                CameraScreen(
                    project = project,
                    onBack = {
                        currentScreen = AppScreen.PROJECTS
                    },
                    onSegmentRecorded = { segment ->
                        // セグメントを追加（iOS版参考: MainView.swift:78-87）
                        currentProject = project.addSegment(segment)
                    }
                )
            }
        }

        AppScreen.PLAYER -> {
            // 動画再生画面
            currentProject?.let { project ->
                PlayerScreen(
                    project = project,
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
}

