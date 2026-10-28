# mobile-app-project

This project is about creating a gym app: an Android-first personal-training application for trainers, clients and guests.

Start with [CLAUDE.md](CLAUDE.md) (identical to `AGENTS.md`), [docs/context.md](docs/context.md) and the draft [architecture](docs/initial-architecture.md).

## Modules

| Module | Contents | Targets |
|---|---|---|
| `:contract` | DTOs and rules shared with the server: exercise and plan DTOs, enums, `PlanValidator`, `ExerciseValidator`, `SearchText`. | JVM (Android uses it too) |
| `:shared:domain` | Models, plan-draft reducers, set-input parsing, search, use cases, locale formats. | JVM |
| `:shared:data` | SQLDelight database (drafts, cached plan versions, own exercises), repositories, fake remotes. | JVM |
| `:shared:designsystem` | The one shared theme (colours, IBM Plex Sans, spacing, shapes) and shared components. | Android, desktop |
| `:shared:feature:workout-builder` | Plan library, plan editor, exercise library/picker, exercise detail, exercise form ([plan](docs/features/plan-creator.md)). | Android, desktop |
| `:shared:app` | App shell: theme, trainer navigation, navigation host, Koin wiring, bundled exercise seed. | Android, desktop |
| `:androidApp` | The Android application. | Android |
| `:desktopApp` | A phone-sized desktop preview for development. Android remains the product target. | Desktop JVM |
| `tools/exercise-seed` | Builds the bundled exercise library from `free-exercise-db` and `curation.tsv`. | Python 3 |

The domain, data and contract modules have no Android or Compose dependencies, so they also build on a plain JVM (for example on a backend machine).

## Build

Requirements: JDK 17 or newer. The Android and Compose modules also need the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`) and access to Google's Maven repository.

```bash
./gradlew :androidApp:installDebug
```

```bash
./gradlew :desktopApp:run
```

```bash
./gradlew check
```

Only the pure Kotlin modules (no Android SDK or Google Maven needed):

```bash
./gradlew -Ptrainer.ui=false check
```

Regenerate the bundled exercise library after editing `tools/exercise-seed/curation.tsv`:

```bash
curl -sSLo /tmp/exercises.json https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
```

```bash
python3 tools/exercise-seed/build_seed.py --dataset /tmp/exercises.json --images
```

`--images` also bundles one resized photo per exercise and needs Pillow (`pip install Pillow`).

## Status

- **Language:** the app is Polish only, whatever the device language. Exercise instructions from the dataset are English, so they are not shown until they are translated.
- **Design:** every screen follows the Stitch references. The plan library is under the orange "+ PLAN" button of the trainer navigation, and the plan creator (S2) copies the "Kreator planu" screen. See section 12 of the feature plan.

- **Built so far:** the trainer plan creator and exercise library, delivery slices 0–3 of [docs/features/plan-creator.md](docs/features/plan-creator.md), against local storage and fake remotes. There is no backend, sign-in or client list yet.
- **Verified:** `./gradlew -Ptrainer.ui=false check`, which runs the `:contract`, `:shared:domain` and `:shared:data` tests.
- **Not verified:** the Compose/Android modules have not been compiled or run yet. Google Maven was unreachable where they were written. The AGP version in `gradle/libs.versions.toml` is unconfirmed too.

## Third-party content

- Exercise data and photos: [free-exercise-db](https://github.com/yuhonas/free-exercise-db), public domain (Unlicense). The bundled photos are resized copies. Polish names and aliases in `tools/exercise-seed/curation.tsv` are ours.
- Font: IBM Plex Sans, SIL Open Font License 1.1 (`shared/designsystem/FONT-LICENSE-IBM-Plex.txt`).
