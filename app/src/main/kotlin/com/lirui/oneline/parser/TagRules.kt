package com.lirui.oneline.parser

/**
 * 一句话待办的「末尾标签」切分规则（v1.0.15）。
 *
 * ★ 纯 Kotlin、不碰 Android —— 项目没有 Robolectric，带 Context / android.jar 的逻辑
 *   在本地单测里跑不了。判定与格式化一律放这里，Parser 只负责把切好的段传进来。
 *
 * 口径（zuige 2026-11-07 拍板）：
 * - 句子按 [com.lirui.oneline.parser.Parser] 现有的分隔符（空格、中英文逗号、顿号、
 *   句号、分号、`·`、`&`）切成文字段后，**最后一段就是标签**；
 * - **一条待办一个标签**，标签是单个词，不长于 [MAX_LENGTH] 个字；
 * - 只有一段时整段是标题，没有标签（「买菜」不能变成一条没有标题、只有标签的待办）；
 * - 两段及以上时：第一段 = 标题，中间段 = 详情，最后一段 = 标签。
 *   ⚠ 这改掉了 v0.7 起「第一段之后全部是详情」的旧语义 —— 两段输入的详情槽位让给了
 *   标签。想保留长详情就在预览卡片点「+ 详情」手动填（[detail] 中间段仍照常拼接）。
 *
 * ★ 日期 / 时间 / 重复词在 Parser 里已被剥离，不会出现在 [split] 收到的 parts 里，
 *   所以「明天 交作业 作业」和「交作业 作业 明天」拿到的是同一组文字段。
 */
object TagRules {

    /** 标签显示宽度有限（列表右侧要和「每天」、日期挤一行），超长直接截断。 */
    const val MAX_LENGTH = 20

    private val SEPARATORS = Regex("[\\s，,。、　&；;·]+")

    /**
     * 规范化一个标签：只取第一个词（「一条一个」）、去掉残留分隔符、限长。
     * 预览卡片编辑框里用户可以打「课外 作业」，这里收敛成「课外」而不是拼成脏字符串。
     */
    fun normalize(raw: String): String =
        raw.split(SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .take(MAX_LENGTH)

    /**
     * 把 Parser 切好的文字段拆成 标题 / 详情 / 标签。
     *
     * @param parts 已经去掉日期、时间、重复词、空段的文字段，顺序即输入顺序。
     */
    fun split(parts: List<String>): TagSplit {
        if (parts.isEmpty()) return TagSplit("", null, null)
        if (parts.size == 1) return TagSplit(parts[0], null, null)

        val tag = normalize(parts.last()).takeIf { it.isNotBlank() }
        // 末尾段被标签占用了，中间段才归详情；末尾段本身不再重复留在详情里。
        val detail = parts.drop(1).dropLast(1)
            .joinToString(" ")
            .trim()
            .ifBlank { null }
        return TagSplit(title = parts.first(), detail = detail, tag = tag)
    }
}

/** [TagRules.split] 的结果。三个槽位互不重叠，不会有两处显示同一段文字。 */
data class TagSplit(
    val title: String,
    val detail: String?,
    val tag: String?
)
