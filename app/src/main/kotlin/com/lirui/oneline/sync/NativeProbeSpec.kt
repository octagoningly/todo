package com.lirui.oneline.sync

/**
 * 原生 HTTP 探针的**请求规格**（纯 Kotlin、无 Android 依赖，便于单测）。
 *
 * ★ 为什么要有这个文件（DESIGN.md §74）：
 *   §73 已证实「页面内 fetch」对课堂派走不通 —— 从 `www.ketangpai.com` 跨域到
 *   `openapiv5*.ketangpai.com`，带 `token` 自定义头会触发 CORS 预检，服务端不放行，
 *   三个 API 域一律 `TypeError: Failed to fetch`。
 *   `HttpURLConnection` 跑在 App 自己的进程里，**根本没有同源策略**，
 *   既能带任意请求头，也不受 Cookie 域限制 —— 这是唯一现实的路线。
 *
 * ★ 凭证来源：`CookieManager` 里 WebView 登录后留下的 Cookie（含 HttpOnly）。
 *   token **只用于拼请求头 / 请求体，绝不写进结果文件、绝不写日志**；
 *   诊断只记 `tokenFound` 这种布尔量。
 */
object NativeProbeSpec {

    /** 一条待发的探针请求。POST 的 body 用原始字符串，便于精确控制格式。 */
    data class ProbeRequest(
        val name: String,
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String?,
        /** 该请求属于哪种凭证传递方式，用于结果归类（`none` / `header` / `body` / `query` / `form`）。 */
        val tokenMode: String
    )

    /** 凭证探测结果：只暴露「有没有」，不暴露值。 */
    data class Credential(
        val tokenFound: Boolean,
        val token: String,
        val source: String
    )

    /**
     * 从 Cookie 里找课堂派 token。
     *
     * ★ 与 §73 的页面内版本对齐：`document.cookie` 里就有 `token` 键。
     *   原生侧还有额外优势 —— `CookieManager.getCookie()` 连 HttpOnly 的也能拿到。
     *   顺序：显式 `token` → `ketangpai_home_remember`（2022 年抓包里的旧名）→
     *   `userSession`（JSON，键 `token` / `data.token`）。
     */
    fun ketangpaiCredential(cookies: Map<String, String>): Credential {
        cookies["token"]?.takeIf { it.isNotBlank() }?.let {
            return Credential(true, it, "cookie:token")
        }
        cookies["ketangpai_home_remember"]?.takeIf { it.isNotBlank() }?.let {
            return Credential(true, it, "cookie:ketangpai_home_remember")
        }
        cookies["userSession"]?.let { raw ->
            tokenFromUserSessionJson(raw)?.let {
                return Credential(true, it, "cookie:userSession")
            }
        }
        return Credential(false, "", "none")
    }

