package com.lirui.oneline.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TagRules] 的纯函数单测（v1.0.15 / DESIGN §60）。
 *
 * 与 Parser 的解析单测互补：Parser 负责把日期/时间剥干净、给出文字段，
 * TagRules 只负责「哪段是 `标签xxx` 标记、其余段怎么分标题/详情」的切分。
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

    @Test fun twoParts_withoutMarker_lastIsDetail() {
        // §60：详情槽回归 v0.7 语义，不再被「末尾段即标签」抢走。
        val r = TagRules.split(listOf("交作业", "带电脑"))
        assertEquals("交作业", r.title)
        assertEquals("带电脑", r.detail)
        assertNull(r.tag)
    }

    @Test fun threeParts_withoutMarker_allTailBecomesDetail() {
        val r = TagRules.split(listOf("交作业", "带电脑", "去图书馆"))
        assertEquals("交作业", r.title)
        assertEquals("带电脑 去图书馆", r.detail)
        assertNull(r.tag)
    }

    @Test fun markerAtSegmentStart_createsTag() {
        val r = TagRules.split(listOf("交作业", "标签作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun markerAfterColonInsideOneSegment_createsTag() {
        // 冒号故意不进 splitTokens（8:30 会被拆坏），整段由 TagRules 自己切。
        val r = TagRules.split(listOf("交作业：标签作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun markerAfterAsciiColonInsideOneSegment_createsTag() {
        // Parser.normalize 会把全角「：」转成半角「:」，两种都要认。
        val r = TagRules.split(listOf("交作业:标签作业"))
        assertEquals("交作业", r.title)
        assertEquals("作业", r.tag)
    }

    @Test fun markerWithValueColonAlsoWorks() {
        val r = TagRules.split(listOf("交作业：标签：作业"))
        assertEquals("交作业", r.title)
        assertEquals("作业", r.tag)
    }

    @Test fun markerWithMiddleDetail_keepsDetail() {
        val r = TagRules.split(listOf("写报告", "带电脑", "标签工作"))
        assertEquals("写报告", r.title)
        assertEquals("带电脑", r.detail)
        assertEquals("工作", r.tag)
    }

    @Test fun markerInsideWord_isNotATag() {
        // 「买标签纸」里的「标签」前面是「买」，既不是段首也不是边界 -> 是标题的一部分。
        val r = TagRules.split(listOf("买标签纸"))
        assertEquals("买标签纸", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun markerOnly_yieldsEmptyTitle() {
        // 只有标记没有标题：addParsed 判 Blank，UI 会禁用提交。
        val r = TagRules.split(listOf("标签作业"))
        assertEquals("", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun bareMarkerWordHasNoValue() {
        val r = TagRules.split(listOf("交作业", "标签"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertNull(r.tag)
    }

    @Test fun multipleMarkers_lastOneWins() {
        val r = TagRules.split(listOf("标签工作", "交作业", "标签作业"))
        assertEquals("交作业", r.title)
        assertNull(r.detail)
        assertEquals("作业", r.tag)
    }

    @Test fun hashStillWorksForCompatibility() {
        assertEquals("作业", TagRules.split(listOf("交作业", "#作业")).tag)
        assertEquals("作业", TagRules.split(listOf("交作业#作业")).tag)
    }

    @Test fun normalize_stripsBoundaryAndHashAndTakesFirstWordOnly() {
        assertEquals("课外", TagRules.normalize("#课外 作业"))
        assertEquals("课外", TagRules.normalize("课外 作业"))
        assertEquals("作业", TagRules.normalize("  作业  "))
    }

    @Test fun normalize_doesNotStripChineseMarkerWord() {
        // ★ chip 里输的是「值」不是句子：值本身可以叫「标签纸」/「标签课外」。
        //   「标签作业」和「标签纸」字面结构一样，抹前缀就必然削错其中一个。
        assertEquals("标签纸", TagRules.normalize("标签纸"))
        assertEquals("标签课外", TagRules.normalize("标签课外 作业"))
        assertEquals("标签", TagRules.normalize("标签：作业"))
    }

    @Test fun split_markerValueStartingWithTagWordIsNotStrippedTwice() {
        // 标记词只在 split 里剥一次：句子「标签标签纸」的标签值是「标签纸」，不是「纸」。
        val r = TagRules.split(listOf("交作业", "标签标签纸"))
        assertEquals("交作业", r.title)
        assertEquals("标签纸", r.tag)
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
