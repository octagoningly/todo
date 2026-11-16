package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * §87/§88 一键抓取与自动抓取的纯逻辑测试。
 *
 * 不能测的部分（真机 / IO）：实际网络抓取、写库。
 * 能测的部分（纯函数）：结果合并、摘要文案、自动抓取的时间门槛。
 */
class OneClickSyncTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(d: String, t: String): Long =
        ZonedDateTime.of(LocalDate.parse(d), LocalTime.parse(t), zone)
            .toInstant().toEpochMilli()

    private fun line(platform: SyncPlatform, id: String, deadline: Long, title: String) =
        ImportRules.buildPreview(
            listOf(
                ImportRules.ImportItem(
                    platform = platform,
                    externalId = id,
                    courseName = "课程A",
                    title = title,
                    deadlineMillis = deadline,
                    kind = "作业"
                )
            ),
            { null }, null, ms("2026-11-16", "00:00")
        )

    @Test
    fun `merge combines platforms sorted by deadline`() {
        val kt1 = line(SyncPlatform.KETANGPAI, "k1", ms("2026-11-19", "00:00"), "5.3 Delta函数")
        val kt2 = line(SyncPlatform.KETANGPAI, "k2", ms("2026-11-17", "00:00"), "5.2 傅里叶")
        val yt1 = line(SyncPlatform.YUKETANG, "y1", ms("2026-11-22", "09:00"), "第九讲作业")

        val results = listOf(
            SyncEngine.PlatformResult(SyncPlatform.KETANGPAI, true, kt1 + kt2),
            SyncEngine.PlatformResult(SyncPlatform.YUKETANG, true, yt1)
        )
        val merged = SyncEngine.mergePreviews(results)
        assertEquals(3, merged.size)
        // 最近截止的排最前
        assertEquals("k2", merged[0].item.externalId)
        assertEquals("k1", merged[1].item.externalId)
        assertEquals("y1", merged[2].item.externalId)
    }

    @Test
    fun `merge dedupes same platform and id`() {
        val a = line(SyncPlatform.KETANGPAI, "same", ms("2026-11-17", "00:00"), "作业A")
        val b = line(SyncPlatform.KETANGPAI, "same", ms("2026-11-17", "00:00"), "作业A")
        val merged = SyncEngine.mergePreviews(
            listOf(SyncEngine.PlatformResult(SyncPlatform.KETANGPAI, true, a + b))
        )
        assertEquals("同一条作业只应出现一次", 1, merged.size)
    }

    @Test
    fun `summary reports skipped platform`() {
        val results = listOf(
            SyncEngine.PlatformResult(SyncPlatform.KETANGPAI, true,
                line(SyncPlatform.KETANGPAI, "k1", ms("2026-11-17", "00:00"), "作业")),
            SyncEngine.PlatformResult(SyncPlatform.YUKETANG, false, emptyList())
        )
        val s = SyncEngine.summarizeAll(results)
        assertEquals(true, s.contains("共 1 条待导入"))
        assertEquals(true, s.contains("课堂派：1 条"))
        assertEquals(true, s.contains("雨课堂：未登录，已跳过"))
    }

    @Test
    fun `summary when nothing logged in`() {
        val s = SyncEngine.summarizeAll(
            listOf(
                SyncEngine.PlatformResult(SyncPlatform.KETANGPAI, false, emptyList()),
                SyncEngine.PlatformResult(SyncPlatform.YUKETANG, false, emptyList())
            )
        )
        assertEquals(true, s.contains("都未登录"))
    }

    @Test
    fun `summary reports fetch error without killing other platform`() {
        val results = listOf(
            SyncEngine.PlatformResult(SyncPlatform.KETANGPAI, true, emptyList(), "SocketTimeout"),
            SyncEngine.PlatformResult(SyncPlatform.YUKETANG, true,
                line(SyncPlatform.YUKETANG, "y1", ms("2026-11-22", "09:00"), "作业"))
        )
        val s = SyncEngine.summarizeAll(results)
        assertEquals(true, s.contains("课堂派：抓取失败"))
        assertEquals(true, s.contains("SocketTimeout"))
        // 另一个平台照常计入
        assertEquals(true, s.contains("共 1 条待导入"))
    }

    @Test
    fun `auto sync interval gate is six hours`() {
        // 这是 §88 的核心门槛：频繁切 App 不能反复打平台接口。
        assertEquals(6L * 60 * 60 * 1000, SyncSettings.AUTO_SYNC_MIN_INTERVAL_MS)
    }

    @Test
    fun `empty merge yields empty list`() {
        assertEquals(0, SyncEngine.mergePreviews(emptyList()).size)
        assertNull(null)
    }
}
