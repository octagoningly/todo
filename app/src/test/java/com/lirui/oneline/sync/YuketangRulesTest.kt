package com.lirui.oneline.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节树解析的纯逻辑单测。
 *
 * ★ 注意：`org.json` 在本地单测里是 **stub**（AGENTS.md §7），
 *   所以这里只测不依赖 JSONObject 的纯函数（[NativeProbeRunner.isHomeworkOrExam]
 *   / [NativeProbeRunner.isDeadlineUsable]）。带 JSONObject 的解析走真机探针验证。
 */
class YuketangRulesTest {

    @Test
    fun `homework and exam leaf types are the only importable ones`() {
        assertTrue(NativeProbeRunner.isHomeworkOrExam("6"))  // 作业
        assertTrue(NativeProbeRunner.isHomeworkOrExam("5"))  // 考试
        // 其余一律不算待办：0=视频 3=公告 4=讨论 8=课件
        assertFalse(NativeProbeRunner.isHomeworkOrExam("0"))
        assertFalse(NativeProbeRunner.isHomeworkOrExam("3"))
        assertFalse(NativeProbeRunner.isHomeworkOrExam("4"))
        assertFalse(NativeProbeRunner.isHomeworkOrExam("8"))
        assertFalse(NativeProbeRunner.isHomeworkOrExam(null))
        assertFalse(NativeProbeRunner.isHomeworkOrExam(""))
    }

    @Test
    fun `zero deadline means unlimited not epoch`() {
        // ★ 这是本轮最重要的一条：0 是「无限制」哨兵，绝不能当 1970 年渲染。
        assertFalse(NativeProbeRunner.isDeadlineUsable(0L))
        assertFalse(NativeProbeRunner.isDeadlineUsable(-1L))
        // 真实毫秒时间戳可用。
        assertTrue(NativeProbeRunner.isDeadlineUsable(1_782_431_606_000L))
        // 越界视为脏数据。
        assertFalse(NativeProbeRunner.isDeadlineUsable(1_000L))
        assertFalse(NativeProbeRunner.isDeadlineUsable(4_102_444_800_001L))
    }

    @Test
    fun `yuketang headers switch xtbz variant`() {
        val cloud = NativeProbeSpec.yuketangHeaders(mapOf("csrftoken" to "c"))
        assertTrue(cloud.any { it.first == "xtbz" && it.second == "cloud" })
        assertFalse(cloud.any { it.first == "x-client" })

        val ykt = NativeProbeSpec.yuketangHeaders(
            mapOf("csrftoken" to "c"), xtbz = "ykt", xClient = true, classroomId = "42"
        )
        assertTrue(ykt.any { it.first == "xtbz" && it.second == "ykt" })
        assertTrue(ykt.any { it.first == "x-client" && it.second == "web" })
        assertTrue(ykt.any { it.first == "classroom-id" && it.second == "42" })
    }
}
