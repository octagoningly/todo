# AGENTS.md — Oneline 项目开发规矩

> **每次改动前先读本文件。** 它是「以后怎么做」的唯一权威；`DESIGN.md` 是「过去为什么这么定」的历史日志。
> 两者冲突时以本文件为准，并在收尾时给 `DESIGN.md` 的过时段落标注勘误 —— **不删历史**。
> 项目：`com.lirui.oneline` / Kotlin + Jetpack Compose / 单模块单 Activity / 离线优先 / 数据只在本机。
> 定稿：2026-11-07（zuige 拍板）。

---

## 0. 谁读什么（文档分工，别写错地方）

| 文件 | 写什么 | 什么时候改 |
| --- | --- | --- |
| **`AGENTS.md`** | 规矩、红线、流程（本文件） | 规矩变了才改，**不记流水账** |
| `README.md` | 对外介绍、功能、版本徽章、文档索引 | 功能 / 版本 / 文档清单变化 |
| `DESIGN.md` | 决策理由、踩坑复盘、按版本追加的变更记录（现至 §57） | 每轮功能改动**追加一节**，保留历史 |
| `docs/DEV-NOTES.md` | 2026-10-22 从记忆迁出的**只读历史归档** | 原则上不改 |
| `docs/PRIVACY.md`、`docs/BACKGROUND.md`、`docs/STORE-*.md`、`docs/COPYRIGHT.md` | 单项专题 | 对应领域变化时 |
| `QUOTES.md`、`docs/COPY.md`、`SMOKE-REPORT.md` | 通知文案 / 对外分享稿 / 验收记录 | 对应内容变化时 |
| `.workbuddy/memory/` | 给 AI 的红线索引（本机，`.gitignore` 忽略） | 提炼出新红线时 |

两条已知文档时点问题，读到别被带偏：

- `DESIGN.md` 开头的「v1.0 定稿 / 待确认问题见 §9」是早期标题，文件实际已演进到 §69（2026-11-12）。
- `docs/STORE-RELEASE.md` 里「关于页没有隐私政策入口、没有首启同意弹窗」是**分叉前快照**，现在只对 `main` 成立。

---

## 1. 技术栈基线（`app/build.gradle.kts` 现状，2026-11-07）

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin；Jetpack Compose + **Material 3 Expressive**（`MaterialExpressiveTheme`） |
| BOM / M3 | `compose-bom:2026.06.01`；`material3:1.5.0-alpha17`（**alpha，别随手升级**） |
| 基础库 | `core-ktx 1.16.0`、`activity-compose 1.13.0`、`lifecycle 2.9.0`、`coroutines-android 1.11.0` |
| 存储 | Room `2.8.4`（schema **v8**）+ SharedPreferences；**无 Hilt / 无 Gson / 无 Moshi**，JSON 用平台 `org.json` |
| 后台 | WorkManager `2.11.2`、AlarmManager 精确闹钟、BroadcastReceiver |
| SDK / 构建 | minSdk 26 / target & compile 36 / JDK 17 / Gradle 9.5.0（无 wrapper）；R8 全开 |
| 导航 | 单 Activity，Compose 内部 4 tab + 专注覆盖层 |

**依赖原则**：版本统一由 BOM 管理；不为单一小功能引入大框架；不引反射序列化（会破坏 R8 裁剪）；不抬 minSdk；不为权限付不该付的代价。升级官方库前先跑全量单测 + 打 release 包真机验。

---

## 2. ★ 新功能实现顺序：四级，不许跳级

> zuige 定的核心规矩：**先复用旧模块 → 再找 Google / Android 官方库 → 再参考别人的开源实现 → 最后才手写，尽量不要完全手写。**
> 每加一个新功能，动手写代码之前要把这四步的结论**写进回复里**（「复用 X」/「X 不适用，因为 Y」），不许默认从零开始。

### 第 1 级：先翻仓库里已有的模块

动手前先 `Grep` / `Read` 找一遍。**优先查这些现成件**：

