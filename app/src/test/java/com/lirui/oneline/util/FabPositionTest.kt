package com.lirui.oneline.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FAB 拖动的边界与归一化（§68）。
 *
 * 纯函数：不碰 Context / View / Compose，所以「拖不出屏幕」「旋屏后仍按同一比例
 * 落位」这些结论能被 JVM 单测钉死；手势本身只能靠编译 + 真机。
 */
class FabPositionTest {

    @Test
    fun minOffsetLeavesMarginOnBothSides() {
        // 内容宽 1000，FAB 56，左右各留 16 → 最多左移 1000 - 56 - 32 = 912
        assertEquals(-912f, FabPosition.minOffset(1000f, 56f, 16f), 0.001f)
        // 垂直还要让出顶部 120（状态栏 + 64dp 顶栏）
        assertEquals(-792f, FabPosition.minOffset(1000f, 56f, 16f, 120f), 0.001f)
    }

    @Test
    fun clampNeverLeavesTheBox() {
        val min = -912f
        assertEquals(-100f, FabPosition.clamp(0f, -100f, min), 0.001f)
        assertEquals(min, FabPosition.clamp(0f, -5_000f, min), 0.001f)
        // 往右下拖不会越过默认位置，也不会把 FAB 顶出屏幕
        assertEquals(0f, FabPosition.clamp(-100f, 500f, min), 0.001f)
        // 已经在最左端，继续往左不累加（否则松手会突然跳回来）
        assertEquals(min, FabPosition.clamp(min, -300f, min), 0.001f)
    }

    @Test
    fun fractionRoundTripSurvivesScreenSizeChange() {
        val min = -912f
        val saved = FabPosition.toFraction(-456f, min)
        assertEquals(0.5f, saved, 0.001f)
        // 旋屏 / 换机后可用范围变成一半，比例不变 → px 等比缩放，不出界
        assertEquals(-228f, FabPosition.toOffsetPx(saved, -456f), 0.001f)
        assertEquals(0f, FabPosition.toOffsetPx(0f, min), 0.001f)
        assertEquals(min, FabPosition.toOffsetPx(1f, min), 0.001f)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0f, FabPosition.toFraction(0f, -912f), 0.001f)
        assertEquals(1f, FabPosition.toFraction(-912f, -912f), 0.001f)
        assertEquals(1f, FabPosition.toFraction(-2_000f, -912f), 0.001f)
        assertEquals(0f, FabPosition.toOffsetPx(-1f, -912f), 0.001f)
        assertEquals(-912f, FabPosition.toOffsetPx(2f, -912f), 0.001f)
    }

    @Test
    fun tinyScreenCollapsesToDefaultPosition() {
        // 内容区比 FAB + 边距还小：可用范围为 0，不能除零、不能拖出屏幕
        val min = FabPosition.minOffset(40f, 56f, 16f)
        assertEquals(0f, min, 0.001f)
        assertEquals(0f, FabPosition.toFraction(0f, min), 0.001f)
        assertEquals(0f, FabPosition.toOffsetPx(1f, min), 0.001f)
        assertEquals(0f, FabPosition.clamp(0f, -100f, min), 0.001f)
    }
}
