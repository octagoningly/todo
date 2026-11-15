package com.lirui.oneline.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lirui.oneline.sync.CookieSnapshot
import com.lirui.oneline.sync.SyncCredentialStore
import com.lirui.oneline.sync.SyncPlatform
import com.lirui.oneline.sync.SyncRules
import com.lirui.oneline.ui.theme.Text

/**
 * 「在官方页登录第三方平台」的全屏对话框。
 *
 * ★ §70.7 布局修复：顶栏**只留一行标题 + 关闭**（46dp），
 *   说明文字与 URL 全部移到设置页，WebView 用 weight 吃满中间剩余高度。
 *   旧版顶部堆了「标题栏 + 两段说明 + URL + 分隔线」约 160dp，
 *   把 PC 版登录页的二维码挤出了可视区，真机上根本扫不了。
 *
 * ★ 这里**不注入任何 JS、不读取输入框、不拦截表单**：账号密码完全由用户在
 *   平台自己的页面里输入，App 只在用户主动点「我已完成登录」之后读一次 Cookie 快照。
 *   这是本功能与「模拟登录」的关键边界。
 *
 * ★ 用 [Dialog] 而不是新增 Activity：草稿期不往 Manifest 加组件，
 *   也避免和 MainActivity 的 singleTask / 通知入口交互纠缠。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeworkSyncLoginDialog(
    platform: SyncPlatform,
    host: String,
    onDismiss: () -> Unit,
    onFinished: (CookieSnapshot) -> Unit
) {
    val url = remember(platform, host) { SyncRules.loginUrl(platform, host) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Dialog 一旦被关掉（点关闭 / 点外部 / 返回键），必须销毁 WebView，
    // 否则每次打开都留一个持有会话的 WebView 实例在内存里。
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "登录${platform.label}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        // 不挂 WebViewClient 做「URL 变了就算登录」的判定：平台可能多跳几次，
                        // 扫码登录也未必换地址。是否成功一律以用户点「我已完成登录」后的
                        // Cookie 快照为准（SyncRules.hasSession）。
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            // ★ 不要打开 loadWithOverviewMode：它会把 PC 版登录页整体缩到
                            //   一屏宽，二维码被缩小挤到边角，手机扫不到（§70.7）。
                            settings.setSupportZoom(true)
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            loadUrl(url)
                        }.also { webViewRef = it }
                    }
                )

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "在上方页面完成登录后点这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        // CookieManager 必须在主线程读：这里本来就是 Compose 点击事件。
                        onFinished(SyncCredentialStore.snapshot(platform, host))
                    }) {
                        Text("我已完成登录")
                    }
                }
            }
        }
    }
}
