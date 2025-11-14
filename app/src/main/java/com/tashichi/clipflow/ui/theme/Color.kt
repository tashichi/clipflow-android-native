package com.tashichi.clipflow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ClipFlow カラースキーム定義
 *
 * iOS版参考: docs/iOS_ClipFlow_Specification.md:380-382
 *
 * カラーパレット:
 * - 背景: Color.black
 * - ボタン背景: Color.black.opacity(0.7)
 * - アクセント: Color.orange (エクスポート関連)
 * - 警告: Color.red (削除、録画中)
 * - 成功: Color.green (成功トースト)
 * - セグメント情報: Color.yellow (セグメント番号、境界線)
 * - プログレス: LinearGradient([.blue, .purple]) (ローディングバー)
 */

// プライマリカラー（メインテーマカラー）
val ClipFlowBlue = Color(0xFF2196F3)           // iOS: .blue
val ClipFlowPurple = Color(0xFF9C27B0)         // iOS: .purple

// アクセントカラー
val ClipFlowOrange = Color(0xFFFF9800)         // iOS: Color.orange (エクスポート)
val ClipFlowRed = Color(0xFFF44336)            // iOS: Color.red (削除、警告)
val ClipFlowGreen = Color(0xFF4CAF50)          // iOS: Color.green (成功)
val ClipFlowYellow = Color(0xFFFFEB3B)         // iOS: Color.yellow (セグメント情報)

// ダークテーマカラー
val ClipFlowBlack = Color(0xFF000000)          // iOS: Color.black (背景)
val ClipFlowDarkGray = Color(0xFF1E1E1E)       // カード背景
val ClipFlowGray = Color(0xFF9E9E9E)           // テキスト、アイコン

// ボタン背景（半透明黒）
val ClipFlowButtonBackground = Color(0xB3000000)  // iOS: Color.black.opacity(0.7)

// 従来のカラー（互換性のため残す）
val Purple80 = ClipFlowPurple
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)