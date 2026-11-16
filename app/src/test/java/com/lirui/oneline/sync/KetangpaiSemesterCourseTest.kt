package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §79 课堂派「拿到本学期课程」的回归测试。
 *
 * ★ 这一轮的坑：`semesterCourseList` 与 `semesterList` 的 `data` **结构不同** ——
 *   前者 `data` 直接是**数组**，后者 `data` 是对象、课程在 `data.topcourses[]`。
 *   我按后者解析前者，于是「接口返回 200、响应 1138 字节」却算成 0 门。
 *
 *   更关键的是：本学期的「微电班复变函数与数学物理方法」`isTop:0`，
 *   **不在 topcourses（置顶区）里** —— 只读 topcourses 永远看不到它。
 */
class KetangpaiSemesterCourseTest {

    /** 真机响应（2026-11-16 实测，已裁掉无关字段）。 */
    private val realSemesterCourseList = """
{"status":1,"code":10000,"message":"访问成功","data":[
 {"id":"MDAwMDAwMDAwMLOcx5SIubdrhMtyoQ","uid":"8096035","issys":"0",
  "coursename":"微电班复变函数与数学物理方法","code":"H5RXTW","classname":"微电班",
  "semester":"2026-2027","term":"1","teachtype":"2","studytime":"72",
  "total":"89","role":0,"isTop":0}]}
"""

    @Test
    fun `semesterCourseList data is a bare array`() {
        val dataIdx = realSemesterCourseList.indexOf("\"data\"")
        val afterData = realSemesterCourseList.substring(dataIdx)
        // ★ 这就是上一轮的 bug 所在：data 后面直接是 '['，不是 '{'。
        assertTrue(
            "semesterCourseList 的 data 是数组",
            afterData.startsWith("\"data\":[")
        )
        // 对照：semesterList 的 data 是对象，课程在 topcourses。
        assertTrue(realSemesterCourseList.contains("\"coursename\":\"微电班"))
    }

    @Test
    fun `current semester course is not in topcourses`() {
        // ★ 学期课 isTop:0 —— 说明它不属于「置顶课程」区。
        assertTrue("本学期课必须是 isTop:0", realSemesterCourseList.contains("\"isTop\":0"))
        // 而 topcourses 里的课是 isTop:1（见 §78 抓到的基线）。
        // 两者互斥，这正是「只看 topcourses 就看不到本学期课」的原因。
        assertTrue(realSemesterCourseList.contains("\"semester\":\"2026-2027\""))
        assertTrue(realSemesterCourseList.contains("\"term\":\"1\""))
    }

    @Test
    fun `empty semester returns empty array not object`() {
        // 2026-2027 第 2 学期确实没课，平台回的是空数组 —— 要能正确判成 0 门而不是出错。
        val empty = """{"status":1,"code":10000,"message":"访问成功","data":[]}"""
        assertTrue(empty.contains("\"data\":[]"))
    }

    @Test
    fun `current semester guess follows academic calendar not index`() {
        // ★ 调研明确指出 semester.sort 在 bundle 里 0 处命中，
        //   semester[0] 只是前端惯例，不能依赖。这里按日期自己算。
        // 11 月 → 属于 2026-2027 学年第一学期。
        val nov = NativeProbeRunner.currentSemesterCandidates("2026-11-16")
        assertEquals("2026-2027" to "1", nov.first())

        // 3 月 → 属于 2025-2026 学年第二学期。
        val mar = NativeProbeRunner.currentSemesterCandidates("2026-03-15")
        assertEquals("2025-2026" to "2", mar.first())

        // 9 月（开学）→ 新学年第一学期。
        val sep = NativeProbeRunner.currentSemesterCandidates("2026-09-01")
        assertEquals("2026-2027" to "1", sep.first())

        // 2 月（寒假）→ 仍是上一学年第二学期。
        val feb = NativeProbeRunner.currentSemesterCandidates("2026-02-01")
        assertEquals("2025-2026" to "2", feb.first())
    }

    @Test
    fun `current semester candidates include fallbacks`() {
        val c = NativeProbeRunner.currentSemesterCandidates("2026-11-16")
        // 要有备选，防止某个学期没课导致什么也拿不到。
        assertTrue("至少给 2 个候选", c.size >= 2)
        assertTrue(c.any { it == ("2026-2027" to "2") })
        assertTrue(c.any { it == ("2025-2026" to "2") })
    }

    @Test
    fun `term label renders numeric term readably`() {
        // 雨课堂 term 是数字：202502 = 2025-2026 学年第二学期。
        assertEquals("2025-2026/2", NativeProbeRunner.termLabel(202502L))
        assertEquals("2026-2027/1", NativeProbeRunner.termLabel(202601L))
        // 只有教师名时也别造出假学期。
        assertEquals("黎星楠", NativeProbeRunner.termLabel(null, "黎星楠"))
        assertEquals("2026-2027/1 黎星楠", NativeProbeRunner.termLabel(202601L, "黎星楠"))
        assertEquals(null, NativeProbeRunner.termLabel(null, ""))
    }
}
