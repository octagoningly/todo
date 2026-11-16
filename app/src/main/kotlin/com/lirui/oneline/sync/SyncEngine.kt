package com.lirui.oneline.sync

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 作业导入的**高层入口**（§86）。
 *
 * 把「跑探针 → 抽作业 → 按学期/过期过滤 → 生成预览」串成一次调用，
 * 让界面层只关心"给我预览"和"确认导入"两件事。
 *
 * ★ 边界：预览阶段**只读**，不碰数据库；只有 `importConfirmed` 才写入。
 *   这样用户在预览里点「取消」时，待办列表一个字节都不会变。
 */
object SyncEngine {

    private const val TAG = "OnelineProbe"

    /** 一次导入的结果。 */
    data class ImportOutcome(
        val added: Int,
        val skipped: Int,
        val previewSize: Int
    ) {
        fun summary(): String = when {
            previewSize == 0 -> "没有可导入的作业（都已过期或不在本学期）"
            added == 0 && skipped > 0 -> "这 $skipped 条之前已经导入过了，没有重复添加"
            skipped > 0 -> "新增 $added 条，跳过 $skipped 条（已存在）"
            else -> "已导入 $added 条作业到待办"
        }
    }

    /**
     * 拉取预览。**只读**，不发写请求、不动数据库。
     *
     * ★ 必须在后台线程调用（内部是同步 HTTP）。
     */
    fun collectPreview(
        platform: SyncPlatform,
        host: String,
        cookies: Map<String, String>,
        semesterTag: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<ImportRules.PreviewLine> {
        Log.d(TAG, "collectPreview platform=${platform.id} semester=$semesterTag")
        val root = runCatching { NativeProbeRunner.probe(platform, host, cookies) }
            .getOrElse { e ->
                Log.e(TAG, "probe failed: ${e.javaClass.simpleName}", e)
                return emptyList()
            }
        val items = NativeProbeRunner.extractImportItems(platform, root)
        // ★ §90：这里**真的**按学期筛了 —— 条目自带 semesterTag（从课程池回填），
        //   之前传 { null } 等于没筛，靠"未过期"间接挡往期作业，
        //   但可补交的往期作业会漏网。
        val current = semesterTag ?: currentSemesterTag()
        val preview = ImportRules.buildPreview(
            items = items,
            semesterOf = { it.semesterTag },
            currentSemester = current,
            nowMillis = nowMillis
        )
        Log.d(TAG, "collectPreview extracted=${items.size} semester=$current " +
            "preview=${preview.size}")
        return preview
    }

    /**
     * 当前学期标签，如 `2026-2027/1`。
     *
     * 口径与 [NativeProbeRunner.currentSemesterCandidates] 一致：
     * **8 月及以后算新学年第一学期**，2–7 月算上一学年第二学期。
     * 不依赖平台返回的学期顺序（调研指出 `semester[0]` 无代码保证）。
     */
    fun currentSemesterTag(nowMillis: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Shanghai")
        )
        cal.timeInMillis = nowMillis
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return if (month >= 8) "$year-${year + 1}/1" else "${year - 1}-$year/2"
    }

    /** 用户点了「确认导入」才走到这里 —— 唯一会写数据库的地方。 */
    suspend fun importConfirmed(
        context: Context,
        lines: List<ImportRules.PreviewLine>
    ): ImportOutcome {
        val items = lines.map { it.item }
        val (added, skipped) = TodoRepositoryHolder.repo(context).importHomework(items)
        Log.d(TAG, "importConfirmed added=$added skipped=$skipped")
        return ImportOutcome(added, skipped, lines.size)
    }

    /** 一个平台在「一键抓取」里的状态。 */
    data class PlatformResult(
        val platform: SyncPlatform,
        val loggedIn: Boolean,
        val preview: List<ImportRules.PreviewLine>,
        val error: String? = null
    )