| 领域 | 可复用的模块 |
| --- | --- |
| 解析 | `parser/Parser.kt`、`parser/RangeMerge.kt`（多日期 / 合并为时间段） |
| 时间 / 格式化 | `util/AppTime.kt`（`ZONE`、`pickerMillis` / `pickerDate`）、`util/Format.kt`（`isOverdue`、日期格式） |
| 分组 | `util/HomeGrouping.kt`（板块 / 档位，分组与标红共用一套判定） |
| 提醒 | `reminder/ReminderEngine.kt`、`ReminderScheduler.scheduleBlocking()`（**排 OVERDUE 唯一入口**）、`NotificationHelper.kt` |
| 专注 | `focus/FocusRules.kt`（纯函数判定）、`FocusStore.kt`、`FocusEngine.kt`；`util/FocusStats.kt` |
| 备份 | `backup/BackupRules.kt` / `BackupCodec.kt` / `BackupManager.kt`（SAF + 三模式恢复） |
| 数据入口 | `data/TodoRepository.archive()`（通知动作完成**必须复用它**）、`ui/MainViewModel.requestArchive`（UI 归档唯一入口） |
| 设置 / 版本 | `util/SettingsRepo.kt`、`update/Version.kt`（语义化版本比较，纯函数） |
| UI 语义色 / 形状 | `ui/theme/Tokens.kt`（`cardBackground` 等）、`ui/theme/Theme.kt`（`OneShapes`、11 套配色、深浅色） |
| 共用 composable | `SlidingSegmented`、`DurationInputDialog`（**目前是 `FocusScreen.kt` 内的 `private fun`**，跨页复用前先抽到共用文件，别复制粘贴） |
| 桌面组件 | `widget/TodoWidgetProvider.kt`（更新时 `removeAllViews` 再 `addView`） |

改一个规则前先想清楚「已有单测覆盖哪些语义」，别写第二套逻辑（曾因另写一套分组逻辑导致标红口径不一致）。

- ★ 专注**手动结束**：「要不要弹三出口」判**墙钟跨度** `FocusRules.wallSpanSeconds()`（含暂停），**落库永远用净专注时长** `elapsedSeconds()` —— 暂停不计专注是红线，`FocusEngine` 别碰；阈值 `LONG_RUN_THRESHOLD_SECONDS = 3600`（§68 从 7200 降下来），净时长 < 60 秒仍走短时确认，`askLongRunSeconds` 存的是净时长；通知栏「结束」不弹窗是有意设计（§52.3）。别把两个时间混用，也别只降阈值就算修好暂停场景。

### 第 2 级：Google / Android 官方

优先用官方 API / 组件 / sample，而不是自己造：

- **AndroidX / Material 3 组件**：`Scaffold`、`TopAppBar`、`NavigationBar`、`FloatingActionButton`、`Card`、`Snackbar`、`DatePicker` / `TimePicker` / `DateRangePicker`、`SegmentedButton`、`FilterChip`、`ExposedDropdownMenu` 等。能用官方 Dialog / Picker 就别自绘（项目里选日期、选时长都已走 M3 选择器）。
- **官方实现与范例**：AOSP 源码、AndroidX / Material 组件源码、`android/nowinandroid`、Material Design 官方动效与 shape/motion 规范。
- 动效优先用 M3 自带 `spring` / `tween`（`DampingRatioNoBouncy, StiffnessLow`），形状优先改主题 `Shapes` 而不是逐个手写 `RoundedCornerShape`。见 `DESIGN.md §44`（形状阶交给主题）、`§48.4`（M3 滑动指示器，未引第三方动效库）。
- 权限、后台、通知、精确闹钟等行为按官方文档口径，不靠「我记得应该是」。

### 第 3 级：第三方开源代码「参考」

- 查得到实现就把**算法 / 画法 / 参数**看明白再搬，注明**出处、版本、License**；优先只借鉴思路，不新增依赖。
- 已废弃、已归档、或抽象层跟本项目对不上的库，**只参考不引入**。项目先例：`DESIGN.md §49.1` 查证 Google Accompanist `PlaceholderHighlight.shimmer()` 的「渐变肩部 + 一次性线性扫过」配方后，只把画法搬到波浪 Canvas 路径上 —— 该库 2023 年已废弃且 `Modifier.placeholder()` 只能盖矩形占位符。
- 真要引依赖：先说明理由（官方无替代、维护活跃、体积可接受），再走第 1 节的依赖原则，并在 `DESIGN.md` 记一笔。

### 第 4 级：才允许手写

只有前三步都不可用才手写，且必须同时做到：

1. 在回复里写明「为什么旧模块 / 官方库 / 开源参考都不适用」；
2. 判定、阈值、格式化这类逻辑**抽成无 Android 依赖的纯函数**（项目没有 Robolectric，带 `Context` 就测不了）并补本地单测；
3. 在 `DESIGN.md` 追加决策理由。

★ **禁止凭印象说「谷歌有个开源库」** —— 先查证名字、维护状态、实际能力，再下结论（`§49.1` 的教训）。

