package com.tashichi.clipflow.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*

/**
 * PermissionManager - カメラ・マイク・ストレージ権限の管理を行うユーティリティクラス
 *
 * iOS版の参考実装:
 * - CameraView.swift:237-252 (setupCamera)
 * - VideoManager (requestCameraPermission)
 */
object PermissionManager {

    /**
     * 必要な権限のリスト
     */
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    /**
     * カメラ権限が付与されているかを確認
     *
     * @param context アプリケーションコンテキスト
     * @return カメラ権限が付与されている場合true
     */
    fun checkCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * マイク（音声録音）権限が付与されているかを確認
     *
     * @param context アプリケーションコンテキスト
     * @return マイク権限が付与されている場合true
     */
    fun checkAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 全ての必要な権限が付与されているかを確認
     *
     * @param context アプリケーションコンテキスト
     * @return 全ての権限が付与されている場合true
     */
    fun checkAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * カメラとマイクの権限をリクエストするComposable関数
 *
 * Accompanist Permissionsライブラリを使用して、
 * Jetpack Composeで宣言的に権限リクエストを処理します。
 *
 * 使用例:
 * ```kotlin
 * RequestCameraPermissions(
 *     onPermissionsGranted = { /* カメラセットアップ処理 */ },
 *     onPermissionDenied = { /* エラー表示 */ }
 * )
 * ```
 *
 * @param onPermissionsGranted 全ての権限が付与された時に呼ばれるコールバック
 * @param onPermissionDenied 権限が拒否された時に呼ばれるコールバック
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestCameraPermissions(
    onPermissionsGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    // 複数の権限を同時に管理
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            // 全ての権限が付与されている
            onPermissionsGranted()
        } else if (permissionsState.permissions.any { it.status.shouldShowRationale }) {
            // ユーザーが以前に権限を拒否した場合、理由を表示する必要がある
            onPermissionDenied()
        } else if (permissionsState.permissions.any { !it.status.isGranted }) {
            // 権限リクエストを実行
            permissionsState.launchMultiplePermissionRequest()
        }
    }
}

/**
 * 個別の権限状態を管理するComposable関数
 *
 * より細かい制御が必要な場合に使用します。
 *
 * @return PermissionStateオブジェクト
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberCameraPermissionState(): MultiplePermissionsState {
    return rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )
}
