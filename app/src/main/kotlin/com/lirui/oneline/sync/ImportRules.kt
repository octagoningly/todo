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
        val submitted: Boolean = false,
        /**
         * ★ §90 **学期标签**，如 `2026-2027/1`。null = 未知。
         *
         * 两个平台都拿得到：
         * - 课堂派：`semesterCourseList` 每条自带 `semester` + `term`；
         * - 雨课堂：`/v2/api/web/courses/list` 的 `term`（数字，如 `202601`）。
         *
         * ★ 有这个字段才能真正按学期筛。之前靠「只导未过期」间接挡，
         * 但**可以补交的往期作业截止日还没到**，会被误导入（§89.2 已知问题）。
         */
        val semesterTag: String? = null
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
     * 拼待办标题：`类型-科目`，没有科目时退回 `类型-原标题`。
     *
     * ★ §95 用户要求：「名字改为『作业-数字电路与……』这样，前面有个作业比较容易分辨出来」。
     *
     * 格式分层（越靠前越显眼）：
     * ```
     * 作业-数字电路与逻辑设计（一）
     * 小测-信号与系统
     * 作业-信号与系统·第九讲作业      ← 原标题与科目不同名时才追加
     * ```
     *
     * 为什么把**类型放最前**：待办列表只显示一行，用户扫一眼要先分清
     * 「这是作业还是小测还是考试」。类型放前面最省事。
     *
     * 为什么**科目**紧跟其后而不是原标题：科目是用户组织任务的维度
     * （「数字电路那门课有什么要交」），原标题常常就是科目名的重复
     * （雨课堂「第九讲作业」、课堂派「5.2 傅里叶积分…」都只说内容）。
     *
     * ★ 尾部的原标题只在**与科目不重复**时追加，避免出现
     *   「作业-信号与系统·信号与系统」这种复读。
     */
    fun todoTitle(courseName: String, kind: String, title: String): String {
        val c = courseName.trim()
        val k = kind.trim().ifBlank { "作业" }
        val t = title.trim()
        val head = if (c.isNotBlank()) "$k-$c" else if (t.isNotBlank()) "$k-$t" else k
        // 标题已包含科目名（或就是科目名）时不再追加，否则追加在后面便于辨别具体哪一次。
        val needTail = t.isNotBlank() && c.isNotBlank() &&
            !t.contains(c) && !c.contains(t) && t != c
        return if (needTail) "$head·$t" else head
    }

    /**
     * ★ §91 **截止时间归一**：平台给 `某天 00:00` 时，真实截止是**前一天 23:59**。
     *
     * 用户明确要求：「如果作业的截止时间是某天的 0:00 分，就将这个时间导入为
     * 某天的前一天的 23:59 分」。
     *
     * 语义依据：课堂派「5.3 Delta函数」的 `endtime = 2026-11-19 00:00`，
     * 平台的真实意思是「**19 号结束**」，也就是 18 号最后一刻。
     * 之前只在**显示**上改成 23:59，但落库的 `dueAtMillis` 仍是 19 号 00:00，
     * 导致提醒排在 19 号零点（比真实截止晚 1 分钟），而且列表排序也会差一天。
     *
     * 现在统一在**入口**就把毫秒值改掉：`00:00` → 前一天 `23:59`（减 1 分钟）。
     * 非零点的截止（如雨课堂 `22:00`）原样保留。
     */
    fun normalizeDeadline(millis: Long, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Long {
        if (millis <= 0L) return millis
        val zdt = Instant.ofEpochMilli(millis).atZone(zone)
        // 只在「恰好 0 点整」时调整；0:00:30 这种脏值也一并当 0 点处理更安全。
        if (zdt.toLocalTime().isBefore(LocalTime.of(0, 1))) {
            return millis - 60_000L
        }
        return millis
    }

    /**
     * 截止毫秒 → (日期, 时刻)，**按归一后的时间显示**。
     *
     * 归一后 0 点已变成前一天 23:59，所以这里不再需要特判 0 点，
     * 但保留一次兜底（万一上游漏调 [normalizeDeadline]）。
     */
    fun deadlineParts(millis: Long, zone: ZoneId = ZoneId.of("Asia/Shanghai")): Pair<String, String> {
        val normalized = normalizeDeadline(millis, zone)
        val zdt = Instant.ofEpochMilli(normalized).atZone(zone)
        val shown = if (zdt.toLocalTime().isBefore(LocalTime.of(0, 1))) {
            LocalTime.of(23, 59)
        } else {
            zdt.toLocalTime()
        }
        return zdt.toLocalDate().toString() to
            String.format("%02d:%02d", shown.hour, shown.minute)
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
        // 2. 必须未过期（用**归一后**的时间判，否则 00:00 那条会多活一分钟）
        if (normalizeDeadline(item.deadlineMillis) <= nowMillis) return false
        // 3. 必须有科目与标题
        if (item.courseName.isBlank() || item.title.isBlank()) return false
        return true
    }

    /**
     * 按学期筛。
     *
     * ★ §90 三条口径：
     * 1. **条目有学期标签** → 必须与当前学期**相等**才导入。
     * 2. **条目没标签**（平台没给）→ 放行。宁可多导一条，也不要因为拿不到
     *    学期就把正常作业全滤掉。
     * 3. **当前学期未知** → 不筛。
     *
     * 比较前统一把 `2026-2027/1` 与 `202601` 这种写法归一，
     * 见 [normalizeSemester]。
     */
    fun matchSemester(semesterTag: String?, currentSemester: String?): Boolean {
        if (semesterTag.isNullOrBlank() || currentSemester.isNullOrBlank()) return true
        return normalizeSemester(semesterTag) == normalizeSemester(currentSemester)
    }

    /**
     * 学期写法归一（§90）。
     *
     * 两种来源写法不同：
     * - 课堂派：`2026-2027` + `1` → `2026-2027/1`
     * - 雨课堂：数字 `202601` → `2026-2027/1`
     *
     * 统一成 `yyyy-yyyy/t` 便于比较。认不出来的原样返回（不让格式差异误杀数据）。
     */
    fun normalizeSemester(raw: String?): String {
        val s = raw.orEmpty().trim()
        if (s.isEmpty()) return ""
        // 已经是 yyyy-yyyy/t 形式
        if (s.matches(Regex("""\d{4}-\d{4}/\d"""))) return s
        // 数字学期：202601 → 2026-2027/1
        if (s.matches(Regex("""\d{6}"""))) {
            val year = s.substring(0, 4)
            val term = s.substring(5, 6)
            return "$year-${year.toInt() + 1}/$term"
        }
        // 课堂派可能给 "2026-2027/1 陈琨"（带教师名），只取前半
        val m = Regex("""(\d{4}-\d{4})/(\d)""").find(s)
        if (m != null) return "${m.groupValues[1]}/${m.groupValues[2]}"
        return s
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
