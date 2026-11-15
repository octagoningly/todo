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
    }

    @Test
    fun `yuketang probe contains course, chapter and task endpoints`() {
        val js = ProbeJs.build(SyncPlatform.YUKETANG, "www.yuketang.cn")
        assertTrue(js.contains("user-courses"))
        assertTrue(js.contains("learn/course/chapter"))
        assertTrue(js.contains("user-tasks"))
        assertTrue(js.contains("exercise/list"))
        assertTrue(js.contains("'yuketang'"))
    }

    @Test
    fun `probe scans deadline keywords and writes to window`() {
        val js = ProbeJs.build(SyncPlatform.YUKETANG, "www.yuketang.cn")
        assertTrue(js.contains("__oneline_probe"))
        assertTrue(js.contains("endtime"))
        assertTrue(js.contains("deadline"))
        assertTrue(js.contains("homework"))
        assertTrue(js.contains("'done'"))
        // 占位符必须全部替换掉，否则页面里跑起来是坏 JS。
        assertFalse(js.contains("__PLATFORM__"))
        assertFalse(js.contains("__HOST__"))
    }
}