---

## 3. UI / 风格红线（M3 Expressive）

- 主题挂 `MaterialExpressiveTheme(motionScheme = MotionScheme.expressive())`；形状阶走 `OneShapes`（extraSmall 8 / small 12 / medium 16 / large 24 / extraLarge 28），**吃主题的组件自动跟随**。
- 卡片色一律取 `ui/theme/Tokens.kt`，不写死 ARGB、不另造 `surfaceVariant2`；深色底「抬明度保色相」，不用 alpha 混出脏色。
- **FAB 恒用经典 56dp**：`FloatingActionButton` + 24dp 图标 + 列表 `contentPadding.bottom=8dp`；底色 `primary` + `onPrimary`，elevation 四档归零 + 只留 fade。别把「全局形状阶」和「单个组件尺寸」绑一起改。
- ★ §68 起 FAB **可长按拖动**（官方 `detectDragGesturesAfterLongPress`，复用 `ReorderableBoardList` 范式），但上一条的 56dp / 配色 / elevation 归零 / fade **一律不变**；位移用 `Modifier.offset{}` 的 lambda 版（layout 位移，点击热区跟着走，别改用 `graphicsLayer`），边界与归一化走 `util/FabPosition.kt` 纯函数，位置存 `SettingsRepo` 的 0..1 比例，**不吸附边缘**。FAB 已从 Scaffold 的 `floatingActionButton` 槽移到 content 的 `BoxWithConstraints`（槽位拿不到内容区尺寸），别搬回去。
- 字阶只改**字重 / 字距**，不改字号（写死行高的地方很多）。
- 浅绿底（`primaryContainer`）文字一律配 `onPrimaryContainer`；主按钮统一 50% 圆角胶囊。
- 时区只用 `AppTime.ZONE`（`Asia/Shanghai`）；**M3 DatePicker 的毫秒是 UTC 当日 00:00**，进出必须走 `AppTime.pickerMillis` / `pickerDate`。
- 小组件**不按尺寸分档**（vivo 恒报 152px），行为由四个开关决定；`widget_label`（系统列表搜索）与 `widget_header`（布局标题）不能混用。
- 归档 / 恢复 / 通知态切换等高频交互，复用同一状态机与同一通知 id（`setOnlyAlertOnce(true)`），不另起一套。

---

## 4. ★ 双分支边界（最容易搞错）

| | `main` | `release/vivo-store` |
| --- | --- | --- |
| 应用内自更新 | **有**（GitHub 主源 + blog 兜底） | **无** |
| 网络权限 | 有 `INTERNET` 等 4 项 | **不申请任何网络权限** |
| 隐私政策 | **无**首启同意弹窗、无应用内政策页 | **有** `PrivacyScreen` + 首启同意 + 关于页入口 + 上架材料 |
| `allowBackup` | `false` | `false` |
| `docs/PRIVACY.md` | 联网版 | 不联网版，**内容不同，别互相覆盖** |
| 分发 | GitHub Release + 博客直链 | vivo 商店后台 |
| 版本号 | `1.0.14 / 48`（2026-11-07） | **独立递增**，不与 main 对齐 |

共同祖先 `e7fb97e`。**边界就是「能否联网更新」与「有没有隐私政策」**，其它功能尽量保持一致。

> ★ **临时冻结（zuige 2026-11-12）**：`release/vivo-store` 正在**申请软件著作权**，
> 申请期间**不要动这条分支的代码** —— 不改、不 cherry-pick、不补功能、不发版。
> 下面「功能改动两条线各落一次」是常规要求，**冻结期间以本条为准**，解冻由 zuige
> 明确通知。§68 / §69 的 main 改动只落 main，不往合规分支搬，也**不记作欠账**。

规则：

- **功能改动要在两条线各落一次**，不能只推 main；版本号各自独立递增。
- 从 main 往合规分支用 `git cherry-pick -n`，只搬功能 diff、**不整文件覆盖**；之后**逐行核对 Manifest**：`INTERNET` / `ACCESS_NETWORK_STATE` / `REQUEST_INSTALL_PACKAGES` / `FOREGROUND_SERVICE_DATA_SYNC` / FileProvider / `update/` / `UpdateDialog.kt` / 关于页检查更新行，一个都不能带过去。
- 无冲突也要重新编译验证。合规分支 `PRIVACY_POLICY_VERSION` 只在政策实质变更时递增；涉及新数据存储 / 通知 / 权限时同步更新**那条分支的** `docs/PRIVACY.md`。
- remote 用 SSH `git@github.com:zuige66/todo.git`（HTTPS 会 `Empty reply from server`）。
- ★ 本文件**应**在两条分支保持一致（2026-11-07 已在两条分支落地）。在某条分支上工作，以「本文件 + 上表」为准判断哪些改动属于越界。
  ⚠ 合规分支的功能基线落后 `main` 较多（见该分支 `DESIGN.md §58.4`）：无 `backup/` / `update/` 模块、无 `ALL_MIGRATIONS` 与 instrumented 迁移测试。§2 的可复用模块清单里这些件在合规分支不存在，`cherry-pick -n` 撞到 modify/delete 冲突时按「本分支缺什么就别带什么」处理，不要顺手搬半个模块进来。

