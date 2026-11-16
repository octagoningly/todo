package com.lirui.oneline.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * **原生 HTTP 探针**：在 App 自己的进程里发请求，不受 CORS 与同源策略限制。
 *
 * ★ 为什么必须走这条路（DESIGN.md §74）：
 *   §73 实测「页面内 fetch」对课堂派彻底不通 —— 跨域 + `token` 自定义头 →
 *   CORS 预检失败，三个 API 域全部 `TypeError: Failed to fetch`；
 *   而同源主站 `/CourseApi/` 前缀只是 SPA 外壳（返回 HTML）。
 *   `HttpURLConnection` 没有同源概念，能带任意头，也能直接读
 *   `CookieManager` 里（含 HttpOnly 的）Cookie。
 *
 * ★ 边界：仍然**只读**。只发课程列表 / 章节树 / 作业列表这类 GET、POST 查询，
 *   不提交作业、不改任何平台数据、不落库、不动 Room。结果只写本机文件。
 * ★ 凭证：token 只进请求头 / body，**绝不写进结果 JSON、绝不写日志**。
 */
object NativeProbeRunner {

    private const val TAG = "OnelineProbe"

    /** 单个请求超时。探针串行跑几条，单条 10 秒足够。 */
    private const val TIMEOUT_MS = 10_000

    /** 每条响应最多留这么多字符，够看字段名与几条记录。 */
    private const val SAMPLE_CHARS = 12_000

