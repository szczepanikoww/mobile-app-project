# Trainer app

Android-first Kotlin Multiplatform / Compose application. The Android app combines main’s trainer home, finance, chat, notifications and profile with the **calendar and trainee views**. The desktop runner previews the shared calendar/trainee UI, plan builder and exercise library.

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
./gradlew :shared:desktopTest :shared:app:desktopTest :contract:jvmTest :shared:domain:jvmTest :shared:data:jvmTest :desktopApp:classes
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

Desktop tests render the shared Compose UI without requiring an unlocked desktop. Reports and screenshots are in `shared/build/reports/`. UI tests use Polish and cover a 390 × 844 viewport and a 320 × 720 viewport at 200% font scaling. UI resources also include English; the app follows the platform locale.

## Current scope

- Day/week calendar, date selection, session and reschedule requests.
- Session creation, trainer cancellation, completion and one-time availability blocks.
- Current trainees and archive, search, overdue-payment indicators, profile, private client information and trainer notes.
- Loading, empty, recoverable error, offline and permission-denied previews.
- Plan library/editor, exercise library and technique/demo-video attachment, using the existing local repositories.
- Shared theme, shell, components, resources, state and repository boundaries.

**All people and sessions are synthetic. Calendar/trainee changes are in memory and reset on restart; plans/exercises use the workout feature’s local SQLite store.** The app does not send invitations, book sessions, settle payments or store private notes on a server. Use `:desktopApp:runPreview` to switch sample states in a separate window. “Today” refers to the fixed fixture date, 28 October 2026.

See [implementation and integration notes](docs/trainer-ui.md), [project context](docs/context.md), and the [draft architecture](docs/initial-architecture.md).

The [design alignment report](docs/main-design-alignment.md) compares the current views with main, includes before/after previews, and provides a proposed palette/surface patch for main's existing screens.

The [shared design/integration decision](docs/design-standardization.md) records the common theme, navigation and build baseline. Android uses one `pl.trainerapp` launcher and one bottom bar; the calendar and trainee feature renders under that host.

PR merge order: merge calendar/trainee PR #1 before workout PR #2. Workout-plan contains the calendar integration so the combined Android app has one host; its diff against main shrinks when #1 is merged. Both branches preserve main’s existing home, finance, chat, notification and profile screens. The Android `TrainingApplication` still initializes the workout repository/SQLite driver; video FileProvider and network permissions are retained.

## Modules

| Module | Contents | Targets |
|---|---|---|
| `:shared` | Canonical trainer theme/navigation and calendar/trainee feature. | Android, desktop |
| `:contract` | DTOs and rules shared with the server: exercise and plan DTOs, enums, `PlanValidator`, `ExerciseValidator`, `SearchText`. | JVM (Android uses it too) |
| `:shared:domain` | Models, plan-draft reducers, set-input parsing, search, use cases, locale formats. | JVM |
| `:shared:data` | SQLDelight database (drafts, cached plan versions, own exercises), repositories, fake remotes. | JVM |
| `:shared:designsystem` | Workout component API; colours, Material theme, spacing, shapes and navigation delegate to `:shared`. | Android, desktop |
| `:shared:feature:workout-builder` | Plan library, plan editor, exercise library/picker, exercise detail, exercise form ([plan](docs/features/plan-creator.md)). | Android, desktop |
| `:shared:app` | App shell: theme, trainer navigation, navigation host, Koin wiring, bundled exercise seed. | Android, desktop |
| `:androidApp` | The Android application. | Android |
| `:desktopApp` | A phone-sized desktop preview for development. Android remains the product target. | Desktop JVM |
| `tools/exercise-seed` | Builds the bundled exercise library from `free-exercise-db` and `curation.tsv`. | Python 3 |

The domain, data and contract modules have no Android or Compose dependencies, so they also build on a plain JVM (for example on a backend machine).


Pure Kotlin validation can still run without an Android SDK:

```sh
./gradlew -Ptrainer.ui=false :contract:jvmTest :shared:domain:jvmTest :shared:data:jvmTest
```

The calendar/trainee resources support Polish and English; the workout feature retains its existing Polish UI and hides untranslated dataset instructions. The exercise seed can be regenerated with `tools/exercise-seed/build_seed.py`; its `--images` option requires Pillow.

## Third-party content

- Exercise data and photos: [free-exercise-db](https://github.com/yuhonas/free-exercise-db), public domain (Unlicense). The bundled photos are resized copies. Polish names and aliases in `tools/exercise-seed/curation.tsv` are ours.
- Font: IBM Plex Sans, SIL Open Font License 1.1 (`shared/designsystem/FONT-LICENSE-IBM-Plex.txt`).