---

## 5. ★ 发版 = 两个源 + 一篇文章（**只有发版才做**）

> **口径（zuige 2026-11-07 确认）**：
> - 只有 **versionName / versionCode 递增并对外出包** 时才同步两个源；日常功能改动只 commit + 更新 md，**不碰 Release / Hexo / 博客文章**。
> - **blog 兜底源只跟 `main` 的公开版**。合规分支发版走 vivo 商店后台，**不得写 `latest.json`** —— 否则会把不联网的商店版推给 main 用户，兜底链路直接失效。

### 源一：GitHub Release

1. APK 放 `dist/todo-<version>.apk`，**先 `git push origin main` 再建 Release**（曾领先 14 提交，否则 tag 打到旧提交）。
2. `POST /repos/zuige66/todo/releases` → tag `v<version>`；tag **不能复用已装机的版本号**。Windows `curl` 带 `--ssl-no-revoke`，PAT 只从本机用户级记忆取，绝不能写进仓库。
3. 资产用 `uploads.github.com`，**文件名小写** `todo-<version>.apk`；notes 覆盖本版 + 累积改动，并勘误历史 notes 里已失效的说法（如 v1.0.2 的「检查更新已下线 / 不申请网络权限」自 1.0.14 起只适用于合规分支）。
4. `git fetch --tags`；`GET /releases/latest` 复验 `tag / draft=false / assets` —— 这正是 `UpdateService.parse()` 读的字段。

### 源二：Hexo 博客兜底源（独立仓库 `/d/Workplace/hexo`，与 App 仓库分开提交）

| 文件 | 改什么 |
| --- | --- |
| `source/downloads/oneline/latest.json` | `versionCode` / `versionName` / `apkUrl` / `changelog`。★ `parseBlog()` 只取 `versionName` + `apkUrl`：版本没同步会让兜底链路「永远查不到新版」 |
| `source/downloads/oneline/todo-<version>.apk` | 从 `dist/` 复制同版本包 |
| `source/_posts/todo-app.md` | **不止改下载直链**：版本号、功能章节、截图说明一并核对（文章曾停在早期版本：还写「没有日期选择器」，缺专注 / 备份 / 自更新回归） |
| `docs/COPY.md`（App 仓库） | 「国内镜像」链接同步到同一条带参数的直链 |

部署：`npx hexo generate && npx hexo deploy` 推 `gh-pages`。

- ★ **禁止 `hexo clean`**：删 `public/` 上百文件会触发 WorkBuddy `safe-delete` 批量删除保护（count 50 / threshold 50）直接 FATAL。
- ★ **Cloudflare**：正常 `max-age=600`，但 **404 会缓存 4 小时**。部署期间别提前探测新 URL；`apkUrl` 与文章直链都加 `?v=<versionCode>` 绕过。文件名由 `ApkInstaller` 从 version 构造、不从 URL 取，所以带查询参数是安全的。
- `latest.json` 自身最多 10 分钟全网一致；线上复验版本号、`apkUrl`、APK 返回 200 与字节数。

### 收尾（App 仓库内）

- README 版本徽章 / 当前版本 / 最新 Release / 文档表；`DESIGN.md` **追加一节**变更记录；`QUOTES.md` / `docs/COPY.md` / `SMOKE-REPORT.md` 按实际变化更新。
- **版本说明的权威源是 `DESIGN.md` 本版小节**；`latest.json` 的 changelog、GitHub Release notes、博客文章都从它摘，避免四处各写一套。`build.gradle.kts` 里已有的历史注释保留，但新版本只在注释里写一行索引，不再堆长篇。
- 三处 APK（`dist/` / GitHub 资产 / 博客同站）**sha256 必须一致**。

细节复盘见 `DESIGN.md §57.8`（发布）与 `§57.9`（博客源同步、CDN 缓存）。

