package com.lirui.oneline.parser

/**
 * 一句话待办的「语义标签」切分规则（v1.0.15，DESIGN §60）。
 *
 * ★ 纯 Kotlin、不碰 Android —— 项目没有 Robolectric，带 Context / android.jar 的逻辑
 *   在本地单测里跑不了。判定与格式化一律放这里，Parser 只负责把切好的段传进来。
 *
 * 口径（zuige 2026-11-07 第三次拍板，推翻 §59 的强制 `#`）：
 * - **不用打任何符号**：句中写「标签作业」，「标签」后面紧跟的字就是标签；
 * - 「标签」必须**在段首，或紧跟冒号 / 空格 / 逗号等边界**，否则不认 ——
 *   「买标签纸」里的「标签」是标题的一部分，不能被切成「买」+ 标签「纸」；
 * - 没有标记词时整句没有标签，第一段 = 标题、其余全部 = 详情（v0.7 语义）；
 * - `#作业` / `交作业#作业` 仍兼容（§59 旧写法，不强制、也不报错）；
 * - 一条待办一个标签；多个标记取最后一个；标签最长 [MAX_LENGTH] 个 code point；
 * - 标记词只在句子里剥一次，预览 chip 里直接输标签值（见 [normalize]）。
 *
 * 为什么改：§59 的 `#` 是额外符号，违背「一句话记一件事」的初衷。换成中文标记词后
 * 输入仍是纯文字，且「标题 详情」的详情槽、编辑态吞字（§58 P0）都不会回来 ——
 * 编辑回填句里的标签也写成「标签作业」，用户看得见、改得动。
 *
 * ★ 日期 / 时间 / 重复词在 Parser 里已被剥离，不会出现在 [split] 收到的 parts 里。
 * ★ 冒号**故意不进** `Parser.splitTokens` 的分隔符：`8:30` / `８：３０` 要靠整段解析，
 *   拆开会坏时刻。这里在段内自己认「标签」前面的冒号。
 */
object TagRules {

    /** 标签徽章只有 80dp，按可见宽度收到 8 字。 */
    const val MAX_LENGTH = 8

    /** 主标记词：`Format.editSentence` 回填、chip 提示都读它。 */
    const val TAG_WORD = "标签"

    /** 兼容 §59 旧写法的符号标记，不强制。 */
    const val MARK = '#'

    /** 归一化用：切开残留分隔符，取第一个词。含 `#` / 冒号，兼容旧写法。 */
    private val SEPARATORS = Regex("[\\s，,。、　&；;·：:#]+")

    /** 标记词前面允许出现的边界字符（含冒号；刻意不含数字，避免误伤时刻）。 */
    private fun Char.isBoundary(): Boolean =
        isWhitespace() || this in "，,。、　&；;·：:#"

    /** 找标记词位置：段首，或前一个字符是边界。找不到返回 -1。 */
    private fun markerStart(part: String, marker: String): Int {
        var from = 0
        while (from <= part.length - marker.length) {
            val i = part.indexOf(marker, from)
            if (i < 0) return -1
            if (i == 0 || part[i - 1].isBoundary()) return i
            from = i + marker.length
        }
        return -1
    }

    /**
     * 规范化一个**标签值**：抹掉残留分隔符 / `#` 等边界字符、只取第一个词、按 code point 限长。
     *
     * ★ 这里**不认中文标记词 [TAG_WORD]** —— 标记词只在句子里由 [split] 剥掉一次。
     *   「标签作业」（标记 + 值「作业」）与「标签纸」（值本身就叫「标签纸」）字面结构
     *   完全一样，靠抹前缀无法区分；在 chip 里再抹一次会把「标签标签纸」削成「纸」，
     *   也会让用户在编辑框里输不了「标签纸」这个值。
     *   所以：句子里写「标签xxx」是语法，chip 里输什么就是什么值。
     */
    fun normalize(raw: String): String =
        raw.trim { it.isBoundary() }
            .split(SEPARATORS)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .takeCodePoints(MAX_LENGTH)

    /** 找一段里最靠前的有效标记，返回「位置 - 长度」；没有返回 null。 */
    private fun firstMarker(text: String): Pair<Int, Int>? {
        val hash = text.indexOf(MARK)
        val word = markerStart(text, TAG_WORD)
        return when {
            hash >= 0 && (word < 0 || hash <= word) -> hash to 1
            word >= 0 -> word to TAG_WORD.length
            else -> null
        }
    }

    /**
     * 把 Parser 切好的文字段拆成 标题 / 详情 / 标签。
     *
     * 每个段里按标记（「标签」或 `#`）逐次切开：标记之前（去掉尾随边界字符）的文字留在
     * 原位置，标记之后**第一个词**收敛成标签值，**之后的文字继续留在详情里**。
     * 没有任何标记时 [TagSplit.tag] 恒为 null，第二段起全部归 [TagSplit.detail]（v0.7 语义）。
     *
     * ★ 「标签值之后继续写的字」不能丢：编辑回填句是「标题 详情 标签作业」，用户在句末
     *   接着打字时，这些字跟在标签后面；只取第一个词就静默吞字（§58 P0 的同款变体）。
     *   多个标记时最后一个生效，同段内的第二个标记也照常覆盖。
     *
     * @param parts 已经去掉日期、时间、重复词、空段的文字段，顺序即输入顺序。
     */
    fun split(parts: List<String>): TagSplit {
        if (parts.isEmpty()) return TagSplit("", null, null)
        val words = mutableListOf<String>()
        var tag: String? = null
        for (part in parts) {
            var rest = part
            while (rest.isNotEmpty()) {
                val marker = firstMarker(rest)
                if (marker == null) {
                    rest.trim { it.isBoundary() }.takeIf { it.isNotEmpty() }?.let { words += it }
                    break
                }
                val (cut, markerLen) = marker
                rest.substring(0, cut).trim { it.isBoundary() }
                    .takeIf { it.isNotEmpty() }?.let { words += it }

                val after = rest.substring(cut + markerLen)
                // 「标签：作业」这类写法：先跳过值前面的冒号等边界符，再找第一个分隔符。
                val valueStart = after.indexOfFirst { !it.isBoundary() }.let {
                    if (it < 0) after.length else it
                }
                val valueEnd = SEPARATORS.find(after, valueStart)?.range?.first ?: after.length
                // 走 [normalize] 而不是再剥一次标记词：「标签标签纸」的值必须保住「标签纸」。
                normalize(after.substring(valueStart, valueEnd))
                    .takeIf { it.isNotBlank() }?.let { tag = it }
                rest = if (valueEnd < after.length) after.substring(valueEnd) else ""
            }
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