    fun probeFile(context: Context, platform: SyncPlatform): File =
        File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "oneline-native-${platform.id}.json"
        )

    /**
     * 跑一轮原生探针。**必须在后台线程调用**（内部用单线程池，不阻塞主线程）。
     * [onResult] 在**主线程**回调（调用方是 Compose，直接在里面改状态才不会崩）。
     */
    fun run(
        context: Context,
        platform: SyncPlatform,
        host: String,
        cookies: Map<String, String>,
        onResult: (String) -> Unit
    ) {
        Log.d(TAG, "native start platform=${platform.id} host=$host cookies=${cookies.size}")
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching { probe(platform, host, cookies) }
                .getOrElse { e ->
                    Log.e(TAG, "native failed: ${e.javaClass.simpleName}", e)
                    JSONObject().put("state", "error").put("error", e.toString())
                }
            val summary = result.optString("summary").ifBlank { "原生探针未产出摘要" }
            val file = probeFile(context, platform)
            val saved = runCatching { file.writeText(result.toString(2)) }.isSuccess
            Log.d(TAG, "native done platform=${platform.id} saved=$saved " +
                "file=${file.name} bytes=${result.toString().length}")
            summary.lineSequence().forEach { if (it.isNotBlank()) Log.d(TAG, "  | $it") }
            val text = buildString {
                append("原生探针：")
                append(if (result.optString("state") == "done") "完成" else "异常")
                append("\n")
                append(summary)
                append("\n原始响应：")
                append(if (saved) "已存到本机 ${file.name}" else "写文件失败")
            }
            // ★ 探针跑在后台线程，回调必须切回主线程 —— 调用方会直接改 Compose 状态。
            Handler(Looper.getMainLooper()).post { onResult(text) }
            executor.shutdown()
        }
    }

    /** 同步版本，便于将来放 WorkManager 里跑。 */
    fun probe(
        platform: SyncPlatform,
        host: String,
        cookies: Map<String, String>
    ): JSONObject = when (platform) {
        SyncPlatform.KETANGPAI -> probeKetangpai(host, cookies)
        SyncPlatform.YUKETANG -> probeYuketang(host, cookies)
    }

    // ---------------------------------------------------------------------
    // 课堂派
    // ---------------------------------------------------------------------

    private fun probeKetangpai(host: String, cookies: Map<String, String>): JSONObject {
        val credential = NativeProbeSpec.ketangpaiCredential(cookies)
        val cookieHeader = NativeProbeSpec.cookieHeader(cookies)
        val root = JSONObject()
            .put("state", "done")
            .put("platform", "ketangpai")
            .put("host", host)
            .put("tokenFound", credential.tokenFound)
            .put("tokenSource", credential.source)
            .put("cookieNames", cookies.keys.sorted().joinToString(","))

        val results = org.json.JSONArray()
        // 先跑四种鉴权方式，谁第一个返回可用 JSON 就用它继续。
        var working: NativeProbeSpec.ProbeRequest? = null
        var courseId: String? = null

        for (req in NativeProbeSpec.ketangpaiRequests(credential, cookieHeader)) {
            val res = execute(req)
            results.put(res)
            val text = res.optString("sample")
            if (res.optInt("status") == 200 && looksLikeJson(text)) {
                val ids = parseKetangpaiCourseId(text)
                if (working == null) working = req
                if (courseId == null) courseId = ids
                Log.d(TAG, "native ketangpai ${req.name} accepted tokenMode=${req.tokenMode}")
            }
        }

        // 鉴权方式确定不了就如实记录，不猜。
        root.put("workingTokenMode", working?.tokenMode ?: "none")
        root.put("courseId", courseId ?: "")

        if (courseId != null) {
            for (req in NativeProbeSpec.ketangpaiContentRequests(
                credential, cookieHeader, courseId
            )) {
                results.put(execute(req))
            }
        }
        root.put("results", results)
        root.put("summary", ketangpaiSummary(root, results))
        return root
    }

    private fun ketangpaiSummary(root: JSONObject, results: org.json.JSONArray): String {
        val lines = mutableListOf<String>()
        lines += "鉴权：token ${if (root.optBoolean("tokenFound")) "找到" else "没找到"}" +
            "（${root.optString("tokenSource")}）/ 可用方式 ${root.optString("workingTokenMode")}"
        lines += "课程 id：${root.optString("courseId").ifBlank { "未取到" }}"
        val parts = (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            "${r.optString("name")} ${r.optInt("status")} ${r.optInt("len")}B"
        }
        lines += "接口：${parts.joinToString(" / ")}"
        return lines.joinToString("\n")
    }

    /** 课堂派课程列表：`data[]` 里取第一条的 `id`，取不到就空。 */
    fun parseKetangpaiCourseId(json: String): String? {
        val key = "\"data\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val arr = json.indexOf('[', idx)
        if (arr < 0) return null
        // 在 data 数组的头部一小段里找第一个 id。
        val window = json.substring(arr, minOf(arr + 600, json.length))
        for (idKey in listOf("\"id\"", "\"courseid\"")) {
            val p = window.indexOf(idKey)
            if (p < 0) continue
            val colon = window.indexOf(':', p)
            if (colon < 0) continue
            var s = colon + 1
            while (s < window.length && (window[s] == ' ' || window[s] == '"')) s++
            var e = s
            while (e < window.length && window[e].isDigit()) e++
            if (e > s) return window.substring(s, e)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // 雨课堂
    // ---------------------------------------------------------------------

    private fun probeYuketang(host: String, cookies: Map<String, String>): JSONObject {
        val root = JSONObject()
            .put("state", "done")
            .put("platform", "yuketang")
            .put("host", host)
            .put("cookieNames", cookies.keys.sorted().joinToString(","))
            .put("probeHeaders", "xtbz,x-csrftoken,Cookie")

        val results = org.json.JSONArray()
        val coursesRes = execute(NativeProbeSpec.yuketangRequests(host, cookies).first())
        results.put(coursesRes)

        val courses = parseYuketangCourses(coursesRes.optString("sample"))
        root.put("courseCount", courses.size)

        val allNodes = org.json.JSONArray()
        courses.forEachIndexed { i, c ->
            val chapter = execute(
                NativeProbeSpec.yuketangChapterRequest(
                    host, cookies, i, c.first, c.third
                )
            )
            results.put(chapter)
            val nodes = parseYuketangNodes(chapter.optString("sample"), c.second)
            for (n in nodes) allNodes.put(n)
            // 日程接口（存疑候选）：每门课打一次，确认是否含截止时间聚合。
            results.put(execute(NativeProbeSpec.yuketangScheduleRequest(host, cookies, c.first, c.third)))
        }

        root.put("nodes", allNodes)
        var withDeadline = 0
        var homeworkCount = 0
        var examCount = 0
        val leafCounts = LinkedHashMap<String, Int>()
        val homeworkNodes = mutableListOf<JSONObject>()
        for (i in 0 until allNodes.length()) {
            val n = allNodes.getJSONObject(i)
            val lt = n.optString("leaf_type", "?")
            leafCounts[lt] = (leafCounts[lt] ?: 0) + 1
            // ★ 0 代表「无限制」，必须走 isDeadlineUsable 判定，不能直接 > 0 之外再当时间用。
            if (isDeadlineUsable(n.optLong("end_time")) ||
                isDeadlineUsable(n.optLong("score_deadline"))
            ) withDeadline++
            if (isHomeworkOrExam(lt)) {
                homeworkNodes += n
                if (lt == "6") homeworkCount++ else examCount++
            }
        }
        root.put("leafTypeCounts", JSONObject(leafCounts.toMap()))
        root.put("withDeadline", withDeadline)
        root.put("homeworkCount", homeworkCount)
        root.put("examCount", examCount)

        // 有作业/考试节点时才去试详情与题目接口，避免白打一串 404。
        // 优先用真正的作业/考试节点；一个都没有就退回第一个有 id 的节点，
        // 让详情接口至少被打一次（能区分「路由不通」与「这课确实没作业」）。
        val candidate = homeworkNodes.firstOrNull()
        val candidateId: String? = if (candidate != null) {
            candidate.optLong("id", 0L).takeIf { it > 0L }?.toString()
        } else {
            firstLeafWithId(allNodes)
        }
        val candidateLeafType = candidate?.optString("leaf_type")
        val uv = cookies["university_id"]
        if (!candidateId.isNullOrBlank() && candidateId != "0" && courses.isNotEmpty()) {
            val cid = courses.first().first
            for (req in NativeProbeSpec.yuketangLeafInfoRequests(
                host, cookies, cid, candidateId, uv
            )) {
                results.put(execute(req))
            }
            // 题目列表：单段形式按多数实现先用节点 id 试（真实 leaf_type_id 要等 leaf_info 回填）。
            for (req in NativeProbeSpec.yuketangExerciseRequests(
                host, cookies, candidateId, candidateId, null, uv
            )) {
                results.put(execute(req))
            }
            if (candidateLeafType == "5") {
                results.put(
                    execute(NativeProbeSpec.yuketangExamCoverRequest(host, cookies, candidateId, cid))
                )
            }
        }
        root.put("results", results)
        root.put("summary", yuketangSummary(root, results, leafCounts, withDeadline))
        return root
    }

    private fun yuketangSummary(
        root: JSONObject,
        results: org.json.JSONArray,
        leafCounts: Map<String, Int>,
        withDeadline: Int
    ): String {
        val lines = mutableListOf<String>()
        val parts = (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            "${r.optString("name")} ${r.optInt("status")}"
        }
        lines += "接口：${parts.joinToString(" / ")}"
        lines += "课程数：${root.optInt("courseCount")} / 节点：${root.optJSONArray("nodes")?.length() ?: 0}" +
            " / leaf_type $leafCounts"
        lines += "作业 ${root.optInt("homeworkCount")} 个 / 考试 ${root.optInt("examCount")} 个" +
            " / 有绝对截止 $withDeadline 个"
        return lines.joinToString("\n")
    }

    /** 课程列表 → (classroom_id, course_name, course_sign)。 */
    fun parseYuketangCourses(json: String?): List<Triple<String, String, String?>> {
        if (json.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Triple<String, String, String?>>()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val list = obj.optJSONObject("data")?.optJSONArray("product_list") ?: return emptyList()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val cid = c.optLong("classroom_id", 0L)
            if (cid <= 0L) continue
            out += Triple(
                cid.toString(),
                c.optString("course_name"),
                c.optString("course_sign").ifBlank { null }
            )
        }
        return out
    }

    /**
     * 章节树 → 扁平节点列表。
     *
     * ★ §74 调研订正，两个必须照做的点：
     *   1. `section_leaf_list[]` 里的节点**可能是叶子本身，也可能带嵌套 `leaf_list`**
     *      —— 只处理顶层会漏掉一半作业节点（多数开源脚本就栽在这）。
     *   2. `start_time` / `end_time` / `score_deadline` 都是 **Unix 毫秒**，
     *      且 **`0` 表示「无限制」**，不是 1970 年。这里原样保留 0，
     *      由 [isDeadlineUsable] 统一判定，避免下游把 0 当合法截止时间。
     */
    fun parseYuketangNodes(json: String?, courseName: String): List<JSONObject> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        val chapters = data.optJSONArray("course_chapter") ?: return emptyList()
        for (i in 0 until chapters.length()) {
            val ch = chapters.optJSONObject(i) ?: continue
            val leaves = ch.optJSONArray("section_leaf_list") ?: continue
            walkLeaves(leaves, courseName, out)
        }
        return out
    }

    /** 递归收集叶子：有 `leaf_list` 就先往里走，否则当前节点本身算一片叶子。 */
    private fun walkLeaves(
        arr: org.json.JSONArray,
        courseName: String,
        out: MutableList<JSONObject>
    ) {
        for (j in 0 until arr.length()) {
            val n = arr.optJSONObject(j) ?: continue
            val nested = n.optJSONArray("leaf_list")
            if (nested != null) {
                walkLeaves(nested, courseName, out)
                continue
            }
            val id = n.optLong("id", 0L)
            if (id <= 0L) continue
            out += JSONObject().apply {
                put("course", courseName)
                put("name", n.optString("name"))
                put("leaf_type", n.optString("leaf_type"))
                put("id", id)
                // 毫秒；0 = 无限制（不是 1970）。
                put("end_time", n.optLong("end_time", 0L))
                put("score_deadline", n.optLong("score_deadline", 0L))
                put("start_time", n.optLong("start_time", 0L))
                put("leafinfo_id", n.optLong("leafinfo_id", 0L))
                put("is_assessed", n.optBoolean("is_assessed", false))
            }
        }
    }

    /**
     * 作业（6）与考试（5）才是要转成待办的类型；其余是课件 / 公告 / 讨论 / 视频。
     *
     * `leaf_type` 枚举有两份独立源码互证：
     * 0=视频 3=公告/推荐 4=讨论 5=考试 6=作业（测验/练习）。
     */
    fun isHomeworkOrExam(leafType: String?): Boolean =
        leafType == "6" || leafType == "5"

    /**
     * `score_deadline` / `end_time` 是否可用作截止时间。
     * **0 代表「无限制」**，必须排除 —— 否则会渲染成 1970-01-01。
     */
    fun isDeadlineUsable(millis: Long): Boolean =
        millis > 0L && millis in 1_500_000_000_000L..4_102_444_800_000L

    /**
     * 挑一个节点去打详情接口：作业（6）/考试（5）优先，
     * 一个都没有就退回第一个有 id 的节点 —— 用来区分「路由不通」与「本课确实没作业」。
     */
    fun firstLeafWithId(nodes: org.json.JSONArray): String? {
        var fallback: String? = null
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val id = n.optLong("id", 0L)
            if (id <= 0L) continue
            if (isHomeworkOrExam(n.optString("leaf_type"))) return id.toString()
            if (fallback == null) fallback = id.toString()
        }
        return fallback
    }

    // ---------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------

    private fun execute(req: NativeProbeSpec.ProbeRequest): JSONObject {
        val startNs = System.nanoTime()
        val out = JSONObject()
            .put("name", req.name)
            .put("method", req.method)
            .put("url", sanitizeUrl(req.url))
            .put("tokenMode", req.tokenMode)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
                requestMethod = req.method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                // 头名一律用平台要求的小写形式（`token` / `xtbz`）。
                req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (req.body != null) doOutput = true
            }
            if (req.body != null) {
                conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            out.put("status", code)
            out.put("ok", code in 200..299)
            out.put("len", text.length)
            out.put("sample", text.take(SAMPLE_CHARS))
            Log.d(TAG, "native ${req.name} ${req.method} -> $code ${text.length}B")
        } catch (e: Exception) {
            out.put("status", -1)
            out.put("ok", false)
            out.put("len", 0)
            out.put("sample", "ERR:${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "native ${req.name} failed: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            conn?.disconnect()
        }
        out.put("ms", (System.nanoTime() - startNs) / 1_000_000)
        return out
    }

    /** 结果文件里不留 token：query 里的 `token=` 一律打码（body 本来就不记）。 */
    private fun sanitizeUrl(url: String): String =
        url.replace(Regex("token=[^&]*"), "token=***")

    private fun looksLikeJson(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }
}
