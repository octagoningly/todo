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

    @Test
    fun `leaf info requires two numeric segments and trailing slash`() {
        val reqs = NativeProbeSpec.yuketangLeafInfoRequests(
            "www.yuketang.cn", emptyMap(), "30547802", "84646252", "3078"
        )
        // §73 踩的坑：单段 /leaf_info/<cid>/ 必然 404，必须是两段 + 末尾斜杠。
        val main = reqs.first { it.name == "leafInfo-2seg" }
        assertTrue(main.url.contains("/leaf_info/30547802/84646252/"))
        assertFalse(main.url.contains("/leaf_info/30547802/?"))
        assertTrue(main.url.contains("uv_id=3078"))
        // 干净形态不带 query，用来单独验证路由。
        val bare = reqs.first { it.name == "leafInfo-bare" }
        assertTrue(bare.url.endsWith("/leaf_info/30547802/84646252/"))
    }

    @Test
    fun `exercise list tries single segment form`() {
        val reqs = NativeProbeSpec.yuketangExerciseRequests(
            "www.yuketang.cn", emptyMap(), "999", "84646252", null, "3078"
        )
        assertEquals(1, reqs.size)
        assertTrue(reqs.first().url.contains("/get_exercise_list/999/"))
        assertTrue(reqs.first().url.contains("uv_id=3078"))
    }

    @Test
    fun `exercise list adds two segment form when sku known`() {
        val reqs = NativeProbeSpec.yuketangExerciseRequests(
            "www.yuketang.cn", emptyMap(), "999", "84646252", "777", "3078"
        )
        assertEquals(2, reqs.size)
        assertTrue(reqs.any { it.url.contains("/get_exercise_list/84646252/777/") })
    }

    @Test
    fun `exam cover hits v exam cover path`() {
        val req = NativeProbeSpec.yuketangExamCoverRequest(
            "www.yuketang.cn", emptyMap(), "1945460", "24211265"
        )
        assertTrue(req.url.contains("/v/exam/cover?exam_id=1945460"))
        assertTrue(req.url.contains("classroom_id=24211265"))
    }
}
