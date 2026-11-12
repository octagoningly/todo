package com.lirui.oneline.util

/**
 * 主页 FAB 长按拖动的位置换算（§68）。
 *
 * ★ 纯 Kotlin、不碰 Android：项目没有 Robolectric，带 Context 的逻辑本地测不了，
 *   所以「边界 / 归一化」这些能算的都放这里，UI 只负责把 px 交给 `Modifier.offset`。
 *
 * 坐标系：以 FAB **默认右下角位置**为原点，往左 / 往上的偏移是负值，
 * 合法区间恒为 `[minOffsetPx, 0]`。存盘用 0..1 的比例而不是像素 ——
 * 旋屏、换屏密度、恢复备份后都按当前屏幕重新换算，不会跑偏或出界。
 */
object FabPosition {

    /**
     * 某个方向上允许的最大负偏移（<= 0）。
     *
     * @param contentSizePx 内容区尺寸（`BoxWithConstraints` 的 maxWidth/maxHeight，已扣掉底栏）
     * @param fabSizePx     FAB 边长（恒 56dp，见 `AGENTS.md` §3）
     * @param marginPx      四周安全边距（16dp，与 M3 默认 FAB 位置一致）
     * @param reservedPx    该方向额外让出的高度（顶部留给状态栏 + 64dp 顶栏）
     */
    fun minOffset(
        contentSizePx: Float,
        fabSizePx: Float,
        marginPx: Float,
        reservedPx: Float = 0f
    ): Float = -(contentSizePx - fabSizePx - 2f * marginPx - reservedPx).coerceAtLeast(0f)

    /** 拖动位移累加后夹回合法区间。`minOffsetPx` 恒 <= 0，这里再兜一次底。 */
    fun clamp(currentPx: Float, deltaPx: Float, minOffsetPx: Float): Float =
        (currentPx + deltaPx).coerceIn(minOffsetPx.coerceAtMost(0f), 0f)

    /** px → 存盘比例。0 = 默认右下角，1 = 已拖到该方向的最远端。 */
    fun toFraction(offsetPx: Float, minOffsetPx: Float): Float =
        if (minOffsetPx >= 0f) 0f else (offsetPx / minOffsetPx).coerceIn(0f, 1f)

    /** 存盘比例 → 当前屏幕下的 px 偏移。 */
    fun toOffsetPx(fraction: Float, minOffsetPx: Float): Float =
        if (minOffsetPx >= 0f) 0f else fraction.coerceIn(0f, 1f) * minOffsetPx
}
