package com.lirui.oneline.parser

/**
 * 一句话待办的「显式标签」切分规则（v1.0.15，DESIGN §59）。
 *
 * ★ 纯 Kotlin、不碰 Android —— 项目没有 Robolectric，带 Context / android.jar 的逻辑
 *   在本地单测里跑不了。判定与格式化一律放这里，Parser 只负责把切好的段传进来。
 *
 * 口径（zuige 2026-11-07 二次拍板，推翻 §58.1 的「末尾段即标签」）：
 * - **只有带 `#` 的段才是标签**（`#作业` / `交作业#作业`）；不带 `#` 时整句没有标签；
 * - 其余文字段回归 v0.7 语义：**第一段 = 标题，其余全部 = 详情**；
 * - 一条待办一个标签；同时出现多个 `#` 段时取**最后一个**（句末标签优先）；
 * - 标签是单个词，不长于 [MAX_LENGTH] 个「字」（按 Unicode code point 计，emoji 不切半）。
 *
 * 为什么改：§58 的「末尾段即标签」让「标题 详情」的详情槽凭空消失，且编辑态
 * `Format.editSentence` 回填的句子不含标签，重解析时末尾段被吃掉后又被钉回库值，
 * 用户在编辑框里新打的字会**静默消失**。显式 `#` 把「标签」和「文字」彻底分开，
 * 添加 / 编辑可以共用同一套解析，不再需要 §58 的 `parseForEdit` 锚定补丁。
 *
 * ★ 日期 / 时间 / 重复词在 Parser 里已被剥离，不会出现在 [split] 收到的 parts 里。
 */
object TagRules {

    /** 标签徽章只有 80dp，20 字在列表里几乎必然全是省略号；按可见宽度收到 8 字。 */
    const val MAX_LENGTH = 8

    /** 标签前缀。`Format.editSentence` 回填、chip 编辑框提示都读它，别各写一份字面量。 */
    const val MARK = '#'

    /** 只用于 [normalize]：把用户输入里的残留分隔符（含 `#`）切开，取第一个词。 */
    private val SEPARATORS = Regex("[\\s，,。、　&；;·#]+")

    /**
     * 规范化一个标签：抹掉 `#` 前缀、只取第一个词、去掉残留分隔符、限长。
     * 预览卡片编辑框里用户可以打「#课外 作业」，这里收敛成「课外」。
     */
    fun normalize(raw: String): String =
        raw.trimStart(MARK)
            .split(SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .takeCodePoints(MAX_LENGTH)

    /**
     * 把 Parser 切好的文字段拆成 标题 / 详情 / 标签。
     *
     * 标签段（含 `#`）会被从文字段里摘掉；紧贴写法「交作业#作业」里 `#` 之前的
     * 文字仍留在原来的位置。没有任何 `#` 时 [TagSplit.tag] 恒为 null，
     * 第二段起全部归 [TagSplit.detail]（v0.7 语义）。
     *
     * @param parts 已经去掉日期、时间、重复词、空段的文字段，顺序即输入顺序。
     */
    fun split(parts: List<String>): TagSplit {
        if (parts.isEmpty()) return TagSplit("", null, null)
        val words = mutableListOf<String>()
        var tag: String? = null
        for (part in parts) {
            val idx = part.indexOf(MARK)
            if (idx < 0) {
                words += part
                continue
            }
            val before = part.substring(0, idx).trim()
            if (before.isNotEmpty()) words += before
            // 多个 # 段：最后一个生效；前面的 # 段只贡献它前面的文字，不丢用户输入的字。
            normalize(part.substring(idx + 1)).takeIf { it.isNotBlank() }?.let { tag = it }
        }
        val title = words.firstOrNull().orEmpty()
        val detail = words.drop(1).joinToString(" ").trim().ifBlank { null }
        return TagSplit(title = title, detail = detail, tag = tag)
    }

    /** 按 Unicode code point 截断，`take(n)` 会把 emoji 的代理对切成半个字符。 */
    private fun String.takeCodePoints(n: Int): String {
        var i = 0
        var count = 0
        while (i < length && count < n) {
            i += Character.charCount(codePointAt(i))
            count++
        }
        return substring(0, i)
    }
}

/** [TagRules.split] 的结果。三个槽位互不重叠，不会有两处显示同一段文字。 */
data class TagSplit(
    val title: String,
    val detail: String?,
    val tag: String?
)
