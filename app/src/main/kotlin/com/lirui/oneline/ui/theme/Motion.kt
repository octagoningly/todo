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

private const val MOTION_PREF = "oneline_settings"
private const val KEY_PAGE_MOTION = "page_motion"

/**
 * 「页面切换动效」总开关（设置 → 外观），默认开。
 *
 * ★ 读写放在这里而不是 `SettingsRepo`：本分支的 `SettingsRepo.kt` 有一行未提交的
 *   上架材料（`PRIVACY_POLICY_VERSION` 1→2），功能提交不能把它夹带进去。开关与
 *   `systemReduceMotion` / `OnelineMotionEasing` 同处一个文件，两条分支代码逐字一致。
 *   用的是**同一个** `oneline_settings`，不另建 prefs 文件。
 */
fun pageMotionEnabled(context: Context): Boolean =
    context.getSharedPreferences(MOTION_PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_PAGE_MOTION, true)

fun setPageMotionEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(MOTION_PREF, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_PAGE_MOTION, enabled).apply()
}
