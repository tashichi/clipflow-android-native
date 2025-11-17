package com.tashichi.clipflow.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.SegmentTimeRange
import com.tashichi.clipflow.data.model.VideoSegment
import com.tashichi.clipflow.util.VideoComposer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * PlayerViewModel - 動画再生機能のビジネスロジックを管理
 *
 * iOS版の参考実装:
 * - PlayerView.swift:847-855 (setupPlayer - プレイヤー初期化)
 * - PlayerView.swift:858-941 (loadComposition - Composition読み込み)
 * - PlayerView.swift:1125-1186 (handleSegmentDeletion - セグメント削除)
 * - PlayerView.swift:1195-1207 (startTimeObserver - タイムオブザーバー)
 * - PlayerView.swift:423-473 (handleSeekGesture - シーク機能)
 *
 * 主な機能:
 * - シームレス再生（Composition統合）
 * - 再生制御（再生/一時停止/次へ/前へ/シーク）
 * - セグメント管理（削除）
 * - プログレス表示（ローディング、再生時刻）
 * - エクスポート機能
 */
class PlayerViewModel : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val TIME_OBSERVER_INTERVAL_MS = 100L // 0.1秒間隔
    }

    // Context（ApplicationContextを使用）
    private var _context: Context? = null

    // ExoPlayer インスタンス
    private var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer? get() = _exoPlayer

    // VideoComposer
    private var videoComposer: VideoComposer? = null

    // Composition
    private var composition: Composition? = null

    // セグメント時間範囲
    private var segmentTimeRanges: List<SegmentTimeRange> = emptyList()

    // --- UI状態 ---

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTime = MutableStateFlow(0L)
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _processedSegments = MutableStateFlow(0)
    val processedSegments: StateFlow<Int> = _processedSegments.asStateFlow()

    private val _showToast = MutableStateFlow(false)
    val showToast: StateFlow<Boolean> = _showToast.asStateFlow()

    private val _toastMessage = MutableStateFlow("")
    val toastMessage: StateFlow<String> = _toastMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // シームレス再生を使用するか
    private val _useSeamlessPlayback = MutableStateFlow(true)

    /**
     * Contextを初期化
     *
     * @param context アプリケーションコンテキスト
     */
    fun initialize(context: Context) {
        _context = context.applicationContext
        videoComposer = VideoComposer(context.applicationContext)
    }

    /**
     * プロジェクトを設定してプレイヤーをセットアップ
     *
     * iOS版参考: PlayerView.swift:847-855 (setupPlayer)
     *
     * @param project 再生対象のプロジェクト
     */
    fun setProject(project: Project) {
        _project.value = project
        setupPlayer()
    }

    /**
     * プレイヤーをセットアップ
     *
     * iOS版参考: PlayerView.swift:847-855 (setupPlayer)
     *
     * 処理フロー:
     * 1. useSeamlessPlayback = true がデフォルト
     * 2. シームレス再生の場合: loadComposition()
     * 3. 個別再生の場合: loadCurrentSegment()
     */
    private fun setupPlayer() {
        val context = _context ?: run {
            Log.e(TAG, "Context not initialized")
            return
        }

        val currentProject = _project.value ?: run {
            Log.e(TAG, "Project not set")
            return
        }

        if (currentProject.segments.isEmpty()) {
            Log.w(TAG, "No segments to play")
            _errorMessage.value = "No segments to play"
            return
        }

        // ExoPlayerを作成（メモリ最適化設定付き）
        if (_exoPlayer == null) {
            // LoadControlを作成してバッファサイズを最適化
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2000,  // 最小バッファ: 2秒
                    /* maxBufferMs = */ 5000,  // 最大バッファ: 5秒（デフォルトは50秒）
                    /* bufferForPlaybackMs = */ 1000,  // 再生開始バッファ: 1秒
                    /* bufferForPlaybackAfterRebufferMs = */ 2000  // 再バッファ後: 2秒
                )
                .build()

            _exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)  // メモリ使用量を削減
                .build()
                .apply {
                // プレイヤーリスナーを設定
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "Player state: IDLE")
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Player state: BUFFERING")
                            }
                            Player.STATE_READY -> {
                                _duration.value = this@apply.duration
                                Log.d(TAG, "Player state: READY, duration: ${this@apply.duration}ms")
                            }
                            Player.STATE_ENDED -> {
                                _isPlaying.value = false
                                Log.d(TAG, "Player state: ENDED")
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        Log.d(TAG, "Is playing changed: $isPlaying")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer error occurred", error)
                        Log.e(TAG, "Error code: ${error.errorCode}")
                        Log.e(TAG, "Error message: ${error.message}")
                        Log.e(TAG, "Error type: ${error.javaClass.simpleName}")
                        _errorMessage.value = "Playback error: ${error.message}"
                        _isPlaying.value = false
                        _isLoading.value = false
                    }
                })
            }
        }

        // シームレス再生を開始
        if (_useSeamlessPlayback.value) {
            loadComposition()
        } else {
            loadCurrentSegment()
        }

        // タイムオブザーバーを開始
        startTimeObserver()
    }

    /**
     * Compositionを読み込んでプレイヤーにセット
     *
     * iOS版参考: PlayerView.swift:858-941 (loadComposition)
     *
     * 処理フロー:
     * 1. ローディング状態を開始（isLoadingComposition = true）
     * 2. createCompositionWithProgress() を呼び出し
     * 3. プログレスコールバックで進捗更新
     * 4. getSegmentTimeRanges() でセグメント時間範囲を取得
     * 5. AVPlayerItemを作成し、AVPlayerにセット
     * 6. ローディング完了（0.5秒後に非表示）
     */
    private fun loadComposition() {
        val currentProject = _project.value ?: return
        val composer = videoComposer ?: return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _loadingProgress.value = 0f
                _processedSegments.value = 0

                Log.d(TAG, "Creating composition...")

                // Compositionを作成（進捗付き）
                composition = composer.createComposition(currentProject) { processed, total ->
                    _processedSegments.value = processed
                    // 最大80%まで（iOS版の仕様に合わせる）
                    _loadingProgress.value = (processed.toFloat() / total.toFloat()) * 0.8f
                }

                if (composition == null) {
                    _errorMessage.value = "Failed to create composition"
                    _isLoading.value = false
                    return@launch
                }

                Log.d(TAG, "Composition created successfully")

                // セグメント時間範囲を取得
                segmentTimeRanges = composer.getSegmentTimeRanges(currentProject)
                Log.d(TAG, "Segment time ranges: ${segmentTimeRanges.size}")

                // プレイヤーにMediaItemsを設定（個別セグメントとして再生）
                loadSegmentsToPlayer(currentProject)

                // 総再生時間を設定
                val totalDuration = composer.getTotalDuration(currentProject)
                _duration.value = totalDuration
                Log.d(TAG, "Total duration: ${totalDuration}ms")

                // ローディング完了（80% → 100%）
                _loadingProgress.value = 1.0f

                // 0.5秒後にローディングを非表示
                delay(500)
                _isLoading.value = false

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load composition", e)
                _errorMessage.value = "Failed to load composition: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    /**
     * セグメントをExoPlayerに読み込む
     *
     * @param project プロジェクト
     */
    private fun loadSegmentsToPlayer(project: Project) {
        val context = _context ?: return
        val player = _exoPlayer ?: return

        Log.d(TAG, "🔄 Loading segments to player...")
        Log.d(TAG, "   Context filesDir: ${context.filesDir.absolutePath}")

        val sortedSegments = project.getSortedSegments()
        Log.d(TAG, "📊 Total segments to load: ${sortedSegments.size}")

        val mediaItems = sortedSegments.mapIndexedNotNull { index, segment ->
            Log.d(TAG, "   [Segment $index] URI: ${segment.uri}")

            val file = File(context.filesDir, segment.uri)
            Log.d(TAG, "   [Segment $index] Full path: ${file.absolutePath}")
            Log.d(TAG, "   [Segment $index] File exists: ${file.exists()}")

            if (file.exists()) {
                Log.d(TAG, "   [Segment $index] ✅ File exists (${file.length()} bytes)")
                try {
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    Log.d(TAG, "   [Segment $index] ✅ MediaItem created: ${mediaItem.mediaId}")
                    mediaItem
                } catch (e: Exception) {
                    Log.e(TAG, "   [Segment $index] ❌ Failed to create MediaItem: ${e.message}", e)
                    null
                }
            } else {
                Log.w(TAG, "   [Segment $index] ❌ File NOT found: ${segment.uri}")
                Log.w(TAG, "   [Segment $index] Expected path: ${file.absolutePath}")
                null
            }
        }

        Log.d(TAG, "📦 Valid media items created: ${mediaItems.size}/${sortedSegments.size}")

        if (mediaItems.isEmpty()) {
            Log.e(TAG, "❌ No valid media items to play!")
            _errorMessage.value = "No valid segments to play"
            return
        }

        Log.d(TAG, "🎬 Setting ${mediaItems.size} media items to player...")
        try {
            Log.d(TAG, "   Calling player.setMediaItems()...")
            player.setMediaItems(mediaItems)
            Log.d(TAG, "   ✅ setMediaItems() completed")

            Log.d(TAG, "   Calling player.prepare()...")
            player.prepare()
            Log.d(TAG, "   ✅ Player prepared successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to prepare player: ${e.message}", e)
            _errorMessage.value = "Failed to prepare player: ${e.message}"
        }
    }

    /**
     * 現在のセグメントを個別に読み込む（フォールバック用）
     *
     * iOS版参考: PlayerView.swift:968-1012 (loadCurrentSegment)
     */
    private fun loadCurrentSegment() {
        val context = _context ?: return
        val player = _exoPlayer ?: return
        val currentProject = _project.value ?: return

        val sortedSegments = currentProject.getSortedSegments()
        if (sortedSegments.isEmpty()) {
            Log.w(TAG, "No segments to load")
            return
        }

        val currentIndex = _currentSegmentIndex.value.coerceIn(0, sortedSegments.size - 1)
        val segment = sortedSegments[currentIndex]

        val file = File(context.filesDir, segment.uri)
        if (!file.exists()) {
            Log.e(TAG, "Segment file not found: ${segment.uri}")
            _errorMessage.value = "Segment file not found"
            return
        }

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        player.prepare()

        Log.d(TAG, "Loaded segment ${currentIndex + 1}/${sortedSegments.size}")
    }

    /**
     * タイムオブザーバーを開始
     *
     * iOS版参考: PlayerView.swift:1195-1207 (startTimeObserver)
     *
     * 0.1秒間隔で現在の再生時刻を更新し、
     * シームレス再生の場合は現在のセグメントインデックスも更新
     */
    private fun startTimeObserver() {
        viewModelScope.launch {
            while (isActive) {
                _exoPlayer?.let { player ->
                    _currentTime.value = player.currentPosition

                    // シームレス再生の場合、現在のセグメントインデックスを更新
                    if (_useSeamlessPlayback.value) {
                        updateCurrentSegmentIndex()
                    }
                }
                delay(TIME_OBSERVER_INTERVAL_MS)
            }
        }
    }

    /**
     * 現在の再生時刻から、どのセグメントを再生中かを判定
     *
     * iOS版参考: PlayerView.swift:944-956 (updateCurrentSegmentIndex)
     */
    private fun updateCurrentSegmentIndex() {
        val currentTimeMs = _currentTime.value
        val index = segmentTimeRanges.indexOfFirst { it.contains(currentTimeMs) }

        if (index != -1 && index != _currentSegmentIndex.value) {
            _currentSegmentIndex.value = index
            Log.d(TAG, "Current segment index updated to: $index")
        }
    }

    /**
     * 指定されたセグメントを再生
     *
     * @param index セグメントインデックス
     */
    fun playSegment(index: Int) {
        val player = _exoPlayer ?: return
        val currentProject = _project.value ?: return

        val segmentCount = currentProject.segmentCount
        if (index !in 0 until segmentCount) {
            Log.w(TAG, "Invalid segment index: $index")
            return
        }

        _currentSegmentIndex.value = index

        if (_useSeamlessPlayback.value && segmentTimeRanges.isNotEmpty()) {
            // シームレス再生: 対応する時刻にシーク
            val timeRange = segmentTimeRanges.getOrNull(index)
            if (timeRange != null) {
                player.seekTo(timeRange.startTimeMs)
                player.play()
            }
        } else {
            // 個別再生: セグメントを切り替え
            player.seekTo(index, 0)
            player.play()
        }

        Log.d(TAG, "Playing segment $index")
    }

    /**
     * 再生を一時停止
     */
    fun pausePlayback() {
        _exoPlayer?.pause()
        Log.d(TAG, "Playback paused")
    }

    /**
     * 再生を再開
     */
    fun resumePlayback() {
        _exoPlayer?.play()
        Log.d(TAG, "Playback resumed")
    }

    /**
     * 再生/一時停止をトグル
     */
    fun togglePlayback() {
        if (_isPlaying.value) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    /**
     * 次のセグメントに移動
     */
    fun nextSegment() {
        val currentProject = _project.value ?: return
        val currentIndex = _currentSegmentIndex.value
        val nextIndex = (currentIndex + 1).coerceAtMost(currentProject.segmentCount - 1)

        if (nextIndex != currentIndex) {
            playSegment(nextIndex)
        }
    }

    /**
     * 前のセグメントに移動
     */
    fun previousSegment() {
        val currentIndex = _currentSegmentIndex.value
        val previousIndex = (currentIndex - 1).coerceAtLeast(0)

        if (previousIndex != currentIndex) {
            playSegment(previousIndex)
        }
    }

    /**
     * 指定された位置にシーク
     *
     * iOS版参考: PlayerView.swift:423-473 (handleSeekGesture)
     *
     * @param positionMs シーク先の位置（ミリ秒）
     */
    fun seekTo(positionMs: Long) {
        val player = _exoPlayer ?: return

        if (_useSeamlessPlayback.value && segmentTimeRanges.isNotEmpty()) {
            // タップ位置に対応するセグメントを特定
            val targetSegmentIndex = segmentTimeRanges.indexOfFirst { it.contains(positionMs) }

            if (targetSegmentIndex != -1) {
                _currentSegmentIndex.value = targetSegmentIndex
                player.seekTo(positionMs)
                Log.d(TAG, "Seeked to ${positionMs}ms (segment $targetSegmentIndex)")
            }
        } else {
            player.seekTo(positionMs)
        }
    }

    /**
     * セグメントを直接指定してシーク（プログレスバータップ）
     *
     * @param segmentIndex セグメントインデックス
     */
    fun seekToSegment(segmentIndex: Int) {
        val currentProject = _project.value ?: return

        if (segmentIndex in 0 until currentProject.segmentCount) {
            playSegment(segmentIndex)
        }
    }

    /**
     * セグメントを削除
     *
     * iOS版参考: PlayerView.swift:1125-1186 (handleSegmentDeletion)
     *
     * 処理フロー:
     * 1. シームレス再生中の場合、個別再生に切り替え
     * 2. セグメントを削除（Projectから）
     * 3. 0.1秒後、プロジェクトの更新を確認
     * 4. currentSegmentIndexを調整（範囲外にならないように）
     * 5. プレイヤーを再読み込み
     * 6. 元がシームレス再生なら、0.3秒後に再度シームレス再生に戻る
     *
     * @param segment 削除するセグメント
     * @param onDeleted 削除完了時のコールバック（更新されたProjectを渡す）
     */
    fun deleteSegment(segment: VideoSegment, onDeleted: (Project) -> Unit) {
        val context = _context ?: return
        val currentProject = _project.value ?: return

        viewModelScope.launch {
            try {
                // 最後の1セグメントは削除できない
                if (currentProject.segments.size <= 1) {
                    _errorMessage.value = "Cannot delete the last segment"
                    return@launch
                }

                // シームレス再生を一時停止
                val wasUsingSeamlessPlayback = _useSeamlessPlayback.value
                if (wasUsingSeamlessPlayback) {
                    _useSeamlessPlayback.value = false
                }

                // プレイヤーを停止
                _exoPlayer?.pause()

                // セグメントを削除
                val updatedProject = currentProject.deleteSegment(segment)

                // ファイルを削除
                val file = File(context.filesDir, segment.uri)
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted segment file: ${segment.uri}")
                }

                // プロジェクトを更新
                _project.value = updatedProject

                // 0.1秒待機
                delay(100)

                // currentSegmentIndexを調整
                val newSegmentCount = updatedProject.segmentCount
                if (_currentSegmentIndex.value >= newSegmentCount) {
                    _currentSegmentIndex.value = (newSegmentCount - 1).coerceAtLeast(0)
                }

                // プレイヤーを再読み込み
                if (wasUsingSeamlessPlayback) {
                    loadCurrentSegment()
                    // 0.3秒後にシームレス再生に戻る
                    delay(300)
                    _useSeamlessPlayback.value = true
                    loadComposition()
                } else {
                    loadCurrentSegment()
                }

                // コールバックを呼び出し
                onDeleted(updatedProject)

                // トーストを表示
                showToast("Segment deleted")

                Log.d(TAG, "Segment deleted successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete segment", e)
                _errorMessage.value = "Failed to delete segment: ${e.message}"
            }
        }
    }

    /**
     * トーストメッセージを表示
     *
     * @param message 表示するメッセージ
     */
    private fun showToast(message: String) {
        viewModelScope.launch {
            _toastMessage.value = message
            _showToast.value = true
            delay(2000) // 2秒間表示
            _showToast.value = false
        }
    }

    /**
     * エラーメッセージをクリア
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * ViewModelが破棄される時にリソースを解放
     */
    override fun onCleared() {
        super.onCleared()
        _exoPlayer?.release()
        _exoPlayer = null
        composition = null
        segmentTimeRanges = emptyList()
        Log.d(TAG, "ViewModel cleared")
    }
}
