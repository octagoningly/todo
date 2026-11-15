package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业导入的判定规则单测。
 *
 * 这里全是纯 Kotlin 函数，不带 Context / android.jar —— 项目没有 Robolectric，
 * 判定逻辑放别处就测不了（AGENTS.md §2 第 4 级、§7）。
 */
class SyncRulesTest {

    @Test
    fun `normalizeHost accepts bare host, pasted url and port`() {
        assertEquals("gsscut.yuketang.cn", SyncRules.normalizeHost("gsscut.yuketang.cn", "x"))
        assertEquals(
            "gsscut.yuketang.cn",
            SyncRules.normalizeHost("https://gsscut.yuketang.cn/pro/portal/home", "x")
        )
        assertEquals(
            "nk.yuketang.cn",
            SyncRules.normalizeHost("  nk.yuketang.cn:8080/path?a=1#b  ", "x")
        )
    }

    @Test
    fun `normalizeHost falls back on empty or illegal input`() {
        assertEquals("www.yuketang.cn", SyncRules.normalizeHost(null, "www.yuketang.cn"))
        assertEquals("fallback", SyncRules.normalizeHost("", "fallback"))
        assertEquals("fallback", SyncRules.normalizeHost("https://", "fallback"))
        // 没有点号 / 含空格 / 含引号，都不该被拼进 https:// 后面。
        assertEquals("fallback", SyncRules.normalizeHost("localhost", "fallback"))
        assertEquals("fallback", SyncRules.normalizeHost("a b.com", "fallback"))
        assertEquals("fallback", SyncRules.normalizeHost("a\"b.com", "fallback"))
    }

    @Test
    fun `parseCookieHeader keeps pairs and drops attributes`() {
        val parsed = SyncRules.parseCookieHeader(
            "PHPSESSID=abc; ketangpai_home_remember=think%3Axyz; path=/; secure; HttpOnly"
        )
        assertEquals(2, parsed.size)
        assertEquals("abc", parsed["PHPSESSID"])
        assertEquals("think%3Axyz", parsed["ketangpai_home_remember"])
        assertTrue(SyncRules.parseCookieHeader(null).isEmpty())
        assertTrue(SyncRules.parseCookieHeader("  ").isEmpty())
    }

    @Test
    fun `hasSession depends on the platform specific cookie`() {
        val ktp = mapOf("PHPSESSID" to "abc", "ketangpai_home_slb" to "x")
        assertTrue(SyncRules.hasSession(SyncPlatform.KETANGPAI, ktp))
        assertFalse(SyncRules.hasSession(SyncPlatform.YUKETANG, ktp))

        val ykt = mapOf("sessionid" to "s", "csrftoken" to "c")
        assertTrue(SyncRules.hasSession(SyncPlatform.YUKETANG, ykt))
        assertFalse(SyncRules.hasSession(SyncPlatform.KETANGPAI, ykt))
        // 空值不算登录成功。
        assertFalse(SyncRules.hasSession(SyncPlatform.YUKETANG, mapOf("sessionid" to "")))
    }

    @Test
    fun `loginUrl and cookieHosts follow the selected school host`() {
        assertEquals(
            "https://www.yuketang.cn/web?type=3",
            SyncRules.loginUrl(SyncPlatform.YUKETANG, "www.yuketang.cn")
        )
        assertEquals(
            "https://nk.yuketang.cn/web?type=3",
            SyncRules.loginUrl(SyncPlatform.YUKETANG, "nk.yuketang.cn")
        )
        val hosts = SyncRules.cookieHosts(SyncPlatform.YUKETANG, "nk.yuketang.cn")
        assertEquals("nk.yuketang.cn", hosts.first())
        assertTrue(hosts.contains("www.yuketang.cn"))
        assertEquals(hosts.size, hosts.distinct().size)
    }

    @Test
    fun `deadlineMillis rejects out of range timestamps`() {
        assertEquals(1_800_000_000_000L, SyncRules.deadlineMillis(1_800_000_000L))
        assertNull(SyncRules.deadlineMillis(0L))
        assertNull(SyncRules.deadlineMillis(-1L))
        assertNull(SyncRules.deadlineMillis(5_000_000_000L))
    }

    @Test
    fun `isValid requires all three fields and an absolute deadline`() {
        val good = HomeworkItem(
            platform = SyncPlatform.KETANGPAI,
            externalId = "12345",
            courseName = "复变函数",
            title = "第三次作业",
            deadlineEpochSeconds = 1_800_000_000L
        )
        assertTrue(SyncRules.isValid(good))
        assertFalse(SyncRules.isValid(good.copy(courseName = "")))
        assertFalse(SyncRules.isValid(good.copy(title = "  ")))
        assertFalse(SyncRules.isValid(good.copy(externalId = "")))
        // 相对时间推算出来的 0 / 脏时间戳必须被挡住。
        assertFalse(SyncRules.isValid(good.copy(deadlineEpochSeconds = 0L)))
    }

    @Test
    fun `dedupeKey is namespaced by platform and diagnostic only shows names`() {
        assertEquals("ketangpai:1", SyncRules.dedupeKey(SyncPlatform.KETANGPAI, " 1 "))
        assertEquals("yuketang:1", SyncRules.dedupeKey(SyncPlatform.YUKETANG, "1"))
        assertEquals(
            listOf("PHPSESSID", "ketangpai_home_remember"),
            SyncRules.diagnosticNames(
                mapOf("ketangpai_home_remember" to "secret", "PHPSESSID" to "secret")
            )
        )
    }
}