    /**
     * `userSession` 是形如 `{"token":"…"}` 或 `{"data":{"token":"…"}}` 的 JSON。
     * 这里不引 org.json（纯函数要能本地单测，且 org.json 在单测里是 stub）。
     */
    fun tokenFromUserSessionJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val key = "\"token\""
        var from = 0
        while (true) {
            val idx = raw.indexOf(key, from)
            if (idx < 0) return null
            from = idx + key.length
            val colon = raw.indexOf(':', from)
            if (colon < 0) return null
            val quote = raw.indexOf('"', colon + 1)
            if (quote < 0) return null
            val end = raw.indexOf('"', quote + 1)
            if (end < 0) return null
            val value = raw.substring(quote + 1, end)
            if (value.isNotBlank()) return value
        }
    }

    /**
     * 雨课堂凭证：Cookie 原样交给 `Cookie` 头，另外 `csrftoken` 同步进 `x-csrftoken`。
     * `xtbz: cloud` 是**必需头**（§72），漏了会回 `XTBZ IS REQUIRED`。
     *
     * ★ §74 调研补充：业界还并存 `xtbz: ykt` + `x-client: web` 这一套写法
     *   （Zachary709 / Raincourse / RainClassSDK），所以 xtbz 做成参数，
     *   某个接口在 `cloud` 下报怪错时能直接换 `ykt` 重试，不用改代码再发版。
     */
    fun yuketangHeaders(
        cookies: Map<String, String>,
        xtbz: String = "cloud",
        xClient: Boolean = false,
        classroomId: String? = null
    ): List<Pair<String, String>> {
        val out = mutableListOf("xtbz" to xtbz)
        if (xClient) out += "x-client" to "web"
        classroomId?.takeIf { it.isNotBlank() }?.let { out += "classroom-id" to it }
        cookies["csrftoken"]?.takeIf { it.isNotBlank() }
            ?.let { out += "x-csrftoken" to it }
        cookies["university_id"]?.takeIf { it.isNotBlank() }
            ?.let { out += "university-id" to it }
        cookieHeader(cookies)?.let { out += "Cookie" to it }
        return out
    }

    /** 拼 `a=1; b=2`。Cookie 值可能含 `;` / `,`，这里只做最朴素的透传。 */
    fun cookieHeader(cookies: Map<String, String>): String? {
        if (cookies.isEmpty()) return null
        return cookies.entries
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }
            .takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------
    // 课堂派：token 的四种传递方式。原生侧没有预检，header 方式理论上能直接通，
    // 但仍把四种都留着 —— 万一服务端只认某一种，一次跑完就有答案。
    // ---------------------------------------------------------------------

    private const val JSON = "application/json"
    private const val TEXT = "text/plain"
    private const val FORM = "application/x-www-form-urlencoded"

    private val KETANGPAI_API_HOSTS = listOf(
        "https://openapiv5.ketangpai.com",
        "https://openapiv52.ketangpai.com",
        "https://openapiv53.ketangpai.com"
    )

    private const val SEMESTER_LIST_BODY = """{"isstudy":"0","search":""}"""
    private const val COURSE_LIST_BODY =
        """{"isstudy":1,"search":"","semester":"","term":""}"""

    /**
     * 课堂派候选请求。
     *
     * 第一组是**鉴权方式的四选一**（都打 `semesterList`，最短路径、最快判定）；
     * 能通的那一种再拿去取课程列表与作业/考试。
     */
    fun ketangpaiRequests(credential: Credential, cookieHeader: String?): List<ProbeRequest> {
        val host = KETANGPAI_API_HOSTS.first()
        val path = "$host/CourseApi/semesterList"
        val baseHeaders = mutableListOf<Pair<String, String>>()
        // 无 CORS 限制后，Cookie 也一并带上 —— 早期版本是纯 Cookie 鉴权。
        cookieHeader?.let { baseHeaders += "Cookie" to it }

        val out = mutableListOf<ProbeRequest>()

        // A：token 进自定义头（页面内做不到的那条，原生能试）。
        out += ProbeRequest(
            name = "api-headerToken",
            method = "POST",
            url = path,
            headers = baseHeaders + ("Content-Type" to JSON) + ("token" to credential.token),
            body = SEMESTER_LIST_BODY,
            tokenMode = "header"
        )
        // B：与页面内上一版等价的简单请求，作为对照基线。
        out += ProbeRequest(
            name = "api-noToken",
            method = "POST",
            url = path,
            headers = baseHeaders + ("Content-Type" to TEXT),
            body = SEMESTER_LIST_BODY,
            tokenMode = "none"
        )
        // C：token 进 JSON body。
        out += ProbeRequest(
            name = "api-bodyToken",
            method = "POST",
            url = path,
            headers = baseHeaders + ("Content-Type" to TEXT),
            body = """{"token":"${credential.token}","isstudy":"0","search":""}""",
            tokenMode = "body"
        )
        // D：token 进 URL query。
        out += ProbeRequest(
            name = "api-queryToken",
            method = "POST",
            url = "$path?token=${encode(credential.token)}",
            headers = baseHeaders + ("Content-Type" to TEXT),
            body = SEMESTER_LIST_BODY,
            tokenMode = "query"
        )
        // E：表单编码。
        out += ProbeRequest(
            name = "api-formToken",
            method = "POST",
            url = path,
            headers = baseHeaders + ("Content-Type" to FORM),
            body = "token=${encode(credential.token)}&isstudy=0&search=",
            tokenMode = "form"
        )
        return out
    }

    /**
     * 拿到课程 id 之后，取作业（contenttype=4）与考试（contenttype=6）。
     * `token` 用 header + Cookie 双保险，与 [ketangpaiRequests] 里能通的那条保持一致。
     */
    fun ketangpaiContentRequests(
        credential: Credential,
        cookieHeader: String?,
        courseId: String
    ): List<ProbeRequest> {
        val host = KETANGPAI_API_HOSTS.first()
        val headers = mutableListOf<Pair<String, String>>()
        cookieHeader?.let { headers += "Cookie" to it }
        headers += "Content-Type" to JSON
        headers += "token" to credential.token

        val common = "\"courseid\":\"$courseId\",\"courserole\":0,\"desc\":3," +
            "\"dirid\":0,\"lessonlink\":[],\"limit\":50,\"page\":1," +
            "\"sort\":[],\"vtr_type\":\"\""
        return listOf(4 to "homework", 6 to "exam").map { (type, name) ->
            ProbeRequest(
                name = name,
                method = "POST",
                url = "$host/FutureV2/CourseMeans/getCourseContent",
                headers = headers,
                body = "{\"contenttype\":$type,$common}",
                tokenMode = "header"
            )
        }
    }

    /**
     * 雨课堂候选请求。`chapter` 需要 cid + sign，两门课都要扫（§73 只扫了第一门）。
     *
     * ★ §75：`term` 不能只试 `latest`。实测两门课共 17 个节点**全是课件**，
     *   但「这门课没作业」与「作业在往期学期」是两件事 ——
     *   `latest` 只取最新学期，历史学期要单独传 `term`。
     */
    fun yuketangRequests(host: String, cookies: Map<String, String>): List<ProbeRequest> {
        val headers = yuketangHeaders(cookies)
        val base = "https://$host"
        return listOf(
            ProbeRequest(
                name = "courses",
                method = "GET",
                url = "$base/mooc-api/v1/lms/user/user-courses/" +
                    "?status=1&page=1&no_page=1&term=latest",
                headers = headers,
                body = null,
                tokenMode = "none"
            )
        )
    }

    /**
     * 往期学期探测：`term=all` / 空 / 具体学期值各试一次，
     * 用来区分「本课确实没有作业」和「作业不在当前学期」。
     */
    fun yuketangTermVariants(host: String, cookies: Map<String, String>): List<ProbeRequest> {
        val base = "https://$host/mooc-api/v1/lms/user/user-courses/"
        return listOf(
            "all",
            ""
        ).mapIndexed { i, term ->
            val q = if (term.isEmpty()) "" else "term=$term"
            ProbeRequest(
                name = "courses-term${if (term.isEmpty()) "none" else term}",
                method = "GET",
                url = "$base?status=1&page=1&no_page=1" + (if (q.isEmpty()) "" else "&$q"),
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            )
        }
    }

    /** 章节树：每门课一条。 */
    fun yuketangChapterRequest(
        host: String,
        cookies: Map<String, String>,
        index: Int,
        classroomId: String,
        courseSign: String?
    ): ProbeRequest {
        var url = "https://$host/mooc-api/v1/lms/learn/course/chapter" +
            "?cid=$classroomId&term=latest"
        if (!courseSign.isNullOrBlank()) url += "&sign=${encode(courseSign)}"
        return ProbeRequest(
            name = "chapter-$index",
            method = "GET",
            url = url,
            headers = yuketangHeaders(cookies),
            body = null,
            tokenMode = "none"
        )
    }

    /**
     * 作业详情候选。
     *
     * ★ §74 调研订正（两份独立源码 + 未登录态路由探针实测）：
     *   `leaf_info` 的路径是 **`<classroom_id>/<leaf_id>/` 两段数字 + 末尾斜杠**，
     *   §73 写的单段 `/leaf_info/<cid>/` 必然 404 —— 那不是缺参数，是**路由不匹配**。
     *   判据：路由错回 nginx 的 `<h1>Not Found</h1>`；路由对但 id 假回
     *   `{"msg":"Objects does not exist.","error_code":404000}`。别把两种 404 混为一谈。
     */
    fun yuketangLeafInfoRequests(
        host: String,
        cookies: Map<String, String>,
        classroomId: String,
        leafId: String,
        universityId: String? = null
    ): List<ProbeRequest> {
        val base = "https://$host/mooc-api/v1/lms/learn/leaf_info"
        val uv = universityId?.takeIf { it.isNotBlank() }
        val uvQuery = if (uv != null) "&uv_id=$uv" else ""
        return listOf(
            // 主口径：两段 + 末尾斜杠。
            ProbeRequest(
                name = "leafInfo-2seg",
                method = "GET",
                url = "$base/$classroomId/$leafId/?term=latest$uvQuery",
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            ),
            // 最干净的形态，只验证路由本身通不通。
            ProbeRequest(
                name = "leafInfo-bare",
                method = "GET",
                url = "$base/$classroomId/$leafId/",
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            ),
            // 备选：`xtbz: ykt` + `x-client: web`，另一派实现的写法。
            ProbeRequest(
                name = "leafInfo-xtbz-ykt",
                method = "GET",
                url = "$base/$classroomId/$leafId/?term=latest$uvQuery",
                headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true, classroomId = classroomId),
                body = null,
                tokenMode = "none"
            )
        )
    }

    /**
     * 作业题目列表。调研给出的两个流派都试一遍：
     * 单段 `{leaf_type_id}`（多数实现）与两段 `{leaf_id}/{sku_id}`（Raincourse）。
     *
     * ★ 注意这不是「作业列表」接口 —— 调研已证实**不存在**专门列表接口，
     *   作业清单只能从章节树按 `leaf_type` 筛。这个接口拿的是某一份作业的**题目**，
     *   用于确认 `leaf_type=6` 的节点确实是可提交作业（以及看有没有更精确的截止时间）。
     */
    fun yuketangExerciseRequests(
        host: String,
        cookies: Map<String, String>,
        leafTypeId: String?,
        leafId: String,
        skuId: String?,
        universityId: String?
    ): List<ProbeRequest> {
        val base = "https://$host/mooc-api/v1/lms/exercise/get_exercise_list"
        val uv = if (!universityId.isNullOrBlank()) "&uv_id=$universityId" else ""
        val out = mutableListOf<ProbeRequest>()
        if (!leafTypeId.isNullOrBlank()) {
            out += ProbeRequest(
                name = "exercise-single",
                method = "GET",
                url = "$base/$leafTypeId/?term=latest$uv",
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            )
        }
        if (!skuId.isNullOrBlank()) {
            out += ProbeRequest(
                name = "exercise-two-seg",
                method = "GET",
                url = "$base/$leafId/$skuId/?term=latest$uv",
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            )
        }
        return out
    }

    /**
     * 考试封面（`leaf_type=5` 用）。`exam_id` 即该节点的 `leaf_type_id`。
     * 实测未登录返回 200 + 跳转 JSON（不是 403），所以判据是看 `url` 字段而不是状态码。
     */
    fun yuketangExamCoverRequest(
        host: String,
        cookies: Map<String, String>,
        examId: String,
        classroomId: String
    ): ProbeRequest = ProbeRequest(
        name = "exam-cover",
        method = "GET",
        url = "https://$host/v/exam/cover?exam_id=$examId&classroom_id=$classroomId",
        headers = yuketangHeaders(cookies),
        body = null,
        tokenMode = "none"
    )

    /**
     * 日程接口（存疑候选）。调研实测路由存在但未确认是否含截止时间聚合，
     * 留着被打一次，省得下一轮再补。
     */
    fun yuketangScheduleRequest(
        host: String,
        cookies: Map<String, String>,
        classroomId: String,
        courseSign: String?
    ): ProbeRequest {
        var url = "https://$host/mooc-api/v1/lms/learn/course/schedule" +
            "?cid=$classroomId&term=latest"
        if (!courseSign.isNullOrBlank()) url += "&sign=${encode(courseSign)}"
        return ProbeRequest(
            name = "schedule",
            method = "GET",
            url = url,
            headers = yuketangHeaders(cookies),
            body = null,
            tokenMode = "none"
        )
    }

    /**
     * 只做百分号编码，不依赖 `java.net.URLEncoder` 的 `+` 行为（query 里 `+` 会被读成空格）。
     *
     * ★ 白名单必须是 **ASCII 字母数字**，不能用 `Char.isLetterOrDigit()` ——
     *   那个对中文也返回 true，`encode("作业")` 会原样吐出裸中文，
     *   query 里就是非法 URL（§74 单测逮到）。
     */
    fun encode(value: String): String {
        if (value.isEmpty()) return value
        val sb = StringBuilder()
        value.forEach { ch ->
            val ascii = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9')
            when {
                ascii || ch in "-_.~" -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    bytes.forEach { b ->
                        sb.append('%')
                        sb.append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                        sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                }
            }
        }
        return sb.toString()
    }
}
