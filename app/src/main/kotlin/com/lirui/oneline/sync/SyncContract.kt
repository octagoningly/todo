package com.lirui.oneline.sync

/**
 * 「教学平台作业自动导入」的数据契约。
 *
 * ★ 本轮只搭链路骨架：登录 → 读取会话凭证 → 判断是否已登录。
 *   作业抓取、字段映射、落库与排提醒都不在这里，等真机登录验证通过后再接
 *   （见 DESIGN.md 本版小节）。文件里的接口常量是**探针用**的已知入口，
 *   不是平台承诺的稳定契约 —— 平台改版就得跟着改。
 *
 * ★ 凭证策略：App 不接触账号密码，也不把 Cookie 明文写进 SharedPreferences。
 *   WebView 自己把 Cookie 持久化在应用私有目录，这里只在需要时读一次快照。
 */
enum class SyncPlatform(
    val id: String,
    val label: String,
    /** 用户没填学校域名时的默认站点。 */
    val defaultHost: String,
    /** 登录页路径（拼在 host 后面）。 */
    val loginPath: String,
    /** 判断「是否已登录」要看的关键 Cookie 名，任一存在即认为有会话。 */
    val sessionCookieNames: List<String>,
    /** 除登录 host 之外还要一并读取 Cookie 的域名。 */
    val extraCookieHosts: List<String>
) {
    KETANGPAI(
        id = "ketangpai",
        label = "课堂派",
        defaultHost = "www.ketangpai.com",
        loginPath = "/",
        // 2022 年抓包：登录响应会写 PHPSESSID / ketangpai_home_slb /
        // ketangpai_home_remember，后者内含 token（Max-Age=10800，约 3 小时）。
        sessionCookieNames = listOf("ketangpai_home_remember", "PHPSESSID"),
        extraCookieHosts = listOf(
            "www.ketangpai.com",
            "ketangpai.com",
            "openapiv5.ketangpai.com",
            "openapiv52.ketangpai.com",
            "w.ketangpai.cn"
        )
    ),
    YUKETANG(
        id = "yuketang",
        label = "雨课堂",
        defaultHost = "www.yuketang.cn",
        // 该页同时提供「账号登录」「微信/App 扫码」「微信快捷登录」。
        loginPath = "/web?type=3",
        // 社区脚本用 sessionid + csrftoken；学校子域名由用户自己填。
        sessionCookieNames = listOf("sessionid", "csrftoken"),
        extraCookieHosts = listOf("www.yuketang.cn", "yuketang.cn")
    );

    companion object {
        /** 读到脏值 / 老数据时回落课堂派，绝不抛异常把设置页搞崩。 */
        fun byId(id: String?): SyncPlatform =
            values().firstOrNull { it.id == id } ?: KETANGPAI
    }
}

/**
 * 一条外部作业。本轮只定义契约，**不落库**。
 *
 * [deadlineEpochSeconds] 必须是平台给出的**绝对** Unix 秒时间戳。
 * 不接受「剩余 3 小时」这类相对文案推算出来的时间 —— 每同步一次都会漂移
 * （sqyCoder/tuoyixia 就是这么做的，见 DESIGN.md）。
 */
data class HomeworkItem(
    val platform: SyncPlatform,
    val externalId: String,
    val courseName: String,
    val title: String,
    val deadlineEpochSeconds: Long
)

/** 一次 Cookie 读取的快照。只用于诊断与「是否已登录」判断，值不写日志、不落盘。 */
data class CookieSnapshot(
    val host: String,
    val cookies: Map<String, String>,
    val capturedAtMillis: Long
)

/** 同步触发方式。默认「打开 App 时 + 手动」，不启用纯后台定时。 */
object SyncTrigger {
    const val ON_OPEN_AND_MANUAL = 0
    const val MANUAL_ONLY = 1
    const val DAILY_REMINDER = 2

    val OPTIONS = listOf(
        ON_OPEN_AND_MANUAL to "打开 App 时 + 手动",
        MANUAL_ONLY to "仅手动",
        // 只是「到点提醒你去同步」，不做无头 WebView 后台取数：
        // 页面内注入 fetch 需要前台会话，纯后台拿不到页面上下文。
        DAILY_REMINDER to "每天提醒我去同步"
    )
}
