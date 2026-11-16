package com.lirui.oneline.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lirui.oneline.sync.CookieSnapshot
import com.lirui.oneline.sync.NativeProbeRunner
import com.lirui.oneline.sync.ProbeRunner
import com.lirui.oneline.sync.SyncCredentialStore
import com.lirui.oneline.sync.SyncPlatform
import com.lirui.oneline.sync.SyncRules
import com.lirui.oneline.ui.theme.Text

/**
 * ★ §79：**完全不再注入任何 CSS**。
 *
 * 历史教训（两次都在这个位置翻车）：
 * - §70.10 给 `html`/`body` 设 `height:auto` + `overflowY:auto`
 *   → 把雨课堂 Vue SPA 的内部滚动容器顶塌，课程区高度变 0。
 * - §77 改成只给 `body` 加 `paddingBottom:72px`
 *   → 用户反馈「点进去是空白界面」。
 *   `/m/v2` 是移动版，外层是 flex 铺满视口的布局，
 *   给它加 padding 会改变盒模型高度，同样会把内层挤没。
 *
 * 结论：**这个页面本来就能正常滚动、正常铺满**，
 * 用户真正需要的只是「别被我们自己的底部按钮条挡住」——
 * 而这件事在**原生布局层**就能干净解决（见底部按钮条下方的 Spacer/inset），
 * 不需要、也不该去改平台自己的页面样式。
 *
 * 保留这个常量只是为了不破坏调用点；内容已清空。
 */
private const val SCROLL_FIX_JS = ""

/** 空脚本不执行，避免一次无意义的 evaluateJavascript。 */
private fun applyLayoutFix(web: WebView) {
    if (SCROLL_FIX_JS.isNotBlank()) web.evaluateJavascript(SCROLL_FIX_JS, null)
}