---

## 6. 每次改动的收尾清单

1. **任务前先讨论方案**，不动手；确认后再写代码。
2. 按第 2 节四级顺序实现；能复用就不新写。
3. 功能改动**两条分支各落一次**（第 4 节）；合规分支不越界。
4. `:app:testDebugUnitTest` → `:app:assembleRelease` → **本地脚本验收**。改完代码不用等 zuige 说就跑构建，构建不等于发版。
   ★ **zuige 2026-11-08 定稿：「adb 装机是允许的，但是不要 adb 随意操控手机与截图查看」**
   —— 覆盖安装（含 `adb install -r`）**属于允许动作，不必每轮单独请示**；
   但一律不做 `input tap` / 输入文字 / `keyevent` 唤醒 / `uiautomator dump` /
   截图 / 看 UI 等操控与查看动作，也不做真机 UI 验收。2026-11-07
   「测试不要控制我手机测试，直接脚本测试就好了」的原则不变。
   验收清单一律固化成 JVM 单测（带 `Context` / `org.json` 的除外，见 §7）。
   ★ 锁屏导致 `INSTALL_FAILED_ABORTED` 时**不要发按键绕过**，直接请 zuige 手动解锁后重试安装。
   确需真机交互验证（如 Room 迁移、SAF 选文件）仍必须**先单独征得同意**；§7 的真机条目只在这种授权后适用。
5. **改完文件必须更新对应的 md**（第 0 节分工）：功能 → `README.md` + `DESIGN.md` 追加节；规矩 → 本文件；领域专题 → `docs/*`。
6. `git status --short` 核对后**按具体路径 `git add` + `git commit`**，每轮功能改动收尾立刻提交，不等 zuige 说。
   ★ **禁止 `git add -A`**；别带 `output/`、`show/`、`dist/`、`docs/COPYRIGHT.md`（除非明确要求一并提交）。
7. 只有走到第 5 节的发版流程才同步两个源与博客文章。

---

## 7. 构建 / 测试 / 验收红线

- 构建前 `export JAVA_HOME="C:/Users/lirui/.jdks/jdk17.0.20_12"`（否则被 VS Code 扩展 JRE 劫持、jlink 报错），先 `gradle --stop` 再用 Gradle 9.5.0 绝对路径。
- 调用 adb 前 `export MSYS_NO_PATHCONV=1`；主力机 vivo V2324HA `10AE721UXN000QG`（Android 15）。
- ★ Room 加列必须**显式写与实体 `@ColumnInfo(defaultValue)` 一致的 DEFAULT**（可空列 `DEFAULT NULL`，非空串列 `DEFAULT ''`）+ `hasColumn` 幂等（0.9.3 漏 `DEFAULT` 秒崩）；schema 变更要跑 `:app:connectedDebugAndroidTest` 的 `MigrationTestHelper` 迁移链测试，并同步 `BackupRules.DB_VERSION` 与 `app/schemas/.../<v>.json`。
- 判定 / 阈值 / 格式化放**无 Android 依赖的纯函数**才能本地单测；`org.json` 在本地单测里是 stub，编解码只能真机验。
- **验收别截图**：看 `adb logcat -s TodoWidget:D`、`uiautomator dump` 的文本 bounds；交互用 `adb shell input tap`。
  ⚠ **以上为 2026-11-07 前的历史真机验收手法，现行默认不执行**（§6.4：装机允许，但不得操控手机 / dump UI / 截图）；仅在 zuige 单独授权真机交互验证时才适用。
- ★ 锁屏安装必失败 `INSTALL_FAILED_ABORTED`；查 `dumpsys window | grep mDreamingLockscreen`，UI 验收必须解锁。
- 精确闹钟：`USE_EXACT_ALARM` + `SCHEDULE_EXACT_ALARM` 都在 Manifest，13+ 后者默认拒绝时降级 `setAndAllowWhileIdle`；排查 `cmd appops get <pkg> android:schedule_exact_alarm`、`dumpsys alarm | grep <pkg>`。
- 恢复备份**绝不 `activity.recreate()`**（vivo 会露出黑色 windowBackground → 上下黑边），改走 `BackupManager.restoreCompleted` → `key(settingsEpoch)` 重建 Compose 子树。

---

## 8. 改本文件本身

规矩有变化才改，改完同步：README「文档」表的指针、`.workbuddy/memory/MEMORY.md` 的红线索引、当日工作日志；按具体路径提交。**不要把变更流水账写进本文件** —— 那是 `DESIGN.md` 的活。
