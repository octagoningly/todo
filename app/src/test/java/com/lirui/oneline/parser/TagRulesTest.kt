package com.lirui.oneline.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TagRules] 的纯函数单测（v1.0.15）。
 *
 * ★ 这个类和 Parser 的解析单测互补：Parser 负责把日期/时间剥干净、给出文字段，
 *   TagRules 只负责「哪段是标题 / 详情 / 标签」的切分。判定抽成无 Android 依赖的
 *   纯函数正是为了能在 JVM 上跑（项目没有 Robolectric）。
 */
class TagRulesTest {

    @Test fun emptyParts_yieldsEmptyTitle() {
        val r = TagRules.split(emptyList())
        assertEquals("", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun singlePart_isTitleOnly() {
        // 「买菜」不能变成一条没有标题、只有标签的待办。
        val r = TagRules.split(listOf("买菜"))
        assertEquals("买菜", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun twoParts_lastIsTag_noDetail() {
        val r = TagRules.split(listOf("交作业", "作业"))
        assertEquals("交作业", r.title)
        assertEquals("作业", r.tag)
        assertNull(r.detail)
    }

    @Test fun threeParts_middleIsDetail_lastIsTag() {
        val r = TagRules.split(listOf("交作业", "带电脑", "去图书馆", "作业"))
        assertEquals("交作业", r.title)
        assertEquals("带电脑 去图书馆", r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun split_neverDuplicatesLastSegmentIntoDetail() {
        val parts = listOf("组会", "要带笔记本", "重要")
        val r = TagRules.split(parts)
        assertEquals("要带笔记本", r.detail)
        assertEquals("重要", r.tag)
        assertEquals(false, r.detail?.contains("重要"))
    }

    @Test fun normalize_takesFirstWordOnly() {
        assertEquals("课外", TagRules.normalize("课外 作业"))
        assertEquals("数学", TagRules.normalize("数学，英语"))
    }

    @Test fun normalize_stripsResidualSeparatorsAndTrims() {
        assertEquals("作业", TagRules.normalize("  作业  "))
        assertEquals("", TagRules.normalize(""))
        assertEquals("", TagRules.normalize("   "))
    }

    @Test fun normalize_limitsLength() {
        val long = "标".repeat(TagRules.MAX_LENGTH + 10)
        assertEquals(TagRules.MAX_LENGTH, TagRules.normalize(long).length)
    }

    @Test fun normalize_separatorOnlyIsBlank() {
        // 预览编辑框里只打标点：保存后应收敛成「无标签」。
        assertEquals("", TagRules.normalize("，"))
        assertEquals("", TagRules.normalize("  、 "))
    }
}
