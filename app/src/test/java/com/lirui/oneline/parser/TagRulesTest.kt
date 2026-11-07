package com.lirui.oneline.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TagRules] 的纯函数单测（v1.0.15 / DESIGN §59）。
 *
 * 与 Parser 的解析单测互补：Parser 负责把日期/时间剥干净、给出文字段，
 * TagRules 只负责「哪段是 `#` 标签、其余段怎么分标题/详情」的切分。
 * 判定抽成无 Android 依赖的纯函数正是为了能在 JVM 上跑（项目没有 Robolectric）。
 */
class TagRulesTest {

    @Test fun emptyParts_yieldsEmptyTitle() {
        val r = TagRules.split(emptyList())
        assertEquals("", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun singlePart_isTitleOnly() {
        val r = TagRules.split(listOf("买菜"))
        assertEquals("买菜", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun twoParts_withoutHash_lastIsDetail() {
        // §59：详情槽回归 v0.7 语义，不再被「末尾段即标签」抢走。
        val r = TagRules.split(listOf("交作业", "带电脑"))
        assertEquals("交作业", r.title)
        assertEquals("带电脑", r.detail)
        assertNull(r.tag)
    }

    @Test fun threeParts_withoutHash_allTailBecomesDetail() {
        val r = TagRules.split(listOf("交作业", "带电脑", "去图书馆"))
        assertEquals("交作业", r.title)
        assertEquals("带电脑 去图书馆", r.detail)
        assertNull(r.tag)
    }

    @Test fun hashSegment_isTag_notDetail() {
        val r = TagRules.split(listOf("交作业", "#作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun hashSegment_withDetail_keepsMiddleAsDetail() {
        val r = TagRules.split(listOf("写报告", "带电脑", "#工作"))
        assertEquals("写报告", r.title)
        assertEquals("带电脑", r.detail)
        assertEquals("工作", r.tag)
    }

    @Test fun hashCanLeadTheSentence() {
        val r = TagRules.split(listOf("#工作", "写报告"))
        assertEquals("写报告", r.title)
        assertNull(r.detail)
        assertEquals("工作", r.tag)
    }

    @Test fun inlineHash_splitsTextBeforeMark() {
        // 中文输入常不打空格：「交作业#作业」也要认标签，且 # 之前的文字不能丢。
        val r = TagRules.split(listOf("交作业#作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun multipleHashSegments_lastOneWins() {
        val r = TagRules.split(listOf("#工作", "交作业", "#作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun hashOnly_yieldsEmptyTitle() {
        // 只有标签没有标题：addParsed 判 Blank，UI 会禁用提交。
        val r = TagRules.split(listOf("#作业"))
        assertEquals("", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun bareHashIsNotATag() {
        val r = TagRules.split(listOf("交作业", "#"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun normalize_stripsHashAndTakesFirstWordOnly() {
        assertEquals("课外", TagRules.normalize("#课外 作业"))
        assertEquals("数学", TagRules.normalize("#数学，英语"))
        assertEquals("作业", TagRules.normalize("  作业  "))
    }

    @Test fun normalize_separatorOnlyIsBlank() {
        assertEquals("", TagRules.normalize(""))
        assertEquals("", TagRules.normalize("   "))
        assertEquals("", TagRules.normalize("，"))
        assertEquals("", TagRules.normalize("  、 "))
        assertEquals("", TagRules.normalize("#"))
    }

    @Test fun normalize_limitsLength() {
        val long = "标".repeat(TagRules.MAX_LENGTH + 10)
        assertEquals(TagRules.MAX_LENGTH, TagRules.normalize(long).length)
    }

    @Test fun normalize_doesNotSplitEmojiSurrogatePair() {
        // take(8) 按 UTF-16 char 截断会把 emoji 的代理对切成半个字符，徽章显示乱码。
        val emoji = "😀".repeat(TagRules.MAX_LENGTH + 2)
        val result = TagRules.normalize(emoji)
        assertEquals(TagRules.MAX_LENGTH * 2, result.length) // 每个 emoji 占 2 个 char
        assertEquals(TagRules.MAX_LENGTH, result.codePointCount(0, result.length))
    }
}
