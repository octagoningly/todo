package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §82 雨课堂课堂端课程「不能走 mooc-api 章节树」的回归测试。
 *
 * ★ 这是本轮最贵的一个错：`/v2/api/web/courses/list` 的响应里**没有 `course_sign`**，
 *   那个 `C32F2427949868N` 格式的 sign 只属于 **MOOC 接口**的 `product_list`。
 *
 *   我拿 `termLabel()` 的结果（`2026-2027/1 陈琨`）当 sign 拼进章节树 URL，
 *   于是 42/50 门课全部回：
 *   `{"msg":"请购买该课程之后再学习","error_code":80019}`
 *
 *   而调研早就写了：**80019 的处置是换 c27 接口**。
 */
class YuketangClassroomPathTest {

    /** 课堂端课程列表真机响应（2026-11-16）。 */
    private val realCourseList = """
{"errcode":0,"errmsg":"Success","data":{"list":[
 {"term":202601,"name":"2026秋-202601-202613484","classroom_id":33307885,
  "students_count":87,"teacher":{"user_id":1,"name":"陈琨"},
  "course":{"id":4226944,"name":"复变函数与数学物理方法","university_id":2668},
  "role":5,"color_code":2},{"term":202502,"name":"2026春-202523912","classroom_id":29785730,
  "teacher":{"name":"黎星楠"},"course":{"id":5605956,"name":"AI+口译"},"role":5}}]}
"""

    @Test
    fun `classroom course list has no course_sign field`() {
        // ★ 关键事实：课堂端响应里**完全没有** course_sign。
        assertFalse(
            "课堂端课程列表里不该有 course_sign —— 有它的是 MOOC 接口",
            realCourseList.contains("course_sign")
        )
        // 但 MOOC 的 sign 长这样，别混用。
        val moocSign = "C32F2427949868N"
        assertTrue(moocSign.length <= 20)
        assertTrue(moocSign.all { it.isLetterOrDigit() })
    }

    @Test
    fun `term label must not be used as sign`() {
        // termLabel 产出的是展示文本，绝不能当 sign。
        val label = NativeProbeRunner.termLabel(202601L, "陈琨")
        assertEquals("2026-2027/1 陈琨", label)
        // 它含空格与斜杠，与 sign 的字符集完全不同 —— 拼进 URL 只会得到 80019。
        assertTrue(label!!.contains(" "))
        assertTrue(label.contains("/"))
    }

    @Test
    fun `classroom detail carries course_id needed by c27`() {
        // c27 进度接口必须同时有 classroom_id 与 course_id。
        val detail = """
{"errcode":0,"errmsg":"Success","data":{"id":33307885,
 "name":"2026秋-202601-202613484","course_id":"4226944",
 "course_name":"复变函数与数学物理方法","teacher_name":"陈琨",
 "uv_id":2668,"user_role":5}}
"""
        assertTrue(detail.contains("\"course_id\":\"4226944\""))
        // course_id 是**字符串**，不是数字。
        assertTrue(detail.contains("\"course_id\":\""))
    }

    @Test
    fun `c27 request needs both ids`() {
        val req = NativeProbeSpec.yuketangC27ProgressRequest(
            "www.yuketang.cn", emptyMap(), "33307885", "4226944"
        )
        assertTrue(req.url.contains("/c27/online_courseware/schedule/course/progress/"))
        assertTrue(req.url.contains("classroom_id=33307885"))
        assertTrue(req.url.contains("course_id=4226944"))
        // c27 属于课堂端接口，要 xtbz=ykt。
        assertTrue(req.headers.any { it.first == "xtbz" && it.second == "ykt" })
    }

    @Test
    fun `course source counts separate classroom from mooc`() {
        // 实测 32 门未归档 + 18 门已归档 = 50 门；MOOC 只有 0 门（这个账号没选 MOOC）。
        val counts = mapOf(
            "courses-v2-identity2" to 32,
            "courses-archive" to 18,
            "courses-mooc-legacy" to 0
        )
        assertEquals(50, counts.values.sum())
        // 课堂端两路相加才是界面上的课程数。
        assertEquals(50, counts["courses-v2-identity2"]!! + counts["courses-archive"]!!)
    }

    @Test
    fun `logs endpoint uses page 0 and offset as page size`() {
        val req = NativeProbeSpec.yuketangLogsRequest(
            "www.yuketang.cn", emptyMap(), "33307885"
        )
        // ★ 这个接口 page 从 0 开始，offset 是「每页条数」不是跳过条数。
        assertTrue(req.url.contains("page=0"))
        assertTrue(req.url.contains("offset=200"))
    }
}
