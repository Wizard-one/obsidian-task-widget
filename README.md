# TaskWidget

TaskWidget 是一款离线优先的 Android 主屏幕小组件应用，用于查看和管理 Obsidian Vault 中的 Markdown 待办与笔记。它通过 Android 的 Storage Access Framework（SAF）访问用户明确授权的文件夹，不申请“所有文件访问”权限，也不收集或上传用户数据。

## 功能

- 在主屏幕显示未完成 Markdown 任务，并可直接勾选完成
- 提供任务清单、今日议程、近期任务、月历与月度议程等小组件
- 为每个小组件独立设置标题、日期范围、标签、文件路径和是否显示无日期任务
- 浏览指定文件夹中的 Markdown 笔记；支持基于模板快速创建笔记
- 支持任务的截止/开始/计划日期、优先级、标签、Dataview 内联字段和常用循环规则
- 提供应用内任务列表、看板、日历、快速添加、搜索和快捷设置磁贴
- 提供每日到期摘要、午夜刷新和定时扫描；支持 Material You 动态配色

## 使用方式

1. 安装 APK 后打开应用，选择并授权 Obsidian Vault 文件夹。
2. 如需快速添加任务，在设置中选择一个 Markdown 收件箱文件。
3. 长按 Android 主屏幕空白处，进入“小组件”，添加所需的小组件并完成实例配置。
4. 笔记小组件可单独选择目标文件夹和可选的 Markdown 模板。

应用直接修改已授权文件夹内的 Markdown 文件。建议先通过版本控制或备份机制保护自己的 Vault。

## 构建

需要 JDK 17 与 Android SDK，并在 `app/local.properties` 中配置 Android SDK 路径。

```powershell
.\app\gradlew.bat -p .\app testDebugUnitTest
.\app\gradlew.bat -p .\app assembleDebug
```

若要生成可发布的签名 APK，请在 `app/signing.properties` 创建仅供本机使用的配置（该文件已被 Git 忽略）：

```properties
storeFile=keystore/release.jks
storePassword=replace-with-your-store-password
keyAlias=replace-with-your-key-alias
keyPassword=replace-with-your-key-password
```

然后执行：

```powershell
.\app\gradlew.bat -p .\app assembleRelease
```

产物位于 `app/app/build/outputs/apk/`。未提供上述本地签名配置时，Release 构建不会嵌入任何签名凭据。

## 隐私

- 不需要网络权限，应用不向外部服务传输 Vault 内容。
- 文件访问仅限用户通过系统选择器明确授权的位置。
- 小组件配置和缓存仅保存在设备本地。

## 已知限制

- 文件夹授权模式无法实时监听所有外部改动，应用会在定时扫描及交互后更新内容。
- 循环任务覆盖常用 `every ...` 规则；复杂 RRULE 暂不支持。
- 桌面角标和部分小组件动画取决于设备启动器支持。
