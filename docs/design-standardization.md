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
