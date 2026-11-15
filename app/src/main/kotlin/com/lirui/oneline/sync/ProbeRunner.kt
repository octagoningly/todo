package com.lirui.oneline.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 在已登录的 WebView 会话里跑 [ProbeJs]，把结果取回并落本机文件。
 *
 * ★ `evaluateJavascript` 不会 await Promise，所以 JS 把结果写进
 *   `window.__oneline_probe`，这里用主线程 Handler 轮询读取（最多 12 秒）。
 *   不引 JavascriptInterface / 不加 proguard keep，改动面最小。
 * ★ 原始响应写到应用专属外部目录 `oneline-probe.json`（无需权限），
 *   后续可用 `adb pull` 取回分析；设置页只显示一行摘要，不糊用户一屏文字。
 */
object ProbeRunner {

    private const val MAX_POLLS = 24
    private const val POLL_MS = 500L

    private const val READ_JS =
        "(function(){try{return JSON.stringify(window.__oneline_probe||{state:'none'})}" +
            "catch(e){return JSON.stringify({state:'error',error:String(e)})}})()"

    /**
     * ★ §72：按平台分文件。上一版两个平台都写 `oneline-probe.json`，
     *   用户在课堂派、雨课堂各点一次后，后一次把前一次**覆盖掉了**，
     *   只拿得到雨课堂的结果。
     */
    fun probeFile(context: Context, platform: SyncPlatform): File =
        File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "oneline-probe-${platform.id}.json"
        )

    fun run(
        context: Context,
        platform: SyncPlatform,
        host: String,
        webView: WebView,
        onResult: (String) -> Unit
    ) {
        webView.evaluateJavascript(ProbeJs.build(platform, host), null)
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        lateinit var poll: Runnable
        poll = Runnable {
            tries++
            webView.evaluateJavascript(READ_JS) { value ->
                val raw = decode(value)
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                val state = obj?.optString("state").orEmpty()
                if (state == "done" || tries >= MAX_POLLS) {
                    val count = obj?.optJSONArray("results")?.length() ?: 0
                    val summary = obj?.optString("summary")?.takeIf { it.isNotBlank() }
                        ?: "探针结束，未拿到摘要"
                    val file = probeFile(context, platform)
                    val saved = runCatching { file.writeText(raw) }.isSuccess
                    onResult(
                        buildString {
                            append(if (state == "done") "抓取探针：完成" else "抓取探针：超时")
                            append("（$count 个接口响应）\n")
                            append(summary)
                            append("\n原始响应：")
                            append(if (saved) "已存到本机 ${file.name}" else "写文件失败")
                        }
                    )
                } else {
                    handler.postDelayed(poll, POLL_MS)
                }
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    /** evaluateJavascript 回调给的是 JSON 字符串（带引号/转义），先解码成原始文本。 */
    private fun decode(value: String?): String {
        if (value.isNullOrBlank() || value == "null") return "{}"
        return runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
    }
}
