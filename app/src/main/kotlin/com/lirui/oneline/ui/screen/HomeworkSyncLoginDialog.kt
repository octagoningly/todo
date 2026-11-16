package com.lirui.oneline.ui.screen

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import com.lirui.oneline.sync.LayoutDiag
import com.lirui.oneline.sync.NativeProbeRunner
import com.lirui.oneline.sync.ProbeRunner
import com.lirui.oneline.sync.SyncCredentialStore
import com.lirui.oneline.sync.SyncPlatform
import com.lirui.oneline.sync.SyncRules
import com.lirui.oneline.ui.theme.Text

/**
 * ★ §81：只解锁**滚动**，绝不改 `height` / `width`。
 *
 * 为什么还是要注入这一点点：
 * 雨课堂 PC 登录页把滚动锁死（`overflow:hidden` 或固定高度容器）。
 * 一旦内容比视口高，**下面是滚不到的**——二维码落在下半部分就永远看不全。
 * §70.10 当初就是因为这个才注入，但它在解锁滚动的同时还把 `height` 改成 `auto`，
 * 顺手把 SPA 的内部滚动容器顶塌了，于是"修好了登录页、弄坏了课程页"。
 *
 * 这次严格分离：
 * - **只**把 `html` / `body` 的 `overflow-y` 从 `hidden` 放开成 `auto`；
 * - **不碰** `height` / `width` / `padding` / `transform`；
 * - 加 `important` 提升优先级，但作用面就这一个属性。
 *
 * 这段注入对正常页面无害：本来就是 `auto` 的元素再设成 `auto` 等于没变。
 */
private const val SCROLL_FIX_JS = """
(function(){
  try {
    var style = document.createElement('style');
    style.id = '__oneline_scroll_unlock';
    style.textContent =
      'html,body{overflow-y:auto !important;}' +
      'body{overscroll-behavior-y:auto !important;}' +
      'html{height:auto !important;min-height:100% !important;}';
    if (!document.getElementById('__oneline_scroll_unlock')) {
      (document.head || document.documentElement).appendChild(style);
    }
  } catch (e) {}
})();
"""

/** 有脚本才执行，避免无意义的一次 evaluateJavascript。 */
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
        // ★ §81 布局重排：从 Box 叠放改成 Column 上下分区。
        //
        // 之前是「WebView fillMaxSize + 按钮条浮在底部」：按钮条**盖住**了网页最下面
        // 那一条（约 48dp）。PC 登录页的二维码如果落在页面偏下位置，
        // 被盖住的部分看起来就是"只显示一半"，而且用户还滚不动它。
        //
        // 现在：WebView 占满剩余高度（weight 1f），按钮条单独占一行，二者不重叠。
        // 网页获得的是**完整可用高度**，不会再被我们自己的浮层遮住。
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                // ★ §70.9：WebView 直接铺到状态栏底下时，二维码上半截会被系统状态栏截断。
                //   网页内容必须从状态栏下方开始。
                .statusBarsPadding()
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                                // ★ §81：只读测量，把真实数字打到 logcat。
                                //   盲改三轮都"没变"，必须拿到实际尺寸才能判断
                                //   是左右被裁、上下被裁，还是二维码元素本身被父容器裁掉。
                                view?.evaluateJavascript(LayoutDiag.JS, null)
                            }
                        }
                        // ★ §81：把页面里的 console.log 引到 logcat，才能看到测量结果。
                        //   之前整条链路没有 WebChromeClient，JS 的一切输出都是黑洞。
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                val text = msg.message()
                                if (text.startsWith("ONELINE_LAYOUT")) {
                                    android.util.Log.d("OnelineProbe", "layout: $text")
                                }
                                return true
                            }
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        loadUrl(url)
                    }.also { webViewRef = it }
                }
            )

            // ★ §81：按钮条不再是浮层，而是**独立的底部一行**。
            //   之前它浮在 WebView 上，把网页最下面一条盖住 ——
            //   PC 登录页二维码若落在偏下位置，被盖住的部分就是"只显示一半"。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
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
