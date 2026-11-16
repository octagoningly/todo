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

    /**
     * 每条响应最多留这么多字符。
     *
     * ★ §79：原来是 12000，而雨课堂 `/v2/api/web/courses/list` 实测 **24494 B**，
     *   被截断后 JSON 不完整 → 解析直接抛「Unterminated string」→ 课程数算成 0。
     *   **「接口返回 200、长度也不小，但解析出 0 条」就是这么来的**，
     *   光看状态码完全发现不了。这里放宽到 40000，够放下这类列表响应。
     */
    private const val SAMPLE_CHARS = 40_000

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

    /**
     * 课程名 → 学期标签（§90）。
     *
     * ★ 为什么需要单独抽：**作业条目本身不带学期**。
     *   实测课堂派 `homework` 返回的 30 个字段里没有 `semester`；
     *   雨课堂的 node 也没有。学期只挂在**课程**上：
     *   - 课堂派：`root.courses` 里是 `课程名 / 2026-2027/1` 这种字符串；
     *   - 雨课堂：`courses-v2` 响应里每门课有 `term`（数字 202601）。
     *
     * 所以先建映射，再用课程名回查。
     */
    fun semesterByCourse(platform: SyncPlatform, root: JSONObject): Map<String, String> {
        val out = HashMap<String, String>()
        when (platform) {
            SyncPlatform.KETANGPAI -> {
                // courses 是 "课程名 / 学期" 的展示字符串数组
                val arr = root.optJSONArray("courses") ?: return out
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    val idx = s.lastIndexOf(" / ")
                    if (idx <= 0) continue
                    val name = s.substring(0, idx).trim()
                    val sem = s.substring(idx + 3).trim()
                    if (name.isNotBlank() && sem.isNotBlank()) out[name] = sem
                }
            }
            SyncPlatform.YUKETANG -> {
                // 从原始 courses-v2 响应里取 term（数字）
                val results = root.optJSONArray("results") ?: return out
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val nm = r.optString("name")
                    if (nm != "courses-v2-identity2" && nm != "courses-archive") continue
                    val data = runCatching {
                        JSONObject(r.optString("sample")).optJSONObject("data")
                    }.getOrNull() ?: continue
                    val list = data.optJSONArray("list") ?: data.optJSONArray("classrooms")
                        ?: continue
                    for (j in 0 until list.length()) {
                        val c = list.optJSONObject(j) ?: continue
                        val course = c.optJSONObject("course")
                        val courseName = course?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: c.optString("name")
                        val term = c.optLong("term", 0L).takeIf { it > 0L } ?: continue
                        if (courseName.isNotBlank()) {
                            out[courseName] = ImportRules.normalizeSemester(term.toString())
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * ★ §86 探针结果 → 可导入的作业列表。
     *
     * 两个平台的结构不同，这里做归一化，交给 [ImportRules] 统一过滤：
     * - **课堂派**：`results[]` 里 `name` 为 `homework` / `exam` 的项，
     *   `data.list[]` 每条有 `id` / `title` / `endtime`（Unix **秒**）；
     *   学期在 `候选人` 的课程名里不带，靠调用方按课程池匹配。
     * - **雨课堂**：`nodes[]` 里 `source=logs`/`c27` 且 `leaf_type=5` 的项，
     *   每条有 `id` / `name` / `score_deadline`（**毫秒**）/ `kind` / `course`。
     */
    fun extractImportItems(
        platform: SyncPlatform,
        root: JSONObject
    ): List<ImportRules.ImportItem> {
        val out = mutableListOf<ImportRules.ImportItem>()
        // ★ §90：先把「课程名 → 学期」建好，作业条目本身不带学期。
        val semesterMap = semesterByCourse(platform, root)
        when (platform) {
            SyncPlatform.KETANGPAI -> {
                val results = root.optJSONArray("results") ?: return out
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val nm = r.optString("name")
                    if (nm != "homework" && nm != "exam") continue
                    val courseName = r.optString("course")
                    val data = runCatching {
                        JSONObject(r.optString("sample")).optJSONObject("data")
                    }.getOrNull() ?: continue
                    val list = data.optJSONArray("list") ?: continue
                    for (j in 0 until list.length()) {
                        val o = list.optJSONObject(j) ?: continue
                        val id = o.optString("id").ifBlank { continue }
                        // ★ 课堂派的 endtime 是 **Unix 秒**，这里转毫秒。
                        val endSec = o.optString("endtime").toLongOrNull()
                            ?: o.optLong("endtime", 0L)
                        if (endSec <= 0L) continue
                        out += ImportRules.ImportItem(
                            platform = platform,
                            externalId = id,
                            courseName = courseName,
                            title = o.optString("title"),
                            deadlineMillis = endSec * 1000L,
                            kind = if (nm == "exam") KIND_EXAM else KIND_HOMEWORK,
                            submitted = false,
                            semesterTag = semesterMap[courseName]
                        )
                    }
                }
            }
            SyncPlatform.YUKETANG -> {
                val nodes = root.optJSONArray("nodes") ?: return out
                for (i in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(i) ?: continue
                    // 只取作业类（5/6）；8 是课件。
                    if (!isHomeworkOrExam(n.optString("leaf_type"))) continue
                    val id = n.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: continue
                    // ★ 雨课堂在 logs 里给的是毫秒（score_deadline 已归一），
                    //   若是 c27 给的 score_deadline 同样是毫秒。
                    val dl = n.optLong("score_deadline", 0L).takeIf { it > 0L }
                        ?: n.optLong("end_time", 0L).takeIf { it > 0L }
                        ?: continue
                    val title = n.optString("name")
                    out += ImportRules.ImportItem(
                        platform = platform,
                        externalId = id,
                        courseName = n.optString("course"),
                        title = title,
                        deadlineMillis = dl,
                        kind = n.optString("kind").ifBlank { classifyYuketangKind(title) },
                        // status=3 表示已完成（真机观测值）。
                        submitted = n.optInt("status", -1) == 3,
                        semesterTag = semesterMap[n.optString("course")]
                    )
                }
            }
        }
        return out
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

        for (req in NativeProbeSpec.ketangpaiRequests(credential, cookieHeader)) {
            val res = execute(req)
            results.put(res)
            val text = res.optString("sample")
            if (res.optInt("status") == 200 && looksLikeJson(text)) {
                if (working == null) working = req
                Log.d(TAG, "native ketangpai ${req.name} accepted tokenMode=${req.tokenMode}")
            }
        }

        // 鉴权方式确定不了就如实记录，不猜。
        root.put("workingTokenMode", working?.tokenMode ?: "none")

        // ★ §79：课程池**不再以 topcourses 为准**。
        //   实测 `topcourses` 只回「置顶」课程（每项 isTop:1），本学期的课
        //   「微电班复变函数与数学物理方法」isTop:0，压根不在里面。
        //   可靠的全量来源是 `semesterCourseList`（data 直接是数组），
        //   所以这里以它为主，topcourses 只留作对照参考。
        val pinnedCourses = org.json.JSONArray()

        val semProbe = JSONObject()
        var semesterGroups: List<Triple<String, String, String>> = emptyList()
        val coursePool = LinkedHashMap<String, Triple<String, String, String>>()

        for (req in NativeProbeSpec.ketangpaiSemesterRequests(credential, cookieHeader)) {
            val res = execute(req)
            val text = res.optString("sample")
            results.put(res)

            // 同一个 text 要按接口类型分别解析：semesterList 与 semesterCourseList 结构不同。
            val bySemester = parseSemesterCourseList(text)
            val bySemesterList = parseKetangpaiCoursesWithSemester(text)
            val found = if (bySemester.isNotEmpty()) bySemester else bySemesterList
            semProbe.put(req.name, "${res.optInt("status")}/${found.size}门")

            if (semesterGroups.isEmpty()) {
                val groups = parseKetangpaiSemesterGroups(text)
                if (groups.isNotEmpty()) semesterGroups = groups
            }
            if (req.name.startsWith("api-") || req.name == "semesterList-isstudy1") {
                // topcourses 区（置顶），只记下来对照，不并进主池。
                val pinned = parseKetangpaiCourses(text)
                pinned.forEach { pinnedCourses.put("${it.second}(${it.third})") }
            }
            if (found.isNotEmpty()) {
                val arr = org.json.JSONArray()
                found.take(12).forEach { arr.put("${it.second}(${it.third})") }
                semProbe.put("${req.name}_courses", arr)
                // ★ 全量池：以 semesterCourseList 的结果为准。
                if (req.name.startsWith("semesterCourseList")) {
                    found.forEach { coursePool[it.first] = it }
                }
            }
        }
        root.put("semesterProbe", semProbe)
        root.put("pinnedCourses", pinnedCourses)

        val courses = coursePool.values.toList()
        root.put("courseId", courses.firstOrNull()?.first ?: "")
        root.put("courseCount", courses.size)
        val namesArr = org.json.JSONArray()
        courses.forEach { namesArr.put("${it.second} / ${it.third}") }
        root.put("courses", namesArr)
        // 当前学期（按日期自己算，不依赖 semester[0] 的顺序惯例）。
        val cands = currentSemesterCandidates(todayIso())
        root.put("currentSemesterGuess", cands.firstOrNull()?.let { "${it.first}/${it.second}" } ?: "")
        val grpArr = org.json.JSONArray()
        semesterGroups.forEach { grpArr.put("${it.first}/${it.second} ${it.third}".trim()) }
        root.put("semesterGroups", grpArr)
        root.put("semesterGroupCount", semesterGroups.size)

        // ★ 作业/考试：对**每门课**都取一次，避免「第一门没作业就下结论」。
        courses.forEach { c ->
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
            for (i in 0 until minOf(cs.length(), 8)) lines += "  · ${cs.optString(i)}"
        }
        // ★ §78：学期分组是判断「本学期有没有课」的钥匙。
        //   之前 data.semester 是空数组（因为 isstudy 用成了教师页签）。
        val grp = root.optJSONArray("semesterGroups")
        if (grp != null && grp.length() > 0) {
            lines += "学期分组（${grp.length()}）：" +
                (0 until minOf(grp.length(), 6)).joinToString(" / ") { grp.optString(it) }
        } else {
            lines += "学期分组：无（isstudy 页签或账号无本学期课）"
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
     * 课堂派课程列表：★ §78 要读**两个区**，缺一不可。
     *
     * - `data.topcourses[]` = **置顶课程**（对应 `isTop:1`），只是首页最上面那一排，
     *   不是全部课程 —— 我们之前只读这里，所以永远只有 5 门。
     * - `data.semester[]` = **按学期分组的全部课程**，元素形如
     *   `{semester, term, termTxt}`，前端再逐个学期调 `semesterCourseList` 取明细。
     *
     * 返回 (课程 id, 课程名, 学期标记)。
     */
    fun parseKetangpaiCourses(json: String?): List<Triple<String, String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()

        val out = LinkedHashMap<String, Triple<String, String, String>>()

        // 1) 置顶课程
        data.optJSONArray("topcourses")?.let { list ->
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                val id = c.optString("id").ifBlank { continue }
                val sem = "${c.optString("semester")}/${c.optString("term")}"
                out[id] = Triple(id, c.optString("coursename"), sem)
            }
        }
        // 2) 学期分组里的课程（结构可能是 {semester, term, list:[]} 或直接课程数组）
        data.optJSONArray("semester")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val sem = g.optString("semester")
                val term = g.optString("term")
                val semLabel = if (sem.isBlank()) "?" else "$sem/$term"
                val list = g.optJSONArray("list") ?: g.optJSONArray("courses")
                if (list != null) {
                    for (j in 0 until list.length()) {
                        val c = list.optJSONObject(j) ?: continue
                        val id = c.optString("id").ifBlank { continue }
                        out[id] = Triple(id, c.optString("coursename"), semLabel)
                    }
                }
            }
        }
        return out.values.toList()
    }

    /** 今天（Asia/Shanghai），格式 `yyyy-MM-dd`。学期判定要用。 */
    private fun todayIso(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(java.util.Date())
    }

    /** 毫秒时间戳 → `MM-dd HH:mm`（Asia/Shanghai），摘要里显示截止时间用。 */
    fun fmtMs(millis: Long): String {
        if (millis <= 0L) return "无截止"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(java.util.Date(millis))
    }

    /**
     * 课堂派 `semesterCourseList` 响应解析（★ §79 新增）。
     *
     * ★ 结构与 `semesterList` **完全不同**，这是之前解析出 0 门的原因：
     *   - `semesterList`      → `data` 是**对象**，课程在 `data.topcourses[]`
     *   - `semesterCourseList` → `data` 直接是**数组**，元素就是课程
     *
     * 实测（2026-11-16）：`{isstudy:"1", semester:"2026-2027", term:"1"}`
     * 能拿到本学期的「微电班复变函数与数学物理方法」，且它 `isTop: 0` ——
     * 也就是说**它不在 `topcourses` 里**，这正是之前一直看不到它的原因。
     */
    fun parseSemesterCourseList(json: String?): List<Triple<String, String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        // 正常形态：data 是数组。也兼容 data.list / data.courses 两种包裹。
        val arr = obj.optJSONArray("data")
            ?: obj.optJSONObject("data")?.optJSONArray("list")
            ?: obj.optJSONObject("data")?.optJSONArray("courses")
            ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id").ifBlank { continue }
            out += Triple(
                id,
                c.optString("coursename"),
                "${c.optString("semester")}/${c.optString("term")}"
            )
        }
        return out
    }

    /**
     * 当前学期判定（纯函数，可单测）。
     *
     * ★ 不依赖「学期列表里排第一的就是当前学期」—— 调研明确指出
     *   `semester.sort` 在 bundle 里 0 处命中，`semester[0]` 只是前端惯例。
     *   这里按「年份降序 + 学期号降序」自己算，取第一个等于或早于今天的。
     *
     * @param todayIso 形如 `2026-11-16`；学年 8 月切分（9月-次年1月为第1学期）。
     */
    fun currentSemesterCandidates(todayIso: String): List<Pair<String, String>> {
        val year = todayIso.take(4).toIntOrNull() ?: return emptyList()
        val month = todayIso.drop(5).take(2).toIntOrNull() ?: 1
        // 8 月及以后 → 新学年第一学期；2-7 月 → 上一学年第二学期。
        return if (month >= 8) {
            listOf(
                "$year-${year + 1}" to "1",
                "$year-${year + 1}" to "2",
                "${year - 1}-$year" to "2"
            )
        } else {
            listOf(
                "${year - 1}-$year" to "2",
                "${year - 1}-$year" to "1",
                "$year-${year + 1}" to "1"
            )
        }
    }

    /** 学期分组本身：`data.semester[]` → (semester, term, termTxt)。用来判断「本学期有没有分组」。 */
    fun parseKetangpaiSemesterGroups(json: String?): List<Triple<String, String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("semester") ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            out += Triple(
                g.optString("semester"),
                g.optString("term"),
                g.optString("termTxt")
            )
        }
        return out
    }

    /**
     * 活动时间轴 `logs/learn` 解析（§83）—— **雨课堂最好用的数据源**。
     *
     * ★ 相比 c27 的优势（真机实测）：
     *   - `activities[]` 直接带 **`deadline`（毫秒）**，不用自己从 score_deadline 推断；
     *   - 带 **`type`**：`5` = 作业/测验，`9` = 公告，`14` = 课件/签到；
     *   - 带 `status` / `total_score` / `problem_count`，能判断「已交 / 未交」。
     *
     * 实测 121 条 activity：type 5 有 49 条（作业）、14 有 53 条（课件）、
     * 9 有 19 条（公告）。**作业只看 type=5。**
     *
     * 而且它覆盖的课程比 c27 更全（c27 只有 11/50 门有数据，
     * 因为部分课程不在「在线课件」体系里）。
     */
    fun parseLogsActivities(json: String?, courseName: String): List<JSONObject> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val acts = root.optJSONObject("data")?.optJSONArray("activities") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until acts.length()) {
            val a = acts.optJSONObject(i) ?: continue
            val type = a.optInt("type", -1)
            // 只收作业/测验类；9=公告、14=课件不是待办。
            if (!isLogsHomeworkType(type)) continue
            val deadline = a.optLong("deadline", 0L)
            val title = a.optString("title")
            out += JSONObject().apply {
                put("course", courseName)
                put("name", title)
                put("leaf_type", "5")               // 与 c27/章节树的作业类型对齐
                put("id", a.optLong("id", 0L))
                // ★ logs 的 deadline 是**毫秒**，直接可用。
                put("score_deadline", deadline)
                put("end_time", 0L)
                put("start_time", a.optLong("create_time", 0L))
                put("status", a.optInt("status", -1))
                put("total_score", a.optDouble("total_score", 0.0))
                put("problem_count", a.optInt("problem_count", 0))
                // ★ §84：雨课堂只有"考试"入口，老师发的作业/小测/考试都是 type=5，
                //   只能按标题判断到底属于哪一类。
                put("kind", classifyYuketangKind(title))
                put("source", "logs")
            }
        }
        return out
    }

    /** logs 里 `type=5` 是作业/测验；9=公告、14=课件，都不进待办。 */
    fun isLogsHomeworkType(type: Int): Boolean = type == 5

    /**
     * ★ §84：雨课堂**只有一个"考试"发布入口**，老师所有的作业、小测、考试
     * 都以 `type=5` / `leaf_type=5` 的形式发出。所以「类型」不能靠接口字段区分，
     * **只能按标题命名判断**。
     *
     * 真机 49 条 type=5 的标题样本，命名规律很清楚：
     *
     * | 模式 | 例子 |
     * | --- | --- |
     * | 小测/测验/quiz | `小测1` `小测3` `Quiz 7` `第1、2章小测验` `傅里叶级数随堂小测（1）` |
     * | 考试/期末/期中 | `期中考试` `期末考试` |
     * | 作业（含周次/讲次） | `第16周作业` `第九讲作业` `第七讲"傅里叶变换"作业` `Homework_7` |
     * | 考查/考察 | `劳动教育考查-2025-2026春` `国家安全教育考察-25-26秋` |
     * | 纯练习/无关键词 | `练习` `积分填空` `第五讲傅里叶级数（1）` |
     *
     * ★ 判成「作业」而不是「考试」的边界很重要：待办里显示成「考试」会吓人，
     *   显示成「作业」更贴切。**默认归为作业**（安全侧），只有明确写了
     *   「考试 / 期末 / 期中」才标成考试。
     */
    fun classifyYuketangKind(title: String?): String {
        val t = title.orEmpty().lowercase()
        // ★ 先判「报告 / 练习 / 习题 / 作业」这类**实体作业词**，
        //   因为它们经常和"期末"同时出现（真机里「电路实习期末报告提交」
        //   含"期末"，但它是一份报告作业，不是考试）。
        if (t.contains("报告") || t.contains("作业") ||
            t.contains("homework") || t.contains("练习") || t.contains("习题")
        ) {
            // 例外：「作业考试」这种同时含两词的，仍按考试判（更醒目）。
            if (t.contains("考试") || t.contains("exam")) return KIND_EXAM
            return KIND_HOMEWORK
        }
        // 1) 明确的考试：期中/期末/考试
        if (t.contains("考试") || t.contains("期末") || t.contains("期中") ||
            t.contains("exam") || t.contains("final")
        ) return KIND_EXAM
        // 2) 小测 / 测验
        if (t.contains("小测") || t.contains("测验") || t.contains("quiz") ||
            t.contains("test")
        ) return KIND_QUIZ
        // 3) 考查 / 考察（很多通识课用这个词，实质是期末作业）
        if (t.contains("考查") || t.contains("考察")) return KIND_ASSESS
        // 4) 兜底：**默认作业**。宁可显示成"作业"也不要误标"考试"。
        return KIND_HOMEWORK
    }

    const val KIND_EXAM = "考试"
    const val KIND_QUIZ = "小测"
    const val KIND_ASSESS = "考查"
    const val KIND_HOMEWORK = "作业"

    /**
     * `c27` 课程进度响应 → 扁平节点（§82 新增）。
     *
     * 这个接口的结构与 mooc 章节树**完全不同**，所以单独解析。已知返回里有
     * `data` 包裹，内容按「章节 → 小节 → 叶」层级；这里做**结构化遍历**：
     * 递归找任何带 `leaf_type`（或 `leafType`）的对象，统一扁平化，
     * 并对 `end_time` / `score_deadline` / `endTime` 等命名做兼容。
     */
    fun parseC27Leaves(json: String?, courseName: String): List<JSONObject> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        fun walk(o: Any?, depth: Int) {
            if (depth > 8) return
            when (o) {
                is JSONObject -> {
                    val lt = o.optString("leaf_type").ifBlank { o.optString("leafType") }
                    if (lt.isNotBlank()) {
                        val id = o.optLong("id", 0L).takeIf { it > 0L }
                            ?: o.optLong("leaf_id", 0L).takeIf { it > 0L }
                        out += JSONObject().apply {
                            val nm = o.optString("name").ifBlank { o.optString("title") }
                            put("course", courseName)
                            put("name", nm)
                            put("leaf_type", lt)
                            put("id", id ?: 0L)
                            put("end_time", o.optLong("end_time", 0L).takeIf { it > 0 }
                                ?: o.optLong("endTime", 0L))
                            put("score_deadline", o.optLong("score_deadline", 0L).takeIf { it > 0 }
                                ?: o.optLong("scoreDeadline", 0L))
                            put("start_time", o.optLong("start_time", 0L).takeIf { it > 0 }
                                ?: o.optLong("startTime", 0L))
                            // §84：同样按标题分词，作业/小测/考试靠命名区分。
                            put("kind", classifyYuketangKind(nm))
                            put("source", "c27")
                        }
                    }
                    val keys = o.keys()
                    while (keys.hasNext()) walk(o.opt(keys.next()), depth + 1)
                }
                is org.json.JSONArray -> {
                    for (i in 0 until o.length()) walk(o.opt(i), depth + 1)
                }
            }
        }
        walk(root.opt("data") ?: root, 0)
        return out
    }

    /**
     * 带学期信息的课程解析（§77）。
     *
     * ★ 目的：判断「本学期有没有课」。返回 (id, 课程名, "semester/term")。
     *   实测基线是 5 门课全为 `2025-2026/2`（上学期），
     *   本学期的「复变函数」「数学物理方法」应当出现在别的学期值下。
     */
    fun parseKetangpaiCoursesWithSemester(json: String?): List<Triple<String, String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("topcourses") ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("id").ifBlank { continue }
            val sem = "${c.optString("semester")}/${c.optString("term")}"
            out += Triple(id, c.optString("coursename"), sem)
        }
        return out
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
        // ★ §82：课堂端课程**不走** mooc-api 章节树。
        //
        //   实测 42/50 门课回 `{"error_code":80019,"msg":"请购买该课程之后再学习"}` ——
        //   因为课堂端课程没有 `course_sign`（那是 MOOC 接口的字段），
        //   而且这类课程本来就不在 mooc 体系里。
        //
        //   正解：走 `c27` 进度接口（调研里 80019 的官方处置就是换 c27），
        //   它只需要 classroom_id + course_id；course_id 在课程详情
        //   `/v2/api/web/classrooms/{cid}` 的 `data.course_id` 里。
        var c27Count = 0
        var c27WithLeaf = 0
        var logsCourses = 0
        courses.forEachIndexed { _, c ->
            val cid = c.first
            // 1) 课程详情：拿 course_id（章节树/进度都要）
            val detail = execute(NativeProbeSpec.yuketangClassroomDetail(host, cookies, cid))
            results.put(detail)
            val courseId = runCatching {
                JSONObject(detail.optString("sample"))
                    .optJSONObject("data")?.optString("course_id").orEmpty()
            }.getOrDefault("")

            if (courseId.isNotBlank()) {
                // 2) c27 进度：课堂端真正的课程内容入口
                val prog = execute(
                    NativeProbeSpec.yuketangC27ProgressRequest(host, cookies, cid, courseId)
                )
                results.put(prog)
                c27Count++
                val leafs = parseC27Leaves(prog.optString("sample"), c.second)
                if (leafs.isNotEmpty()) c27WithLeaf++
                for (n in leafs) allNodes.put(n)
            }
            // 3) 活动时间轴：★ §83 实测这是雨课堂**最好用**的数据源 ——
            //    直接带 deadline（毫秒）与 type（5=作业/9=公告/14=课件），
            //    覆盖面也比 c27 全（c27 只有 11/50 门有数据）。
            val logs = execute(NativeProbeSpec.yuketangLogsRequest(host, cookies, cid))
            results.put(logs)
            val acts = parseLogsActivities(logs.optString("sample"), c.second)
            if (acts.isNotEmpty()) logsCourses++
            for (n in acts) allNodes.put(n)
        }
        root.put("c27Probed", c27Count)
        root.put("c27WithLeaf", c27WithLeaf)
        root.put("logsCourses", logsCourses)

        root.put("nodes", allNodes)
        var withDeadline = 0
        var homeworkCount = 0
        var examCount = 0
        var futureCount = 0
        val leafCounts = LinkedHashMap<String, Int>()
        val homeworkNodes = mutableListOf<JSONObject>()
        // ★ §83：c27 与 logs 会覆盖同一门课的同一份作业，按 (课程名, 标题) 去重，
        //   否则同一个「第九讲作业」会被数两次。
        val seen = HashSet<String>()
        val deduped = org.json.JSONArray()
        val nowMs = System.currentTimeMillis()
        for (i in 0 until allNodes.length()) {
            val n = allNodes.getJSONObject(i)
            val key = n.optString("course") + "|" + n.optString("name")
            if (!seen.add(key)) continue
            deduped.put(n)
            val lt = n.optString("leaf_type", "?")
            leafCounts[lt] = (leafCounts[lt] ?: 0) + 1
            // ★ 0 代表「无限制」，必须走 isDeadlineUsable 判定，不能直接 > 0 之外再当时间用。
            val dl = if (isDeadlineUsable(n.optLong("score_deadline"))) {
                n.optLong("score_deadline")
            } else if (isDeadlineUsable(n.optLong("end_time"))) {
                n.optLong("end_time")
            } else {
                0L
            }
            if (dl > 0L) {
                withDeadline++
                if (dl > nowMs) futureCount++
            }
            if (isHomeworkOrExam(lt)) {
                homeworkNodes += n
                // ★ §83：5 就是作业类（真机里全是「第N周作业」「小测」「练习」），
                //   不再按「6=作业 / 5=考试」拆开报，避免把作业误标成考试。
                homeworkCount++
            }
        }
        root.put("nodes", deduped)
        root.put("leafTypeCounts", JSONObject(leafCounts.toMap()))
        root.put("withDeadline", withDeadline)
        root.put("futureDeadline", futureCount)
        root.put("homeworkCount", homeworkCount)
        root.put("examCount", examCount)

        // ★ §84：按「作业/小测/考试/考查」分类统计，并把**未过期的**单独列出来。
        //   雨课堂只有一个发布入口，类型只能靠标题判断 —— 分类结果要能直接看到。
        val kindCounts = LinkedHashMap<String, Int>()
        val pending = org.json.JSONArray()
        for (i in 0 until deduped.length()) {
            val n = deduped.getJSONObject(i)
            val kind = n.optString("kind").ifBlank { KIND_HOMEWORK }
            kindCounts[kind] = (kindCounts[kind] ?: 0) + 1
            val dl = if (isDeadlineUsable(n.optLong("score_deadline"))) {
                n.optLong("score_deadline")
            } else if (isDeadlineUsable(n.optLong("end_time"))) {
                n.optLong("end_time")
            } else {
                0L
            }
            if (dl > nowMs) {
                pending.put(
                    JSONObject().apply {
                        put("course", n.optString("course"))
                        put("name", n.optString("name"))
                        put("kind", kind)
                        put("deadline", dl)
                        put("status", n.optInt("status", -1))
                    }
                )
            }
        }
        root.put("kindCounts", JSONObject(kindCounts.toMap()))
        root.put("pendingCount", pending.length())
        root.put("pending", pending)

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
        // ★ §82：c27 路线单独报，用来区分「课时节点没拿到」与「这课真没作业」。
        lines += "c27：探测 ${root.optInt("c27Probed")} 门 / 拿到节点 ${root.optInt("c27WithLeaf")} 门"
        lines += "logs：${root.optInt("logsCourses")} 门课有活动流"
        // ★ §84：雨课堂只有"考试"入口，作业/小测/考试按标题分类。
        val kc = root.optJSONObject("kindCounts")
        if (kc != null && kc.length() > 0) {
            val kv = kc.keys().asSequence().joinToString(" / ") { "$it=${kc.optInt(it)}" }
            lines += "按标题分类：$kv"
        }
        lines += "作业 ${root.optInt("homeworkCount")} 个 / 考试 ${root.optInt("examCount")} 个" +
            " / 有绝对截止 ${root.optInt("withDeadline")} 个" +
            " / **未过期 ${root.optInt("futureDeadline")} 个**"
        // 未过期的逐条列出 —— 这才是真正会写进待办的东西。
        val pend = root.optJSONArray("pending")
        if (pend != null && pend.length() > 0) {
            lines += "待办候选（未过期 ${pend.length()} 条）："
            for (i in 0 until minOf(pend.length(), 10)) {
                val p = pend.optJSONObject(i) ?: continue
                lines += "  · [${p.optString("kind")}] ${p.optString("course")} / " +
                    "${p.optString("name")} @${fmtMs(p.optLong("deadline"))}"
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 课堂端课程列表解析 —— 返回 (classroom_id, 课程名, 学期标签)。
     *
     * ★ §82 **重大修正**：第三个字段以前被当作 `course_sign` 用，这是错的。
     *
     *   `/v2/api/web/courses/list` 的响应里**没有 `course_sign`** ——
     *   那个 `C32F2427949868N` 只出现在 **MOOC 接口**（`user-courses` 的 `product_list`）里。
     *   课堂端课程拿不到 sign，我之前把 `termLabel()` 的结果（`2026-2027/1 陈琨`）
     *   当成 sign 拼进章节树 URL，于是 42 门课全部回：
     *   `{"msg":"请购买该课程之后再学习","error_code":80019}`。
     *
     *   ★ 而调研早就写了这条：错误码 **80019 的处置是「用 c27 接口绕开」**。
     *   所以课堂端课程**不能**走 `mooc-api/.../chapter`，要走
     *   `/c27/online_courseware/schedule/course/progress/?classroom_id=&course_id=`。
     *
     *   第三项现在只承载「学期标签」用于展示与过滤，**绝不再当 sign 用**。
     */
    fun parseWebCourses(json: String?): List<Triple<String, String, String?>> {
        if (json.isNullOrBlank()) return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = obj.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("list") ?: data.optJSONArray("classrooms")
            ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String?>>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // 归档用 `id`，未归档用 `classroom_id`。
            val cid = c.optLong("classroom_id", 0L).takeIf { it > 0L }
                ?: c.optLong("id", 0L).takeIf { it > 0L }
                ?: continue
            val course = c.optJSONObject("course")
            val courseName = course?.optString("name")?.takeIf { it.isNotBlank() }
                ?: c.optString("name")
            val term = c.optLong("term", 0L).takeIf { it > 0L }
            val teacher = c.optJSONObject("teacher")?.optString("name").orEmpty()
            // 第三项：**学期标签**（仅供展示/过滤），不是 sign。
            out += Triple(cid.toString(), courseName, termLabel(term, teacher))
        }
        return out
    }

    /**
     * 雨课堂的数字学期 → 可读标签。`202502` = 2025-2026 第 2 学期。
     * 传入 0 / 空时只回教师名，避免造出假学期。
     */
    fun termLabel(term: Long?, extra: String = ""): String? {
        val base = when {
            term == null || term <= 0L -> null
            else -> {
                val y = term / 100
                val t = term % 100
                "$y-${y + 1}/${t}"
            }
        }
        return when {
            base != null && extra.isNotBlank() -> "$base $extra"
            base != null -> base
            extra.isNotBlank() -> extra
            else -> null
        }
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
    /**
     * `leaf_type` 是否属于「要转成待办」的类型。
     *
     * ★ §83 实测订正：**`leaf_type=5` 才是作业/测验**，不是考试。
     *   真机数据里 49 个 `leaf_type=5` 节点的名字全是
     *   「第16周作业」「第15周作业」「小测3」「练习」——
     *   而 53 个 `leaf_type=8` 是「Chapter 8 Counters」这类**课件/章节**。
     *
     *   之前把 5 当考试、6 当作业，于是摘要报
     *   `作业 0 个 / 考试 49 个` —— 数字对、标签错，
     *   会让人误以为「雨课堂没有作业」。这类错标比不显示更危险。
     *
     *   `6` 也一并收下：它在本账号没出现，但社区资料把它归为作业/测验，
     *   留着不影响（有就导，没有就算了）。
     */
    fun isHomeworkOrExam(leafType: String?): Boolean =
        leafType == "5" || leafType == "6"

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
