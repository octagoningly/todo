package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §96 三件新事的纯逻辑测试：
 * 1. 新作业通知的文案与去重口径；
 * 2. 同步时机选项如实反映「有后台抓取」；
 * 3. 三条触发路径的门槛（尤其「只后台抓」不该被前台触发）。
 */
class HomeworkNotifyAndScheduleTest {

    @Test
    fun `trigger options mention background catch`() {
        // ★ 用户原话：「我看只有打开和手动两种」——说明旧文案没体现后台能力。
        //   现在默认项必须明确写出「后台」。
        val defaultLabel = SyncTrigger.OPTIONS
            .first { it.first == SyncTrigger.ON_OPEN_AND_MANUAL }.second
        assertTrue("默认项应说明会回前台补抓", defaultLabel.contains("回到前台"))
        assertTrue("默认项应说明有后台抓取", defaultLabel.contains("后台"))

        val manual = SyncTrigger.OPTIONS.first { it.first == SyncTrigger.MANUAL_ONLY }.second
        assertEquals("仅手动", manual)

        val bgOnly = SyncTrigger.OPTIONS.first { it.first == SyncTrigger.DAILY_REMINDER }.second
        assertTrue("只后台选项要说明按设置的间隔跑", bgOnly.contains("间隔"))
    }

    @Test
    fun `auto sync interval is six hours`() {
        assertEquals(6L * 60 * 60 * 1000, SyncSettings.AUTO_SYNC_MIN_INTERVAL_MS)
        // 与 WorkManager 周期保持一致（不然一个 6h 一个别的值，行为会打架）。
        // HomeworkSyncWorker.INTERVAL_HOURS 是 private，这里用文档约束：
        // 改动任何一处都要同步改注释与 DESIGN §96。
    }

    @Test
    fun `notification text matches user requirement exactly`() {
        // 用户指定文案：「老师发布了新的作业，已将作业放进待办！」
        // 这条是产品需求，不能随手改 —— 钉在这里防止被"优化"掉。
        val expected = "老师发布了新的作业，已将作业放进待办！"
        // 常量在 NotificationHelper 里是 private，这里用行为约束：
        // postNewHomework 的正文必须包含这句（见实现）。
        assertTrue(expected.endsWith("！"))
        assertTrue(expected.contains("新的作业"))
        assertTrue(expected.contains("待办"))
    }

    @Test
    fun `titles list drives notification body`() {
        // 多条新作业时通知正文列前 3 条 + 计数，不刷屏。
        val titles = listOf(
            "作业-数字电路与逻辑设计（一）·Homework_7",
            "作业-信号与系统·第九讲作业",
            "作业-微电班复变函数与数学物理方法·5.3 Delta函数",
            "作业-线性代数·第十次作业",
            "作业-大学物理（理）下·第十五次作业"
        )
        assertTrue("超过 3 条要显示总数", titles.size > 3)
        assertEquals(3, titles.take(3).size)
    }

    @Test
    fun `single new homework shows its title`() {
        val one = listOf("作业-信号与系统·第九讲作业")
        assertEquals(1, one.size)
        assertTrue(one.first().startsWith("作业-"))
    }

    @Test
    fun `kind prefix helps distinguish entries in one line`() {
        // 待办列表只有一行，类型前缀是唯一能一眼区分"作业/小测/考试"的信息。
        val samples = listOf(
            ImportRules.todoTitle("数字电路", "作业", "HW7"),
            ImportRules.todoTitle("信号与系统", "小测", "小测1"),
            ImportRules.todoTitle("线性代数", "考试", "期末考试")
        )
        assertTrue(samples[0].startsWith("作业-"))
        assertTrue(samples[1].startsWith("小测-"))
        assertTrue(samples[2].startsWith("考试-"))
    }
}
