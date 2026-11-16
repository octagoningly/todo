package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * §90 学期过滤的测试。
 *
 * ★ 补的是 DESIGN §89.2 记的已知问题：
 *   之前 `semesterOf = { null }` 等于没筛，靠「只导未过期」间接挡往期作业。
 *   但如果**往期作业的截止日还没到**（可补交、跨年重修），就会被误导入。
 *
 * 现在真的按学期筛了。
 */
class SemesterFilterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(d: String, t: String): Long =
        ZonedDateTime.of(LocalDate.parse(d), LocalTime.parse(t), zone)
            .toInstant().toEpochMilli()

    private val now = ms("2026-11-16", "11:00")

    private fun item(
        id: String = "x",
        course: String = "微电班复变函数与数学物理方法",
        deadline: Long = ms("2026-11-19", "00:00"),
        semester: String? = "2026-2027/1"
    ) = ImportRules.ImportItem(
        platform = SyncPlatform.KETANGPAI,
        externalId = id,
        courseName = course,
        title = "作业$id",
        deadlineMillis = deadline,
        kind = "作业",
        semesterTag = semester
    )

    @Test
    fun `semester tag normalization handles both platforms`() {
        // 课堂派写法已经是 yyyy-yyyy/t
        assertEquals("2026-2027/1", ImportRules.normalizeSemester("2026-2027/1"))
        // 雨课堂给数字 202601
        assertEquals("2026-2027/1", ImportRules.normalizeSemester("202601"))
        assertEquals("2025-2026/2", ImportRules.normalizeSemester("202502"))
        // 课堂派可能带教师名后缀，只取前半
        assertEquals("2026-2027/1", ImportRules.normalizeSemester("2026-2027/1 陈琨"))
        // 认不出来的原样返回，不误杀
        assertEquals("whatever", ImportRules.normalizeSemester("whatever"))
        assertEquals("", ImportRules.normalizeSemester(null))
    }

    @Test
    fun `current semester tags differ by month`() {
        // 11 月 → 2026-2027 第一学期
        assertEquals("2026-2027/1", SyncEngine.currentSemesterTag(ms("2026-11-16", "12:00")))
        // 9 月（开学）→ 同样是第一学期
        assertEquals("2026-2027/1", SyncEngine.currentSemesterTag(ms("2026-09-01", "08:00")))
        // 3 月 → 2025-2026 第二学期
        assertEquals("2025-2026/2", SyncEngine.currentSemesterTag(ms("2026-03-15", "08:00")))
        // 7 月（暑假）→ 仍算上一学年第二学期
        assertEquals("2025-2026/2", SyncEngine.currentSemesterTag(ms("2026-07-20", "08:00")))
        // 8 月 → 切换点，算新学年第一学期
        assertEquals("2026-2027/1", SyncEngine.currentSemesterTag(ms("2026-08-01", "08:00")))
    }

    @Test
    fun `current semester item passes filter`() {
        val preview = ImportRules.buildPreview(
            listOf(item(id = "a", semester = "2026-2027/1")),
            semesterOf = { it.semesterTag },
            currentSemester = "2026-2027/1",
            nowMillis = now
        )
        assertEquals(1, preview.size)
    }

    @Test
    fun `past semester item is filtered out even if not expired`() {
        // ★ 关键用例：上学期作业，但截止日还没到（可补交）。
        //   这正是旧实现会误导入的场景。
        val notExpiredButOldSemester = item(
            id = "old",
            deadline = ms("2026-12-31", "23:59"),   // 未过期
            semester = "2025-2026/2"                // 但属于上学期
        )
        // 先确认它确实未过期 —— 证明过滤靠的是学期而不是时间
        assertTrue(ImportRules.shouldImport(notExpiredButOldSemester, null, now))

        val preview = ImportRules.buildPreview(
            listOf(notExpiredButOldSemester),
            semesterOf = { it.semesterTag },
            currentSemester = "2026-2027/1",
            nowMillis = now
        )
        assertEquals("上学期作业即使未过期也不该导入", 0, preview.size)
    }

    @Test
    fun `mixed semesters only current ones survive`() {
        val preview = ImportRules.buildPreview(
            listOf(
                item(id = "cur1", semester = "2026-2027/1"),
                item(id = "old1", semester = "2025-2026/2", deadline = ms("2026-12-01", "00:00")),
                item(id = "cur2", semester = "2026-2027/1", deadline = ms("2026-11-22", "09:00")),
                item(id = "old2", semester = "2025-2026/1", deadline = ms("2027-01-10", "00:00"))
            ),
            semesterOf = { it.semesterTag },
            currentSemester = "2026-2027/1",
            nowMillis = now
        )
        assertEquals(2, preview.size)
        assertEquals(setOf("cur1", "cur2"), preview.map { it.item.externalId }.toSet())
    }

    @Test
    fun `item without semester tag is kept not dropped`() {
        // 平台没给学期信息时放行 —— 宁可多导一条，也不要因为拿不到学期就全滤掉。
        val preview = ImportRules.buildPreview(
            listOf(item(id = "unknown", semester = null)),
            semesterOf = { it.semesterTag },
            currentSemester = "2026-2027/1",
            nowMillis = now
        )
        assertEquals(1, preview.size)
    }

    @Test
    fun `unknown current semester disables filtering`() {
        val preview = ImportRules.buildPreview(
            listOf(
                item(id = "a", semester = "2025-2026/2", deadline = ms("2026-12-01", "00:00")),
                item(id = "b", semester = "2026-2027/1")
            ),
            semesterOf = { it.semesterTag },
            currentSemester = null,
            nowMillis = now
        )
        assertEquals("当前学期未知时不筛，两条都留", 2, preview.size)
    }

    @Test
    fun `matching ignores format difference between platforms`() {
        // 雨课堂给 202601，当前学期算出来是 2026-2027/1 —— 必须能匹配上。
        assertTrue(ImportRules.matchSemester("202601", "2026-2027/1"))
        assertTrue(ImportRules.matchSemester("2026-2027/1", "202601"))
        // 不匹配的仍然拒绝
        assertFalse(ImportRules.matchSemester("202502", "2026-2027/1"))
    }
}
