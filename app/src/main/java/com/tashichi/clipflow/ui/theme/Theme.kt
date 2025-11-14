package com.tashichi.clipflow.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * ClipFlow Dark Color Scheme
 *
 * iOS版参考: docs/iOS_ClipFlow_Specification.md:380-382
 *
 * カラースキーム:
 * - primary: ClipFlowBlue (青色) - 再生ボタン、プログレスバー
 * - secondary: ClipFlowPurple (紫色) - プログレスグラデーション
 * - tertiary: ClipFlowOrange (オレンジ色) - エクスポートボタン
 * - error: ClipFlowRed (赤色) - 削除ボタン、警告
 * - background: ClipFlowBlack (黒色) - 全画面背景
 * - surface: ClipFlowDarkGray (ダークグレー) - カード背景
 */
private val ClipFlowDarkColorScheme = darkColorScheme(
    // プライマリカラー（青色）- 再生ボタン、プログレスバー
    primary = ClipFlowBlue,
    onPrimary = Color.White,
    primaryContainer = ClipFlowBlue.copy(alpha = 0.3f),
    onPrimaryContainer = Color.White,

    // セカンダリカラー（紫色）- プログレスグラデーション
    secondary = ClipFlowPurple,
    onSecondary = Color.White,
    secondaryContainer = ClipFlowPurple.copy(alpha = 0.3f),
    onSecondaryContainer = Color.White,

    // ターシャリカラー（オレンジ色）- エクスポートボタン
    tertiary = ClipFlowOrange,
    onTertiary = Color.White,
    tertiaryContainer = ClipFlowOrange.copy(alpha = 0.3f),
    onTertiaryContainer = Color.White,

    // エラーカラー（赤色）- 削除ボタン、警告
    error = ClipFlowRed,
    onError = Color.White,
    errorContainer = ClipFlowRed.copy(alpha = 0.3f),
    onErrorContainer = Color.White,

    // 背景カラー（黒色）
    background = ClipFlowBlack,
    onBackground = Color.White,

    // サーフェスカラー（ダークグレー）- カード背景
    surface = ClipFlowDarkGray,
    onSurface = Color.White,
    surfaceVariant = ClipFlowGray.copy(alpha = 0.2f),
    onSurfaceVariant = ClipFlowGray,

    // その他
    outline = ClipFlowGray,
    outlineVariant = ClipFlowGray.copy(alpha = 0.5f)
)

/**
 * ClipFlow Light Color Scheme (未使用 - ClipFlowは常にDark themeを使用)
 *
 * 将来的にLight themeをサポートする場合のために残しています
 */
private val ClipFlowLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * ClipFlow Theme - ClipFlowアプリ専用のMaterial Design 3テーマ
 *
 * iOS版参考: docs/iOS_ClipFlow_Specification.md:380-382
 *
 * 特徴:
 * - 常にDark themeを使用（iOS版と同様）
 * - Dynamic color無効化（独自のカラースキームを使用）
 * - ClipFlow専用のカラーパレット
 *
 * @param darkTheme ダークテーマを使用するか（デフォルト: true）
 * @param dynamicColor ダイナミックカラーを使用するか（デフォルト: false）
 * @param content コンテンツComposable
 */
@Composable
fun ClipFlowTheme(
    darkTheme: Boolean = true,  // ClipFlowは常にDark themeを使用
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,  // ClipFlowは独自のカラースキームを使用
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> ClipFlowDarkColorScheme
        else -> ClipFlowLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}