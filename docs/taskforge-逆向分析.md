# TaskForge 逆向分析与克隆路线图

对 `TaskForge - Obsidian Tasks 2.7.2`(包名 `com.azhard.taskforge`,APKPure 版)做的静态逆向,
用作我们自己 clean-room 重写的功能蓝图。**方法:静态分析 APK 的 manifest、Flutter 资源、
以及 `libapp.so`(Dart AOT)/ `librust_lib_taskforge.so`(Rust 核心)中的字符串常量**;
不复制其专有代码(也无法复制——已 AOT 编译成原生指令),仅还原"它有哪些功能、如何组织"。

> 我们当前的实现(`app/`)是纯 Kotlin + Jetpack Glance,只覆盖了下表中"任务清单 widget"这一小块。

---

## 1. 技术栈

| 层 | 技术 | 证据 |
|---|---|---|
| UI | **Flutter (Dart)**,AOT 编译 | `assets/flutter_assets/`、`libapp.so` (18MB)、`libflutter.so` |
| 核心引擎 | **Rust**,经 flutter_rust_bridge 桥接 | `librust_lib_taskforge.so` (11MB)、`libtaskforge_platform_bindings.so`、`libdartjni.so`/`libjnidispatch.so` |
| 解析逻辑 | Obsidian Tasks / Dataview / TaskNotes / YAML frontmatter | Rust 库字符串:`recurrence`×108、`priority`×66、`frontmatter`×26、`Dataview`、`TaskNotes` |
| 日期解析 | Rust `chrono` + 自然语言 | `Chrono`、`today/tomorrow/yesterday/next week` |
| 计费 | **RevenueCat**(+ Amazon IAP 变体) | `RevenueCat`、`Paywall`、`Lifetime`、`Subscription`、`com.amazon.inapp.purchasing` |
| 后端/分析 | Firebase、Google Play Services、okhttp | 各 `*.properties`、`play-services-*` |
| 通知 | flutter_local_notifications + 精确闹钟 | `SCHEDULE_EXACT_ALARM`、`flutter_local_notifications_web` |

**关键结论**:业务核心(markdown 解析、任务模型、循环规则、同步)在 **Rust** 层,UI 在 **Flutter**。
Android 原生层(Kotlin/Java,在 dex 里)只负责 **widget、磁贴、分享接收、开机刷新** 这些平台胶水。

---

## 2. Android 平台层(从 Manifest 还原)

### Widget(共 5 种,与官网一致)
- `TaskForgeWidgetProvider` — 任务清单 widget(+ `WidgetRemoteViewsService` 列表、`WidgetConfigurationActivity` 配置、`WidgetChooseListActivity` 选列表)
- `calendar_widget.DailyAgendaWidgetProvider` — 每日议程
- `calendar_widget.MonthAgendaWidgetProvider` — 月+议程
- `calendar_widget.MonthMiniWidgetProvider` — 迷你月历
- `calendar_widget.UpNextWidgetProvider` — 接下来
- 配套:`AgendaRemoteViewsService`、`CalendarWidgetClickActivity`、`CalendarWidgetRefreshReceiver`、自定义 action `ACTION_CALENDAR_DAY_SELECT` / `ACTION_CALENDAR_MONTH_NAV`

### 快捷设置磁贴(下拉通知栏)
- `QuickAddTileService` — 快速添加任务
- `SearchTileService` — 搜索
- `CalendarTileService` — 日历

### 快捷入口 Activity
- `QuickAddActivity` — 快速添加
- `VoiceTaskActivity` — 语音建任务
- `ShareReceiverActivity` — 从其他 app 分享文本 → 任务(`SEND`/`SENDTO`)
- `PROCESS_TEXT` intent — 在任意 app 选中文字 → 加为任务

### 后台刷新
- `BootReceiver`(开机)、`MorningRefreshReceiver`(早晨)、`ACTION_MIDNIGHT_REFRESH`(午夜)、`TIMEZONE_CHANGED`/`TIME_SET` — 保证日期相关视图/widget 跨天刷新

### 权限画像
- `MANAGE_EXTERNAL_STORAGE` — **全盘文件访问**(直接读写 vault,不走 SAF)
- `READ_CALENDAR` / `WRITE_CALENDAR` — 系统日历双向
- `POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`、`VIBRATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE` — 提醒
- `INTERNET`、`ACCESS_NETWORK_STATE` — RevenueCat/Firebase
- `com.anddoes.launcher.UPDATE_COUNT` — 桌面角标未完成数
- `BIND_QUICK_SETTINGS_TILE`、`RECEIVE_BOOT_COMPLETED`

### Deep link / URL scheme
`taskforge://`(如 `taskforge:///?search=true&q=meeting`)、`obsidian://`(跳回 Obsidian)、`https`、
`mailto`/`tel`/`smsto`/`geo`/`google.navigation`(任务内联动作)、`rc-*`(RevenueCat 回调)

---

## 3. 核心任务引擎(Obsidian Tasks 兼容,Rust 层)

