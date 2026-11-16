package com.lirui.oneline.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针 JS 是字符串常量，编译期查不出拼接错/占位符漏替换，
 * 用本地单测把关键接口路径与关键词表钉住（AGENTS.md §2 第 4 级）。
 */
class ProbeJsTest {

    @Test
    fun `ketangpai probe contains semester, course and homework endpoints`() {
        val js = ProbeJs.build(SyncPlatform.KETANGPAI, "www.ketangpai.com")
        assertTrue(js.contains("CourseApi/semesterList"))
        assertTrue(js.contains("CourseApi/semesterCourseList"))
        assertTrue(js.contains("FutureV2/CourseMeans/getCourseContent"))
        assertTrue(js.contains("contenttype:4"))
        assertTrue(js.contains("contenttype:6"))
        assertTrue(js.contains("'ketangpai'"))
        // §73：跨域带 token 头会 CORS 预检失败，必须留同源/简化/v4 候选。
        assertTrue(js.contains("main-semesterList"))
        assertTrue(js.contains("api-simple"))
        assertTrue(js.contains("v4-semesterList"))
        assertTrue(js.contains("credentials"))
        // §74：Cookie 里的 token 必须用不触发 CORS 预检的方式传。
        assertTrue(js.contains("cookieTokenFound"))
        assertTrue(js.contains("api-bodyToken"))
        assertTrue(js.contains("api-queryToken"))
        assertTrue(js.contains("api-formToken"))
    }

    @Test
    fun `yuketang probe contains course, chapter and task endpoints`() {
        val js = ProbeJs.build(SyncPlatform.YUKETANG, "www.yuketang.cn")
        assertTrue(js.contains("user-courses"))
        assertTrue(js.contains("learn/course/chapter"))
        assertTrue(js.contains("user-tasks"))
        assertTrue(js.contains("exercise/list"))
        assertTrue(js.contains("'yuketang'"))
        // §72 回归：缺这个头，接口一律回 XTBZ IS REQUIRED。
        assertTrue(js.contains("xtbz"))
        assertTrue(js.contains("'cloud'"))
        assertTrue(js.contains("x-csrftoken"))
        // §73：章节树必须在 JS 里解析出 leaf_type 分布与绝对截止时间节点数。
        assertTrue(js.contains("leafTypeCounts"))
        assertTrue(js.contains("withDeadline"))
        assertTrue(js.contains("section_leaf_list"))
    }

    @Test
    fun `probe scans deadline keywords and writes to window`() {
        val js = ProbeJs.build(SyncPlatform.YUKETANG, "www.yuketang.cn")
        assertTrue(js.contains("__oneline_probe"))
        assertTrue(js.contains("endtime"))
        assertTrue(js.contains("deadline"))
        assertTrue(js.contains("homework"))
        assertTrue(js.contains("'done'"))
        // §73：完整响应不再被 800 字截断。
        assertTrue(js.contains("12000"))
        // 占位符必须全部替换掉，否则页面里跑起来是坏 JS。
        assertFalse(js.contains("__PLATFORM__"))
        assertFalse(js.contains("__HOST__"))
    }
}
