package com.tashichi.clipflow.ui.screen

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.VideoSegment
import com.tashichi.clipflow.ui.viewmodel.CameraViewModel
import com.tashichi.clipflow.util.rememberCameraPermissionState

/**
 * CameraScreen - カメラ撮影画面
 *
 * iOS版の参考実装:
 * - CameraView.swift (全体のUIレイアウト)
 * - レイアウト構造: docs/iOS_ClipFlow_Specification.md:402-416
 *
 * UIコンポーネント:
 * - カメラプレビュー表示
 * - ヘッダー（戻るボタン、プロジェクト名、カメラ切り替え、セグメント数）
 * - コントロール（フラッシュライト、録画ボタン）
 * - 成功トースト
 *
 * @param project 撮影対象のプロジェクト
 * @param onBack 戻るボタンが押された時のコールバック
 * @param onSegmentRecorded セグメントが録画された時のコールバック
 * @param viewModel CameraViewModel
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    project: Project,
    onBack: () -> Unit,
    onSegmentRecorded: (VideoSegment) -> Unit,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ViewModelにプロジェクトを設定
    LaunchedEffect(project) {
        viewModel.setProject(project)
    }

    // 権限管理
    val permissionsState = rememberCameraPermissionState()

    // ViewModel状態
    val isRecording by viewModel.isRecording.collectAsState()
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val cameraSelector by viewModel.cameraSelector.collectAsState()
    val showSuccessToast by viewModel.showSuccessToast.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    // カメラプレビューの参照
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // 権限チェック
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            // カメラセットアップ
            previewView?.let { pv ->
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                viewModel.setupCamera(context, lifecycleOwner, preview)
            }
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // カメラプレビュー
        if (permissionsState.allPermissionsGranted) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPreviewViewCreated = { pv ->
                    previewView = pv
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(pv.surfaceProvider)
                    }
                    viewModel.setupCamera(context, lifecycleOwner, preview)
                }
            )
        } else {
            // 権限がない場合のプレースホルダー
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera permission required",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // オーバーレイ（グラデーション）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        )

        // UIコントロール
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ヘッダー
            CameraHeaderView(
                project = project,
                onBack = onBack,
                onToggleCamera = {
                    previewView?.let { pv ->
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(pv.surfaceProvider)
                        }
                        viewModel.toggleCamera(context, lifecycleOwner, preview)
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // コントロール
            CameraControlsView(
                isRecording = isRecording,
                isTorchOn = isTorchOn,
                isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA,
                onRecord = {
                    if (!isRecording) {
                        viewModel.startRecording(context, onSegmentRecorded)
                    }
                },
                onToggleTorch = {
                    viewModel.toggleTorch()
                }
            )
        }

        // 成功トースト
        if (showSuccessToast) {
            SuccessToast(
                message = toastMessage,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

/**
 * カメラプレビューコンポーネント
 *
 * @param modifier Modifier
 * @param onPreviewViewCreated PreviewViewが作成された時のコールバック
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onPreviewViewCreated: (PreviewView) -> Unit
) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onPreviewViewCreated(this)
            }
        },
        modifier = modifier
    )
}

/**
 * カメラヘッダービュー
 *
 * iOS版参考: CameraView.swift:159-168 (headerView)
 *
 * レイアウト:
 * - [← Projects]  プロジェクト名  [🔄]
 * - セグメント数表示
 *
 * @param project 現在のプロジェクト
 * @param onBack 戻るボタンのコールバック
 * @param onToggleCamera カメラ切り替えボタンのコールバック
 */
@Composable
fun CameraHeaderView(
    project: Project,
    onBack: () -> Unit,
    onToggleCamera: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 20.dp, end = 20.dp)
    ) {
        // トップバー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 戻るボタン
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            // カメラ切り替えボタン
            IconButton(
                onClick = onToggleCamera,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // プロジェクト名
        Text(
            text = project.name,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // セグメント数表示
        Text(
            text = "${project.segmentCount}s recorded",
            color = Color.Yellow,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
    }
}

/**
 * カメラコントロールビュー
 *
 * iOS版参考: CameraView.swift:159-168 (controlsView)
 *
 * レイアウト:
 * - [🔦] フラッシュライト（バックカメラのみ）
 * - ⭕ 録画ボタン（中央）
 * - REC テキスト
 *
 * @param isRecording 録画中かどうか
 * @param isTorchOn フラッシュライトがオンかどうか
 * @param isFrontCamera フロントカメラかどうか
 * @param onRecord 録画ボタンのコールバック
 * @param onToggleTorch フラッシュライト切り替えのコールバック
 */
@Composable
fun CameraControlsView(
    isRecording: Boolean,
    isTorchOn: Boolean,
    isFrontCamera: Boolean,
    onRecord: () -> Unit,
    onToggleTorch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 50.dp, start = 40.dp, end = 40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // フラッシュライトボタン（バックカメラのみ）
        if (!isFrontCamera) {
            IconButton(
                onClick = onToggleTorch,
                modifier = Modifier
                    .size(50.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Flashlight,
                    contentDescription = if (isTorchOn) "Turn off flashlight" else "Turn on flashlight",
                    tint = if (isTorchOn) Color.Yellow else Color.Gray,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            // スペーサー（レイアウトバランス用）
            Spacer(modifier = Modifier.size(50.dp))
        }

        // 録画ボタン
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 録画ボタン（円形）
            IconButton(
                onClick = onRecord,
                enabled = !isRecording,
                modifier = Modifier
                    .size(80.dp)
                    .border(
                        width = 4.dp,
                        color = if (isRecording) Color.Red else Color.White,
                        shape = CircleShape
                    )
                    .background(
                        color = if (isRecording) Color.Red.copy(alpha = 0.8f) else Color.Red,
                        shape = CircleShape
                    )
            ) {
                // 録画中はアニメーション
                if (isRecording) {
                    val infiniteTransition = rememberInfiniteTransition(label = "recording")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = Color.White.copy(alpha = alpha),
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // RECテキスト
            Text(
                text = "REC",
                color = if (isRecording) Color.Red else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // スペーサー（レイアウトバランス用）
        Spacer(modifier = Modifier.size(50.dp))
    }
}

/**
 * 成功トースト
 *
 * iOS版参考: CameraView.swift:71-78 (successToastView)
 *
 * アニメーション:
 * - transition: .scale.combined(with: .opacity)
 * - animation: .easeInOut(duration: 0.3)
 * - 表示時間: 1.5秒
 *
 * @param message 表示するメッセージ
 * @param modifier Modifier
 */
@Composable
fun SuccessToast(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 200.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            color = Color(0xFF4CAF50), // 緑色
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
