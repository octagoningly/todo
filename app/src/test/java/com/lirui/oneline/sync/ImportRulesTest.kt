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
 * §85 作业 → 待办的字段映射与过滤规则测试。
 *
 * ★ 全部样本取自 2026-11-16 真机抓到的真实数据。
 */
class ImportRulesTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(date: String, time: String): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)
            .toInstant().toEpochMilli()

    /** 2026-11-16 11:00 作为"现在"。 */
    private val now = ms("2026-11-16", "11:00")

    private fun item(
        platform: SyncPlatform = SyncPlatform.KETANGPAI,
        externalId: String = "MDAwMDAwMDAwMLR2y5WGua9phaVyoQ",
        course: String = "微电班复变函数与数学物理方法",
        title: String = "5.2 傅里叶积分与傅里叶变换",
        deadline: Long = ms("2026-11-17", "00:00"),
        kind: String = "作业"
    ) = ImportRules.ImportItem(platform, externalId, course, title, deadline, kind)

    @Test
    fun `todo title puts kind first then course`() {
        // ★ §95 用户要求：名字改成「作业-数字电路与……」这样，类型在最前。
        //   原标题与科目不重复时会追加在后面（便于辨别具体哪一次）。
        val t1 = ImportRules.todoTitle("数字电路与逻辑设计（一）", "作业", "Homework_7")
        assertTrue("类型必须最前", t1.startsWith("作业-数字电路与逻辑设计（一）"))
        assertTrue("原标题要有", t1.contains("Homework_7"))

        val t2 = ImportRules.todoTitle(
            "微电班复变函数与数学物理方法", "作业", "5.2 傅里叶积分与傅里叶变换"
        )
        assertTrue(t2.startsWith("作业-微电班复变函数与数学物理方法"))
        assertTrue(t2.contains("5.2 傅里叶积分与傅里叶变换"))

        val t3 = ImportRules.todoTitle("信号与系统", "小测", "小测1")
        assertEquals("小测-信号与系统·小测1", t3)
    }

    @Test
    fun `todo title appends original title when it adds info`() {
        // 原标题与科目不同、且不重复时，追加在后面便于辨别具体哪一次。
        val t = ImportRules.todoTitle("信号与系统", "作业", "第九讲作业")
        assertTrue("应含类型前缀", t.startsWith("作业-信号与系统"))
        assertTrue("应含原标题", t.contains("第九讲作业"))
    }

    @Test
    fun `todo title avoids repeating course name`() {
        // 原标题里已经含科目名时不再复读。
        val t = ImportRules.todoTitle("线性代数", "作业", "线性代数第十次作业")
        assertEquals("作业-线性代数", t)
        // 完全同名也不复读
        assertEquals("作业-信号与系统", ImportRules.todoTitle("信号与系统", "作业", "信号与系统"))
    }

    @Test
    fun `todo title falls back when course missing`() {
        // 没科目时退回原标题，仍要带类型前缀。
        assertEquals("作业-习题", ImportRules.todoTitle("", "作业", "习题"))
        // 类型为空时默认「作业」；此时 №科目与标题不重复 → 追加原标题
        assertEquals("作业-信号与系统·第九讲", ImportRules.todoTitle("信号与系统", "", "第九讲"))
    }

    @Test
    fun `midnight deadline becomes previous day 2359`() {
        // ★ §91 用户明确要求：截止时间是某天 0:00 时，要导入成**前一天 23:59**。
        //   课堂派「5.3 Delta函数」endtime = 2026-11-19 00:00，
        //   平台语义是「19 号结束」，即 18 号最后一刻。
        val (date, time) = ImportRules.deadlineParts(ms("2026-11-19", "00:00"))
        assertEquals("2026-11-18", date)
        assertEquals("23:59", time)
        // 归一后的真实时间戳也要对得上（不只是显示）。
        assertEquals(
            ms("2026-11-18", "23:59"),
            ImportRules.normalizeDeadline(ms("2026-11-19", "00:00"))
        )
    }

    @Test
    fun `normalizeDeadline leaves non midnight untouched`() {
        // 雨课堂「Homework_7」22:00 这种真实时刻不该被改。
        val d = ms("2026-11-17", "22:00")
        assertEquals(d, ImportRules.normalizeDeadline(d))
        // 09:00 同理
        val d2 = ms("2026-11-22", "09:00")
        assertEquals(d2, ImportRules.normalizeDeadline(d2))
        // 0 值原样返回，不变成负数
        assertEquals(0L, ImportRules.normalizeDeadline(0L))
    }

    @Test
    fun `non midnight deadline keeps its exact time`() {
        // 真机：雨课堂「Homework_7」deadline = 2026-11-17 23:00
        val (d1, t1) = ImportRules.deadlineParts(ms("2026-11-17", "23:00"))
        assertEquals("2026-11-17", d1)
        assertEquals("23:00", t1)
        // 雨课堂「第九讲作业」= 2026-11-22 09:00
        val (d2, t2) = ImportRules.deadlineParts(ms("2026-11-22", "09:00"))
        assertEquals("2026-11-22", d2)
        assertEquals("09:00", t2)
    }

    @Test
    fun `expired homework is filtered out`() {
        // 真机：课堂派「5.1 傅里叶级数」截止 2026-11-11，已过期。
        val expired = item(deadline = ms("2026-11-11", "23:59"))
        assertFalse(
            "已过期的不该导入",
            ImportRules.shouldImport(expired, null, now)
        )
    }

    @Test
    fun `future homework is imported`() {
        // 真机 4 条未过期的全部应该通过。
        assertTrue(ImportRules.shouldImport(
            item(deadline = ms("2026-11-17", "00:00")), null, now))
        assertTrue(ImportRules.shouldImport(
            item(externalId = "x2", deadline = ms("2026-11-19", "00:00")), null, now))
        assertTrue(ImportRules.shouldImport(
            item(platform = SyncPlatform.YUKETANG, externalId = "1",
                course = "数字电路与逻辑设计（一）", title = "Homework_7",
                deadline = ms("2026-11-17", "23:00")), null, now))
        assertTrue(ImportRules.shouldImport(
            item(platform = SyncPlatform.YUKETANG, externalId = "2",
                course = "信号与系统", title = "第九讲作业",
                deadline = ms("2026-11-22", "09:00")), null, now))
    }

    @Test
    fun `zero deadline is rejected`() {
        // 0 = 无限制，不是 1970。
        assertFalse(ImportRules.shouldImport(item(deadline = 0L), null, now))
    }

    @Test
    fun `blank course or title is rejected`() {
        assertFalse(ImportRules.shouldImport(item(course = ""), null, now))
        assertFalse(ImportRules.shouldImport(item(title = "  "), null, now))
    }

    @Test
    fun `semester filter keeps current only`() {
        // 真机：课堂派本学期是 2026-2027/1，其余 6 门是往期。
        assertTrue(ImportRules.matchSemester("2026-2027/1", "2026-2027/1"))
        assertFalse(ImportRules.matchSemester("2025-2026/2", "2026-2027/1"))
        // 标签缺失时不筛（宁可多导也别漏）。
        assertTrue(ImportRules.matchSemester(null, "2026-2027/1"))
        assertTrue(ImportRules.matchSemester("2025-2026/2", null))
    }

    @Test
    fun `preview sorts by deadline and dedupes`() {
        val items = listOf(
            item(externalId = "late", deadline = ms("2026-11-22", "09:00"),
                title = "第九讲作业"),
            item(externalId = "early", deadline = ms("2026-11-17", "00:00")),
            // 重复的 externalId 只留一条
            item(externalId = "early", deadline = ms("2026-11-17", "00:00")),
            // 已过期的要剔掉
            item(externalId = "gone", deadline = ms("2026-11-11", "23:59"))
        )
        val preview = ImportRules.buildPreview(items, { null }, null, now)
        assertEquals(2, preview.size)
        // 最近的在最前
        assertEquals("early", preview[0].item.externalId)
        assertEquals("late", preview[1].item.externalId)
    }

    @Test
    fun `preview summary mentions count and platform`() {
        val preview = ImportRules.buildPreview(
            listOf(
                item(externalId = "a", deadline = ms("2026-11-17", "00:00")),
                item(platform = SyncPlatform.YUKETANG, externalId = "b",
                    deadline = ms("2026-11-22", "09:00"))
            ),
            { null }, null, now
        )
        val s = ImportRules.summarizePreview(preview)
        assertTrue(s.contains("可导入 2 条"))
        assertTrue(s.contains("课堂派"))
        assertTrue(s.contains("雨课堂"))
    }

    @Test
    fun `empty preview says so`() {
        val s = ImportRules.summarizePreview(emptyList())
        assertTrue(s.contains("没有可导入"))
    }
}