/**
 * 「在官方页登录第三方平台」的全屏页面。
 *
 * ★ §70.8 布局二次修复：上一版虽然把顶栏压到一行，但它仍在 **Column 里** ——
 *   WebView 被排在顶栏下面，页面内容整体被往下推，PC 版登录页的二维码正好被
 *   挤到可视区外，而且页面本身不能滚动，用户「下移不了」。
 *   现在改成 **Box**：`AndroidView(fillMaxSize())` 从屏幕顶端铺到底端，
 *   所有操作按钮作为**底部浮层**叠在网页上，顶部 100% 干净、二维码零遮挡。
 *   同时打开 WebView 自带缩放，页面布局不友好时也能双指调整。
 *
 * ★ 「重新登录」只清本平台相关域的 Cookie 再重载登录页，
 *   不会误伤另一个平台（也不会清掉 App 里的待办数据）。
 *
 * ★ 这里**不注入任何 JS、不读取输入框、不拦截表单**：账号密码完全由用户在
 *   平台自己的页面里输入，App 只在用户主动点「我已完成登录」之后读一次 Cookie 快照。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeworkSyncLoginDialog(
    platform: SyncPlatform,
    host: String,
    onDismiss: () -> Unit,
    onFinished: (CookieSnapshot) -> Unit,
    /** 抓取探针完成后回传摘要；设置页显示一行，原始响应已由 ProbeRunner 落本机文件。 */
    onProbeResult: (String) -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    val url = remember(platform, host) { SyncRules.loginUrl(platform, host) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Dialog 一旦被关掉（点关闭 / 点外部 / 返回键），必须销毁 WebView。
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    /** 按域清除本平台的 Cookie，然后重新打开登录页。 */
    fun relogin() {
        val cm = CookieManager.getInstance()
        SyncRules.cookieHosts(platform, host).forEach { domain ->
            val base = "https://$domain/"
            val raw = runCatching { cm.getCookie(base) }.getOrNull()
            SyncRules.parseCookieHeader(raw).keys.forEach { name ->
                cm.setCookie(base, "$name=; Max-Age=0; path=/")
            }
        }
        cm.flush()
        webViewRef?.loadUrl(url)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ★ §70.9：上一版改 Box 时漏了这一句，WebView 直接铺到状态栏底下，
                //   PC 登录页二维码的上半截被系统状态栏截断。网页内容必须从状态栏下方开始。
                .statusBarsPadding()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // ★ §80：二维码只显示一半的**真正原因**就在这两行。
                        //
                        //   `useWideViewPort = true` 让网页按**桌面宽度**（约 980px）排版，
                        //   但手机物理宽度只有 ~400dp。**再配 `loadWithOverviewMode = false`**，
                        //   页面就按 980px 的布局宽度渲染、却塞进 400dp 的视口 →
                        //   右侧内容（二维码正好在中间偏右）被裁掉一半，且横向能滚但不明显。
                        //
                        //   历史：§70.10 说"不要 loadWithOverviewMode，它会把 PC 页缩到一屏宽"，
                        //   但**不缩的代价就是裁切**。对"看一眼二维码"这个需求，
                        //   把整页缩进一屏（per-viewport 缩放）才是对的：
                        //   用户看得到完整的二维码，也能双指放大。
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // ★ §77：去掉默认 UA 里的 `; wv` 标记。
                        //   WebView 默认 UA 形如 `... Chrome/xx ...; wv) ...`，
                        //   不少站点（含雨课堂）据此判定「这是内嵌浏览器」并降级、
                        //   或直接返回与真机浏览器不同的布局。
                        //   只改 UA 字符串，不伪装成别的设备型号，页面仍是平台自己的官方页。
                        settings.userAgentString = settings.userAgentString
                            ?.replace("; wv)", ")")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                // §79：不再注入任何 CSS。页面自己就能正常滚动/铺满，
                                // 底部遮挡问题在原生布局层解决（见底部按钮条的 padding）。
                                view?.let { applyLayoutFix(it) }
                            }
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        loadUrl(url)
                    }.also { webViewRef = it }
                }
            )

            // 唯一的 UI 浮层，贴在底部；顶部完全留给网页。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                TextButton(onClick = { relogin() }) { Text("重登") }
                // ★ §80：登录成功后直达**移动版课程页** `/m/v2`。
                //   未登录时它会 302 回 PC 登录页（所以不能当入口用），
                //   但**登录之后**这就是平台自己的移动版首页，直接给课程列表。
                //   只做导航，不注入脚本、不改页面。
                TextButton(onClick = {
                    val w = webViewRef ?: return@TextButton
                    val base = SyncRules.loginUrl(platform, host).substringBefore("/web")
                    w.loadUrl(
                        if (platform == SyncPlatform.YUKETANG) "$base/m/v2" else base
                    )
                }) { Text("课程页") }
                TextButton(onClick = {
                    val web = webViewRef ?: return@TextButton
                    Toast.makeText(
                        appContext, "正在抓取（页面内 + 原生），完成后自动返回",
                        Toast.LENGTH_SHORT
                    ).show()
                    // §74：两条探针并行，互不阻塞，各写各的文件：
                    //   页面内 fetch → oneline-probe-<platform>.json（雨课堂仍走这条）
                    //   原生 HTTP    → oneline-native-<platform>.json（课堂派只能走这条）
                    // ★ 原生那条**先发**：它不依赖 WebView，Dialog 关掉也照跑；
                    //   而且课堂派是本轮重点，不能因为页面内超时把结论拖没。
                    NativeProbeRunner.run(
                        appContext,
                        platform,
                        host,
                        SyncCredentialStore.snapshot(platform, host).cookies
                    ) { result ->
                        // Dialog 可能已被页面内那条关掉，这里只回写诊断，不再触发关闭。
                        onProbeResult(result)
                    }
                    // §70.11：用当前已登录会话调候选接口，只做读取、不提交任何数据。
                    ProbeRunner.run(appContext, platform, host, web) { result ->
                        onProbeResult(result)
                        onDismiss()
                    }
                }) { Text("抓作业") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    onFinished(SyncCredentialStore.snapshot(platform, host))
                }) {
                    Text("我已完成登录")
                }
            }
        }
    }
}
