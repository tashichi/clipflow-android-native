package com.tashichi.clipflow.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * DebugLogger - 統合テスト・デバッグ用のログユーティリティ
 *
 * Section_7参考: デバッグ・トラブルシューティング
 *
 * 主な機能:
 * - メモリ使用量監視
 * - ファイルディスクリプタ監視
 * - 処理時間計測
 * - テストケースロギング
 */
object DebugLogger {

    private const val TAG = "ClipFlow_Debug"

    // 初期メモリ（ベースライン計測用）
    private var initialMemory: Long = 0L
    private var operationStartTime: Long = 0L

    /**
     * セッション開始時にベースラインを記録
     */
    fun startSession() {
        initialMemory = getUsedMemoryBytes()
        Log.i(TAG, "=== Debug Session Started ===")
        logSystemInfo()
        logMemoryUsage("Session Start")
        logFileDescriptorCount("Session Start")
    }

    /**
     * システム情報をログ出力
     */
    private fun logSystemInfo() {
        val runtime = Runtime.getRuntime()
        Log.i(TAG, "[System Info]")
        Log.i(TAG, "  Max Memory: ${runtime.maxMemory() / 1024 / 1024}MB")
        Log.i(TAG, "  Available Processors: ${runtime.availableProcessors()}")
        Log.i(TAG, "  Android Version: ${android.os.Build.VERSION.SDK_INT}")
        Log.i(TAG, "  Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /**
     * 操作開始を記録（処理時間計測用）
     *
     * @param operationName 操作名
     */
    fun startOperation(operationName: String) {
        operationStartTime = System.currentTimeMillis()
        Log.i(TAG, ">>> $operationName started")
        logMemoryUsage("Before $operationName")
    }

    /**
     * 操作終了を記録（処理時間計測用）
     *
     * @param operationName 操作名
     */
    fun endOperation(operationName: String) {
        val elapsed = System.currentTimeMillis() - operationStartTime
        Log.i(TAG, "<<< $operationName completed in ${elapsed}ms")
        logMemoryUsage("After $operationName")
        logFileDescriptorCount("After $operationName")

        // GCを促進して真のメモリリークを検出
        System.gc()
        Thread.sleep(100)
        logMemoryUsage("After GC ($operationName)")
    }

    /**
     * メモリ使用量をログ出力
     *
     * @param label ラベル
     */
    fun logMemoryUsage(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val usedMemoryMB = usedMemory / 1024 / 1024
        val maxMemoryMB = runtime.maxMemory() / 1024 / 1024
        val deltaMemory = usedMemory - initialMemory
        val deltaMemoryMB = deltaMemory / 1024 / 1024
        val usagePercent = (usedMemory.toFloat() / runtime.maxMemory()) * 100

        Log.i(TAG, "[Memory] $label: ${usedMemoryMB}MB / ${maxMemoryMB}MB (${String.format("%.1f", usagePercent)}%)")
        Log.i(TAG, "[Memory] Delta from start: ${if (deltaMemoryMB >= 0) "+" else ""}${deltaMemoryMB}MB")

        // 警告チェック
        if (usagePercent > 80) {
            Log.w(TAG, "[Memory] WARNING: High memory usage (${String.format("%.1f", usagePercent)}%)")
        }
    }

    /**
     * ファイルディスクリプタ数をログ出力
     *
     * @param label ラベル
     */
    fun logFileDescriptorCount(label: String) {
        val fdCount = getOpenFileDescriptorCount()
        Log.i(TAG, "[FD] $label: $fdCount / 1024 open file descriptors")

        // 警告チェック
        if (fdCount > 800) {
            Log.w(TAG, "[FD] WARNING: High file descriptor count ($fdCount)")
        }
    }

    /**
     * テストケース開始をログ出力
     *
     * @param testName テスト名
     * @param description 説明
     */
    fun logTestCaseStart(testName: String, description: String) {
        Log.i(TAG, "")
        Log.i(TAG, "╔══════════════════════════════════════════════════")
        Log.i(TAG, "║ TEST: $testName")
        Log.i(TAG, "║ $description")
        Log.i(TAG, "╚══════════════════════════════════════════════════")
        startOperation("Test: $testName")
    }

    /**
     * テストケース成功をログ出力
     *
     * @param testName テスト名
     */
    fun logTestCaseSuccess(testName: String) {
        endOperation("Test: $testName")
        Log.i(TAG, "✅ $testName: PASSED")
        Log.i(TAG, "")
    }

    /**
     * テストケース失敗をログ出力
     *
     * @param testName テスト名
     * @param reason 失敗理由
     */
    fun logTestCaseFailure(testName: String, reason: String) {
        endOperation("Test: $testName")
        Log.e(TAG, "❌ $testName: FAILED")
        Log.e(TAG, "   Reason: $reason")
        Log.e(TAG, "")
    }

    /**
     * セグメント処理をログ出力
     *
     * @param index セグメントインデックス
     * @param total 総セグメント数
     * @param details 詳細情報
     */
    fun logSegmentProcessing(index: Int, total: Int, details: String = "") {
        val progressPercent = ((index + 1).toFloat() / total) * 100
        Log.d(TAG, "[Segment ${index + 1}/$total] ${String.format("%.1f", progressPercent)}% $details")

        // 10セグメントごとにメモリチェック
        if ((index + 1) % 10 == 0) {
            logMemoryUsage("After processing ${index + 1} segments")
        }
    }

    /**
     * エラーをログ出力
     *
     * @param operation 操作名
     * @param error エラー
     */
    fun logError(operation: String, error: Throwable) {
        Log.e(TAG, "╔══════════════════════════════════════════════════")
        Log.e(TAG, "║ ERROR in $operation")
        Log.e(TAG, "║ Type: ${error.javaClass.simpleName}")
        Log.e(TAG, "║ Message: ${error.message}")
        Log.e(TAG, "╚══════════════════════════════════════════════════")
        Log.e(TAG, "Stack trace: ${error.stackTraceToString()}")
        logMemoryUsage("At error ($operation)")
        logFileDescriptorCount("At error ($operation)")
    }

    /**
     * アプリ状態のスナップショットをログ出力
     *
     * @param context アプリケーションコンテキスト
     * @param projectSegmentCount プロジェクトのセグメント数
     */
    fun logAppStateSnapshot(context: Context, projectSegmentCount: Int) {
        Log.i(TAG, "╔══════════════════════════════════════════════════")
        Log.i(TAG, "║ APP STATE SNAPSHOT")
        Log.i(TAG, "╠══════════════════════════════════════════════════")
        Log.i(TAG, "║ Project Segments: $projectSegmentCount")
        logMemoryUsage("Current")
        logFileDescriptorCount("Current")

        // ストレージ情報
        val filesDir = context.filesDir
        val availableSpace = filesDir.freeSpace / 1024 / 1024
        val totalSpace = filesDir.totalSpace / 1024 / 1024
        Log.i(TAG, "║ Storage: ${availableSpace}MB / ${totalSpace}MB available")

        // セグメントファイル数
        val segmentFiles = filesDir.listFiles()?.filter { it.name.startsWith("segment_") } ?: emptyList()
        val totalSegmentSize = segmentFiles.sumOf { it.length() } / 1024 / 1024
        Log.i(TAG, "║ Segment Files: ${segmentFiles.size} files (${totalSegmentSize}MB)")

        Log.i(TAG, "╚══════════════════════════════════════════════════")
    }

    /**
     * 統合テストチェックリストをログ出力
     */
    fun logIntegrationTestChecklist() {
        Log.i(TAG, "")
        Log.i(TAG, "=== INTEGRATION TEST CHECKLIST ===")
        Log.i(TAG, "1. [ ] New project creation")
        Log.i(TAG, "2. [ ] Record 5 segments")
        Log.i(TAG, "3. [ ] Seamless playback")
        Log.i(TAG, "4. [ ] Delete 1 segment")
        Log.i(TAG, "5. [ ] Playback after deletion")
        Log.i(TAG, "6. [ ] Export to gallery")
        Log.i(TAG, "")
        Log.i(TAG, "=== ERROR HANDLING CHECKS ===")
        Log.i(TAG, "7. [ ] Storage insufficient error")
        Log.i(TAG, "8. [ ] Camera permission denied error")
        Log.i(TAG, "9. [ ] Corrupted file error")
        Log.i(TAG, "")
        Log.i(TAG, "=== PERFORMANCE CHECKS ===")
        Log.i(TAG, "10. [ ] 100+ segments without crash")
        Log.i(TAG, "11. [ ] No memory leak")
        Log.i(TAG, "12. [ ] Smooth UI transitions")
        Log.i(TAG, "")
    }

    /**
     * デバイス能力情報をログ出力
     *
     * @param context アプリケーションコンテキスト
     */
    fun logDeviceCapabilities(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalRamMB = memoryInfo.totalMem / 1024 / 1024
        val availableRamMB = memoryInfo.availMem / 1024 / 1024
        val threshold = memoryInfo.threshold / 1024 / 1024
        val isLowMemory = memoryInfo.lowMemory

        Log.i(TAG, "╔══════════════════════════════════════════════════")
        Log.i(TAG, "║ DEVICE CAPABILITIES")
        Log.i(TAG, "╠══════════════════════════════════════════════════")
        Log.i(TAG, "║ Total RAM: ${totalRamMB}MB")
        Log.i(TAG, "║ Available RAM: ${availableRamMB}MB")
        Log.i(TAG, "║ Low Memory Threshold: ${threshold}MB")
        Log.i(TAG, "║ Is Low Memory: $isLowMemory")
        Log.i(TAG, "║ Recommended Max Segments: ${getRecommendedSegmentLimit(totalRamMB)}")
        Log.i(TAG, "╚══════════════════════════════════════════════════")
    }

    /**
     * 推奨最大セグメント数を計算
     *
     * @param totalRamMB 総RAM（MB）
     * @return 推奨最大セグメント数
     */
    private fun getRecommendedSegmentLimit(totalRamMB: Long): Int {
        return when {
            totalRamMB < 1024 -> 20   // 1GB未満
            totalRamMB < 3072 -> 50   // 3GB未満
            totalRamMB < 6144 -> 100  // 6GB未満
            else -> 200               // 6GB以上
        }
    }

    /**
     * 使用中メモリをバイト数で取得
     *
     * @return 使用中メモリ（バイト）
     */
    private fun getUsedMemoryBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * 開いているファイルディスクリプタ数を取得
     *
     * @return ファイルディスクリプタ数（取得失敗時は-1）
     */
    private fun getOpenFileDescriptorCount(): Int {
        return try {
            val fdDir = File("/proc/self/fd")
            fdDir.listFiles()?.size ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * セッション終了時のサマリーを出力
     */
    fun endSession() {
        Log.i(TAG, "")
        Log.i(TAG, "=== Debug Session Ended ===")
        logMemoryUsage("Session End")
        logFileDescriptorCount("Session End")

        val deltaMemory = getUsedMemoryBytes() - initialMemory
        val deltaMemoryMB = deltaMemory / 1024 / 1024

        if (deltaMemoryMB > 50) {
            Log.w(TAG, "[POTENTIAL MEMORY LEAK] Session memory increase: ${deltaMemoryMB}MB")
        } else {
            Log.i(TAG, "[OK] Session memory increase: ${deltaMemoryMB}MB (within acceptable range)")
        }
        Log.i(TAG, "")
    }
}
