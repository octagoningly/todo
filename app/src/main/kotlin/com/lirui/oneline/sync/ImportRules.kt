package com.lirui.oneline.sync

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 平台作业 → 待办的**字段映射**（§85）。
 *
 * ★ 纯函数、无 Android 依赖，可本地单测。
 *
 * 三条过滤规则（不做就会把列表写废）：
 * 1. **只要本学期**。实测课堂派 7 门课里 6 门是上学期、共 80 条作业；
 *    雨课堂 43 条有截止的里 41 条已过期。不筛就没法用。
 * 2. **只要未过期**。
 * 3. **只要作业类**。雨课堂的公告(type=9)/课件(type=14)不是待办。
 */
object ImportRules {

    /** 一条待导入的作业（已从两个平台归一化）。 */
    data class ImportItem(
        val platform: SyncPlatform,
        val externalId: String,
        val courseName: String,
        val title: String,
        /** 绝对截止时间，epoch **毫秒**。 */
        val deadlineMillis: Long,
        /** 作业 / 小测 / 考查 / 考试（雨课堂按标题分，课堂派统一"作业"）。 */
        val kind: String,
        /** 平台侧是否已有提交状态（课堂派没有，雨课堂有 status）。 */
        val submitted: Boolean = false
    )

    /** 一条预览行：给用户确认用。 */
    data class PreviewLine(
        val item: ImportItem,
        /** 待办标题（科目 + 标题拼好）。 */
        val todoTitle: String,
        /** 截止日期，`yyyy-MM-dd`。 */
        val dueDate: String,
        /** 截止时刻，`HH:mm`；平台给的是 00:00 时按「当天结束」理解为 23:59。 */
        val dueTime: String,
        /** 去重键。 */
        val dedupeKey: String
    )

    /**
     * 拼待办标题：`科目 · 类型 · 原标题`。
     *
     * 例子：`复变函数与数学物理方法 · 作业 · 5.2 傅里叶积分与傅里叶变换`
     *
     * ★ 为什么带「类型」：雨课堂里 `第九讲作业` 与 `小测1` 都是 type=5，
     *   不标出来在待办列表里完全分不清性质。
     */
    fun todoTitle(courseName: String, kind: String, title: String): String {
        val c = courseName.trim()
        val k = kind.trim()
        val t = title.trim()
        return listOf(c, k, t).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * 截止毫秒 → (日期, 时刻)。
     *
     * ★ **`00:00` 的处理**（实测踩到的坑）：
     *   课堂派「5.3 Delta函数」的 endtime 是 `2026-11-19 00:00`。
     *   平台的语义是「**19 号结束**」，而不是「19 号零点就截止」。
     *   若原样显示 `00:00`，用户会以为 18 号晚上 24 点前要交，
     *   实际还有一整天。这里统一改成 `23:59`，并在测试里钉死。
     */
    fun deadlineParts(millis: Long, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Pair<String, String> {
        val zdt = Instant.ofEpochMilli(millis).atZone(zone)
        val date = zdt.toLocalDate()
        val time = zdt.toLocalTime()
        // 0 点视为「当天结束」
        val shown = if (time == LocalTime.MIDNIGHT) LocalTime.of(23, 59) else time
        return date.toString() to String.format("%02d:%02d", shown.hour, shown.minute)
    }

    /**
     * 是否应该导入这一条。
     *
     * @param currentSemester 当前学期标签，如 `2026-2027/1`。传 null 表示不按学期筛。
     * @param nowMillis 现在（便于测试注入）。
     */
    fun shouldImport(
        item: ImportItem,
        currentSemester: String?,
        nowMillis: Long
    ): Boolean {
        // 1. 必须有绝对、可用的截止时间（0 与越界值都排除）
        if (!NativeProbeRunner.isDeadlineUsable(item.deadlineMillis)) return false
        // 2. 必须未过期
        if (item.deadlineMillis <= nowMillis) return false
        // 3. 必须有科目与标题
        if (item.courseName.isBlank() || item.title.isBlank()) return false
        return true
    }

    /**
     * 按学期筛（课堂派/雨课堂都给了学期标签）。
     * 标签为空时不筛（宁可多导也不要漏导）。
     */
    fun matchSemester(semesterTag: String?, currentSemester: String?): Boolean {
        if (semesterTag.isNullOrBlank() || currentSemester.isNullOrBlank()) return true
        return semesterTag == currentSemester
    }

    /** 生成预览。按截止时间升序，最近的排前面。 */
    fun buildPreview(
        items: List<ImportItem>,
        semesterOf: (ImportItem) -> String?,
        currentSemester: String?,
        nowMillis: Long
    ): List<PreviewLine> =
        items
            .filter { matchSemester(semesterOf(it), currentSemester) }
            .filter { shouldImport(it, currentSemester, nowMillis) }
            .distinctBy { SyncRules.dedupeKey(it.platform, it.externalId) }
            .sortedBy { it.deadlineMillis }
            .map { item ->
                val (date, time) = deadlineParts(item.deadlineMillis)
                PreviewLine(
                    item = item,
                    todoTitle = todoTitle(item.courseName, item.kind, item.title),
                    dueDate = date,
                    dueTime = time,
                    dedupeKey = SyncRules.dedupeKey(item.platform, item.externalId)
                )
            }

    /** 预览摘要，给设置页显示一行。 */
    fun summarizePreview(lines: List<PreviewLine>): String {
        if (lines.isEmpty()) return "没有可导入的作业（可能都已过期或不在本学期）"
        val byPlatform = lines.groupingBy { it.item.platform.label }.eachCount()
        val head = byPlatform.entries.joinToString(" / ") { "${it.key} ${it.value} 条" }
        val firstFew = lines.take(3).joinToString("\n") {
            "· ${it.todoTitle} @${it.dueDate} ${it.dueTime}"
        }
        return "可导入 ${lines.size} 条（$head）\n$firstFew" +
            if (lines.size > 3) "\n…" else ""
    }
}
