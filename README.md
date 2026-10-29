# Trainer app

Android-first Kotlin Multiplatform / Compose application. The Android app combines main’s trainer home, finance, chat, notifications and profile with the **calendar and trainee views**. The desktop runner previews the shared calendar/trainee UI.

## Run

Requirements: JDK 17. Android builds additionally require Android SDK Platform 35 and Build Tools 35.0.0. Configure the SDK in Android Studio or an untracked `local.properties` (`sdk.dir=/absolute/path/to/sdk`). The Gradle wrapper is included.

```sh
./gradlew :desktopApp:run
./gradlew :androidApp:assembleDebug
```

To open scenario controls in a **separate developer window**, use:

```sh
./gradlew :desktopApp:runPreview
```

The ordinary app has no preview banner or developer controls.

The debug APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Open the repository in Android Studio to run it on a device or emulator.

## Validate

```sh
./gradlew :shared:desktopTest :desktopApp:classes
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

Desktop tests render the shared Compose UI without requiring an unlocked desktop. Reports and screenshots are in `shared/build/reports/`. UI tests use Polish and cover a 390 × 844 viewport and a 320 × 720 viewport at 200% font scaling. UI resources also include English; the app follows the platform locale.

## Current scope

- Day/week calendar, date selection, session and reschedule requests.
- Session creation, trainer cancellation, completion and one-time availability blocks.
- Current trainees and archive, search, overdue-payment indicators, profile, private client information and trainer notes.
- Loading, empty, recoverable error, offline and permission-denied previews.
- Shared theme, shell, components, resources, state and repository boundaries.

**All people and sessions are synthetic. Changes are in memory only and reset on restart.** The app does not send invitations, book sessions, settle payments or store private notes on a server. Use `:desktopApp:runPreview` to switch sample states in a separate window. “Today” refers to the fixed fixture date, 28 October 2026.

See [implementation and integration notes](docs/trainer-ui.md), [project context](docs/context.md), and the [draft architecture](docs/initial-architecture.md).

The [design alignment report](docs/main-design-alignment.md) compares the current views with main, includes before/after previews, and provides a proposed palette/surface patch for main's existing screens.

The [shared design/integration decision](docs/design-standardization.md) records the common theme, navigation and build baseline. Android uses one `pl.trainerapp` launcher and one bottom bar; the calendar and trainee feature renders under that host.
