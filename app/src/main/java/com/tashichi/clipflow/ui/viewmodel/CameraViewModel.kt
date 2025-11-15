package com.tashichi.clipflow.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.VideoSegment
import com.tashichi.clipflow.data.repository.ProjectRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * CameraViewModel - カメラ撮影機能のビジネスロジックを管理
 *
 * iOS版の参考実装:
 * - CameraView.swift:260-306 (recordOneSecondVideo - 1秒録画機能)
 * - CameraView.swift:254-258 (toggleCamera - カメラ切り替え)
 * - CameraView.swift:308-338 (toggleTorch - ライト機能)
 * - VideoManager (setupCamera, cameraPermissionGranted)
 *
 * 主な機能:
 * - 1秒間の自動録画
 * - 前後カメラ切り替え
 * - フラッシュライト制御
 * - セグメント保存
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        private const val RECORDING_DURATION_MS = 1000L // 1秒
    }

    // ProjectRepository
    private val repository = ProjectRepository(application)

    // カメラとビデオキャプチャのインスタンス
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // UI状態
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _cameraSelector = MutableStateFlow(CameraSelector.DEFAULT_BACK_CAMERA)
    val cameraSelector: StateFlow<CameraSelector> = _cameraSelector.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _showSuccessToast = MutableStateFlow(false)
    val showSuccessToast: StateFlow<Boolean> = _showSuccessToast.asStateFlow()

    private val _toastMessage = MutableStateFlow("")
    val toastMessage: StateFlow<String> = _toastMessage.asStateFlow()

    /**
     * プロジェクトを設定
     *
     * @param project 撮影対象のプロジェクト
     */
    fun setProject(project: Project) {
        _currentProject.value = project
    }

    /**
     * カメラをセットアップ
     *
     * iOS版参考: VideoManager.setupCamera() (推定実装)
     *
     * @param context アプリケーションコンテキスト
     * @param lifecycleOwner ライフサイクルオーナー
     * @param preview カメラプレビュー
     */
    fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        preview: Preview
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // VideoCapture use case を作成
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()

                videoCapture = VideoCapture.withOutput(recorder)

                // 既存のバインディングを解除
                cameraProvider?.unbindAll()

                // カメラをバインド
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    _cameraSelector.value,
                    preview,
                    videoCapture
                )

                Log.d(TAG, "Camera setup completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 1秒間の動画を録画
     *
     * iOS版参考: CameraView.swift:260-306 (recordOneSecondVideo)
     *
     * 処理フロー:
     * 1. isRecording = true で録画状態を表示
     * 2. videoManager.recordOneSecond() を呼び出し
     * 3. 内部で1秒タイマーを起動し、自動停止
     * 4. 録画完了後、VideoSegmentを作成
     * 5. onRecordingComplete コールバックを呼び出し
     * 6. 成功トーストを1.5秒間表示
     *
     * @param context アプリケーションコンテキスト
     * @param onSegmentRecorded セグメント録画完了時のコールバック
     */
    fun startRecording(
        context: Context,
        onSegmentRecorded: (VideoSegment) -> Unit
    ) {
        val currentVideoCapture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized")
            return
        }

        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return
        }

        try {
            _isRecording.value = true
            Log.d(TAG, "[TIMER] Recording requested at ${System.currentTimeMillis()}")

            // ファイル名を生成（Unix timestamp）
            val timestamp = System.currentTimeMillis()
            val outputFile = File(
                context.filesDir,
                "segment_$timestamp.mp4"
            )

            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            // 録画開始
            Log.d(TAG, "[TIMER] Preparing recording...")
            recording = currentVideoCapture.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            val startTime = System.currentTimeMillis()
                            Log.d(TAG, "[TIMER] Recording STARTED at $startTime")

                            // 録画が実際に開始されてから1秒タイマーを起動
                            viewModelScope.launch {
                                try {
                                    Log.d(TAG, "[TIMER] Starting 1-second countdown...")
                                    delay(RECORDING_DURATION_MS)

                                    val stopTime = System.currentTimeMillis()
                                    val actualDuration = stopTime - startTime
                                    Log.d(TAG, "[TIMER] Timer completed. Actual duration: ${actualDuration}ms")
                                    Log.d(TAG, "[TIMER] Calling stopRecording() at $stopTime")

                                    stopRecording()
                                } catch (e: Exception) {
                                    Log.e(TAG, "[TIMER] Error during recording timer", e)
                                    stopRecording()
                                    _isRecording.value = false
                                }
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            val finalizeTime = System.currentTimeMillis()
                            Log.d(TAG, "[TIMER] Recording FINALIZED at $finalizeTime")

                            if (recordEvent.hasError()) {
                                Log.e(TAG, "[ERROR] Recording error: ${recordEvent.error}")
                                Log.e(TAG, "[ERROR] Error code: ${recordEvent.error}")
                                _isRecording.value = false
                            } else {
                                val fileSize = if (outputFile.exists()) outputFile.length() else 0
                                Log.d(TAG, "[SUCCESS] Recording saved: ${outputFile.absolutePath}")
                                Log.d(TAG, "[SUCCESS] File size: $fileSize bytes")

                                // VideoSegmentを作成
                                val facing = if (_cameraSelector.value == CameraSelector.DEFAULT_BACK_CAMERA) {
                                    "back"
                                } else {
                                    "front"
                                }

                                val project = _currentProject.value
                                val order = (project?.segments?.size ?: 0) + 1

                                val segment = VideoSegment(
                                    id = timestamp,
                                    uri = outputFile.name,
                                    timestamp = timestamp,
                                    facing = facing,
                                    order = order
                                )

                                Log.d(TAG, "[SUCCESS] VideoSegment created: order=$order, facing=$facing")

                                // コールバックを呼び出し
                                onSegmentRecorded(segment)

                                // プロジェクトを更新
                                project?.let {
                                    viewModelScope.launch {
                                        try {
                                            val updatedProject = it.copy(
                                                segments = it.segments + segment,
                                                lastModified = System.currentTimeMillis()
                                            )
                                            repository.updateProject(updatedProject)
                                            _currentProject.value = updatedProject
                                            Log.d(TAG, "[SUCCESS] Project updated with new segment")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "[ERROR] Failed to update project", e)
                                        }
                                    }
                                }

                                // 成功トーストを表示
                                showToast("Segment $order recorded")

                                _isRecording.value = false
                            }
                        }
                        is VideoRecordEvent.Status -> {
                            // 録画中のステータス更新（オプション）
                            Log.v(TAG, "[STATUS] Recording duration: ${recordEvent.recordingStats.recordedDurationNanos / 1_000_000}ms")
                        }
                    }
                }

            Log.d(TAG, "[TIMER] Recording object created, waiting for Start event...")

        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to start recording", e)
            _isRecording.value = false
        }
    }

    /**
     * 録画を停止
     *
     * iOS版参考: CameraView.swift:260-306 (1秒後の自動停止処理)
     */
    fun stopRecording() {
        val currentRecording = recording
        if (currentRecording != null) {
            Log.d(TAG, "[TIMER] Stopping recording...")
            currentRecording.stop()
            recording = null
            Log.d(TAG, "[TIMER] Recording stopped successfully")
        } else {
            Log.w(TAG, "[TIMER] stopRecording() called but recording is null")
        }
    }

    /**
     * カメラを切り替え（前面 ↔ 背面）
     *
     * iOS版参考: CameraView.swift:254-258 (toggleCamera)
     * VideoManager内部:
     * - session.stopRunning()
     * - 現在の入力デバイスを削除
     * - 反対のカメラデバイスを取得（.back ↔ .front）
     * - 新しい入力デバイスを追加
     * - session.startRunning()
     *
     * @param context アプリケーションコンテキスト
     * @param lifecycleOwner ライフサイクルオーナー
     * @param preview カメラプレビュー
     */
    fun toggleCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        preview: Preview
    ) {
        // カメラセレクタを切り替え
        _cameraSelector.value = if (_cameraSelector.value == CameraSelector.DEFAULT_BACK_CAMERA) {
            // フロントカメラに切り替えた場合、トーチをオフにする
            _isTorchOn.value = false
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // カメラを再バインド
        setupCamera(context, lifecycleOwner, preview)
    }

    /**
     * フラッシュライト（トーチ）を切り替え
     *
     * iOS版参考: CameraView.swift:308-338 (toggleTorch)
     *
     * 実装詳細:
     * - バックカメラの場合のみ有効
     * - device.lockForConfiguration()
     * - device.torchMode = .off / .on
     * - device.unlockForConfiguration()
     *
     * UI実装:
     * - アイコン: flashlight.on.fill / flashlight.off.fill
     * - 色: ON時は黄色、OFF時はグレー
     * - 位置: 録画ボタンの左側
     */
    fun toggleTorch() {
        // フロントカメラの場合はトーチを使用できない
        if (_cameraSelector.value != CameraSelector.DEFAULT_BACK_CAMERA) {
            Log.w(TAG, "Torch is not available on front camera")
            return
        }

        val newTorchState = !_isTorchOn.value
        camera?.cameraControl?.enableTorch(newTorchState)
        _isTorchOn.value = newTorchState

        Log.d(TAG, "Torch ${if (newTorchState) "enabled" else "disabled"}")
    }

    /**
     * トーストメッセージを表示
     *
     * iOS版参考: CameraView.swift:71-78 (成功トースト)
     * - transition: .scale.combined(with: .opacity)
     * - animation: .easeInOut(duration: 0.3)
     * - 表示時間: 1.5秒
     *
     * @param message 表示するメッセージ
     */
    private fun showToast(message: String) {
        viewModelScope.launch {
            _toastMessage.value = message
            _showSuccessToast.value = true
            delay(1500) // 1.5秒間表示
            _showSuccessToast.value = false
        }
    }

    /**
     * ViewModelが破棄される時にリソースを解放
     */
    override fun onCleared() {
        super.onCleared()
        stopRecording()
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
    }
}
