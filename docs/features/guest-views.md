# Guest views: implementation handoff

Implements [docs/plans/guest-views.md](../plans/guest-views.md) on branch `guest`. Role: **guest** (not signed in). Module: `:shared:feature:guest` (`com.trainingapp.feature.guest`, Android + desktop).

## Views

| View | File | Route |
|---|---|---|
| Welcome (Powitanie) | `welcome/WelcomeScreen.kt` | `GuestWelcomeDestination` (first launch only) |
| Guest shell: Start / Trenerzy / Siłownie tabs + "Zaloguj się" | `GuestShell.kt` | `GuestHomeDestination` |
| Start, "Znajdź swojego trenera" | `home/GuestHomeScreen.kt` | tab |
| Trainers, with 5 filter sheets, sort, list/grid | `trainers/TrainersScreen.kt`, `trainers/TrainerFilterSheets.kt` | tab |
| Gyms list (new, D5) | `gyms/GymsScreen.kt` | tab |
| Trainer profile | `trainerprofile/TrainerProfileScreen.kt` | `TrainerProfileDestination(trainerId)` |
| Gym detail | `gymdetail/GymDetailScreen.kt` | `GymDetailDestination(gymId)` |
| Log in / register | `auth/AuthScreen.kt` | `AuthDestination(register, intent…)` |
| Login-required sheet | `component/GuestComponents.kt` (`LoginRequiredSheet`) | sheet, not a route |
| Client placeholder (D12) | `auth/ClientHomePlaceholder.kt` | `ClientHomeDestination(intent…)` |

Every screen is `Route` (Koin + navigation, in `GuestNavigation.kt`) plus `Screen(state, actions)` content that takes plain state and callbacks. Styling uses only `:shared:designsystem` (`TopLevelHeader`, `DetailHeader`, `CardRow`, `ActionRow`, `SearchField`, `AppFilterChip`, `SegmentedTabs`, `StatusChip`, `InlineMessage`, `EmptyState`, `ErrorState`, `BottomActionBar`, `AppBottomSheet`, the shared buttons). That is the same header, tile rows, chips, spacing and pill buttons as the trainer calendar, clients and plan screens. There is no screen-specific colour, font or shape.

## Shared changes (affect other views)

| Change | File | Compatibility |
|---|---|---|
| `SharedBottomBar(createLabel: String? = null, onCreate: (() -> Unit)? = null)`. Without `onCreate`: no FAB, no overhang, no centre gap. | `shared/.../pl/trainer/ui/SharedBottomBar.kt`, `designsystem/.../BottomNavigation.kt` | Existing callers unchanged |
| `Avatar(initials, large)`; `Avatar(client)` delegates to it. Exported as `InitialsAvatar`. | `shared/.../ui/Components.kt`, `designsystem/.../Avatar.kt` | Unchanged |
| `PasswordField` (show/hide toggle with state description, password keyboard) | `designsystem/.../Inputs.kt` | New API |
| `SearchField(onSearch = …)` for the keyboard search action | `designsystem/.../Inputs.kt` | Optional parameter |
| `Money(minor, currency)`, `LocaleFormats.formatMoney`, `LocaleFormats.formatDecimal` | `shared/domain/.../format/` | New API; `androidApp`'s `formatPln` can migrate later |
| **App start depends on the session:** Welcome (first launch), then the guest shell, or the role shell when signed in. `App(initialSession: AuthSession? = null, host = …)`. | `shared/app/.../App.kt` | **Behaviour change**: the app no longer opens on the trainer home. `TrainerIntegrationTest` passes `DemoAccounts.trainer`. |
| `WorkoutNavigationActions.onLogOut` (default `{}`) | `shared/app/.../TrainerShell.kt` | Unchanged for callers |
| `CurrentUser` reads the signed-in trainer from the session; falls back to the demo trainer id `demo-trainer` | `shared/app/.../AppModule.kt` | Same id as before |
| "Wyloguj się" on Android is wired: `TrainerProfileRoute(onLogOut)`, `TrainerShell(onLogOut)`, `MainActivity` | `androidApp/...` | New optional parameters |
| New module registered | `settings.gradle.kts`, `shared/app/build.gradle.kts` | No new third-party dependencies |

## Data contracts

- `GuestCatalogRepository` and `AuthRepository` (`model/Auth.kt`) are the integration boundary. Their implementations are **explicit demo sources**: `DemoGuestCatalogRepository` has 12 synthetic trainers and 6 synthetic gyms, and `DemoAuthRepository` holds sign-in in memory. Neither is a live integration.
- Public models (`model/GuestModels.kt`) are separate from `Client` and other private types. They carry no notes, health data, chat or relationship data; only aggregate public numbers are allowed.
- Filtering and sorting are a pure function (`data/CatalogSearch.kt`), so a server can take them over later.
- `GuestIntent.RequestSession(trainerId?, gymId?)` travels through Auth to the role shell. It never creates a booking request by itself.
- `isOpenAt(hours, zone, instant)` uses the gym's IANA zone and the injected `TimeSource`.
- Reused: `MediaState` from `:shared:domain`, `SearchText` from `:contract`, `NetworkMonitor`/`TimeSource` from the app.

