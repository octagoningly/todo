package com.lirui.oneline.sync

/**
 * 作业同步的判定规则。
 *
 * ★ 纯 Kotlin、无 Android 依赖 —— 项目没有 Robolectric，带 Context / android.jar
 *   的逻辑在本地单测里跑不了（AGENTS.md §2 第 4 级、§7）。
 *   所有判定、归一化、校验一律放这里，Android 侧只负责取字符串 / 存设置。
 */
object SyncRules {

    /** 1970 之后的作业才有意义；2100-01-01 之后视为脏数据。 */
    private const val MIN_EPOCH_SECONDS = 1_500_000_000L   // 2017-07-14
    private const val MAX_EPOCH_SECONDS = 4_102_444_800L   // 2100-01-01

    /** Cookie 头里的属性名不是键值对，解析时要丢掉。 */
    private val COOKIE_ATTRIBUTES = setOf(
        "path", "domain", "expires", "max-age", "secure", "httponly", "samesite"
    )

    /**
     * 把用户填的「学校域名」收敛成一个纯 host。
     *
     * 允许直接粘贴整条 URL（很多人会从浏览器地址栏复制），也允许带端口。
     * 非法字符一律判空并回落默认站点，不把奇怪字符串拼进 https:// 后面。
     */
    fun normalizeHost(raw: String?, fallback: String): String {
        val value = raw.orEmpty().trim()
        if (value.isEmpty()) return fallback
        val cleaned = stripScheme(value)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim()
            .lowercase()
        if (cleaned.isEmpty()) return fallback
        if (cleaned.length > 253) return fallback
        if (!cleaned.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return fallback
        if (!cleaned.contains('.')) return fallback
        return cleaned
    }

    private fun stripScheme(value: String): String =
        if (value.contains("://")) value.substringAfter("://") else value

    /** 登录页地址。雨课堂换学校时只换 host，路径不变。 */
    fun loginUrl(platform: SyncPlatform, host: String): String =
        "https://$host${platform.loginPath}"

    /** 需要读取 Cookie 的域名：用户填的学校域名优先，其余按平台补全。 */
    fun cookieHosts(platform: SyncPlatform, host: String): List<String> =
        (listOf(host) + platform.extraCookieHosts).distinct()

    /**
     * 解析 `CookieManager.getCookie()` 返回的 `"a=1; b=2; path=/"`。
     * 同名键后出现的覆盖前面的；属性项（path/domain/...）直接丢弃。
     */
    fun parseCookieHeader(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split(';').forEach { part ->
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) return@forEach
            val name = kv.substring(0, eq).trim()
            if (name.isEmpty() || name.lowercase() in COOKIE_ATTRIBUTES) return@forEach
            out[name] = kv.substring(eq + 1).trim()
        }
        return out
    }

    /**
     * 只有**认证 Cookie** 存在且非空才算登录成功。
     *
     * ★ §70.7：旧版「任一关键 Cookie 存在」会把匿名 `csrftoken`（雨课堂）/
     *   `PHPSESSID`（课堂派）误判成已登录。这里必须严格、fail-closed：
     *   宁可提示「未登录」让用户再点一次，也不能假装登录成功。
     *   这只是本机 Cookie 判定，**不等于向平台校验过凭证有效性**。
     */
    fun hasSession(platform: SyncPlatform, cookies: Map<String, String>): Boolean =
        cookies[platform.authCookieName]?.isNotBlank() == true

    /** 只返回 Cookie **名字**用于诊断，绝不返回值（那等同登录凭证）。 */
    fun diagnosticNames(cookies: Map<String, String>): List<String> =
        cookies.keys.sorted()

    /**
     * 平台侧作业 id → 去重键。前缀带平台，避免两个平台撞 id 时互相覆盖。
     */
    fun dedupeKey(platform: SyncPlatform, externalId: String): String =
        "${platform.id}:${externalId.trim()}"

    /** 绝对 Unix 秒 → 毫秒；越界返回 null，绝不把脏时间戳写进待办。 */
    fun deadlineMillis(epochSeconds: Long): Long? =
        if (epochSeconds in MIN_EPOCH_SECONDS..MAX_EPOCH_SECONDS) epochSeconds * 1000L
        else null

    /**
     * 三个必需字段齐全才算一条可用作业：科目、标题、绝对截止时间。
     * 缺任一条都跳过并告警，不允许「部分成功静默覆盖旧数据」。
     */
    fun isValid(item: HomeworkItem): Boolean =
        item.externalId.isNotBlank() &&
            item.courseName.isNotBlank() &&
            item.title.isNotBlank() &&
            deadlineMillis(item.deadlineEpochSeconds) != null
}
