package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课堂派 `isstudy` 页签映射的回归测试（§78）。
 *
 * ★ 这是本轮**最贵的 bug**：`isstudy` 看起来像个布尔开关，
 *   实际是「我教的 / 我协助的 / 我学的」三个 UI 页签的映射值，
 *   而且**学生页签对应的值是 "1"、教师页签才是 "0"**（直觉完全相反）。
 *
 *   我们全链路写死 `"0"`（教师页签），结果：
 *   - `topcourses` 还能容错回 5 门课，让人误以为「通了」；
 *   - `data.semester` 永远是空数组 → 按学期取明细拿不到东西；
 *   - 本学期的「复变函数」「数学物理方法」一门都不出现。
 *
 * 依据：课堂派 H5 线上 bundle 原文
 * `isstudy: "1"===isstudyTbs ? "0" : "2"===isstudyTbs ? "2" : "1"`，
 * 以及 `usertype` 学生为 `"0"`（页签「我学的」）。
 */
class KetangpaiIsstudyTest {

    private val cred = NativeProbeSpec.Credential(true, "T", "cookie:token")

    @Test
    fun `student tab maps to isstudy 1 not 0`() {
        val reqs = NativeProbeSpec.ketangpaiRequests(cred, null)
        val headerReq = reqs.first { it.name == "api-headerToken" }
        assertTrue(
            "学生页签必须是 isstudy=1，写成 0 会掉进教师页签",
            headerReq.body!!.contains("\"isstudy\":\"1\"")
        )
        assertFalse(
            "绝不能再写回 0（教师页签）",
            headerReq.body!!.contains("\"isstudy\":\"0\"")
        )
    }

    @Test
    fun `semester probe tries student tab first`() {
        val reqs = NativeProbeSpec.ketangpaiSemesterRequests(cred, null)
        // 第一条必须是学生页签，优先级最高。
        assertEquals("semesterList-isstudy1", reqs.first().name)
        assertTrue(reqs.first().body!!.contains("\"isstudy\":\"1\""))
    }

    @Test
    fun `semester probe keeps teacher tab as control group`() {
        val reqs = NativeProbeSpec.ketangpaiSemesterRequests(cred, null)
        // 教师页签必须保留，用来对照「换页签前后的差异」。
        val teacher = reqs.firstOrNull { it.name == "semesterList-teacher" }
        assertTrue("需保留教师页签做对照", teacher != null)
        assertTrue(teacher!!.body!!.contains("\"isstudy\":\"0\""))
    }

    @Test
    fun `semester probe asks for all semesters and current term`() {
        val reqs = NativeProbeSpec.ketangpaiSemesterRequests(cred, null)
        val all = reqs.firstOrNull { it.name == "semesterCourseList-all" }
        assertTrue("要能拉全部学期以确认账号下有哪些学期", all != null)
        // 空字符串 = 全部学期（前端搜索就是这么用的）。
        assertTrue(all!!.body!!.contains("\"semester\":\"\""))

        val current = reqs.firstOrNull { it.name == "semesterCourseList-2026-2027-1" }
        assertTrue("要显式查本学期 2026-2027 第 1 学期", current != null)
        assertTrue(current!!.body!!.contains("\"semester\":\"2026-2027\""))
        assertTrue(current.body!!.contains("\"term\":\"1\""))
    }

    @Test
    fun `every semester request carries reqtimestamp`() {
        // 平台自己的 axios 拦截器每次都注入毫秒时间戳，开源实现也要求带。
        NativeProbeSpec.ketangpaiSemesterRequests(cred, null).forEach { r ->
            assertTrue(
                "${r.name} 缺 reqtimestamp，可能被服务端拒绝",
                r.body!!.contains("reqtimestamp")
            )
        }
    }

    @Test
    fun `semester probe includes student main path simpleLists`() {
        val reqs = NativeProbeSpec.ketangpaiSemesterRequests(cred, null)
        val simple = reqs.firstOrNull { it.name == "simpleLists" }
        assertTrue("学生主路径 /courseApi/simpleLists 要单独打", simple != null)
        // ★ 小写 courseApi，与 CourseApi 是两个命名空间。
        assertTrue(simple!!.url.contains("/courseApi/simpleLists"))
    }
}
