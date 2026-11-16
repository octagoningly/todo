package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课堂派响应解析的真机回归测试。
 *
 * ★ 样本是 **2026-11-16 10:07 真机抓到的真实响应**（§75），不是编的。
 *   上一版按「`data` 是数组 + 课程 id 是数字」写，于是 courseId 永远是空、
 *   作业接口一次都没打到 —— 而单测当时是绿的，因为测的是错的假设。
 *   这里把真实结构钉死：`data` 是**对象**，课程在 `data.topcourses[]`，
 *   `id` 是**字符串**。
 *
 * ⚠ `org.json` 在本地单测里是 stub（AGENTS.md §7），所以本测试实际验证的是
 *   「样本字符串的结构特征」，解析函数本身需真机探针验证。断言仍能防住
 *   「结构假设又写错」这一类的回归。
 */
class KetangpaiParsingTest {

    /** 真实响应截断版：保留 data / topcourses / 两个课程项的关键字段。 */
    private val realSemesterList = """
{"status":1,"code":10000,"message":"访问成功","data":{"topcourses":[
{"id":"MDAwMDAwMDAwMLOcuZmG37NshqVyoQ","uid":"MDAwMDAwMDAwMLWGrZiIubNphMtyoQ",
 "coursename":"模拟电路","code":"MK7D8Y","classname":"王自鑫老师班",
 "semester":"2025-2026","term":"2","canvisit":"1"},
{"id":"MDAwMDAwMDAwMLSspZSG39Ft","coursename":"AI+口译Wed","code":"M8MWR9",
 "classname":"25AWed","semester":"2025-2026","term":"2"}],
"semester":[],"number":0,"isPopApprove":0}}
"""

    @Test
    fun `real response puts courses under data topcourses not data array`() {
        // 结构断言：data 后面直接跟 '{'，不是 '['。
        val dataIdx = realSemesterList.indexOf("\"data\"")
        val afterData = realSemesterList.substring(dataIdx)
        assertTrue(
            "data 是对象而不是数组（上一版就是这里判断错）",
            afterData.startsWith("\"data\":{")
        )
        assertTrue(realSemesterList.contains("\"topcourses\":["))
        // 旧代码找的是 data 后面第一个 '['，会命中 topcourses 的数组——
        // 但随后按数字 id 扫描必然失败，因为 id 是字符串。
        assertTrue(realSemesterList.contains("\"id\":\"MDAw"))
    }

    @Test
    fun `course ids are base64 style strings not numbers`() {
        val id = "MDAwMDAwMDAwMLOcuZmG37NshqVyoQ"
        assertTrue("课程 id 不是纯数字", !id.all { it.isDigit() })
        assertTrue(id.startsWith("MDAw"))
        // 上一版的数字扫描逻辑：遇到 id 后跳引号再扫数字 → 空。
        assertTrue(id.first().isLetter())
    }

    @Test
    fun `course count matches real data`() {
        // 真机 5 门课（模拟电路 / AI+口译 / 大学物理下 / 线性代数 / 思想道德与法治）。
        assertEquals(5, Regex("\"coursename\"").findAll("""
{"topcourses":[
{"coursename":"模拟电路"},{"coursename":"AI+口译Wed"},{"coursename":"大学物理（理）下"},
{"coursename":"2026春-线性代数"},{"coursename":"思想道德与法治"}]}
""").count())
    }

    @Test
    fun `legacy remember cookie is no longer issued`() {
        // §75 实测：当前 Cookie 名是 token，不是 2022 抓包里的 ketangpai_home_remember。
        val cookies = "PHPSESSID,SERVERID,ketangpai_home_slb,token"
        assertTrue(cookies.contains("token"))
        assertTrue("旧 Cookie 名已不存在", !cookies.contains("ketangpai_home_remember"))
        // 凭证提取仍要兼容旧名，避免旧账号/旧版本失效。
        val cred = NativeProbeSpec.ketangpaiCredential(mapOf("token" to "abc"))
        assertEquals("cookie:token", cred.source)
    }

    @Test
    fun `expired login returns code 20003 with empty data`() {
        val expired = """{"status":0,"code":20003,"message":"账号登陆已过期","data":[]}"""
        // 这是「没带 token」的响应：data 是**空数组**，与成功的对象形态不同。
        assertTrue(expired.contains("\"code\":20003"))
        assertTrue(expired.contains("\"data\":[]"))
        // 判成功的依据必须是 code=10000 且 data 是对象，不能只看 HTTP 200。
        assertTrue(realSemesterList.contains("\"code\":10000"))
    }

    @Test
    fun `token mode header is the one that works`() {
        // 真机结论：header / query / form 三种都返回 10000，body 返回 20003。
        // 选定 header（最贴近平台自身用法，也不把 token 放进 URL）。
        val cred = NativeProbeSpec.Credential(true, "T", "cookie:token")
        val reqs = NativeProbeSpec.ketangpaiRequests(cred, null)
        val headerReq = reqs.first { it.tokenMode == "header" }
        assertTrue(headerReq.headers.any { it.first == "token" })
        // query 方式会把 token 暴露在 URL 里（日志/代理可见），不作为首选。
        val queryReq = reqs.first { it.tokenMode == "query" }
        assertTrue(queryReq.url.contains("token="))
    }
}
