package com.lirui.oneline.sync

import android.content.Context

/**
 * 作业导入功能的设置项。
 *
 * ★ 刻意用**独立的** SharedPreferences 文件，不放进 `oneline_settings`：
 *   后者会被备份模块整份导出（`BackupRules.importableSetting` 除隐私版本外全放行）。
 *   这里虽然不存 Cookie，但平台/学校域名属于用户就读信息，没必要跟着备份走。
 *
 * ★ 总闸默认 **false**（zuige 要求）。关掉时整节只显示一行开关，
 *   不加载 WebView、不读 Cookie、不发任何网络请求。
 */
object SyncSettings {

    private const val PREF_NAME = "oneline_homework_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PLATFORM = "platform_id"
    private const val KEY_TRIGGER = "trigger_mode"
    private const val KEY_LAST_LOGIN_AT = "last_login_at"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_USER_CONFIRMED = "user_confirmed_login"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun platform(context: Context): SyncPlatform =
        SyncPlatform.byId(prefs(context).getString(KEY_PLATFORM, null))

    fun setPlatform(context: Context, value: SyncPlatform) {
        prefs(context).edit().putString(KEY_PLATFORM, value.id).apply()
    }

    /**
     * 平台站点。
     *
     * ★ §92：用户决定**去掉「学校网址」自定义项** —— 各校虽然子域不同，
     *   但走平台默认站点就能正常登录与抓取（实测中山大学账号在
     *   `www.yuketang.cn` 下工作正常，`uv_id` 由服务端按 Cookie 识别）。
     *   少一个输入项，设置页也少一处歧义。
     *
     * 保留函数签名（不再接受自定义 host），调用方无需改动。
     */
    fun host(context: Context, platform: SyncPlatform): String = platform.defaultHost

    fun triggerMode(context: Context): Int {
        val v = prefs(context).getInt(KEY_TRIGGER, SyncTrigger.ON_OPEN_AND_MANUAL)
        return if (SyncTrigger.OPTIONS.any { it.first == v }) v
        else SyncTrigger.ON_OPEN_AND_MANUAL
    }

    fun setTriggerMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_TRIGGER, mode).apply()
    }

    fun lastLoginAt(context: Context): Long = prefs(context).getLong(KEY_LAST_LOGIN_AT, 0L)
    fun setLastLoginAt(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_LOGIN_AT, millis).apply()
    }

    fun lastSyncAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC_AT, 0L)
    fun setLastSyncAt(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC_AT, millis).apply()
    }

    /**
     * 用户在登录页主动点过「我已完成登录」。
     *
     * ★ §70.9：不再用猜出来的 Cookie 名当登录门槛。课堂派现状已不写 2022 年抓包里的
     *   `ketangpai_home_remember`，硬判会让「明明登录成功」的用户永远显示未登录。
     *   骨架阶段还没有真实接口探测，因此以**用户确认**为准；
     *   自动检测结果仍原样留在诊断里，供定位 Q1。清除登录状态时一并复位。
     */
    fun userConfirmedLogin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USER_CONFIRMED, false)

    fun setUserConfirmedLogin(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USER_CONFIRMED, value).apply()
    }

    /** 一键停用：只清状态与时间，不碰 WebView Cookie（那由 SyncCredentialStore 负责）。 */
    fun clearStatus(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_USER_CONFIRMED, false)
            .remove(KEY_LAST_LOGIN_AT)
            .remove(KEY_LAST_SYNC_AT)
            .apply()
    }

    // ---------- §88 打开 App 时自动抓取 ----------

    /**
     * 上次**自动**抓取的时间。
     *
     * ★ 与 [lastSyncAt]（用户主动点的那次）分开记，否则"用户手动抓过"
     *   会让下一次冷启动误以为刚抓过而跳过。
     */
    fun lastAutoSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AUTO_SYNC, 0L)

    fun setLastAutoSyncAt(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_SYNC, millis).apply()
    }

    /**
     * 该不该自动抓一次。
     *
     * 四条门槛，缺一不可：
     * 1. 总闸开着（用户没关）；
     * 2. 同步时机不是「仅手动」；
     * 3. 距上次自动抓取已超过 [minIntervalMillis]（默认 6 小时）——
     *    避免用户频繁切 App 时反复发请求打平台；
     * 4. 至少有一个平台已登录（由调用方判断，这里不管）。
     *
     * ★ §96 补充：选了 `DAILY_REMINDER`（只后台抓）时**跳过前台触发**，
     *   这样用户选「只后台」就真的不会在打开 App 时抓。
     */
    fun shouldAutoSync(
        context: Context,
        nowMillis: Long,
        minIntervalMillis: Long = AUTO_SYNC_MIN_INTERVAL_MS,
        fromForeground: Boolean = true
    ): Boolean {
        if (!enabled(context)) return false
        val mode = triggerMode(context)
        if (mode == SyncTrigger.MANUAL_ONLY) return false
        // 「只后台抓」不允许前台（冷启动 / 回前台）触发。
        if (mode == SyncTrigger.DAILY_REMINDER && fromForeground) return false
        val last = lastAutoSyncAt(context)
        if (last <= 0L) return true
        return nowMillis - last >= minIntervalMillis
    }

    /** 自动抓取最小间隔：6 小时。 */
    const val AUTO_SYNC_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val KEY_LAST_AUTO_SYNC = "last_auto_sync_at"
}
