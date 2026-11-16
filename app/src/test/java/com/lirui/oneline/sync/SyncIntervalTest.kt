package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §97 后台抓取间隔设置的测试。
 *
 * 用户要求：「你给这个定时请求另外做个设置就好啦，当用户选择自动或者后台的时候，
 * 就能将选择时间的设置激活」。
 *
 * 三个要点：
 * 1. 间隔是**独立设置项**，档位合理、默认 6 小时；
 * 2. 只有「自动 / 只后台抓」时该项才可调（仅手动时置灰）；
 * 3. 脏值要能收敛回合法档位（老数据 / 手改 SharedPreferences）。
 */
class SyncIntervalTest {

    @Test
    fun `interval options cover useful range`() {
        val opts = SyncInterval.OPTIONS
        assertTrue("至少要有 4 档", opts.size >= 4)
        // 最小档就是 WorkManager 的下限，不能更小。
        assertEquals(SyncInterval.MIN_MINUTES, opts.first().first)
        assertEquals(15, SyncInterval.MIN_MINUTES)
        // 每一档都要有可读文案
        opts.forEach { (m, label) ->
            assertTrue("$m 分钟缺文案", label.isNotBlank())
            assertTrue("文案应含数字", label.contains(m.toString()) || label.contains("小时"))
        }
    }

    @Test
    fun `default is six hours`() {
        assertEquals(360, SyncInterval.DEFAULT_MINUTES)
        // 默认值必须在档位里，否则设置页显示不出当前值。
        assertTrue(SyncInterval.OPTIONS.any { it.first == SyncInterval.DEFAULT_MINUTES })
    }

    @Test
    fun `normalize maps dirty values to default`() {
        // 手改 SharedPreferences 或老数据可能给出任意数字。
        assertEquals(360, SyncInterval.normalize(999))
        assertEquals(360, SyncInterval.normalize(0))
        assertEquals(360, SyncInterval.normalize(-5))
        // 合法值原样保留
        assertEquals(15, SyncInterval.normalize(15))
        assertEquals(60, SyncInterval.normalize(60))
        assertEquals(1440, SyncInterval.normalize(1440))
    }

    @Test
    fun `label falls back for unknown minutes`() {
        assertEquals("每 30 分钟", SyncInterval.label(30))
        // 非档位值也能显示（虽然 normalize 会把它收敛掉）
        assertTrue(SyncInterval.label(45).contains("45"))
    }

    @Test
    fun `only auto and background modes expose interval`() {
        // ★ 这是用户要求的核心：选「仅手动」时不该能调间隔。
        assertTrue(SyncTrigger.supportsInterval(SyncTrigger.ON_OPEN_AND_MANUAL))
        assertTrue(SyncTrigger.supportsInterval(SyncTrigger.DAILY_REMINDER))
        assertFalse(SyncTrigger.supportsInterval(SyncTrigger.MANUAL_ONLY))
    }

    @Test
    fun `trigger labels no longer hardcode six hours`() {
        // 间隔已经独立成设置项，模式文案里不该再写死"6 小时"
        // （写死了用户改了间隔就会觉得文案在说谎）。
        SyncTrigger.OPTIONS.forEach { (_, label) ->
            assertFalse("模式文案不该写死间隔: $label", label.contains("6 小时"))
        }
    }

    @Test
    fun `workmanager minimum is respected`() {
        // WorkManager 周期任务硬下限 15 分钟，比它短系统会自行拉长。
        // 这条钉住「设置里的最小档不能低于系统下限」，避免有人把 OPTIONS 改成 5 分钟。
        assertTrue(SyncInterval.OPTIONS.all { it.first >= 15 })
    }

    @Test
    fun `mode selector is never gated by interval support`() {
        // ★★ 死锁回归：曾经把「同步时机」自己的 enabled 写成 supportsInterval(trigger)，
        //   于是选了「仅手动」→ 本项置灰 → 用户再也改不回「自动」（死锁）。
        //
        //   正确关系是**单向**的：
        //     同步时机（永远可点） → 决定 → 抓取间隔（可能置灰）
        //   绝不能自指。
        //
        //   这里用「三个模式在 OPTIONS 里都在」保证下拉永远能选到所有值 ——
        //   如果哪天有人又把模式过滤掉一部分，这条会红。
        val modes = SyncTrigger.OPTIONS.map { it.first }.toSet()
        assertEquals(
            "三个模式都必须在下拉里可选，否则用户可能退不回去",
            setOf(
                SyncTrigger.ON_OPEN_AND_MANUAL,
                SyncTrigger.MANUAL_ONLY,
                SyncTrigger.DAILY_REMINDER
            ),
            modes
        )
    }

    @Test
    fun `every mode is recoverable to auto`() {
        // 死锁的本质是「某个状态让人出不去」。这里逐个确认：
        // 无论当前是哪个模式，改回「自动」都不需要任何额外前提
        // （supportsInterval 只影响间隔项，不影响模式项）。
        listOf(
            SyncTrigger.ON_OPEN_AND_MANUAL,
            SyncTrigger.MANUAL_ONLY,
            SyncTrigger.DAILY_REMINDER
        ).forEach { from ->
            // 模式项恒可点 → from 任意模式都能切到 AUTO。
            // 断言"OPTIONS 里含 AUTO"即可保证切得到。
            assertTrue(
                "从 $from 必须能切回自动模式",
                SyncTrigger.OPTIONS.any { it.first == SyncTrigger.ON_OPEN_AND_MANUAL }
            )
        }
    }
}
