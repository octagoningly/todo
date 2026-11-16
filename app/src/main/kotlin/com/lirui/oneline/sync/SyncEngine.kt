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
        Log.d(TAG, "collectPreview extracted=${items.size}")
        val preview = ImportRules.buildPreview(
            items = items,
            // 课堂派的学期标签在课程池里，这里用「课程名 → 学期」映射；
            // 目前两个平台的 ImportItem 都不带学期，所以传 null = 不筛学期。
            // ★ 学期过滤实际由调用方传入的 semesterTag 与课程池配合完成，
            //   见 PreviewWithSemester。这里保守地不丢数据。
            semesterOf = { null },
            currentSemester = null,
            nowMillis = nowMillis
        )
        Log.d(TAG, "collectPreview preview=${preview.size}")
        return preview
    }

    /**
     * 带学期过滤的预览（推荐入口）。
     *
     * @param semesterTagOf 按课程名给出学期标签，如 `2026-2027/1`；
     *   返回 null 表示未知（不参与过滤）。
     */
    fun collectPreviewWithSemester(
        platform: SyncPlatform,
        host: String,
        cookies: Map<String, String>,
        currentSemester: String?,
        semesterTagOf: (String) -> String?,
        nowMillis: Long = System.currentTimeMillis()
    ): List<ImportRules.PreviewLine> {
        val root = runCatching { NativeProbeRunner.probe(platform, host, cookies) }
            .getOrElse { e ->
                Log.e(TAG, "probe failed: ${e.javaClass.simpleName}", e)
                return emptyList()
            }
        val items = NativeProbeRunner.extractImportItems(platform, root)
        return ImportRules.buildPreview(
            items = items,
            semesterOf = { semesterTagOf(it.courseName) },
            currentSemester = currentSemester,
            nowMillis = nowMillis
        )
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

    /** 说明链路状态的提示文案。 */
    const val NOT_IMPLEMENTED_HINT =
        "链路骨架已就绪：登录验证通过后，再接入作业抓取与落库"
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
