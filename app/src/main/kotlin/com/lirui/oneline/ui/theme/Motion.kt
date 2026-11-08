package com.lirui.oneline.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing

/**
 * 系统是否关掉了动画（无障碍里的「减少动态效果」/ 开发者选项把动画时长设成 0）。
 * 读不到或抛异常时按「没关」处理 —— 宁可多放一点动效，也别把开关变成哑的。
 *
 * ★ 2026-11-08（§62）：与 main 同口径。main 是从 `FocusScreen.kt` 抽出来的共用判定，
 *   本分支基线较旧、`FocusScreen` 里还没有这套判定，因此这里只给 tab 转场 / 顶栏标题用，
 *   不动 `FocusScreen`（按 AGENTS §4「本分支缺什么就别带什么」）。
 */
fun systemReduceMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
} catch (_: Exception) {
    false
}

/**
 * 页面 / 文字切换的统一缓动。
 *
 * 参数参考 `T8RIN/ImageToolbox`（Apache-2.0）`core/ui/.../animation/Easing.kt` 的
 * `FancyTransitionEasing = CubicBezierEasing(0.48f, 0.19f, 0.05f, 1.03f)`：末端 y > 1，
 * 有轻微过冲回弹但不晃。★ 只抄曲线参数，不引入该项目的任何依赖。
 */
val OnelineMotionEasing = CubicBezierEasing(0.48f, 0.19f, 0.05f, 1.03f)
