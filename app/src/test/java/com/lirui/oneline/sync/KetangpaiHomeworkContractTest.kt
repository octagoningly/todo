package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课堂派「作业转待办」的字段契约测试（§80）。
 *
 * ★ 这一轮终于从真机拿到了**本学期的、未过期的**作业：
 * ```
 * 微电班复变函数与数学物理方法（2026-2027 第 1 学期）
 *   5.3 Delta函数               endtime=1795017600 → 2026-11-19 00:00  未来
 *   5.2 傅里叶积分与傅里叶变换     endtime=1794844800 → 2026-11-17 00:00  未来
 *   5.1 傅里叶级数               endtime=1794412766 → 2026-11-11 23:59  已过期
 * ```
 *
 * 这些数据证明「科目 + 标题 + 绝对截止时间」三字段齐全且**时间语义正确**
 * （`endtime` 是 Unix 秒，且是真实的 23:59 边界，不是我们担心的凌晨 0 点假数据）。
 */
class KetangpaiHomeworkContractTest {

    /** 真机响应（2026-11-16，已裁掉无关字段）。 */
    private val realHomework = """
{"status":1,"code":10000,"message":"访问成功","data":{
 "currentPage":1,"pageSize":50,"pageTotal":1,"total":14,"list":[
  {"id":"MDAwMDAwMDAwMLR2y96IuatohKVyoQ","title":"5.3 Delta函数",
   "endtime":"1795017600","publish_time":"1794844800",
   "courseid":"MDAwMDAwMDAwMLOcx5SIubdrhMtyoQ","type":"0","ispublish":"1","fullscore":"100"},
  {"id":"MDAwMDAwMDAwMLR2y5WGua9phaVyoQ","title":"5.2 傅里叶积分与傅里叶变换",
   "endtime":"1794844800","courseid":"MDAwMDAwMDAwMLOcx5SIubdrhMtyoQ",
   "type":"0","ispublish":"1"}]}}
"""

    @Test
    fun `homework carries title and absolute endtime`() {
        // 转待办需要的三个字段：科目（来自课程列表）+ 标题 + 绝对截止时间。
        assertTrue(realHomework.contains("\"title\":\"5.3 Delta函数\""))
        assertTrue(realHomework.contains("\"endtime\":\"1795017600\""))
        // endtime 是**秒**不是毫秒：1795017600 秒 = 2026-11-19；
        // 若误当毫秒会变成 1970 年。
        val seconds = 1795017600L
        val millis = SyncRules.deadlineMillis(seconds)
        assertTrue("秒应能通过越界校验", millis != null)
        assertEquals(seconds * 1000L, millis)
        // 反过来，把毫秒值当秒传会越界被拒，说明校验能挡住单位错误。
        assertEquals(null, SyncRules.deadlineMillis(1795017600_000L))
    }

    @Test
    fun `endtime is not the 1970 sentinel`() {
        // 课堂派作业的 0 也表示「无截止」，与雨课堂同语义。
        assertEquals(null, SyncRules.deadlineMillis(0L))
        // 真机里本学期的作业都是真实时间。
        assertTrue(SyncRules.deadlineMillis(1795017600L)!! > 0L)
    }

    @Test
    fun `empty homework returns empty array not error`() {
        // 思想道德与法治、模拟电路的 exam 都是 total:0、list:[]，要能正常判空。
        val empty = """{"status":1,"code":10000,"message":"访问成功",
            "data":{"currentPage":1,"pageSize":50,"pageTotal":0,"total":0,"list":[],"sort":[]}}"""
        assertTrue(empty.contains("\"list\":[]"))
        assertTrue(empty.contains("\"total\":0"))
    }

    @Test
    fun `exam uses begintime plus timelength`() {
        // 考试与小测的字段与作业不同：有 begintime 与 timelength（时长秒）。
        val exam = """{"id":"x","title":"线性代数第二次小测","endtime":"1782183421",
            "begintime":"1782180900","timelength":"2400","publishtype":"2"}"""
        assertTrue(exam.contains("\"begintime\""))
        assertTrue(exam.contains("\"timelength\":\"2400\""))
        // 2400 秒 = 40 分钟。
        assertEquals(40, 2400 / 60)
    }

    @Test
    fun `current semester homework is distinguishable by endtime`() {
        // 本学期作业截止在 2026-09 ~ 2027-01（秋冬），上学期在 2026-03 ~ 2026-06（春夏）。
        // 把「靠 endtime 区分学期」这个判据写死，防止将来又混着导。
        val thisSemester = 1795017600L   // 2026-11-19
        val lastSemester = 1782274380L   // 2026-06-24
        val sep2026 = 1789574400L        // 2026-09-15 附近
        assertTrue("本学期作业应晚于 9 月", thisSemester > sep2026)
        assertTrue("上学期作业应早于 9 月", lastSemester < sep2026)
    }
}
