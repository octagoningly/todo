package com.lirui.oneline.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * ★ §96 作业自动抓取的**周期性后台任务**。
 *
 * 用户指出的真问题：
 * > 「如果我的 app 一直运行，岂不是不能自动抓取了，因为我看中只有打开和手动两种」
 *
 * 完全成立。之前的实现只挂在 `MainActivity` 的 `LaunchedEffect(Unit)` 上，
 * 那个只在 **Activity 创建时跑一次**：
 * - App 一直开着不重建 → 永远不再抓；
 * - App 被系统杀掉 → 也不会自己醒来抓。
 *
 * 现在补三条触发路径，覆盖全部场景：
 *
 * | 场景 | 触发 |
 * | --- | --- |
 * | 冷启动 | `MainActivity` 的 `LaunchedEffect`（保持） |
 * | App 在前台放着不动 | **回前台时检查**（`ON_START` 生命周期） |
 * | App 在后台 / 被杀 | **本 Worker**，周期唤醒 |
 *
 * ★ 为什么用 WorkManager 而不是前台服务：
 *   AGENTS.md 明确「不为权限付不该付的代价」。WorkManager 是官方推荐的后台调度，
 *   不需要 FGS 类型审核，也不用常驻通知栏。
 *
 * ★ 频率取 **6 小时**：与 `SyncSettings.AUTO_SYNC_MIN_INTERVAL_MS` 一致。
 *   作业不是分秒必争的提醒，6 小时足够，也不会被系统判定为滥用。
 *   WorkManager 的周期任务本身有 15 分钟下限，6 小时远高于它，安全。
 */
class HomeworkSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val titles = SyncEngine.autoSyncAndImport(applicationContext, fromForeground = false)
            if (titles.isNotEmpty()) {
                // 后台抓到新作业：发通知告诉用户（这样即使 App 没开也知道）。
                com.lirui.oneline.reminder.NotificationHelper
                    .postNewHomework(applicationContext, titles)
            }
            Result.success()
        }.getOrElse {
            android.util.Log.w(TAG, "worker failed", it)
            // 网络波动之类的问题：交给 WorkManager 按退避策略重试一次，
            // 不要用 failure 把周期任务彻底停掉。
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "OnelineProbe"
        private const val UNIQUE_NAME = "homework_sync_periodic"
        private const val INTERVAL_HOURS = 6L

        /**
         * 注册周期任务。幂等：重复调用不会叠加多个任务
         * （`ExistingPeriodicWorkPolicy.KEEP` 保留已在跑的那个）。
         *
         * 总闸关掉时由调用方负责 [cancel]。
         */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<HomeworkSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                // 没网就别唤醒 —— 抓取必须联网，白跑一次还会拖长待机。
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        /** 用户关掉总闸 / 清除登录时取消，别在后台偷偷抓。 */
        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            }
        }
    }
}
