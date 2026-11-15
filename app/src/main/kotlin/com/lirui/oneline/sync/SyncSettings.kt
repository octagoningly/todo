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
    private const val KEY_CUSTOM_HOST = "custom_host"
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
     * 用户填的学校域名（雨课堂各校独立子域，如 `gsscut.yuketang.cn`）。
     * 空串 = 用平台默认站点。写入前统一走 [SyncRules.normalizeHost]。
     */
    fun customHost(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_HOST, "").orEmpty()

    fun setCustomHost(context: Context, raw: String) {
        val host = SyncRules.normalizeHost(raw, "")
        prefs(context).edit().putString(KEY_CUSTOM_HOST, if (host == "") "" else host).apply()
    }

    /** 不设默认参数：默认表达式里写 `platform(context)` 会被同名形参遮蔽，编译不过。 */
    fun host(context: Context, platform: SyncPlatform): String =
        SyncRules.normalizeHost(customHost(context), platform.defaultHost)

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
}
