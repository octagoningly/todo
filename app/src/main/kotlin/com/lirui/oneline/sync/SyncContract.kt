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
    /**
     * 认证 Cookie：**只有登录成功后才下发**，用它判定「已登录」。
     *
     * ★ 不能用 `csrftoken` / `PHPSESSID` —— 前者是 Django 匿名访问就发的 CSRF token，
     *   后者是 PHP 匿名会话 Cookie，光打开登录页就有了。拿它们判断会误报「已登录」
     *   （§70.7 真机实测）。判定收不到认证 Cookie 时一律按「未登录」处理，fail-closed。
     */
    val authCookieName: String,
    /** 仅用于诊断展示，不参与登录判定。 */
    val hintCookieNames: List<String>,
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
        authCookieName = "ketangpai_home_remember",
        hintCookieNames = listOf("PHPSESSID", "ketangpai_home_slb"),
        // §70.8：课堂派登录会跨好几个域（官网 / 微信入口 / API 域），
        // 认证 Cookie 可能落在其中任意一个上，逐个都读一遍再合并。
        extraCookieHosts = listOf(
            "www.ketangpai.com",
            "ketangpai.com",
            "www.ketangpai.cn",
            "w.ketangpai.cn",
            "coursehome.ketangpai.com",
            "openapiv5.ketangpai.com",
            "openapiv52.ketangpai.com",
            "openapiv53.ketangpai.com"
        )
    ),
    YUKETANG(
        id = "yuketang",
        label = "雨课堂",
        defaultHost = "www.yuketang.cn",
        // ★ §79：改走**移动版**入口 `/m/v2`。
        //
        // 原来用 `/web?type=3`（PC 版）：页面是给宽屏设计的，
        // 在手机上二维码会被裁掉一半，为此我们先后加过两轮 CSS 注入去「修布局」，
        // 两次都把 SPA 的滚动容器顶塌（§70.10、§77），越修越坏。
        //
        // 实测 `/m/v2` 是平台**自己的移动版**：进入后直接是课程列表，
        // 有「我教的课 / 我听的课 / 全部课程」，195 个课程卡片正常渲染，
        // 完全不需要我们注入任何样式。登录后也会落在这个页面。
        loginPath = "/m/v2",
        // 社区脚本用 sessionid + csrftoken；只有 sessionid 是登录后才有。
        // 学校子域名由用户自己填，否则 Cookie 可能落在别的 host 上读不到。
        authCookieName = "sessionid",
        hintCookieNames = listOf("csrftoken"),
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
