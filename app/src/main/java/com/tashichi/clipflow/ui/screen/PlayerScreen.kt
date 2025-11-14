package com.tashichi.clipflow.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * PlayerScreen - 動画再生画面
 *
 * iOS版の参考実装:
 * - PlayerView.swift (全体のUIレイアウト)
 * - レイアウト構造: docs/iOS_ClipFlow_Specification.md:384-401
 *
 * UIコンポーネント:
 * - ヘッダー（戻るボタン、プロジェクト名、セグメント数、エクスポートボタン）
 * - 動画再生エリア（ExoPlayer）
 * - コントロールバー（前/再生/一時停止/次）
 * - プログレスバー（セグメント境界線、タップでシーク）
 * - セグメント削除UI
 * - ローディング表示
 * - トースト表示
 *
 * @param project 再生対象のプロジェクト
 * @param onBack 戻るボタンが押された時のコールバック
 * @param onExport エクスポートボタンが押された時のコールバック
 * @param onProjectUpdated プロジェクトが更新された時のコールバック
 * @param viewModel PlayerViewModel
 */
@Composable
fun PlayerScreen(
    project: Project,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onProjectUpdated: (Project) -> Unit,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current

    // ViewModelを初期化
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // ViewModelにプロジェクトを設定
    LaunchedEffect(project) {
        viewModel.setProject(project)
    }

    // ViewModel状態
    val currentProject by viewModel.project.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentTime by viewModel.currentTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val currentSegmentIndex by viewModel.currentSegmentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()
    val processedSegments by viewModel.processedSegments.collectAsState()
    val showToast by viewModel.showToast.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // セグメント削除ダイアログの状態
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 動画再生エリア
        if (currentProject != null && currentProject!!.segments.isNotEmpty()) {
            ExoPlayerView(
                exoPlayer = viewModel.exoPlayer,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 空状態
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No segments to play",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // コントロールオーバーレイ
        Column(
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
        ) {
            // ヘッダー
            PlayerHeaderView(
                project = currentProject,
                currentSegmentIndex = currentSegmentIndex,
                onBack = onBack,
                onExport = onExport
            )

            Spacer(modifier = Modifier.weight(1f))

            // 再生コントロール
            if (currentProject != null && currentProject!!.segments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    // プログレスバー
                    ProgressBarView(
                        currentTime = currentTime,
                        duration = duration,
                        segmentCount = currentProject!!.segmentCount,
                        onSeek = { positionMs ->
                            viewModel.seekTo(positionMs)
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 再生コントロールボタン
                    PlaybackControlsView(
                        isPlaying = isPlaying,
                        onPlayPause = { viewModel.togglePlayback() },
                        onPrevious = { viewModel.previousSegment() },
                        onNext = { viewModel.nextSegment() }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // セグメント情報と削除ボタン
                    SegmentInfoView(
                        currentSegmentIndex = currentSegmentIndex,
                        segmentCount = currentProject?.segmentCount ?: 0,
                        onDelete = {
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }

        // ローディングオーバーレイ
        if (isLoading) {
            LoadingOverlay(
                progress = loadingProgress,
                processedSegments = processedSegments,
                totalSegments = currentProject?.segmentCount ?: 0
            )
        }

        // トースト
        if (showToast) {
            Toast(
                message = toastMessage,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // エラーメッセージ
        errorMessage?.let { error ->
            ErrorDialog(
                message = error,
                onDismiss = { viewModel.clearError() }
            )
        }

        // セグメント削除確認ダイアログ
        if (showDeleteDialog && currentProject != null) {
            DeleteConfirmationDialog(
                onConfirm = {
                    val segments = currentProject!!.getSortedSegments()
                    if (currentSegmentIndex in segments.indices) {
                        val segmentToDelete = segments[currentSegmentIndex]
                        viewModel.deleteSegment(segmentToDelete) { updatedProject ->
                            onProjectUpdated(updatedProject)
                        }
                    }
                    showDeleteDialog = false
                },
                onDismiss = {
                    showDeleteDialog = false
                }
            )
        }
    }
}

/**
 * ExoPlayerビュー
 *
 * @param exoPlayer ExoPlayerインスタンス
 * @param modifier Modifier
 */
@Composable
fun ExoPlayerView(
    exoPlayer: androidx.media3.exoplayer.ExoPlayer?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
        modifier = modifier
    )
}

/**
 * プレイヤーヘッダービュー
 *
 * iOS版参考: PlayerView.swift headerView
 *
 * レイアウト:
 * - [← Back]  プロジェクト名  [Export]
 * - セグメント数表示（例: "3/10"）
 *
 * @param project 現在のプロジェクト
 * @param currentSegmentIndex 現在のセグメントインデックス
 * @param onBack 戻るボタンのコールバック
 * @param onExport エクスポートボタンのコールバック
 */
@Composable
fun PlayerHeaderView(
    project: Project?,
    currentSegmentIndex: Int,
    onBack: () -> Unit,
    onExport: () -> Unit
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

            // セグメント数表示
            if (project != null) {
                Text(
                    text = "${currentSegmentIndex + 1} / ${project.segmentCount}",
                    color = Color.Yellow,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // エクスポートボタン
            IconButton(
                onClick = onExport,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = Color(0xFFFF9800), // オレンジ色
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Export",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // プロジェクト名
        if (project != null) {
            Text(
                text = project.name,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * プログレスバービュー
 *
 * iOS版参考: PlayerView.swift:352-420 (seekableProgressBar)
 *
 * 実装詳細:
 * - セグメント境界に黄色の縦線を表示
 * - タップでシーク
 * - 現在位置を青色で表示
 *
 * @param currentTime 現在の再生時刻（ミリ秒）
 * @param duration 総再生時間（ミリ秒）
 * @param segmentCount セグメント数
 * @param onSeek シーク時のコールバック
 */
@Composable
fun ProgressBarView(
    currentTime: Long,
    duration: Long,
    segmentCount: Int,
    onSeek: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 時間表示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentTime),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = formatTime(duration),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // プログレスバー
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val progress = (offset.x / size.width).coerceIn(0f, 1f)
                        val targetTime = (progress * duration).toLong()
                        onSeek(targetTime)
                    }
                }
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2

                // 背景バー（グレー）
                drawLine(
                    color = Color.Gray,
                    start = Offset(0f, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )

                // 再生済みバー（青色）
                if (duration > 0) {
                    val progress = (currentTime.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    drawLine(
                        color = Color(0xFF2196F3), // 青色
                        start = Offset(0f, centerY),
                        end = Offset(width * progress, centerY),
                        strokeWidth = 8f,
                        cap = StrokeCap.Round
                    )
                }

                // セグメント境界線（黄色）
                if (segmentCount > 1 && duration > 0) {
                    val segmentDuration = duration / segmentCount.toFloat()
                    for (i in 1 until segmentCount) {
                        val segmentProgress = (i * segmentDuration) / duration
                        val x = width * segmentProgress
                        drawLine(
                            color = Color.Yellow,
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }
    }
}

/**
 * 再生コントロールビュー
 *
 * iOS版参考: PlayerView.swift mainControls
 *
 * レイアウト:
 * - [⏮]  [▶️/⏸]  [⏭]
 *
 * @param isPlaying 再生中かどうか
 * @param onPlayPause 再生/一時停止ボタンのコールバック
 * @param onPrevious 前へボタンのコールバック
 * @param onNext 次へボタンのコールバック
 */
@Composable
fun PlaybackControlsView(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前へボタン
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(60.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(40.dp))

        // 再生/一時停止ボタン（大きく青色）
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = Color(0xFF2196F3), // 青色
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.width(40.dp))

        // 次へボタン
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(60.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * セグメント情報ビュー
 *
 * @param currentSegmentIndex 現在のセグメントインデックス
 * @param segmentCount セグメント数
 * @param onDelete 削除ボタンのコールバック
 */
@Composable
fun SegmentInfoView(
    currentSegmentIndex: Int,
    segmentCount: Int,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // セグメント番号
        Text(
            text = "Segment ${currentSegmentIndex + 1}",
            color = Color.Yellow,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 削除ボタン
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red.copy(alpha = 0.8f)
            ),
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Delete Segment",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * ローディングオーバーレイ
 *
 * iOS版参考: PlayerView.swift:133-225
 *
 * アニメーション:
 * - 回転アニメーション
 * - プログレスバー
 *
 * @param progress ローディング進捗（0.0 ~ 1.0）
 * @param processedSegments 処理済みセグメント数
 * @param totalSegments 総セグメント数
 */
@Composable
fun LoadingOverlay(
    progress: Float,
    processedSegments: Int,
    totalSegments: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 回転アニメーション
            val infiniteTransition = rememberInfiniteTransition(label = "loading")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Loading",
                tint = Color.White,
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer { rotationZ = rotation }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ローディングメッセージ
            Text(
                text = "Preparing playback...",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // セグメント処理状況
            Text(
                text = "$processedSegments / $totalSegments segments",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // プログレスバー
            Box(
                modifier = Modifier
                    .width(250.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF2196F3), // 青
                                    Color(0xFF9C27B0)  // 紫
                                )
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // パーセンテージ
            Text(
                text = "${(progress * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * トースト
 *
 * @param message 表示するメッセージ
 * @param modifier Modifier
 */
@Composable
fun Toast(
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
            modifier = Modifier.padding(16.dp),
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

/**
 * エラーダイアログ
 *
 * @param message エラーメッセージ
 * @param onDismiss 閉じる時のコールバック
 */
@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Error")
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

/**
 * セグメント削除確認ダイアログ
 *
 * @param onConfirm 確認ボタンのコールバック
 * @param onDismiss キャンセル時のコールバック
 */
@Composable
fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Delete Segment")
        },
        text = {
            Text(text = "Are you sure you want to delete this segment?")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Red
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * 時間をフォーマット（mm:ss）
 *
 * @param timeMs 時間（ミリ秒）
 * @return フォーマットされた時間文字列
 */
private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
