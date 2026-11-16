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
        var courses: List<Triple<String, String, String>> = emptyList()

        for (req in NativeProbeSpec.ketangpaiRequests(credential, cookieHeader)) {
            val res = execute(req)
            results.put(res)
            val text = res.optString("sample")
            if (res.optInt("status") == 200 && looksLikeJson(text)) {
                if (working == null) working = req
                if (courses.isEmpty()) courses = parseKetangpaiCourses(text)
                Log.d(TAG, "native ketangpai ${req.name} accepted tokenMode=${req.tokenMode}")
            }
        }

        // 鉴权方式确定不了就如实记录，不猜。
        root.put("workingTokenMode", working?.tokenMode ?: "none")
        root.put("courseId", courses.firstOrNull()?.first ?: "")
        root.put("courseCount", courses.size)
        val namesArr = org.json.JSONArray()
        courses.forEach { namesArr.put("${it.second} / ${it.third}") }
        root.put("courses", namesArr)

        // ★ 作业/考试：对**每门课**都取一次，避免「第一门没作业就下结论」。
        courses.take(6).forEach { c ->
            for (req in NativeProbeSpec.ketangpaiContentRequests(
                credential, cookieHeader, c.first
            )) {
                val res = execute(req)
                res.put("course", c.second)
                results.put(res)
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
        lines += "课程：${root.optInt("courseCount")} 门"
        val cs = root.optJSONArray("courses")
        if (cs != null) {
            for (i in 0 until minOf(cs.length(), 5)) lines += "  · ${cs.optString(i)}"
        }
        val parts = (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val course = r.optString("course")
            val prefix = if (course.isBlank()) "" else "$course/"
            "$prefix${r.optString("name")} ${r.optInt("status")} ${r.optInt("len")}B"
        }
        lines += "接口：${parts.joinToString(" / ")}"
        return lines.joinToString("\n")
    }

    /**
     * 课堂派课程列表：`data.topcourses[]` 里取第一条的 `id`。
     *
     * ★ §75 实测订正：`data` 是**对象**不是数组，课程挂在 `data.topcourses` 下；
     *   而且课程 `id` 是一串**Base64 风格的字符串**（`MDAwMDAwMDAwMLOcuZmG37NshqVyoQ`），
     *   **不是数字** —— 上一版按「data 数组 + 数字 id」解析，于是 courseId 永远是空。
     *   这里改用 org.json 正规解析，并把字符串 id 原样保留。
     */
    fun parseKetangpaiCourses(json: String?): List<Triple<String, String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("topcourses") ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("id").ifBlank { continue }
            out += Triple(id, c.optString("coursename"), c.optString("classname"))
        }
        return out
    }

    /** 保留旧名给探针主体用：取第一门课的 id。 */
    fun parseKetangpaiCourseId(json: String?): String? =
        parseKetangpaiCourses(json).firstOrNull()?.first

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

        // ★ §76：课程列表走**课堂端**的两个接口，不再是 MOOC 选课接口。
        //   界面上的课程 = courses/list（未归档） + classroom_archive（已归档）。
        var courses: List<Triple<String, String, String?>> = emptyList()
        val courseSources = JSONObject()
        for (req in NativeProbeSpec.yuketangCourseListRequests(host, cookies)) {
            val res = execute(req)
            results.put(res)
            val text = res.optString("sample")
            val parsed = if (req.name.startsWith("courses-v2") ||
                req.name.startsWith("courses-archive")
            ) {
                parseWebCourses(text)
            } else {
                // MOOC 老接口只作对照，不并进课程池。
                emptyList()
            }
            courseSources.put(req.name, parsed.size)
            // 归档接口的数组在 data.classrooms[]，主键叫 id —— parseWebCourses 已兼容两种形态。
            if (parsed.isNotEmpty()) courses = (courses + parsed).distinctBy { it.first }
        }
        root.put("courseSourceCounts", courseSources)
        root.put("courseCount", courses.size)
        val courseArr = org.json.JSONArray()
        courses.forEach { courseArr.put("${it.second}(${it.first})") }
        root.put("courses", courseArr)

        // 用户信息：确认 uv_id，章节树缺它可能回空。
        for (req in NativeProbeSpec.yuketangUserInfoRequests(host, cookies)) {
            results.put(execute(req))
        }

        // 老接口的学期变体保留做对照（证明「两套是不同数据源」）。
        val termCounts = JSONObject()
        for (req in NativeProbeSpec.yuketangTermVariants(host, cookies)) {
            val res = execute(req)
            results.put(res)
            termCounts.put(req.name, parseYuketangCourses(res.optString("sample")).size)
        }
        root.put("termCounts", termCounts)

        val allNodes = org.json.JSONArray()
        courses.forEachIndexed { i, c ->
            // 章节树：课堂端课程的 sign 可能为空，照发（接口对空 sign 也能回）。
            val chapter = execute(
                NativeProbeSpec.yuketangChapterRequest(
                    host, cookies, i, c.first, c.third
                )
            )
            results.put(chapter)
            val nodes = parseYuketangNodes(chapter.optString("sample"), c.second)
            for (n in nodes) allNodes.put(n)
            results.put(execute(NativeProbeSpec.yuketangScheduleRequest(host, cookies, c.first, c.third)))
            // 活动时间轴：作业/考试会以 activity 形式出现，是独立于章节树的第二条线索。
            for (req in NativeProbeSpec.yuketangCourseDetailRequests(host, cookies, c.first)) {
                results.put(execute(req))
            }
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
            var leafTypeId: String? = null
            var skuId: String? = null
            for (req in NativeProbeSpec.yuketangLeafInfoRequests(
                host, cookies, cid, candidateId, uv
            )) {
                val res = execute(req)
                results.put(res)
                // ★ §75：`leaf_info` 已通，从 content_info.leaf_type_id 回填题目接口要的参数。
                //   上一版直接拿「节点 id」当 leafTypeId 打 get_exercise_list，
                //   服务端回 `No permissions` —— id 用错了，不是没权限。
                if (res.optInt("status") == 200) {
                    val parsed = parseLeafInfo(res.optString("sample"))
                    if (leafTypeId == null) leafTypeId = parsed.first
                    if (skuId == null) skuId = parsed.second
                }
            }
            root.put("leafTypeId", leafTypeId ?: "")
            root.put("skuId", skuId ?: "")
            // 题目列表：先用真实 leaf_type_id 打单段；再按 Raincourse 写法打两段。
            for (req in NativeProbeSpec.yuketangExerciseRequests(
                host, cookies, leafTypeId, candidateId, skuId, uv
            )) {
                results.put(execute(req))
            }
            if (candidateLeafType == "5" && !leafTypeId.isNullOrBlank()) {
                results.put(
                    execute(
                        NativeProbeSpec.yuketangExamCoverRequest(
                            host, cookies, leafTypeId, cid
                        )
                    )
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
        // ★ §76：课程来源要分开报 —— 「课堂端 N 门」与「MOOC 选课 M 门」是两套数据，
        //   上一版只看 MOOC，于是以为用户只有 2 门课。
        val src = root.optJSONObject("courseSourceCounts")
        if (src != null) {
            val kv = src.keys().asSequence().joinToString(" / ") { "$it=${src.optInt(it)}" }
            lines += "课程来源：$kv"
        }
        lines += "课程数：${root.optInt("courseCount")} / 节点：${root.optJSONArray("nodes")?.length() ?: 0}" +
            " / leaf_type $leafCounts"
        lines += "作业 ${root.optInt("homeworkCount")} 个 / 考试 ${root.optInt("examCount")} 个" +
            " / 有绝对截止 $withDeadline 个"
        return lines.joinToString("\n")
    }

    /**
     * 课堂端课程列表解析，**同时兼容两种结构**（§76 调研给出）：
     *
     * - `/v2/api/web/courses/list?identity=2` → `data.list[]`，
     *   每项有 `classroom_id`、`name`、`course.id` / `course.name`、`term`(数字)、`role`。
     * - `/v2/api/web/classroom_archive` → `data.classrooms[]`，
     *   ★ 主键字段叫 **`id`** 而不是 `classroom_id`，要自己映射，
     *     而且 `name` 是课堂名、课程名在 `course.name`。
     *
     * 返回 (classroom_id, 课程名, course_sign)。课堂端不给 `course_sign`，
     * 所以第三项基本为 null —— 章节树对空 sign 也能回。
     */
    fun parseWebCourses(json: String?): List<Triple<String, String, String?>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        // 未归档在 data.list，归档在 data.classrooms —— 两个都试，谁有读谁。
        val arr = data.optJSONArray("list") ?: data.optJSONArray("classrooms")
            ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String?>>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // 归档接口用 `id`，未归档用 `classroom_id`。
            val cid = c.optLong("classroom_id", 0L).takeIf { it > 0L }
                ?: c.optLong("id", 0L).takeIf { it > 0L }
                ?: c.optString("classroom_id").toLongOrNull()?.takeIf { it > 0L }
                ?: c.optString("id").toLongOrNull()?.takeIf { it > 0L }
                ?: continue
            val course = c.optJSONObject("course")
            val name = course?.optString("name")?.takeIf { it.isNotBlank() }
                ?: c.optString("name").takeIf { it.isNotBlank() }
                ?: c.optString("course_name")
            val sign = c.optString("course_sign").ifBlank { null }
            out += Triple(cid.toString(), name, sign)
        }
        return out
    }

    /**
     * `leaf_info` 响应 → (leaf_type_id, sku_id)。
     *
     * ★ §75 实测：`data.content_info.leaf_type_id` 才是题目接口要的参数
     *   （拿章节树里的节点 `id` 去打 `get_exercise_list` 会回 `No permissions`）。
     *   `sku_id` 在 `data.sku_id`，给「两段式」写法备用。
     */
    fun parseLeafInfo(json: String?): Pair<String?, String?> {
        if (json.isNullOrBlank()) return null to null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null to null
        val data = obj.optJSONObject("data") ?: return null to null
        val contentInfo = data.optJSONObject("content_info")
        val leafTypeId = contentInfo?.optLong("leaf_type_id", 0L)
            ?.takeIf { it > 0L }?.toString()
        val skuId = data.optLong("sku_id", 0L).takeIf { it > 0L }?.toString()
        return leafTypeId to skuId
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
