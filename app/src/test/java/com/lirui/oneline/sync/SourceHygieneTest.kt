package com.lirui.oneline.sync

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码卫生检查 —— 防止「块注释里出现注释起始符」这类编译期才炸的低级错误。
 *
 * ★ 背景：本项目已经**三次**栽在同一处。Kotlin 的块注释支持**嵌套**，
 *   在 KDoc 里写路径通配（例如 v2 接口路径后面紧跟一个星号）时，
 *   那个「斜杠 + 星号」组合会被当成**开启一层新的注释**，
 *   于是整个文件少一个闭合符，编译报 Unclosed comment，
 *   而错误位置指向文件末尾，完全看不出真正的问题在几百行之前。
 *
 * ★ 本文件自身也必须遵守这条：注释里一律用文字描述（「斜杠加星号」），
 *   样本一律用字符拼接构造，绝不在源码里写出那两个字符的连写。
 *
 * ★ 为什么不用「数总数」的朴素做法：`ProbeJs.kt` 里满是 JS 的块注释，
 *   写在 Kotlin 原始字符串里，朴素计数会把正常文件判成坏的（实测算出 -2）。
 */
class SourceHygieneTest {

    /** 注释起始符的字符拼接形式，避免本文件自己触发嵌套。 */
    private val openMark = "/" + "*"

    /** 注释结束符的字符拼接形式。 */
    private val closeMark = "*" + "/"

    /**
     * 扫描 Kotlin 源码，返回块注释的最终深度（正常应为 0）。
     *
     * 处理三类干扰，避免把字符串内容误当注释：
     * 1. 行注释（其内部出现起始符不算嵌套）
     * 2. 普通字符串与三引号原始字符串
     * 3. 字符字面量
     */
    private fun commentDepth(src: String): Int {
        var depth = 0
        var i = 0
        val n = src.length
        while (i < n) {
            // 三引号原始字符串
            if (src.startsWith("\"\"\"", i) && depth == 0) {
                val end = src.indexOf("\"\"\"", i + 3)
                i = if (end < 0) n else end + 3
                continue
            }
            // 普通字符串
            if (src[i] == '"' && depth == 0) {
                i++
                while (i < n) {
                    if (src[i] == '\\') { i += 2; continue }
                    if (src[i] == '"') { i++; break }
                    i++
                }
                continue
            }
            // 字符字面量
            if (src[i] == '\'' && depth == 0) {
                i++
                while (i < n) {
                    if (src[i] == '\\') { i += 2; continue }
                    if (src[i] == '\'') { i++; break }
                    i++
                }
                continue
            }
            // 行注释
            if (src.startsWith("//", i)) {
                val end = src.indexOf('\n', i)
                i = if (end < 0) n else end
                continue
            }
            if (src.startsWith(openMark, i)) { depth++; i += 2; continue }
            if (src.startsWith(closeMark, i)) { depth--; i += 2; continue }
            i++
        }
        return depth
    }

    @Test
    fun `nested marker inside a block comment is detected`() {
        // 用拼接构造样本：KDoc 里出现「路径 + 通配星号」，且星号前有斜杠。
        // ★ 路径末尾不能再以斜杠结尾（否则拼出三连斜杠被当行注释），
        //   这里写成 web + 斜杠 + 星号 —— 正是会触发嵌套的那个形状。
        val broken = "/**" + "\n * 通配写在 /v2/api/web" + openMark + " 里。\n " +
            closeMark + "\nobject X"
        assertTrue("应能复现嵌套问题", commentDepth(broken) == 1)

        // 去掉通配星号后恢复正常。
        val fixed = "/**" + "\n * 通配写在 /v2/api/web 里。\n " +
            closeMark + "\nobject X"
        assertTrue("去掉通配后应归零", commentDepth(fixed) == 0)
    }

    @Test
    fun `string literals containing comment markers do not confuse the scanner`() {
        // ProbeJs.kt 的真实形态：JS 的块注释写在 Kotlin 原始字符串里。
        val src = "object P {\n" +
            "  private const val JS = \"\"\"\n" +
            "  // js line comment with " + openMark + " inside\n" +
            "  " + openMark + " js block " + closeMark + "\n" +
            "\"\"\"\n" +
            "  private val r = Regex(\"a" + openMark + "b\")\n" +
            "}"
        assertTrue("字符串内的注释标记不应影响深度", commentDepth(src) == 0)
    }

    @Test
    fun `probe sources have balanced block comments`() {
        val root = java.io.File("src/main/kotlin/com/lirui/oneline/sync")
        if (!root.exists()) return // 不在 app 模块根目录就跳过，别误红。
        listOf(
            "NativeProbeSpec.kt",
            "NativeProbeRunner.kt",
            "ProbeJs.kt",
            "ProbeRunner.kt",
            "SyncRules.kt",
            "SyncContract.kt",
            "SyncSettings.kt"
        ).forEach { name ->
            val f = java.io.File(root, name)
            if (!f.exists()) return@forEach
            val d = commentDepth(f.readText())
            assertTrue(
                "$name 的块注释不平衡（depth=$d）—— 极可能是注释里写了斜杠加星号",
                d == 0
            )
        }
    }
}
