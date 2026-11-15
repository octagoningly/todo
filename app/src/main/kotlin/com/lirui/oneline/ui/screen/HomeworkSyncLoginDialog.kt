@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lirui.oneline.ui.screen

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TopAppBar
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
                TopAppBar(
                    title = { Text("登录${platform.label}") },
                    actions = { TextButton(onClick = onDismiss) { Text("关闭") } }
                )
                Text(
                    "请在下方平台官方页面自行完成登录。App 不接触账号密码，" +
                        "登录状态只保存在本机浏览器会话里；完成后点右下角按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

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
                            settings.loadWithOverviewMode = true
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
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "登录完成后再点这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = {
                        // CookieManager 必须在主线程读：这里本来就是 Compose 点击事件。
                        onFinished(SyncCredentialStore.snapshot(platform, host))
                    }) {
                        Text("我已完成登录")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