## Decisions taken (team confirmation still needed)

The plan's defaults were applied. They are **not agreed team decisions** until confirmed:

| # | Applied |
|---|---|
| D1 | Ratings and review counts are read-only and nullable, hidden when null; the "Ocena" sort is included. |
| D2 | "Zweryfikowany" is a display-only boolean (no "PRO"). |
| D3 | Start keeps the professionals strip and one join panel with one CTA. |
| D4 | The quiz prompt opens the "Cel" filter sheet. |
| D5 | A gyms list was built for the Siłownie tab. |
| D6 | Demo distances come from fixtures; no location permission. Without distances the section is titled "Siłownie". |
| D7 | No map image: address plus a "Mapy Google" web link via `LocalUriHandler`. |
| D8 | Initials avatars for people and an icon tile for gyms; no photos. |
| D9 | The video player is passed in by the app (`videoPlayer` slot → `DemoVideoPlayer`). Demo media has no URL, so tiles show "Wideo niedostępne" or "Przetwarzanie". |
| D10 | The Google button is shown; the demo answers "Logowanie Google nie jest jeszcze dostępne". |
| D11 | Welcome shows once per install (`SharedPreferences` / `java.util.prefs`). |
| D12 | Trainer → existing trainer shell; client → placeholder with Wyloguj się. |
| D13 | The terms sheet has placeholder text; the consent version (`demo-2026-11`) is sent with registration. |
| D14 | Trainer registration → trainer shell with demo data. |
| D15 | No product name on Welcome. `app_name` is unchanged ("Aplikacja trenera"); the team still needs to pick a neutral one. |
| D16 | Copy reworded as listed in the plan. CTAs say "poprosić o termin". Resources mention no cancellation time limit; the policy text comes from each trainer's data. |
| D17 | Package validity is the trainer's display text; there is no logic. |
| D18 | Voice search was left out. |
| D19 | Forgot password was not built. |

Stitch parity: the functionality in each table of plan §4 was built as described, with the D-numbered exceptions above. Also removed, per plan §2: hero photos, glass/blur/gradients, carousels (replaced by vertical rows and a 2-column goal grid), the logo and placeholder brand, duplicated headers, tiny uppercase labels, and animations other than the welcome slides.

## Accessibility and adaptation

- Touch targets are at least 48 dp. Icon-only actions have labels; decorative icons have none.
- `heading()` on section titles. `selected` semantics on tabs, chips and the view toggle. Role tiles use `Role.RadioButton` in a `selectableGroup`; pager dots are labelled "Slajd n z 3".
- Status always has text or an icon as well as colour (open/closed, verified, recommended, media states).
- At font scale ≥ 1.5: the shared 2-row bottom bar, a forced list view with the grid toggle hidden, and goal tiles in 1 column.
- Welcome auto-advance stops on first touch, with animations off, or with TalkBack (Android).
- The password is held only in screen-local `remember`. `Registration` and `AuthInput` redact `toString()`.

## Validation

This sandbox cannot reach Google's Maven repository (`dl.google.com`, HTTP 403), so the real Gradle build cannot configure: there is no Android Gradle plugin or AndroidX. What was run instead:

- **Ran and passed, plain JVM:** 40 guest tests (catalogue filter/sort, opening hours with DST and foreign zones, `AuthValidator`, `AuthViewModel` with double tap, offline, failures, tab switching and roles, `GuestCatalogViewModel`, detail view models, media playability, fixture privacy, maps link) and 5 `LocaleFormats` money/decimal tests. All existing `:contract`, `:shared:domain` and `:shared:data` tests also pass (124 in total). The view models ran against a test-only `ViewModel` stand-in in a scratch harness, not the real AndroidX class.
- **Type-checked, not run:** `:shared`, `:shared:designsystem`, `:shared:feature:guest`, `:shared:app` (main and desktop tests) compile on desktop against the JetBrains Compose 1.9.3 foundation/UI/Material3 jars, with compose-runtime 1.8.2 and small stubs for the Google-only lifecycle/savedstate types.
- **Not run:** `GuestIntegrationTest` (Welcome → browse → trainer → gym → sheet → register → client; validation; offline; 320 × 720 at 200 %), screenshots, `androidMain` code, `androidApp` changes, Android lint and device checks (keyboard insets, share sheet, maps link, TalkBack). Run the README validation commands on a machine with normal network access. The UI test saves screenshots to `shared/app/build/reports/screenshots/guest-*.png` for comparison with `docs/previews/trainer-calendar.png`.

## Remaining integration gaps

- Catalogue backend and a public data view with server authorisation.
- Identity provider, Google sign-in and password reset.
- The booking request flow that consumes `GuestIntent`.
- The client shell.
- Real terms and privacy documents.
- Location.
- Public media hosting and share links.
- On desktop, the trainer shell has no logout entry; only Android's profile screen has one.
