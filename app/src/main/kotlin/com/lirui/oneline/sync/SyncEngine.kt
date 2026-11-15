package com.lirui.oneline.sync

/**
 * 作业同步引擎 —— **本轮只有骨架**。
 *
 * 完整链路（等登录验证通过后再接，见 DESIGN.md 本版小节）：
 *
 * ```
 * 前台 WebView 会话（用户已登录）
 *   → evaluateJavascript 注入 fetch，调同源 mooc-api / openapiv5
 *   → org.json 解析出 courseName / title / 绝对 deadlineEpochSeconds
 *   → SyncRules.isValid 逐条校验（缺字段跳过并告警，不用相对时间推算）
 *   → SyncRules.dedupeKey 去重、用户手动改过的待办不覆盖
 *   → TodoRepository.addFromParsed 范式落库
 *   → ReminderScheduler.scheduleBlocking() 排提醒（唯一入口）
 * ```
 *
 * 本轮刻意**不发任何网络请求、不碰 Room、不落库**：先把「能不能登录」
 * 这一件事验证掉，避免接口还没确证就动数据库 schema（v8→v9 迁移链）。
 */
object SyncEngine {

    data class Probe(
        val platform: SyncPlatform,
        val host: String,
        val loginUrl: String,
        val loggedIn: Boolean,
        val cookieNames: List<String>
    )

    /** 登录探针：只回答「凭证拿到没有」，不抓作业。 */
    fun probe(platform: SyncPlatform, host: String, snapshot: CookieSnapshot): Probe =
        Probe(
            platform = platform,
            host = host,
            loginUrl = SyncRules.loginUrl(platform, host),
            loggedIn = SyncRules.hasSession(platform, snapshot.cookies),
            cookieNames = SyncRules.diagnosticNames(snapshot.cookies)
        )

    /** 说明本轮为什么还没有真实同步结果，供设置页展示，避免按钮点了没反馈。 */
    const val NOT_IMPLEMENTED_HINT =
        "链路骨架已就绪：登录验证通过后，再接入作业抓取与落库"
}
