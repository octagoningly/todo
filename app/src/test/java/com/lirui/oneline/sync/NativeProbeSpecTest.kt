package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生探针的**请求规格**单测。
 *
 * ★ 这些断言钉的是「请求长什么样」，不是「平台会怎么答」：
 *   路径拼错、头名写错、token 漏带，编译期完全看不出来，
 *   真机跑一轮才发现白跑 —— 正是 §72/§73 各浪费一轮的原因。
 */
class NativeProbeSpecTest {

    // ---- 课堂派凭证提取 ----

    @Test
    fun `ketangpai token prefers explicit cookie token`() {
        val c = NativeProbeSpec.ketangpaiCredential(
            mapOf("token" to "abc123", "SERVERID" to "x")
        )
        assertTrue(c.tokenFound)
        assertEquals("abc123", c.token)
        assertEquals("cookie:token", c.source)
    }

    @Test
    fun `ketangpai token falls back to legacy remember cookie`() {
        val c = NativeProbeSpec.ketangpaiCredential(
            mapOf("ketangpai_home_remember" to "legacy-token")
        )
        assertTrue(c.tokenFound)
        assertEquals("legacy-token", c.token)
        assertEquals("cookie:ketangpai_home_remember", c.source)
    }

    @Test
    fun `ketangpai token can be read from userSession json`() {
        val raw = """{"code":0,"data":{"token":"from-json","uid":"1"}}"""
        assertEquals("from-json", NativeProbeSpec.tokenFromUserSessionJson(raw))
        val flat = """{"token":"flat"}"""
        assertEquals("flat", NativeProbeSpec.tokenFromUserSessionJson(flat))
    }

    @Test
    fun `ketangpai token missing is reported not guessed`() {
        val c = NativeProbeSpec.ketangpaiCredential(mapOf("SERVERID" to "x"))
        assertFalse(c.tokenFound)
        assertEquals("none", c.source)
        assertEquals("", c.token)
    }

    // ---- 课堂派请求规格 ----

    @Test
    fun `ketangpai offers all four token transports`() {
        val cred = NativeProbeSpec.Credential(true, "T", "cookie:token")
        val reqs = NativeProbeSpec.ketangpaiRequests(cred, "token=T")
        val modes = reqs.map { it.tokenMode }.toSet()
        // header 是页面内做不到的那条；其余三条是对照组。
        assertEquals(setOf("header", "none", "body", "query", "form"), modes)
        // 自定义头方式必须真的把 token 放进头里。
        val headerReq = reqs.first { it.tokenMode == "header" }
        assertTrue(headerReq.headers.any { it.first == "token" && it.second == "T" })
        assertTrue(headerReq.url.contains("/CourseApi/semesterList"))
        assertEquals("POST", headerReq.method)
        // query 方式不能把裸 token 拼进去而不编码。
        val queryReq = reqs.first { it.tokenMode == "query" }
        assertTrue(queryReq.url.contains("token=T"))
    }

    @Test
    fun `ketangpai carries cookie header when available`() {
        val cred = NativeProbeSpec.Credential(true, "T", "cookie:token")
        val reqs = NativeProbeSpec.ketangpaiRequests(cred, "token=T; SERVERID=s1")
        assertTrue(reqs.all { r -> r.headers.any { it.first == "Cookie" } })
    }

    @Test
    fun `ketangpai content requests ask for homework and exam`() {
        val cred = NativeProbeSpec.Credential(true, "T", "cookie:token")
        val reqs = NativeProbeSpec.ketangpaiContentRequests(cred, null, "900")
        assertEquals(listOf("homework", "exam"), reqs.map { it.name })
        assertTrue(reqs[0].body!!.contains("\"contenttype\":4"))
        assertTrue(reqs[1].body!!.contains("\"contenttype\":6"))
        assertTrue(reqs[0].body!!.contains("\"courseid\":\"900\""))
        assertTrue(reqs.all { it.url.endsWith("/FutureV2/CourseMeans/getCourseContent") })
    }

    // ---- 雨课堂请求规格 ----

    @Test
    fun `yuketang course list uses classroom endpoints not mooc`() {
        // ★ §76：主口径必须是课堂端的 courses/list 与 classroom_archive，
        //   MOOC 的 user-courses 只作对照组。
        val reqs = NativeProbeSpec.yuketangCourseListRequests(
            "www.yuketang.cn", mapOf("csrftoken" to "c")
        )
        val names = reqs.map { it.name }
        assertEquals(
            listOf("courses-v2-identity2", "courses-archive", "courses-mooc-legacy"),
            names
        )
        val main = reqs.first { it.name == "courses-v2-identity2" }
        assertTrue(main.url.endsWith("/v2/api/web/courses/list?identity=2"))
        val archive = reqs.first { it.name == "courses-archive" }
        assertTrue(archive.url.endsWith("/v2/api/web/classroom_archive"))
        // 课堂端接口用 xtbz=ykt（错误码 80005 的官方建议）。
        assertTrue(main.headers.any { it.first == "xtbz" && it.second == "ykt" })
        assertTrue(main.headers.any { it.first == "x-client" })
    }

    @Test
    fun `yuketang headers carry csrftoken and cookie`() {
        val reqs = NativeProbeSpec.yuketangCourseListRequests(
            "www.yuketang.cn", mapOf("csrftoken" to "c", "sessionid" to "s")
        )
        val main = reqs.first()
        assertTrue(main.headers.any { it.first == "x-csrftoken" && it.second == "c" })
        assertTrue(main.headers.any { it.first == "Cookie" && it.second.contains("sessionid=s") })
    }

    // ---- 编码 ----

    @Test
    fun `encode escapes plus and non ascii`() {
        assertEquals("a%2Bb", NativeProbeSpec.encode("a+b"))
        assertEquals("a%20b", NativeProbeSpec.encode("a b"))
        assertEquals("abc-_.~", NativeProbeSpec.encode("abc-_.~"))
        // 中文按 UTF-8 百分号编码，不能留裸字符。
        val encoded = NativeProbeSpec.encode("作业")
        assertTrue(encoded.startsWith("%"))
        assertFalse(encoded.contains("作"))
    }

    @Test
    fun `cookie header joins non blank pairs`() {
        assertEquals(
            "a=1; b=2",
            NativeProbeSpec.cookieHeader(mapOf("a" to "1", "b" to "2", "c" to ""))
        )
        assertNull(NativeProbeSpec.cookieHeader(emptyMap()))
    }
}