    /**
     * ★ §87 **一键抓取全部已登录平台**的作业。
     *
     * 这是用户要的主入口：不再需要进登录页、不必点「抓作业」，
     * 设置里一个按钮就把所有已登录平台的作业拉回来。
     *
     * 规则：
     * - **只抓已登录的**平台。未登录的直接跳过并在结果里标注，
     *   绝不弹登录框、绝不替用户去登录（账号密码只能用户自己在官方页输入）。
     * - 一个平台失败不影响另一个：各自 `runCatching` 包住。
     * - 合并成一个预览列表，按截止时间升序 —— 用户一次确认全部。
     */
    fun collectAllLoggedIn(
        checks: List<PlatformCheck>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PlatformResult> {
        val out = mutableListOf<PlatformResult>()
        checks.forEach { c ->
            if (!c.loggedIn) {
                out += PlatformResult(c.platform, false, emptyList(), null)
                return@forEach
            }
            val preview = runCatching {
                collectPreview(
                    platform = c.platform,
                    host = c.host,
                    cookies = c.cookies,
                    semesterTag = null,
                    nowMillis = nowMillis
                )
            }.getOrElse { e ->
                Log.e(TAG, "collect ${c.platform.id} failed: ${e.javaClass.simpleName}", e)
                out += PlatformResult(
                    c.platform, true, emptyList(), e.javaClass.simpleName
                )
                return@forEach
            }
            out += PlatformResult(c.platform, true, preview, null)
        }
        return out
    }

    /** 一个平台的抓取前提（登录态 + 凭证）。由 UI 层填，core 层只消费。 */
    data class PlatformCheck(
        val platform: SyncPlatform,
        val host: String,
        val loggedIn: Boolean,
        val cookies: Map<String, String>
    )

    /** 把多平台结果合并成一个按截止时间升序的预览（同一条只留一次）。 */
    fun mergePreviews(results: List<PlatformResult>): List<ImportRules.PreviewLine> =
        results
            .flatMap { it.preview }
            .distinctBy { it.dedupeKey }
            .sortedBy { it.item.deadlineMillis }

    /** 一键抓取结果的一句话摘要，直接显示在设置页。 */
    fun summarizeAll(results: List<PlatformResult>): String {
        if (results.isEmpty()) return "没有启用的平台"
        val loggedIn = results.filter { it.loggedIn }
        if (loggedIn.isEmpty()) {
            return "两个平台都未登录。请先点「登录」在官方页登录一次。"
        }
        val total = mergePreviews(results).size
        val parts = results.joinToString("\n") { r ->
            when {
                !r.loggedIn -> "· ${r.platform.label}：未登录，已跳过"
                r.error != null -> "· ${r.platform.label}：抓取失败（${r.error}）"
                r.preview.isEmpty() -> "· ${r.platform.label}：没有待导入的作业"
                else -> "· ${r.platform.label}：${r.preview.size} 条"
            }
        }
        val head = if (total == 0) "没有可导入的作业" else "共 $total 条待导入"
        return "$head\n$parts"
    }

    /**
     * ★ §88 **打开 App 时自动抓取并静默导入**（用户要求的「todo 里面自动抓取」）。
     *
     * 与 [collectAllLoggedIn] 的区别：**不弹预览，直接写入**。
     *
     * 这样做安全的前提（都已具备）：
     * 1. **去重**：已导入过的 `source+externalId` 一定跳过，重复冷启动不会堆条目；
     * 2. **只导未过期的**：[ImportRules.shouldImport] 已经滤掉过期与脏时间；
     * 3. **不覆盖用户编辑**：导入只 insert，不 update——用户改过的标题/时间不会被冲掉；
     * 4. **门槛**：[SyncSettings.shouldAutoSync] 限制 6 小时内不重复抓、总闸关了不抓。
     *
     * 返回一句话摘要（为空表示没有变化，UI 可以完全不打扰用户）。
     */
    suspend fun autoSyncAndImport(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        // 门槛先过，避免无意义的网络请求。
        if (!SyncSettings.shouldAutoSync(context, nowMillis)) return null

        val checks = SyncPlatform.values().map { p ->
            val h = SyncSettings.host(context, p)
            val snap = SyncCredentialStore.snapshot(p, h)
            PlatformCheck(
                platform = p,
                host = h,
                loggedIn = SyncSettings.userConfirmedLogin(context) ||
                    SyncRules.hasSession(p, snap.cookies),
                cookies = snap.cookies
            )
        }
        if (checks.none { it.loggedIn }) {
            Log.d(TAG, "autoSync skipped: no platform logged in")
            return null
        }
        Log.d(TAG, "autoSync start")
        val results = collectAllLoggedIn(checks, nowMillis)
        val merged = mergePreviews(results)
        SyncSettings.setLastAutoSyncAt(context, nowMillis)
        if (merged.isEmpty()) {
            Log.d(TAG, "autoSync: nothing to import")
            return null
        }
        val outcome = importConfirmed(context, merged)
        Log.d(TAG, "autoSync done: ${outcome.summary()}")
        // 只有真的新增了才返回摘要 —— 没有新增就不打扰用户。
        return if (outcome.added > 0) outcome.summary() else null
    }

    /** 登录探针：只回答「凭证拿到没有」，不抓作业。 */
    fun probe(platform: SyncPlatform, host: String, snapshot: CookieSnapshot): Probe =
        Probe(
            platform = platform,
            host = host,
            loginUrl = SyncRules.loginUrl(platform, host),
            loggedIn = SyncRules.hasSession(platform, snapshot.cookies),
            cookieNames = SyncRules.diagnosticNames(snapshot.cookies)
        )

    data class Probe(
        val platform: SyncPlatform,
        val host: String,
        val loginUrl: String,
        val loggedIn: Boolean,
        val cookieNames: List<String>
    )

}

/** 避免 SyncEngine 直接依赖 data 包造成循环，用一个薄壳延迟取 Repository。 */
internal object TodoRepositoryHolder {
    @Volatile
    private var cached: com.lirui.oneline.data.TodoRepository? = null

    fun repo(context: Context): com.lirui.oneline.data.TodoRepository =
        cached ?: synchronized(this) {
            cached ?: com.lirui.oneline.data.TodoRepository(context.applicationContext)
                .also { cached = it }
        }
}
