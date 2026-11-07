package com.lirui.oneline.parser

import com.lirui.oneline.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * §60 语义标签的**端到端脚本验收**（v1.0.15 开发中）。
 *
 * 这些用例原本要在真机上逐句敲字、看预览卡片与列表徽章。按 zuige 要求改为纯本地脚本：
 * 全部走「一句话 → Parser → 编辑回填 → 再解析」的真实调用链，不碰 Android、不连设备
 * （项目没有 Robolectric，所以只复用 `Parser` / `TagRules` / `Format` 这些纯函数模块）。
 *
 * 分工：`TagRulesTest` 测切分纯函数，`ParserTest` 测解析规则，本文件测**用户路径** ——
 * 尤其是编辑态在句末继续打字时不能吞字（§58 P0 的同款变体，靠这个脚本才抓到）。
 */
class SemanticTagFlowTest {

    private val today = LocalDate.of(2026, 10, 21) // 周三，与 ParserTest 用同一参考日
    private fun parser() = Parser(today)
    private fun parse(s: String) = parser().parse(s)
    private fun parseAll(s: String) = parser().parseAll(s)

    /** 模拟 TodoListScreen 的编辑入口：把库里一条待办回填成输入框里的一句话。 */
    private fun editSentence(title: String, detail: String, tag: String, due: Long? = null) =
        Format.editSentence(title, detail, null, due, today = today, tag = tag)

    // ---- zuige 原话对应的验收清单 ----

    @Test fun userPhrase_colonMarkerCreatesTag() {
        // 「交作业：标签作业」：冒号后的「标签」是标记，后面的字是标签值。
        val r = parse("交作业：标签作业")
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun userPhrase_spaceMarkerCreatesTag() {
        val r = parse("交作业 标签作业")
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun userPhrase_detailReturnsWithoutMarker() {
        // 没有标记词：详情槽回归 v0.7 语义，不再被「末尾段即标签」抢走。
        val r = parse("交作业 带电脑")
        assertEquals("交作业", r.title)
        assertEquals("带电脑", r.detail)
        assertNull(r.tag)
    }

    @Test fun userPhrase_markerInsideWordIsNotATag() {
        val r = parse("买标签纸")
        assertEquals("买标签纸", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun userPhrase_markerWithDetailKeepsBothSlots() {
        val r = parse("写报告 带电脑 标签工作")
        assertEquals("写报告", r.title)
        assertEquals("带电脑", r.detail)
        assertEquals("工作", r.tag)
    }

    // ---- 编辑态：回填句里改字 / 追加字，一个字都不能丢 ----

    @Test fun editing_appendingWordAfterTagKeepsItInDetail() {
        // ★ P0 回归：editSentence 无日期时标签在句末，用户在末尾继续打字，
        //   新字会跟在标签值后面。旧实现「只取第一个词」会把它们静默丢掉，
        //   标签值也不该被新字覆盖。现在标签之后的文字归 detail。
        val sentence = editSentence("交作业", "带电脑", "作业")
        assertEquals("交作业 带电脑 标签作业", sentence)
        val r = parseAll("$sentence 再确认").single()
        assertEquals("交作业", r.title)
        assertEquals("带电脑 再确认", r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun editing_changingTagValueInSentenceTakesEffect() {
        val r = parseAll("交作业 带电脑 标签生活").single()
        assertEquals("交作业", r.title)
        assertEquals("带电脑", r.detail)
        assertEquals("生活", r.tag)
    }

    @Test fun editing_removingTagSegmentClearsTag() {
        val r = parseAll("交作业 带电脑").single()
        assertEquals("交作业", r.title)
        assertEquals("带电脑", r.detail)
        assertNull(r.tag)
    }

    @Test fun editing_roundTripWithDateStillKeepsTagAndTime() {
        val due = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        val sentence = editSentence("开会", "带电脑", "作业", due)
        assertEquals("开会 带电脑 标签作业 10月26日 9点", sentence)
        val r = parseAll(sentence).single()
        assertEquals("开会", r.title)
        assertEquals("带电脑", r.detail)
        assertEquals("作业", r.tag)
        assertEquals(LocalDate.of(2026, 10, 26), r.dueDate)
        assertEquals(LocalTime.of(9, 0), r.dueTime)
    }

    @Test fun editing_appendingWordAfterTagWithDateStillKeepsIt() {
        val due = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        val r = parseAll("${editSentence("开会", "带电脑", "作业", due)} 再确认").single()
        assertEquals("开会", r.title)
        assertEquals("带电脑 再确认", r.detail)
        assertEquals("作业", r.tag)
    }

    // ---- 边界：标记词歧义、多标记、时刻里的冒号、旧写法兼容 ----

    @Test fun chip_normalizeKeepsTagWordAsValue() {
        // 预览 chip 里直接输标签值，不认句子里的标记词；「标签纸」不能被削成「纸」。
        assertEquals("标签纸", TagRules.normalize("标签纸"))
        assertEquals("作业", TagRules.normalize("#作业"))
        val r = parseAll("交作业 标签标签纸").single()
        assertEquals("标签纸", r.tag)
    }

    @Test fun multipleMarkersInSameSegment_lastOneWins() {
        val r = parseAll("交作业 标签工作 标签作业").single()
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun timeWithColonIsNotBrokenByMarkerParsing() {
        // 冒号故意不进 splitTokens：8:30 要整段解析成时刻，不能为了认「标签」把时刻拆坏。
        val r = parse("8:30 晨会 标签工作")
        assertEquals("晨会", r.title)
        assertEquals("工作", r.tag)
        assertEquals(LocalTime.of(8, 30), r.dueTime)
    }

    @Test fun legacyHashSyntaxStillWorks() {
        // §59 旧写法不强制、也不报错：老用户输入习惯与旧备份数据都能读回。
        assertEquals("作业", parse("交作业 #作业").tag)
        assertEquals("作业", parse("交作业#作业").tag)
        assertEquals("作业", parse("交作业：#作业").tag)
    }
}
