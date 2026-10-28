# Trainer calendar and clients: implementation handoff

## Status and scope

This is the first runnable scaffold in the repository. `initial-architecture.md` remains a draft. The implementation follows its KMP/Compose and MVVM/UDF direction without implementing authentication or a backend. None of G1–G15 is closed by this change.

The active demo context is a trainer and their synthetic client relationships. `Client.id` identifies a relationship inside the active trainer's scoped repository, not a globally accessible account. Production composition must provide an authenticated, role- and relationship-scoped implementation of `TrainerRepository`.

Implemented: day/week agenda, date picker, booking acceptance/rejection, a distinct reschedule proposal, session creation for an existing client, fee-free trainer cancellation, completion confirmation, one-time availability blocks, current trainees / archive tabs, search and an overdue filter, profile, session history, private client information and editable notes.

Invitations, plans, progress, chat/technique video, finance, no-show eligibility, fee quotes, recurring availability and external-calendar integration need other features or backend contracts. The demo identifies its unavailable integrations. Completion does not charge money or deduct package credits. No-show and fee-bearing actions are unavailable without an authoritative server decision.

## Shared foundations introduced

| Module | Responsibility |
| --- | --- |
| `shared` | Common domain, synthetic repository, ViewModel/StateFlow, Compose UI/resources; platform date formatting and Android back handler. |
| `androidApp` | Android launcher, edge-to-edge activity and manifest. |
| `desktopApp` | Review runner for the same shared UI, with an owned ViewModelStore. This is development tooling, not a product-platform commitment. |

`Theme.kt` centralizes semantic colors, typography, spacing and shapes from AGENTS.md. System sans is the shared fallback until IBM Plex Sans is bundled. Only the shared light theme is implemented. Feature content accepts state and callbacks. The shell owns the top bar, insets, snackbars, shared add menu and bottom navigation. The two implemented destinations do not finalize the team's full role-aware navigation.

Provisional build baseline: Kotlin 2.2.21, Compose Multiplatform 1.9.3, AGP 8.11.1, Gradle 8.13, JDK 17, Android minimum API 26 / compile and target API 35. Versions are centralized in `gradle/libs.versions.toml`. Material 3's datetime dependency is aligned with kotlinx-datetime 0.7.1 and `kotlin.time.Instant`. DI, persistence and networking dependencies are deferred until needed.

