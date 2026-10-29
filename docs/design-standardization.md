# Shared visual baseline and branch integration

The trainer calendar/trainee design is the shared baseline. `:shared` owns `pl.trainer.ui.TrainerTheme`, `Layout`, `AppColors` and `SharedBottomBar`. Main’s `pl.trainerapp.core.designsystem` API remains a compatibility facade for its home, finance, chat, notifications and profile screens. Feature themes must consume this foundation instead of defining a separate Material palette, type scale or navigation bar.

- Strong brand `#A04100` for filled actions/readable brand text; `#FF6B00` for accents.
- Shared readable status pairs, including distinct green confirmed and slate completed sessions.
- White/subtle surfaces, fine outlines, 16/20/22/24/28 dp shared shapes; no card/navigation shadows. Floating controls use 2/3 dp elevation.
- 20 dp screen gutters, 24 dp section gaps; bold 20/26 and 17/22 titles; 16/24, 14/20, 12/16 body/supporting text. One system sans family currently matches the calendar/trainee views.
- Four navigation tabs and a central create control; full labels reflow into two rows at 150% text scaling.

The common build foundation uses Kotlin 2.2.21, Compose Multiplatform 1.9.3, AGP 8.11.1, Gradle 8.13, Java 17 and SDK 35, matching the validated calendar/trainee stack. The Android host keeps main’s application identity (`pl.trainerapp`) and existing screens. Feature module directories are included when present so both PRs can merge without replacing the app scaffold. SDK 36 targeting remains follow-up work; no store release is part of this task.

All current repositories remain explicit synthetic/local data sources. This styling/integration work does not add backend confirmation, fee policies, real payments or health-data persistence.

Main validation: `31` Android unit tests pass; `:androidApp:assembleDebug` and `:androidApp:lintDebug` pass. Native screenshot/keyboard/system-bar checks remain unverified.

Calendar/trainee PR validation: 28 shared behavior/UI tests and 31 Android tests pass; desktop compilation, Android assembly and lint pass. The embedded-host UI test verifies that the host can own navigation, request the create sheet, and hide its bar on detail/edit routes. Android uses main’s home/finance screens plus the calendar/trainee feature under a single launcher and bar.

## Workout integration

Workout-plan merges the calendar/trainee branch, which already contains main. Merge PR #1 before PR #2; histories are preserved and no branch is force-pushed. The workout design-system API remains compatible, but its palette, dimensions, Material theme and bottom bar delegate to `:shared`. The same system-sans family is used across both feature sets; bundled workout fonts remain available for a future app-wide typography decision. Existing plan/exercise reducers, SQLDelight persistence and media code are retained.

Android keeps main’s launcher and screens inside the workout NavHost. The common create menu adds new plan, plan library and exercise library after session/availability actions. Desktop uses the calendar/trainee shell with those same actions. Plan assignment remains an explicit integration point; having trainee profiles available does not implement the assignment API.

Workout dependency versions are aligned with the shared validated compiler/UI baseline; compatible serialization, navigation, Koin and SQLDelight dependencies are verified by the combined build. The original workout catalog used a different compiler/UI stack.

Combined validation: 140 tests pass (16 contract, 46 domain, 18 persistence, 28 calendar/trainee, 1 combined UI flow, 31 Android). Android assembly/lint and desktop compilation pass. The combined UI test creates a draft through the shared menu, verifies it in the persisted library and returns to the calendar; it also checks editor actions at 200% text. Screenshots: [editor](previews/integrated-plan-editor-pl.png), [large-text editor](previews/integrated-plan-editor-font200-pl.png), [plan library](previews/integrated-plan-library-pl.png). Native Android visual/keyboard/system-bar and video recording/playback checks remain unverified.

The JVM-only command (`-Ptrainer.ui=false :contract:jvmTest :shared:domain:jvmTest :shared:data:jvmTest`) also passes. The empty `shared/headless.gradle.kts` avoids configuring the parent UI module in that mode. The combined desktop preview compiles and starts successfully.
