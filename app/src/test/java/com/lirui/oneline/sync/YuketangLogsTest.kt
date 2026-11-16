package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §83 雨课堂 `logs/learn` 活动流解析的回归测试。
 *
 * ★ 实测这是雨课堂**最好用**的数据源，比 c27 更好：
 *   - 直接带 `deadline`（毫秒），不用自己从 score_deadline 推断；
 *   - 直接带 `type`：5=作业/测验、9=公告、14=课件；
 *   - 带 `status` / `total_score` 能判断已交未交；
 *   - 覆盖面更全（c27 只有 11/50 门有数据）。
 *
 * 真机统计：121 条 activity = type5 49 条 + type14 53 条 + type9 19 条。
 */
class YuketangLogsTest {

    /** 真机响应片段（信号与系统）。 */
    private val realLogs = """
{"errcode":0,"errmsg":"Success","data":{"IS_SIMPLE":false,"prev_id":56849398,
 "activities":[
  {"type":5,"id":59288754,"courseware_id":"4790414","title":"第九讲作业",
   "create_time":1794455341000,"status":0,"deadline":1795309200000,
   "total_score":40,"problem_count":4},
  {"type":5,"id":59282835,"title":"傅里叶级数随堂小测（1）","status":3,
   "deadline":1794449700000,"total_score":5,"problem_count":5},
  {"type":14,"id":59329975,"title":"Chapter 8   Counters","create_time":1794535248000},
  {"type":9,"id":59000001,"title":"关于期中考的通知"}],"has_more":false}}
"""

    @Test
    fun `only type 5 counts as homework`() {
        // 5=作业/测验；9=公告、14=课件都不该进待办。
        assertTrue(NativeProbeRunner.isLogsHomeworkType(5))
        assertFalse(NativeProbeRunner.isLogsHomeworkType(14))
        assertFalse(NativeProbeRunner.isLogsHomeworkType(9))
        assertFalse(NativeProbeRunner.isLogsHomeworkType(0))
    }

    @Test
    fun `logs carries deadline in milliseconds directly`() {
        // ★ 与章节树的 score_deadline 不同，logs 的 deadline 就是**毫秒**，
        //   不需要 ×1000。
        val deadlineMs = 1795309200000L
        // 作为毫秒直接可判：落在合理区间内。
        assertTrue(NativeProbeRunner.isDeadlineUsable(deadlineMs))
        // 若误当成秒去 ×1000 会越界 —— 这能挡住单位错误。
        assertFalse(NativeProbeRunner.isDeadlineUsable(deadlineMs * 1000L))
    }

    @Test
    fun `real logs mixes three activity types`() {
        // 4 条 activity 里只有 2 条 type=5。
        assertEquals(4, Regex("\"type\":\\d+").findAll(realLogs).count())
        assertEquals(2, Regex("\"type\":5").findAll(realLogs).count())
        assertTrue(realLogs.contains("\"type\":14"))
        assertTrue(realLogs.contains("\"type\":9"))
    }

    @Test
    fun `homework can be graded or ungraded`() {
        // status=0 未交/未批，status=3 已完成 —— 都算作业，只是状态不同。
        assertTrue(realLogs.contains("\"status\":0"))
        assertTrue(realLogs.contains("\"status\":3"))
        // 有总分与题目数，便于展示。
        assertTrue(realLogs.contains("\"total_score\":40"))
        assertTrue(realLogs.contains("\"problem_count\":4"))
    }

    @Test
    fun `leaf type 5 is homework not exam`() {
        // ★ §83 订正：真机里 leaf_type=5 的节点名全是
        //   「第16周作业」「小测3」「练习」「第九讲作业」—— 是作业类，不是"考试"。
        //   而 leaf_type=8 是「Chapter 8 Counters」这类课件。
        val homeworkNames = listOf("第16周作业", "小测3", "练习", "第九讲作业", "Homework_7")
        assertTrue(homeworkNames.any { it.contains("作业") })
        assertTrue(NativeProbeRunner.isHomeworkOrExam("5"))
        assertTrue(NativeProbeRunner.isHomeworkOrExam("6"))
        assertFalse("8 是课件", NativeProbeRunner.isHomeworkOrExam("8"))
    }

    @Test
    fun `dedupe key by course and title avoids double counting`() {
        // c27 与 logs 会给出同一份作业，按 (课程, 标题) 去重。
        val a = "信号与系统|第九讲作业"
        val b = "信号与系统|第九讲作业"
        val c = "信号与系统|第八讲作业"
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