Compatibility references: [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [Compose 1.9.3 dependencies](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html), [datetime Instant migration](https://github.com/Kotlin/kotlinx-datetime#deprecation-of-instant).

## Routes

| Route | Entry / exit |
| --- | --- |
| `Main(Calendar)` | Calendar tab; session → details; add → editor; availability → block editor. |
| `Main(Clients)` | Trainees tab; current/archive sub-tabs; trainee → profile; search/filter state lives in ViewModel. |
| `SessionDetail(id)` | Calendar or profile; client → profile; propose time → editor; back → previous route. |
| `ClientDetail(id)` | Clients or session; next/history session → details; add → editor; notes → note editor. |
| `SessionEditor(sessionId?, clientId?)` | New session or proposal for an existing session. Successful demo save returns to the previous route. |
| `Availability` | Add/remove one-time blocks, retaining input on failure. |
| `NoteEditor(clientId)` | Profile; successful save returns to profile. Input is retained during errors and refresh. |
| `ClientInformationEditor(clientId)` | Profile; optional information editor with validation and retained input after failure. |

Routes form a typed local ViewModel back stack. Android system back and the shared back button use it. Deep links, navigation persistence across process death and the full app graph await integration. Forms use saved Compose state; repository data is explicitly in memory for the demo.

## Backend integration contract

`TrainerRepository` is the read/command boundary. Replace `DemoTrainerRepository` in the composition root when services exist; composables do not access networking or databases.

- Reads provide relationships scoped to the active trainer, sessions, blocks and private notes. The client list gets a boolean overdue flag, not balances.
- Sessions carry UTC start/end instants, an IANA zone, revision, status and a separate proposal with its author. A proposal does not overwrite the confirmed time. A trainer cannot accept their own proposal.
- Acceptance checks conflicts and expected revision. Rejection/withdrawal preserves the original session. The demo serializes commands and checks overlaps; production needs server transactions, trainer/client availability, database constraints, command idempotency and an event history.
- Trainer cancellation must remain fee-free for the client. Production must return authoritative refund/credit state; the UI cannot infer settlement.
- Real completion, no-show, fees and waivers need server capabilities/eligibility. Fee-bearing operations require fresh revision-bound quotes, expiry and consequence-specific confirmation.
- Bookings and similar commands cannot succeed offline. The demo shows “not sent” and retains input; it does not implement a durable scheduling queue.
- Authentication, authorization and private-note storage are absent. Hiding controls does not provide authorization. Do not connect this demo repository to real private records.
- Invitations, weekly availability rules, buffers, counterproposals for initial booking requests and package policies remain team decisions/contracts.

Dates/times use shared locale-aware formatters. “Today” is `DemoDate` (28 October 2026), identified in the separate preview-tools window and this documentation. It never determines fees or real policy. Nonexistent spring-DST times are rejected rather than shifted; production must also specify a policy for ambiguous autumn times.

## Design reference

Reviewed through Stitch MCP: [calendar](https://stitch.google.com/preview/10185753988111686080?node-id=12dc0ad4256a4530a3ff28afba59d6b0), [clients](https://stitch.google.com/preview/10185753988111686080?node-id=86546d3e37904d939096d90d2f8db6fd), client profile, request details, check-in and the neighboring trainer home.

Kept the shared light surfaces, orange accents, rounded components and hierarchy. Removed promotional content, heavy shadows, duplicate headers and the extra central Plan navigation action. Corrected the mockup's prematurely cancelled original time. At large font scales, weekdays scroll with wider targets, session times move above cards and the add action uses an accessible icon. Decorative subtitles are omitted to preserve room for essential content.

## Validation

19 behavior tests cover acceptance/rejection, reschedule conflicts and original preservation, self-acceptance, revisions, simultaneous conflicting creates, blocks, duplicate taps, offline failure, retained routes, invalid ranges, DST gaps, Polish search and error recovery.

Eight Compose UI tests exercise calendar → clients → profile, retained search, accepting a reschedule, offline note retention including refresh, loading/error/empty/access states, and a small viewport with 200% Polish text. Actual Compose renders are generated under `shared/build/reports/screenshots/`.

Verified commands:

```sh
./gradlew :shared:desktopTest :desktopApp:classes :androidApp:assembleDebug :androidApp:lintDebug
```

All 27 tests pass; desktop classes, Android debug APK and Android lint build successfully. Lint reports four non-blocking scaffold warnings: compile/target SDK below the latest API and missing launcher/backup metadata. No physical Android device or emulator was available for keyboard/system-bar testing. Native macOS automation was unavailable because the desktop was locked; visual QA used Compose test renders.

Preview images generated from the implementation:

- [Calendar](previews/trainer-calendar.png)
- [Clients](previews/trainer-clients.png)

## Trainer review changes

The ordinary app has no demo banner, scenario menu, developer notices in forms, or “demo” wording in actions. `:desktopApp:runPreview` opens a second window containing scenario controls and the in-memory/fixture-date explanation. Both entry points still use the synthetic in-memory repository; removing development copy does not add production integration or persistence.

The main headings are “Twój plan” and “Twoi podopieczni”; navigation uses “Podopieczni”. The current-trainee tab excludes archived relationships, and “Archiwum” shows only inactive relationships. Active-relationship boilerplate and negative overdue-payment labels are omitted; the underlying boolean contract is unchanged. Archive browsing does not grant access to private information from an ended relationship.

A single “Dodaj” action opens session creation or availability blocks. Proposals appear at their proposed start/end in both day and week agendas, with the original time in “Obecnie” metadata and the details screen. **This is a presentation change only:** the original confirmed slot remains reserved until acceptance. A trainer's own proposal stays unaccepted, awaiting the client.

Shared status tokens distinguish confirmed (green, calendar check), completed (slate, double check), a client's reschedule request (blue, update) and waiting for the client (purple, hourglass). New text/container pairs are `#244690` / `#EAF0FF`, `#603B8E` / `#F1EBFA` and `#3D5368` / `#E9EEF2`, centralized in the shared theme. Labels and icons accompany color.

The profile includes age, weight, height, goal, injuries and movement limitations. Unknown values show “Nie podano”; missing injury information never implies no injuries. Age is derived from a birth date, and numeric values use locale-aware formatting. All fields are optional. These fields are a private coaching-information projection, separate from an authoritative health questionnaire and measurement history.

`loadClientInformation` / `saveClientInformation` are separate relationship-scoped repository methods; health information is not part of the client-list response or public fixtures. The in-memory repository starts with unknown values and denies inactive/unknown relationships. Production must check health-data grants/consent, audit access, provide read/edit capabilities and establish data ownership; the prototype editor does not finalize those policies or overwrite a client's questionnaire. No real health records or persistence have been added.

Regression coverage includes archive filtering, proposed-date placement without releasing the original slot, trainer/client proposal badges, completed-state rendering, optional private information, scoped reads/writes, age and decimal validation, offline form retention, clean normal-app copy and the unified add menu.
