# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

TaskWidget is a Kotlin/Jetpack Compose Android app (`app/`) for exposing Obsidian Markdown tasks and notes as Android home-screen widgets. It uses SAF tree/document URIs instead of broad-storage permissions and writes task/note changes back to the selected documents.

- Package/application ID: `dev.local.taskwidget`
- SDK: compile/target 35; minimum 26; Java/Kotlin target 17
- Current app version is set in `app/app/build.gradle.kts`.
- The Gradle wrapper uses Tencent's mirror and repositories are configured with Aliyun mirrors. Preserve these mirrors because direct Google/Gradle access is unreliable in this environment.

## Commands

Run from the repository root (`G:\Project\taskforge`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"

# All JVM unit tests
.\app\gradlew.bat -p .\app testDebugUnitTest

# One test class or method
.\app\gradlew.bat -p .\app testDebugUnitTest --tests "dev.local.taskwidget.data.NoteRepositoryTest"
.\app\gradlew.bat -p .\app testDebugUnitTest --tests "dev.local.taskwidget.data.NoteRepositoryTest.renderContent_replacesAllSupportedTemplateVariables"

# APKs
.\app\gradlew.bat -p .\app assembleDebug
.\app\gradlew.bat -p .\app assembleRelease

# Static checks
.\app\gradlew.bat -p .\app lintDebug
```

APK outputs:

- Debug: `app/app/build/outputs/apk/debug/app-debug.apk`
- Signed release: `app/app/build/outputs/apk/release/app-release.apk`

The release build uses the project-local signing configuration in `app/app/build.gradle.kts`; retain the existing keystore so upgrades preserve the package signature. `lintDebug` currently fails on pre-existing API-29 `Theme.DeviceDefault.DayNight` references while `minSdk` is 26; do not treat that baseline failure as caused by unrelated feature work.

## Architecture

### Data and Markdown mutation

- `data/VaultRepository.kt` owns the configured Vault tree URI, incrementally scans all Vault Markdown files via `DocumentsContract`, caches parsed task data in `filesDir`, and rewrites source lines for complete/edit/delete operations. Task-facing UI and widgets should read `loadTasks()` rather than rescan on every render.
- `data/TaskParser.kt`, `NaturalDate.kt`, `Recurrence.kt`, and `QuickAdd.kt` contain the pure Markdown/task grammar and quick-add rendering behavior. Unit tests in `app/app/src/test/java/.../data/` cover this layer.
- `data/NoteRepository.kt` is deliberately separate from the task cache: it lists only direct-child `.md` files in a per-widget SAF folder, creates notes by rendering an optional template (`{{title}}`, `{{date}}`) as a preamble and appending the dialog's Markdown body, and resolves a note's Vault-relative path for Obsidian links.
- `data/ObsidianLink.kt` opens tasks/notes through `obsidian://`; notes fall back to `ACTION_VIEW` on their granted `content://` URI.

### UI and interactions

- `MainActivity.kt` is the Compose settings screen: Vault/inbox SAF permission selection, manual scanning, reminder configuration, and documentation.
- `TaskListActivity.kt` and `EditTaskActivity.kt` provide the full task browsing/editing views.
- `QuickAddActivity.kt` and `QuickAddNoteActivity.kt` are transparent Compose dialog Activities used by widgets/tiles. They must write only through the appropriate repository and refresh widgets after successful changes.
- `WidgetActionActivity.kt` and `NoteWidgetActionActivity.kt` are short-lived transparent Activity bridges for widget clicks. Keep background work in their process-level coroutine scopes: widget actions must survive the Activity finishing immediately.

### Widgets and background refresh

- `widget/TaskWidgetReceiver.kt` + `TaskWidgetService.kt` implement the scrollable task-list widget using classic `RemoteViews`/`ListView`; this is intentional for reliable HyperOS refresh behavior. The receiver owns header rendering and the service owns collection rows.
- `widget/NoteWidgetReceiver.kt` + `NoteWidgetService.kt` use the same classic RemoteViews collection pattern for per-instance notes folders. `NoteWidgetConfigStore.kt` persists a folder/template per `appWidgetId`.
- The four calendar/task widgets (`UpNext`, `DailyAgenda`, `MonthMini`, `MonthAgenda`) use Jetpack Glance and shared state/UI in `CalendarWidgetState.kt`, `CalendarData.kt`, and `CalendarWidgetUi.kt`.
- `widget/WidgetActions.kt::updateAllWidgets()` is the single cross-widget refresh path. Call it after global task/Vault changes; use `NoteWidgetReceiver.renderFolder()` after creating a note to avoid redrawing unrelated widgets.
- `work/RefreshWorker.kt` performs the 30-minute Vault scan and calls `updateAllWidgets()`. `RefreshReceiver`, `ReminderScheduler`, and `ReminderReceiver` reschedule/refresh around boot, clock changes, and notifications.

### Android declarations and resources

`app/app/src/main/AndroidManifest.xml` declares all Activities, RemoteViews services, receivers, widget metadata, quick settings tiles, and system refresh receivers. Add new widget components there alongside a provider XML resource in `res/xml/`; RemoteViews layouts must only use controls supported across process boundaries. For widget icons, use `widget/WidgetIcons.kt` to rasterize vectors before placing them in `RemoteViews`, which avoids HyperOS loading failures.

## Delivery workflow

- After completing code changes, automatically create a git commit for the task's related files.
- Automatically build the signed Release APK after committing and report the artifact path and build result.
- Do not include unrelated user changes in automatic commits.
