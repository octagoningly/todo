package com.lirui.oneline.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * §66 文字级「末端展开 / 末端收起」的作用域参数。
 *
 * @param visible true = 显示整句文字；false = 收起到空串（宽度 0、仍占一行高度）。
 * @param reduceMotion 系统「减少动态效果」或设置里关掉「页面切换动效」时不播。
 * @param replayKey 当前所在的 tab，仅用于说明与调试；显隐由 [visible] 驱动。
 */
data class TextEnterSpec(
    val visible: Boolean,
    val reduceMotion: Boolean = false,
    val replayKey: Any? = null
)

/**
 * null = 当前不在「四个 tab 的文字层」里（顶栏 / 底栏 / FAB / 弹窗等），
 * 文字保持原样、一点动画都不加 —— 这样作用域不会意外扩到卡片或容器上。
 */
val LocalTextEnter = staticCompositionLocalOf<TextEnterSpec?> { null }

/**
 * 与 M3 `Text` 同签名的包装：四个 tab 页面里的文字统一获得官方默认转场。
 *
 * ★ 严格照 `T8RIN/ImageToolbox`（Apache-2.0）的结构：`AnimatedContent` **只包 `Text`**，
 *   `Card` / `Row` / 图标 / 背景全在动画作用域外（`PreferenceItemOverload.kt`、
 *   `PreferenceRow.kt`、`MainTopAppBar.kt`、`TopAppBarTitle.kt`）。只借结构，不引依赖。
 *
 * ★ 这里**故意不写 `transitionSpec`**，走 `AnimatedContentDefaults.ContentTransform`：
 *   ```
 *   (fadeIn(tween(220, delayMillis = 90)) +
 *    scaleIn(initialScale = 0.92f, tween(220, delayMillis = 90)))
 *       togetherWith fadeOut(tween(90))
 *   ```
 *   而 `ContentTransform` 的 `sizeTransform` 默认就是 `SizeTransform()`
 *   （`clip = true` + `spring(StiffnessMediumLow)`），**即使显式写了 spec 也不会消失**。
 *   默认 `contentAlignment = Alignment.TopStart`：文字左上角钉住，容器宽度变化时
 *   裁剪边界停在句子右端（末端）—— 宽度 0→full 就是「往末端展开」，
 *   full→0 就是「从末端收起」；`fadeOut(90)` + `scaleIn(0.92f)` 即「先回撤一下」。
 *
 * ★ 收起态用**空串**而不是空 composable：Compose 的空串 `Text` 仍占一行高度，
 *   宽度却是 0 —— 卡片高度不塌，`SizeTransform` 才有宽度可动画。
 *
 * ★ `modifier` 挂在 `AnimatedContent` 容器上，保证 `weight` / `fillMaxWidth` / `align`
 *   仍作用在原位置的那个 child 上，布局与改造前一致。
 *
 * 用「换 import」而不是逐个改 `Text(...)` 调用来收口作用域：只要某个文件不引入
 * 这个别名（顶栏外的弹窗 / 添加面板 / 专注页），它就完全不受影响。
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val spec = LocalTextEnter.current
    if (spec == null || spec.reduceMotion) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style
        )
        return
    }

    AnimatedContent(
        targetState = if (spec.visible) text else "",
        modifier = modifier,
        label = "textReveal"
    ) { target ->
        androidx.compose.material3.Text(
            text = target,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = style
        )
    }
}
