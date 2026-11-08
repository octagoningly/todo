package com.lirui.oneline.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * §65 文字级进场的作用域参数。
 *
 * @param replayKey  换页（tab）时换一个值，让这一层的所有文字重新播一次进场。
 * @param order      按行错落的序号；实际延迟 = order * 24ms，封顶 240ms。
 * @param reduceMotion 系统「减少动态效果」或设置里关掉「页面切换动效」时不播。
 */
data class TextEnterSpec(
    val replayKey: Any,
    val reduceMotion: Boolean = false,
    val order: Int = 0
)

/**
 * null = 当前不在「四个 tab 的文字层」里（顶栏 / 底栏 / FAB / 弹窗等），
 * 文字保持原样、一点动画都不加 —— 这样作用域不会意外扩到卡片或容器上。
 */
val LocalTextEnter = staticCompositionLocalOf<TextEnterSpec?> { null }

/**
 * 只给**文字本身**加淡入 + 轻微上滑。
 *
 * ★ 结构参考 `T8RIN/ImageToolbox`（Apache-2.0）：
 *   `PreferenceItemOverload.kt` / `PreferenceRow.kt` / `TopAppBarTitle.kt` 里
 *   `AnimatedContent` 只包住 `Text`，`Card` / `Row` / 图标全在动画作用域外。
 *   本项目用官方 `graphicsLayer + animateFloatAsState` 达到同样的「只有文字动」，
 *   而且文字始终占位、不会像 `AnimatedVisibility` 那样把卡片高度撑开。
 *   源码的 spec 是 `fadeIn() togetherWith fadeOut()`；这里按 §65 的方案加了
 *   18dp 上滑与按行错落，让「文字进出场」更明显。只借结构与思路，不引依赖。
 */
@Composable
fun Modifier.textEnter(): Modifier {
    val spec = LocalTextEnter.current ?: return this
    if (spec.reduceMotion) return this

    val shiftPx = with(LocalDensity.current) { 18.dp.toPx() }
    var shown by remember(spec.replayKey) { mutableStateOf(false) }
    LaunchedEffect(spec.replayKey) {
        delay((spec.order * 24).coerceAtMost(240).toLong())
        shown = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "textEnterAlpha"
    )
    val shift by animateFloatAsState(
        targetValue = if (shown) 0f else shiftPx,
        animationSpec = tween(260, easing = OnelineMotionEasing),
        label = "textEnterShift"
    )
    // graphicsLayer 只影响绘制层：不改测量结果，卡片背景 / 圆圈 / 徽章一律不动。
    return this.graphicsLayer {
        this.alpha = alpha
        translationY = shift
    }
}

/**
 * 与 M3 `Text` 同签名的包装：四个 tab 页面里的文字统一获得 [textEnter]。
 *
 * 用「换 import」而不是逐个改 `Text(...)` 调用，是为了让作用域严格落在文字上：
 * 只要某个文件不引入这个别名（顶栏外的弹窗 / 添加面板 / 专注页），它就完全不受影响。
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
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier.textEnter(),
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