- **完整 emoji 字段**:📅 截止、⏳ 计划、🛫 开始、➕ 创建、✅ 完成、❌ 取消、🔁 循环、🔺⏫🔼🔽⏬ 优先级、🆔 id、⛔ 依赖(dependsOn/blockedBy)
- **循环任务**:RRULE、`every day/week/month/weekday/Sunday...`、`when done`(完成后生成下一次)
- **多种格式并存**:
  - Obsidian Tasks emoji 格式
  - **Dataview** 内联字段(`[due:: 2026-07-11]`)
  - **YAML frontmatter**(文件属性)
  - **TaskNotes** 插件格式(`status`、`contexts`、`projects`、`timeEstimate`)
- **子任务**(subtask 层级)、标签 `#tag`、`[[wikilink]]`、按标题(heading)归属
- **全局过滤器 / 查询**(global filter / query)
- **自然语言日期**:输入 "tomorrow"、"next week" 自动转日期

---

## 4. App 内视图与交互(Flutter 层)

- **视图**:列表 List、**看板 Kanban/Board**、**日历**(日/周/月/议程 Agenda 四视图)
- **筛选排序**:Filter by / Sort by / Group by、Overdue、隐藏已完成、not done
- **搜索**
- **主题**:深/浅色、**Material You 动态取色**、强调色、字号、密度(紧凑模式)
- **快速添加**:含自然语言解析

## 5. 通知与提醒
本地通知、提醒、贪睡(Snooze)、今日到期、早晨摘要、精确闹钟、跨时区/跨天刷新

## 6. 同步与存储
本地 vault 文件夹(全盘权限直读写)、iCloud(Apple 端)、文件监听(file watcher)、冲突处理(conflict)、双向同步

## 7. 集成与自动化
Deep link/URL scheme、Quick Add、语音、分享面板、PROCESS_TEXT 选词加任务、Tasker、快捷指令(Shortcut)、快捷设置磁贴

## 8. 商业化
RevenueCat 计费、终身买断(官网 $49.99)、订阅/试用、Paywall、恢复购买

---

## 9. 克隆路线图(基于我们现有 Kotlin/Glance 项目)

> 现实评估:完整对标 = Flutter app + Rust 引擎 + 5 种 widget + 日历/看板 + 同步 + 计费,是数月工程。
> 但我们**不需要**照搬其 Flutter+Rust 架构——对"widget 优先 + 够用的 app"目标,纯 Kotlin 更轻。
> 下面按投入产出排序,分阶段推进。

### 图例:✅ 已完成 · 🟡 部分 · ⬜ 未做

**解析引擎(纯 Kotlin,可持续演进,最高复用价值)**
- ✅ emoji 基础:📅 截止、优先级、#标签、✅ 完成写回
- 🟡 其余 emoji 字段(⏳🛫➕❌🆔⛔):编辑时保留,但未参与显示/排序/筛选
- ⬜ 🔁 循环任务(完成后生成下一次)——**建议下一个做**,用户高频需求
- ⬜ Dataview 内联字段格式
- ⬜ YAML frontmatter / TaskNotes 格式
- ⬜ 自然语言日期解析(可引入轻量 Kotlin 库或自写)
- ⬜ 子任务层级、[[wikilink]]

**Widget(我们的主战场)**
- ✅ 任务清单 widget:筛选(日期/标签/路径)+ 勾选完成 + 点击编辑
- ⬜ 快速添加 widget(➕ 按钮 → 写入指定 inbox 文件)——**建议下一个做**,成本低收益高
- ⬜ 日历类 widget(每日议程 / 迷你月历 / UpNext)

**App 内(需要一个真正的列表/详情 UI,目前只有配置页)**
- 🟡 任务编辑页(已有:文本/日期/优先级)
- ⬜ 任务列表主界面(全库浏览、搜索、分组排序)
- ⬜ 看板视图、日历视图
- ⬜ 主题(深浅色已随系统;可加 Material You 动态取色、强调色)

**平台集成**
- ⬜ 快捷设置磁贴(Quick Add / Search)
- ⬜ 分享接收 + PROCESS_TEXT(选词加任务)
- ⬜ 本地通知/提醒(到期提醒、精确闹钟)
- ⬜ 桌面角标未完成数

**存储/同步**
- ✅ 本地 vault 文件夹(SAF 授权,比 TaskForge 的全盘权限更克制)
- 🟡 增量扫描 + 缓存;⬜ 文件监听(实时)、冲突处理

**明确不做**(与"简化版"初衷相悖):计费/Paywall、Firebase 分析、账号体系。

### 建议的近期三步
1. **🔁 循环任务**:勾选循环任务时,按 RRULE/"every ..." 生成下一次并写回(纯解析器工作,已有测试框架)
2. **快速添加 widget**:一个 ➕ widget,点开输入(含自然语言日期)→ 追加到用户指定的 inbox.md
3. **到期提醒通知**:用 WorkManager/AlarmManager,对当天到期任务发本地通知
