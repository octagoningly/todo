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
        // ★ §80：入口回到**明确的登录页** `/web?type=3`。
        //
        // 上一版改 `/m/v2`（移动版课程页）是个错误假设：
        // 实测**未登录时 `/m/v2` 会被服务端重定向**到
        // `https://www.yuketang.cn/web/?next=/m/v2&type=3` —— 也就是 PC 登录页。
        // 所以换入口根本绕不开 PC 布局，`loginPath` 指哪都只是多一跳。
        //
        // 真正的修复不在入口，而在 WebView 的缩放策略：
        // 之前 `useWideViewPort=true` + `loadWithOverviewMode=false`
        // 让 980px 宽的桌面页面塞进 ~400dp 的视口，右侧被裁 → 二维码只剩一半。
        // 现在打开 overview 模式把整页缩进一屏（见 `HomeworkSyncLoginDialog`）。
        //
        // 登录成功后 App 仍会跟随平台自己的跳转，落到课程页。
        loginPath = "/web?type=3",
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

/** 同步触发方式。默认「自动（打开 + 后台）」，不启用纯后台定时。 */
object SyncTrigger {
    const val ON_OPEN_AND_MANUAL = 0
    const val MANUAL_ONLY = 1
    const val DAILY_REMINDER = 2

    /**
     * ★ §96 选项文案改了。
     *
     * 用户指出：「如果 app 一直运行，岂不是不能自动抓取了，因为我看只有打开和手动两种」。
     *
     * 这确实是之前的问题 —— 旧选项说「打开 App 时 + 手动」，而实现只在
     * `LaunchedEffect(Unit)` 里跑一次，App 一直开着就再也不抓。
     *
     * 现在三条触发路径都接上了（冷启动 / 回前台 / 周期 Worker），
     * 选项名如实反映能力，别让用户以为只能手动。
     */
    val OPTIONS = listOf(
        ON_OPEN_AND_MANUAL to "自动（打开时 + 回到前台 + 后台每 6 小时）",
        MANUAL_ONLY to "仅手动",
        // 与上面那条的区别：只在后台静默跑，不做"回前台"补抓。
        DAILY_REMINDER to "只后台抓（每 6 小时）"
    )
}
