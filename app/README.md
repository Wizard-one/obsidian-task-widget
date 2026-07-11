# 任务小组件 (TaskWidget)

一个极简的安卓 App:只做一件事——把 Obsidian vault 里的待办任务放到主屏幕 widget 上,
直接勾选完成,改动实时写回 markdown 文件。灵感来自 [TaskForge](https://taskforge.md/) 的
widget 功能,但去掉了其余所有复杂功能。

## 功能

- 主屏幕 widget 显示 vault 中未完成任务(`- [ ]`),点复选框直接完成
- 完成时把 `[ ]` 改成 `[x]` 并追加 `✅ 完成日期`(兼容 Obsidian Tasks 插件格式)
- 解析 `📅 截止日期`、`🔺⏫🔼🔽⏬ 优先级`、`#标签`;按 过期 → 今天 → 日期 → 无日期 排序,过期标红
- **筛选(v1.1)**:每个 widget 实例独立配置——标题、日期范围(全部 / 今天+过期 / 7天内+过期)、
  是否显示无日期任务、#标签、文件路径。添加 widget 时弹出配置,点 widget 齿轮或长按 → 重新配置可修改;
  可添加多个 widget 各用不同筛选
- **编辑(v1.1)**:点任务文字打开编辑页,改内容 / 截止日期 / 优先级,保存写回 markdown;
  行内其他元数据(⏳ 🛫 ➕ 🔁 🆔 ⛔)原样保留
- 每 30 分钟自动扫描 vault;widget 右上角可手动刷新
- 通过系统文件夹授权(SAF)访问 vault,无需"所有文件访问"权限;无网络权限,数据不出手机

## 已知限制 (v1)

- 不支持 🔁 循环任务:勾选只标记完成,不会生成下一次任务
- 仅识别 emoji 格式的元数据,不支持 dataview 格式(`[due:: ...]`)
- `⏳ scheduled` / `🛫 start` 日期会从显示文本中剥离,但不参与排序

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
