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

    /**
     * ★ §78：`isstudy` 是「我教的/我协助的/我学的」页签映射，**不是布尔量**。
     *
     * 学生对应「我学的」页签 → 发给后端必须是 `"1"`。
     * 之前全链路都写 `"0"`（那是**教师**页签「我教的」），
     * 于是 `topcourses` 还能容错回点东西、但 `semester[]` 永远是空数组、
     * 按学期取明细也自然拿不到本学期的课。
     */
    private const val ISSTUDY_STUDENT = "1"
    private const val SEMESTER_LIST_BODY =
        """{"isstudy":"$ISSTUDY_STUDENT","search":""}"""
    private const val COURSE_LIST_BODY =
        """{"isstudy":1,"search":"","semester":"","term":""}"""

    /**
     * 课堂派「按学期查」候选（§77）。
     *
     * ★ 现象：`semesterList` 只回 5 门课，**全是 `semester=2025-2026, term=2`**
     *   （上学期），而当前是 2026-2027 学年第一学期。用户本学期的
     *   「复变函数」「数学物理方法」一门都没出现。`data.semester` 还回了空数组，
     *   本应是可选学期列表。
     *
     * ★§78 调研从课堂派 H5 线上 bundle（`w.ketangpai.com/js/app.*.js`）还原出真因：
     *
     * **`isstudy` 不是布尔开关，是「我教的 / 我协助的 / 我学的」三个页签的映射值。**
     * 前端原文：
     * ```js
     * isstudy: "1"===this.isstudyTbs ? "0" : "2"===this.isstudyTbs ? "2" : "1"
     * ```
     * 于是：学生（`usertype="0"`，页签「我学的」）发给后端的 **`isstudy="1"`**；
     * 而 `"0"` 是**教师**页签「我教的」。我们一直传 `"0"`，
     * 难怪 `data.semester` 是空数组、`number=0` —— 学生没有「我教的课程」，学期分组自然为空。
     *
     * 第二个真因：**`topcourses` 只是「置顶课程」区**（对应 `isTop:1`），
     * 全部课程在 `semester[]` 里按学期分组，前端再逐个学期调 `semesterCourseList` 取明细。
     * 我们只读了 `topcourses`，所以只有 5 门 —— 本学期的课可能压根不在置顶区。
     *
     * 还有一处命名空间细节：小写 `/courseApi/semesterList` 是 H5 首页入口，
     * 大写 `/CourseApi/semesterCourseList` 是按学期取明细，两者不是同一个接口。
     */
    fun ketangpaiSemesterRequests(
        credential: Credential,
        cookieHeader: String?
    ): List<ProbeRequest> {
        val host = KETANGPAI_API_HOSTS.first()
        val headers = mutableListOf<Pair<String, String>>()
        cookieHeader?.let { headers += "Cookie" to it }
        headers += "Content-Type" to JSON
        headers += "token" to credential.token

        // axios 拦截器会自动注入的毫秒时间戳；开源实现里也要求带上。
        val reqTs = System.currentTimeMillis()
        val out = mutableListOf<ProbeRequest>()

        // ★ R1 最高优先：学生页签的 semesterList（isstudy=1）。
        //   顺便带上 reqtimestamp —— 平台自己的拦截器每次都加，可能参与校验。
        out += ProbeRequest(
            name = "semesterList-isstudy1",
            method = "POST",
            url = "$host/CourseApi/semesterList",
            headers = headers,
            body = "{\"isstudy\":\"1\",\"search\":\"\",\"reqtimestamp\":$reqTs}",
            tokenMode = "header"
        )
        // R2 助教页签；R3 教师页签（对照，就是之前一直用的那个）。
        listOf("2" to "assist", "0" to "teacher").forEach { (v, label) ->
            out += ProbeRequest(
                name = "semesterList-$label",
                method = "POST",
                url = "$host/CourseApi/semesterList",
                headers = headers,
                body = "{\"isstudy\":\"$v\",\"search\":\"\",\"reqtimestamp\":$reqTs}",
                tokenMode = "header"
            )
        }
        // R10 学生主路径精简列表：独立于 semesterList，值得单独打。
        out += ProbeRequest(
            name = "simpleLists",
            method = "POST",
            url = "$host/courseApi/simpleLists",
            headers = headers,
            body = "{\"reqtimestamp\":$reqTs}",
            tokenMode = "header"
        )
        // ★ R4/R6：按学期取明细。`semester=""`+`term=""` = 全部学期，
        //   所以先打「全部学期」拿到账号下真实存在的学期集合，再打本学期。
        val queries = listOf(
            "all" to ("" to ""),
            "2026-2027-1" to ("2026-2027" to "1"),
            "2026-2027-2" to ("2026-2027" to "2"),
            "2025-2026-2" to ("2025-2026" to "2")
        )
        queries.forEach { (label, semTerm) ->
            val (sem, term) = semTerm
            out += ProbeRequest(
                name = "semesterCourseList-$label",
                method = "POST",
                url = "$host/CourseApi/semesterCourseList",
                headers = headers,
                body = "{\"isstudy\":\"1\",\"search\":\"\"," +
                    "\"semester\":\"$sem\",\"term\":\"$term\",\"reqtimestamp\":$reqTs}",
                tokenMode = "header"
            )
        }
        // 归档接口：若本学期课程被误归档，会出现在这里。
        out += ProbeRequest(
            name = "archive-lists",
            method = "POST",
            url = "$host/ArchiveApi/lists",
            headers = headers,
            body = "{\"reqtimestamp\":$reqTs}",
            tokenMode = "header"
        )
        return out
    }

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
            body = """{"token":"${credential.token}","isstudy":"$ISSTUDY_STUDENT","search":""}""",
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
     * 雨课堂课程列表候选 —— **按优先级排列**，全都试一遍。
     *
     * ★ §76 重大订正：`mooc-api/v1/lms/user/user-courses/` **不是**雨课堂课堂端的课程列表，
     *   那是**学堂在线 MOOC 的选课接口**（字段 `product_list` / `course_sign` / `sku_id`
     *   都是学堂在线的商品术语）。它只会返回你在学堂在线选过的课 ——
     *   实测只有 2 门，而用户在界面上看到的课程明显更多，根因就在这。
     *
     *   课堂端真正的课程列表是 `/v2/api/web/courses/list?identity=2`（`identity=2` = 学生视角），
     *   界面上的卡片数还等于它 **加上** `/v2/api/web/classroom_archive`（已归档/已结课）。
     *   归档接口的数组在 `data.classrooms[]`，主键字段叫 `id` 而不是 `classroom_id`。
     *
     * 判读要点：`/v2/api/web/` 系列返回 **401 = 有路由但缺登录**，**404 才是路径错**。
     */
    fun yuketangCourseListRequests(
        host: String,
        cookies: Map<String, String>
    ): List<ProbeRequest> {
        val base = "https://$host"
        val headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true)
        return listOf(
            // ★ 主口径：课堂端学生课程列表。
            ProbeRequest(
                name = "courses-v2-identity2",
                method = "GET",
                url = "$base/v2/api/web/courses/list?identity=2",
                headers = headers,
                body = null,
                tokenMode = "none"
            ),
            // 已归档 / 已结课课程，要**相加**才是界面上的全部。
            ProbeRequest(
                name = "courses-archive",
                method = "GET",
                url = "$base/v2/api/web/classroom_archive",
                headers = headers,
                body = null,
                tokenMode = "none"
            ),
            // 对照：老接口（MOOC 选课），用来证明「两套是不同数据源」。
            ProbeRequest(
                name = "courses-mooc-legacy",
                method = "GET",
                url = "$base/mooc-api/v1/lms/user/user-courses/" +
                    "?status=1&page=1&no_page=1&term=latest",
                headers = yuketangHeaders(cookies),
                body = null,
                tokenMode = "none"
            )
        )
    }

    /** MOOC 接口的学期变体（保留做对照，值不再当作课程来源）。 */
    fun yuketangTermVariants(host: String, cookies: Map<String, String>): List<ProbeRequest> {
        val base = "https://$host/mooc-api/v1/lms/user/user-courses/"
        return listOf("all", "").map { term ->
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

    /**
     * 课程详情 / 活动时间轴 / 用户信息 —— 用来给课堂端课程补 `course_sign` 与 `uv_id`，
     * 以及确认「这门课到底有没有作业类活动」。
     */
    fun yuketangCourseDetailRequests(
        host: String,
        cookies: Map<String, String>,
        classroomId: String
    ): List<ProbeRequest> {
        val base = "https://$host"
        val headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true)
        return listOf(
            ProbeRequest(
                name = "classroom-$classroomId",
                method = "GET",
                url = "$base/v2/api/web/classrooms/$classroomId?role=5",
                headers = headers,
                body = null,
                tokenMode = "none"
            ),
            // 活动时间轴：作业/考试会以 activity 形式出现，这是另一条独立线索。
            // ★ 这个接口 `page` 从 0 开始，`offset` 含义是**每页条数**不是跳过条数。
            ProbeRequest(
                name = "logs-$classroomId",
                method = "GET",
                url = "$base/v2/api/web/logs/learn/$classroomId" +
                    "?actype=-1&page=0&offset=200&sort=-1",
                headers = headers,
                body = null,
                tokenMode = "none"
            )
        )
    }

    /** 用户信息：确认 `uv_id`（= university_id），章节树缺它可能回空。 */
    fun yuketangUserInfoRequests(
        host: String,
        cookies: Map<String, String>
    ): List<ProbeRequest> {
        val base = "https://$host"
        val headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true)
        return listOf(
            ProbeRequest("userinfo-v2", "GET", "$base/v2/api/web/userinfo", headers, null, "none"),
            ProbeRequest("basic-info", "GET", "$base/api/v3/user/basic-info", headers, null, "none")
        )
    }

    /**
     * 课程详情：拿 `course_id` 与 `uv_id`（§82）。
     *
     * 实测 `/v2/api/web/classrooms/{cid}?role=5` 返回：
     * ```json
     * {"data":{"id":33307885,"course_id":"4226944","course_name":"复变函数与数学物理方法",
     *  "teacher_name":"陈琨","uv_id":2668,"user_role":5}}
     * ```
     * `course_id` 是 c27 进度接口必需的参数。
     */
    fun yuketangClassroomDetail(
        host: String,
        cookies: Map<String, String>,
        classroomId: String
    ): ProbeRequest = ProbeRequest(
        name = "classroom-$classroomId",
        method = "GET",
        url = "https://$host/v2/api/web/classrooms/$classroomId?role=5",
        headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true),
        body = null,
        tokenMode = "none"
    )

    /**
     * 活动时间轴（§82）：作业/考试会以 activity 形式出现，是独立于章节树的线索。
     * ★ `page` 从 **0** 开始，`offset` 的含义是**每页条数**不是跳过条数。
     */
    fun yuketangLogsRequest(
        host: String,
        cookies: Map<String, String>,
        classroomId: String
    ): ProbeRequest = ProbeRequest(
        name = "logs-$classroomId",
        method = "GET",
        url = "https://$host/v2/api/web/logs/learn/$classroomId" +
            "?actype=-1&page=0&offset=200&sort=-1",
        headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true),
        body = null,
        tokenMode = "none"
    )

    /**
     * `c27` 章节进度接口：调研称可绕开 `mooc-api` 章节树的 80019 错误，
     * 需要 `classroom_id` + `course_id`（后者在课程列表里叫 `course.id`）。
     */
    fun yuketangC27ProgressRequest(
        host: String,
        cookies: Map<String, String>,
        classroomId: String,
        courseId: String
    ): ProbeRequest = ProbeRequest(
        name = "c27-progress-$classroomId",
        method = "GET",
        url = "https://$host/c27/online_courseware/schedule/course/progress/" +
            "?classroom_id=$classroomId&course_id=$courseId",
        headers = yuketangHeaders(cookies, xtbz = "ykt", xClient = true),
        body = null,
        tokenMode = "none"
    )

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
