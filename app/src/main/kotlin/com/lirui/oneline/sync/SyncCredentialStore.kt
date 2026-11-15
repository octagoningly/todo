package com.lirui.oneline.sync

import android.webkit.CookieManager

/**
 * 会话凭证的读取与清除。
 *
 * ★ **不落盘**：Cookie 由 WebView 自己持久化在应用私有目录
 *   （`app_webview/`，`allowBackup=false` 时不会进系统备份）。
 *   这里只在用户点「我已完成登录」时读一次快照，用于判断是否登录成功与诊断。
 *   不复制进 SharedPreferences、不写日志、不上传。
 *
 * ★ 下一轮接作业抓取时走「页面内注入 fetch」：同源请求由 WebView 自动带 Cookie，
 *   连这份快照都不需要常驻内存，也绕开 HttpOnly / token 解析与 CORS。
 *
 * ★ CookieManager 的读与 flush 必须在主线程调用（调用方是 Compose 回调，天然主线程）。
 */
object SyncCredentialStore {

    fun snapshot(platform: SyncPlatform, host: String): CookieSnapshot {
        val cm = CookieManager.getInstance()
        cm.flush()
        val merged = LinkedHashMap<String, String>()
        SyncRules.cookieHosts(platform, host).forEach { domain ->
            val raw = runCatching { cm.getCookie("https://$domain/") }.getOrNull()
            SyncRules.parseCookieHeader(raw).forEach { (name, value) -> merged[name] = value }
        }
        return CookieSnapshot(
            host = host,
            cookies = merged,
            capturedAtMillis = System.currentTimeMillis()
        )
    }

    fun loggedIn(platform: SyncPlatform, host: String): Boolean =
        SyncRules.hasSession(platform, snapshot(platform, host).cookies)

    /** 退出登录：清掉 WebView 的全部 Cookie，并让设置页的状态一起归零。 */
    fun clear(context: android.content.Context) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        SyncSettings.clearStatus(context)
    }
}
