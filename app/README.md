# 任务小组件 (TaskWidget)

一个把 Obsidian vault 待办任务搬到安卓桌面的 App:主屏幕 widget 勾选完成、改动实时写回
markdown。这是对 [TaskForge](https://taskforge.md/) 的 clean-room 复刻——**复现了除计费
(RevenueCat)外的全部机制**,但用纯 Kotlin + Jetpack Glance 实现(原版是 Flutter + Rust)。

## 与 TaskForge 的对应

| TaskForge 机制 | 本项目 |
|---|---|
| 5 种 widget(任务清单 + 每日议程/月+议程/迷你月历/UpNext) | ✅ 全部 |
| 快速添加(widget/磁贴/分享/选词)+ 自然语言日期 | ✅ |
| 完整 Obsidian Tasks + Dataview + 循环任务 | ✅ |
| 快捷设置磁贴(快速添加/搜索) | ✅ |
| App 视图:列表 / 看板 Kanban / 日历 | ✅ |
| 本地通知提醒 + 精确闹钟 | ✅ 每日摘要 |
| 桌面角标未完成数 | ✅ |
| Material You 动态取色 | ✅ |
| RevenueCat 计费 / 账号 / 云分析 | ❌ 有意不做 |

## 功能

- 主屏幕 widget 显示 vault 中未完成任务(`- [ ]`),点复选框直接完成
- 完成时把 `[ ]` 改成 `[x]` 并追加 `✅ 完成日期`(兼容 Obsidian Tasks 插件格式)
- 解析 `📅 截止日期`、`🔺⏫🔼🔽⏬ 优先级`、`#标签`;按 过期 → 今天 → 日期 → 无日期 排序,过期标红
- **筛选(v1.1)**:每个 widget 实例独立配置——标题、日期范围(全部 / 今天+过期 / 7天内+过期)、
  是否显示无日期任务、#标签、文件路径。添加 widget 时弹出配置,点 widget 齿轮或长按 → 重新配置可修改;
  可添加多个 widget 各用不同筛选
- **编辑(v1.1)**:点任务文字打开编辑页,改内容 / 截止日期 / 优先级,保存写回 markdown;
  行内其他元数据(⏳ 🛫 ➕ 🔁 🆔 ⛔)原样保留
- **完整解析引擎(v1.2)**:
  - 全套 emoji 字段:📅 截止、⏳ 计划、🛫 开始、➕ 创建、✅ 完成、❌ 取消、🔁 循环、🆔 id、⛔ 依赖
  - **Dataview 内联格式**:`[due:: 2026-07-15]` `[priority:: high]` `[repeat:: every week]`
  - 任务状态:待办 `[ ]` / 完成 `[x]` / 取消 `[-]` / 进行中 `[/]`;子任务缩进层级
- **循环任务(v1.2)**:勾选循环任务时,按 `🔁 every day/week/month/year`、`every weekday`、
  `every monday`、`every N units`、`when done` 规则生成下一次实例(插入完成行上方),日期字段一起前移
- **快速添加(v1.2)**:选一个 `.md` 作收件箱,从 widget ➕ / 下拉磁贴 / 分享文本 / 选中文字快速加任务;
  支持自然语言日期(`交房租 明天`、`report next week`、`in 3 days`)
- **提醒(v1.2)**:每日早晨摘要通知(今天到期 + 已过期计数)、午夜自动刷新、开机/时区变化后重排
- 每 30 分钟自动扫描 vault;widget 右上角可手动刷新
- 通过系统文件夹授权(SAF)访问 vault,无需"所有文件访问"权限;不联网,数据不出手机
- Material You 动态取色(Android 12+)

## 已知限制

- 循环任务只处理 emoji 格式的日期字段(Dataview 格式循环仅识别不重排)
- 复杂 RRULE(如 "every 2nd monday")暂不支持,覆盖常见 "every ..." 语法
- 提醒为每日摘要,非逐任务精确到点提醒
- 用 SAF 文件夹授权(不申请"所有文件访问"),因此无法像原版那样实时监听文件改动;
  以"每 30 分钟 + 进入 App/widget 交互时"重扫作为等效同步
- 桌面角标依赖启动器支持(Nova/Apex/三星/Sony 等),不支持的启动器会忽略

## 构建

需要 JDK 17 和 Android SDK(`local.properties` 指向 SDK 路径):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleRelease
```

产物:`app/build/outputs/apk/release/app-release.apk`

签名文件在 `keystore/taskwidget.jks`(密码 `taskwidget`,仅本地个人使用)。
**保留这个 keystore**,以后重新构建才能覆盖安装升级。

## 安装使用

1. 把 APK 传到手机安装(需允许安装未知来源应用)
2. 打开 App → 选择 Vault 文件夹 → 授权
3. 长按主屏幕空白处 → 小部件 → 添加"任务清单"
