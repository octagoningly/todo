package com.lirui.oneline.parser

import java.time.LocalDate
import java.time.LocalTime

/**
 * Result of parsing one input sentence.
 *
 * @param title  the description with date/time tokens removed (trimmed)
 * @param dueDate  the parsed due date; null if no date token was given (=> no-date group)
 * @param dateError  when true, a date token was recognized but resolved to a past date
 *                        and should not be used. This is an *error* per DESIGN §3.2 / user decision.
 * @param dueTime  the parsed time-of-day; null if only a date was given (=> default 09:00)
 * @param repeatDays  repeat step in days when the item is completed. 0 = no repeat.
 *                    1 = daily, 7 = weekly, 28/30 ≈ monthly. Driven by 每天/每周/每月/每周X.
 * @param detail  the free text between the event and the trailing tag. 详见 [TagRules.split]：
 *                「写报告 带电脑 作业」-> title 写报告, detail 带电脑, tag 作业。
 *                ⚠ v1.0.15 起末尾段归标签，不再并入详情。
 * @param tag     the single trailing tag (v1.0.15). null = 无标签。显示在列表「每天/每周」
 *                徽章旁、预览卡片的 chip 行，可在预览卡片上点开编辑。
 */
data class ParsedTodo(
    val title: String,
    val dueDate: LocalDate?,       // null => no-date item
    val dueTime: LocalTime?,       // null => no explicit time
    val repeatDays: Int = 0,       // 0 = no repeat
    val timeError: Boolean = false, // true if a recognizable "time-like" token was past/impossible
    val dateError: Boolean = false,  // true if a date was recognized but already past
    val detail: String? = null,     // free-text detail after the event title
    /** 时间段的开始日；null = 单日（[dueDate] 即唯一日期）。 */
    val startDate: LocalDate? = null,
    /** 时间区间的结束时刻（与 [dueTime] 同一天）；null = 无结束时刻。「下午3点到5点」→ 15:00~17:00。 */
    val endTime: LocalTime? = null,
    /** 末尾标签（单值、已规范化）；null / 空 = 无标签。见 [TagRules]。 */
    val tag: String? = null
) {
    /** The sanitized remainder after removing recognized tokens. */
    val cleanTitle: String get() = title.trim()

    val hasExactTime: Boolean get() = dueTime != null
}