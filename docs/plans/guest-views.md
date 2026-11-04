# Plan: Guest views (welcome, start, trainers, trainer profile, gyms, log in / register)

Status: plan only, nothing implemented · 2026-11-04 · branch `guest`

**The rule for this task:** *what a screen does* comes from Stitch; *how it looks and behaves as a component* comes from the implemented trainer views (calendar, clients, session details, new session, new plan).

Sources: `CLAUDE.md` §2–§9; `docs/initial-architecture.md` §2 (G1, G2, G6, G7, G10, G12, G13, G15) and the catalogue, privacy and auth rows; `docs/trainer-ui.md`; `docs/design-standardization.md`; the Stitch HTML of the screens in §1; the code under `shared/`, `shared/designsystem/`, `shared/app/` and `androidApp/`.

---

## 1. Scope

**Role:** guest (not signed in). Guests browse public data only; booking requests, workout logging, private messages and account management need an account (`CLAUDE.md` §2, G6).

| Requested view | Stitch screen | Screen ID | Notes |
|---|---|---|---|
| Greeting | Powitanie | `25cafb1d…` | `787977db…` ("Kinetic Apex Fitness Platform") is an older copy of the same screen. |
| Main screen / find your trainer | Start – gość | `c9e6f94e…` | Treated as **one screen**: "Znajdź swojego trenera" is the heading of Start. There is no separate Stitch screen with that name. |
| Trainers | Trenerzy – gość | `b0f68c67…` | |
| Trainer's profile | Profil trenera – gość | `f2fb15ee…` | |
| Gyms | Siłownia – gość | `71971511…` | Stitch only has a gym **detail** screen, so the list behind the "Siłownie" tab is new (D5). |
| Log in and register | Logowanie / Rejestracja | `c2eb8095…` | One screen with two tabs. |
| *(added: needed by the flow)* | Wymagane logowanie | `37ff489c…` | Bottom sheet shown when a guest tries a protected action. |

**Out of scope:** the booking request flow after sign-in, the client shell (Start – klient and others), a real identity provider, a real catalogue backend, real payments and a trainer's public-profile editor. These stay as integration points (§6, §13).

### Where "same functionality as Stitch" conflicts with the project rules

`CLAUDE.md` §2 says not to add ratings, verification systems, promotional sections or nutrition *merely because a mockup contains them*, and `docs/context.md` says Stitch is not 1:1. This task explicitly asks for Stitch functionality, so the plan **keeps the functionality by default** and lists each conflict in §10 (D1–D3, D16). These need a team decision before they count as agreed scope.

## 2. Translating Stitch into the trainer style

Every Stitch control maps to a component that already exists. Use these rules on all guest screens instead of copying the mockup's styling.

| Stitch | Guest implementation | Reference in trainer views |
|---|---|---|
| `#FF6B00` buttons, chips and prices with white or orange small text | `brandStrong` filled controls with white text; prices in `brandStrong` | `PrimaryButton`, calendar selected tab |
| Glass, blur, glow, gradients, quarter-circle arcs, coloured shadows | **Removed**: flat white surfaces with a 1 dp `outline` | `docs/main-design-alignment.md` |
| Photo cards (trainers, gyms, goals), hero backgrounds, photo overlays | Initials avatar for people, icon tile for gyms and goals (D8) | Client rows, client profile header |
| Horizontal photo carousels | Vertical `CardRow` lists with "See all"; goals as a 2-column grid | Clients list |
| Pill inputs with a leading icon on a grey fill | Outlined 16 dp fields with a persistent label | New session form, `AppTextField`, `SearchField` |
| In-page back/share bar under the header, duplicated titles | One `DetailHeader` with back, title and share action | Session and client details |
| "K" logo, placeholder brand names and the header's person icon | **Removed** (brand is not final, D15; the icon does nothing) | Calendar header |
| Tiny uppercase labels (10 sp) | Sentence case, minimum 12/16 sp | Shared type scale |
| Mixed section heading sizes with decorative icons | `SectionHeader` (20/26 bold), no icon | "Plan dnia", "Informacje" |
| Bottom bar visible on detail screens | Hidden on detail routes; the screen CTA sits in `BottomActionBar` | Trainer detail routes |
| Pulsing dots, `active:scale`, auto-animated elements | Removed; only the welcome slides move, and they respect reduced motion | Shared motion rule |
| Hard-coded data that disagrees between screens (one trainer costs 120 zł and 180 zł, rated 4.9 and 4.96) | One fixture source used by every screen | Demo repositories |

**Layout tokens:** 20 dp gutter, 24 dp between sections, 8–12 dp between related items, 16 dp card padding, `tile` (22 dp) for rows, `frame` (28 dp) for prominent panels, pill buttons at least 52 dp high, 48 dp touch targets, `widthIn(max = Layout.contentWidth)`.

**Component source:** `:shared:designsystem` (`com.trainingapp.designsystem`). It is already styled to match the calendar and takes its tokens from `pl.trainer.ui`. Do **not** use `pl.trainerapp.core.designsystem`, which is an Android-only compatibility facade and cannot be used from common code. Use `TopLevelHeader` and `DetailHeader`, not `AppTopBar`: the first two have the calendar's white header.

## 3. Navigation

```
First launch ─► Welcome ─┬─ Załóż konto ──────────► Auth (register tab) ─┐
                         ├─ Zaloguj się ──────────► Auth (login tab) ────┤
                         └─ Przeglądaj bez logowania ─┐                  │
                                                      ▼                  │
Guest shell:  [ Start ]  [ Trenerzy ]  [ Siłownie ]  [ Zaloguj się ]─────┤
                 │            │             │                            │
                 ├────────────┤             │                            │
                 ▼            ▼             ▼                            │
            Trainer profile ◄──────────► Gym detail                      │
                 │                          │                            │
                 └── protected action ──────┘                            │
                          ▼                                              │
                 Login-required sheet ─ Załóż konto / Zaloguj się ───────┤
                          └─ Nie teraz: stay on the same screen          ▼
                                                     success ─► role shell
                                                     (back never returns to guest)
```

- **Bottom bar (4 items, no create button):** Start (`Explore`), Trenerzy (`FitnessCenter`), Siłownie (`LocationOn`), Zaloguj się (`AccountCircle`), all outlined. The last item **pushes** Auth; it is never a selected tab. In Stitch, Auth is a stack screen without the bar.
- **Routes:** typed `@Serializable` destinations in a `guestGraph(...)` added to the `NavHost` in `shared/app/.../App.kt`, following `workoutBuilderGraph`. The tab state lives in `GuestShell`, as `TrainerShell` does for trainer tabs.
  - `GuestWelcomeDestination`
  - `GuestHomeDestination`
  - `TrainerProfileDestination(trainerId)`
  - `GymDetailDestination(gymId)`
  - `AuthDestination(mode, pendingIntent?)`
- **Login-required** is a sheet, not a route, so the screen behind it stays in place.
- **Tab state:** switching tabs keeps scroll position, search and filters.

## 4. Screens

Polish copy is shown for reference; all text goes into resources (§7.5).

### 4.1 Welcome (Powitanie)

**Purpose:** explain the app in three short steps and offer three ways forward.

```
│                                       │
│            (icon in soft circle)      │
│   Znajdź trenera i trenuj z planem    │  screen title 26/32
│   Rezerwuj sesje, loguj treningi…     │  body, secondary text
│               ━━  •  •                │
│                                       │
│ [          Załóż konto →            ] │  primary
│ [           Zaloguj się             ] │  outlined
│        Przeglądaj bez logowania       │  text button
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| 3 slides, auto-advance every 4.5 s | `HorizontalPager`; swipe supported. Auto-advance stops on first touch, with reduced motion and when a screen reader is active. | Foundation pager |
| Tappable page dots | Dots with 48 dp targets; label "Slajd 1 z 3"; never only colour (the current dot is also wider) | New small `PagerDots`, local to the screen |
| Załóż konto | → Auth (register) | `PrimaryButton` with `ArrowForward` |
| Zaloguj się | → Auth (login) | `TonalButton` |
| Przeglądaj bez logowania | → Guest shell; removes Welcome from the back stack | `AppTextButton` |

**Removed:** hero photo, "Nowa Jakość Treningu" badge, top bar with back and person icon (it is the first screen). Copy changes: D16.

**States:** static screen. At 200 % text the content scrolls and the actions stay reachable.

### 4.2 Start / find your trainer (Start – gość)

**Purpose:** get a guest to a trainer quickly.

```
┌ TopLevelHeader: Start ────────────── [Zaloguj się] ┐
│ Znajdź swojego trenera                  26/32 bold │
│ [🔍 Szukaj trenera, siłowni lub celu…         ][⚙] │
│ ┌ Trenuj z profesjonalistami  ──────────────── > ┐ │ ActionRow
│                                                    │
│ Polecani trenerzy              Zobacz wszystkich > │
│ ┌ (MK) Michał K.                               > ┐ │ TrainerRow (§5)
│ │      Siła · Kettlebell   ★ 4,9   od 120 zł/60  │ │ × 3
│                                                    │
│ Czego szukasz?                                     │
│ ┌ 🔥 Redukcja ┐ ┌ 🏋 Siła ─────┐                  │ 2 × 2 tiles
│ ┌ 🧘 Mobilność┐ ┌ 🏆 Zawody ───┐                  │
│                                                    │
│ Siłownie w pobliżu                    Wszystkie >  │
│ ┌ [icon] Studio Centrum · 1,2 km               > ┐ │ GymRow × 3
│                                                    │
│ ┌ Załóż konto, aby poprosić o pierwszą sesję ────┐ │ frame panel
│ │ ✓ Płatności  ✓ Czat  ✓ Postępy                 │ │
│ │ [ Załóż konto → ]                              │ │
└─ Start · Trenerzy · Siłownie · Zaloguj się ────────┘
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Zaloguj się (header) | → Auth (login) | `AppTextButton` in `TopLevelHeader.actions` |
| Search by trainer, gym or goal | IME Search → Trenerzy tab with the query applied, Polish-insensitive via `searchKey()` | `SearchField` |
| Filter icon | → Trenerzy tab, opens the filter sheet | `IconButton` with a label |
| "Trenuj z profesjonalistami" strip | → Trenerzy tab | `ActionRow` |
| Polecani trenerzy + Zobacz wszystkich | Row → Trainer profile; link → Trenerzy tab. The order comes from the repository, never computed in the UI. | `SectionHeader`, `TrainerRow` |
| Czego szukasz? (4 goals) | Tile → Trenerzy tab with that goal filter set; subtitles kept ("Masa i progresja") | New `GoalTile` |
| Siłownie w pobliżu + Wszystkie | Row → Gym detail; link → Siłownie tab; distance per D6 | `SectionHeader`, `GymRow` |
| Join CTA with benefits | Załóż konto → Auth (register) | `frame` panel, `PrimaryButton` |

**States:** spinner while loading (as the calendar does); `ErrorState` with retry; offline banner over the last loaded data; a section with no items is hidden; a completely empty catalogue uses `EmptyState` and keeps the join CTA.

### 4.3 Trainers (Trenerzy – gość)

**Purpose:** search, narrow down and open a trainer. Same structure as the Clients screen.

```
┌ TopLevelHeader: Trenerzy ──────────────────────────┐
│ [🔍 Szukaj trenera, celu, siłowni…                ]│
│ (Cel ▾)(Siłownia ▾)(Cena ▾)(Dzielnica ▾)(Dostępność ▾) → scroll
│ Wyniki: 12                 Wyczyść filtry          │
│ ⇅ Cena: rosnąco ▾                       [≣] [▦]   │
│ ┌ (MK) Michał Kowalski      ★ 4,9 · 42 opinie  > ┐ │
│ │      📍 Studio Centrum                         │ │
│ │      [Siła] [Trójbój] [Hipertrofia]            │ │
│ │      od 120 zł / 60 min                        │ │
│ …                                                  │
│ ┌ Nie wiesz kogo wybrać? ─────────────────────── > ┐
└─ bottom bar ───────────────────────────────────────┘
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Search with a clear action | Debounced 300 ms; matches name, gym, goal and specialisation | `SearchField` |
| 5 filter chips (Cel, Siłownia, Cena, Dzielnica, Dostępność) | Chip opens a sheet; an active chip shows its value and the soft accent. Input is kept until Zastosuj. | `AppFilterChip(showChevron = true)`, `AppBottomSheet` |
| Filter values | Cel, Dzielnica: multi-select. Siłownia: single with search. Cena: fixed ranges. Dostępność: rano / popołudnie / wieczór / weekend, declared by the trainer, not live free slots. | Sheet content |
| Clear filters (Stitch's `tune` icon is labelled "Wyczyść filtry") | Visible only with active filters; resets filters and query | `AppTextButton` |
| Result count ("38 dostępnych") | **Derived** from the results, using plurals | Text as `results_count` in Clients |
| Sort ("Cena: rosnąco") | Cena rosnąco (default) / Cena malejąco / Ocena | `DropdownMenu`, as the client picker in New session |
| List / grid toggle | Choice is remembered in the tab. Grid is 2 columns of a compact card; at font scale ≥ 1.5 the list is forced and the toggle hidden. | `SegmentedTabs` or icon toggle with `selected` semantics |
| Trainer row | Avatar, name, rating and count, main gym, up to 3 tags, "od X zł / 60 min" → Trainer profile | `TrainerRow` |
| Quiz prompt | See D4 | `ActionRow` |
| Voice search (`mic`) | Not wired in Stitch; see D18 | |

**States:** loading; populated; `EmptyState` "Brak wyników" with Wyczyść filtry; error with retry; offline banner. Long Polish names wrap to 2 lines and tags wrap with `FlowRow`.

### 4.4 Trainer profile (Profil trenera – gość)

**Purpose:** let a guest decide whether to ask this trainer for a session. One dominant action.

```
┌ ← Profil trenera ───────────────────────── [share] ┐
│ (MK 64dp)  Michał Kowalski                         │
│            Trener personalny                       │
│            [★ 4,9 (42)] [✓ Zweryfikowany]          │
│ 🕘 8 lat doświadczenia   👥 140+ podopiecznych      │
│                                                    │
│ O moim podejściu                                   │
│ Specjalizuję się w …                               │
│                                                    │
│ Specjalizacje                                      │
│ [Trójbój] [Hipertrofia] [Korekta postawy] …        │
│                                                    │
│ Gdzie trenuję                        2 lokalizacje │
│ ┌ Studio Centrum  [Główna]                       > ┐
│ │ ul. Przykładowa 1 · Pon–Pt 06:00–21:00         │ │
│                                                    │
│ Jak pracuję                                        │
│ ┌ ▶ Technika przysiadu · Biomechanika bioder     ┐ │
│                                                    │
│ Cennik sesji                                       │
│ ┌ Trening personalny · 60 min            120 zł  ┐ │ frame panel
│ │ Trening rozszerzony · 90 min           170 zł  │ │
│ │ Pakiet 8 sesji [Polecany]              880 zł  │ │ soft accent row
│ │ Pakiet 12 sesji                      1 200 zł  │ │
│                                                    │
│ ⓘ Zasady odwołań: bezpłatnie do 24 h …             │
│    Szczegóły polityki >                            │
├────────────────────────────────────────────────────┤
│ [ 🔒 Zaloguj się, aby poprosić o termin ]          │ BottomActionBar
│ Konto pozwala sprawdzić wolne terminy              │
└────────────────────────────────────────────────────┘
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Back and share | Share opens the system share sheet with name and text (no public URL exists yet); desktop copies to the clipboard | `DetailHeader`, new `rememberShareAction()` (§6) |
| Identity, rating, verified, experience, client count | Read-only public fields; rating and verified per D1/D2. Hidden when null. | `Avatar`, `StatusChip`, `MetaLine` |
| Bio | Plain text, no truncation | `SectionHeader` + body |
| Specialisations | Wrapping neutral chips; not clickable, as in Stitch | `StatusChip(tone = Neutral)` in `FlowRow` |
| Gdzie trenuję, count, "Główna" badge, hours | Row → Gym detail | `CardRow` |
| Jak pracuję (videos) | Only `READY` media is playable; tap opens a player; horizontal scrolling list of tiles with title and subtitle (D9) | `MediaTile` |
| Price list with services and packages, "Polecany" highlight | Money in minor units + currency via a shared formatter. The validity text is shown as the trainer's offer text; **no expiry logic** (G13, D17). | `frame` panel, `RowDivider` |
| Cancellation note + Szczegóły polityki | Text comes from that trainer's published policy version, **never a hard-coded 24 h**. Details open a read-only sheet with the policy tiers. | `InlineMessage`, `AppBottomSheet` |
| Sticky CTA | Opens the login-required sheet with the `RequestSession(trainerId)` intent. Content scrolls clear of it. | `BottomActionBar`, `PrimaryButton(icon = Lock)` |

**Privacy:** the model has **no** client names, notes, health data or relationship data. Only aggregate public numbers are allowed (§7.2).

**States:** loading; populated; `EmptyState` "Profil niedostępny" with a way back; error with retry; offline.

### 4.5 Gyms list (not in Stitch, D5)

Same pattern as Trainers: `TopLevelHeader` "Siłownie", `SearchField`, Dzielnica chip, result count, `GymRow` list.

`GymRow`: icon tile, name, district and hours summary, distance if known, chevron → Gym detail. States as in §4.3.

### 4.6 Gym detail (Siłownia – gość)

```
┌ ← Szczegóły siłowni ─────────────────────── [share] ┐
│ Studio Centrum                              20/26   │
│ [Wola] [1,2 km od Ciebie]  [★ 4,8 (128)]            │
│ 📍 ul. Przykładowa 68, Warszawa                     │
│ ┌ Dojazd ──────────────────────────────────────────┐│ frame panel
│ │ 🧭 Metro Rondo (150 m)          [🗺 Mapy Google] ││
│                                                     │
│ Godziny otwarcia                [✓ Otwarte teraz]   │
│ Poniedziałek – Piątek                 Całą dobę     │
│ Sobota – Niedziela                    Całą dobę     │
│                                                     │
│ Udogodnienia                                        │
│ [🏋 Wolne ciężary] [🏃 Cardio] [🅿 Parking] …       │
│                                                     │
│ Trenerzy w tej siłowni                 3 dostępnych │
│ ┌ (MK) Michał K. · Siła · ★ 4,9 · od 120 zł      > ┐│
├─────────────────────────────────────────────────────┤
│ [ Zaloguj się, aby poprosić o termin → ]            │
└─────────────────────────────────────────────────────┘
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Share | As on the trainer profile | `DetailHeader` |
| District, distance, rating | Chips; distance per D6, rating per D1 | `StatusChip` |
| Address, transit note | Text | `MetaLine` |
| Mapy Google | Opens the external maps app or browser with the address via `LocalUriHandler` (common Compose API; no new dependency) | `TonalButton` |
| Map preview image | Not reproduced (D7) | |
| Hours + "Otwarte teraz" | **Computed** from structured hours and the gym's IANA zone with an injected `TimeSource`; label and icon, not colour alone. "Zamknięte" is the other state. | `StatusChip(Success)` |
| Amenities | Icon + text chips, not clickable | `StatusChip` in `FlowRow` |
| Trainers at this gym, count | Row → Trainer profile. Replaces Stitch's per-card button ("Profil trenera/trenerki"), so no gendered copy is needed. | `TrainerRow` (compact) |
| CTA | Login-required sheet with the `RequestSession(gymId = …)` intent | `BottomActionBar` |

**States:** as §4.4. No trainers: hide the section's list and show a short note.

### 4.7 Log in / register (Logowanie / Rejestracja)

**Purpose:** one form with two tabs. Layout follows the New session form: one scrolling column, `Spacing.xxl` between groups, one primary button.

```
┌ ← Logowanie i rejestracja ─────────────────────────┐
│ ( Zaloguj się | Załóż konto )        SegmentedTabs │
│ Imię i nazwisko              (register only)       │
│ Adres e-mail                                       │
│ Hasło                                         [👁] │
│   Przynajmniej 8 znaków                            │
│ Wybierz profil startowy      (register only)       │
│ ┌ 🏋 Chcę trenować ✓ ┐ ┌ 📣 Jestem trenerem ┐      │
│ Możesz mieć obie role w ramach jednego profilu     │
│ ☐ Akceptuję regulamin i politykę prywatności       │
│ [            Załóż konto →            ]            │
│ ──────────────  lub  ──────────────                │
│ [ G  Kontynuuj z Google ]                          │
│         Przeglądaj bez logowania                   │
└────────────────────────────────────────────────────┘
```

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Login/register tabs | Starting tab comes from the entry point. Switching tabs keeps e-mail and password; name, role and consent appear only for register. | `SegmentedTabs` |
| Name, e-mail, password | Persistent labels; `Email` and `Password` keyboards, no auto-capitalisation for e-mail. Validation appears on submit, then live for that field. | `AppTextField`, new `PasswordField` |
| Show / hide password | Trailing icon with a state description | `PasswordField` |
| Role tiles | Single choice with radio semantics; default "Chcę trenować"; a selected tile uses the soft accent + check icon | New `RoleTile` |
| "Możesz mieć obie role" note | Supporting text | Text |
| Terms checkbox and links | Required for register; inline error if unset. Links open a sheet (no documents exist yet, D13). The accepted version is sent with the request. | `Checkbox` with a 48 dp row |
| Submit | Validate, then call the repository. Button shows progress and ignores repeat taps; **all input is kept on failure**. | `PrimaryButton(loading = …)` |
| Kontynuuj z Google | D10. The Google mark is the only non-Material icon: a bundled vector following Google's branding rules. | `TonalButton` |
| Przeglądaj bez logowania | → Guest shell | `AppTextButton` |

**Removed:** welcome banner, "Personalizacja" label (decorative), glass card, "Trener PRO" label (implies a tier that does not exist).

**Errors (inline, never raw exceptions):** wrong e-mail or password (the message does not reveal which); account already exists (with a shortcut to the login tab); offline ("Nie wysłano. Sprawdź połączenie."); service unavailable. Offline never counts as success.

**Security:** the password stays in screen-local `remember`, not `rememberSaveable` or UI state, so it never reaches the saved-state bundle. Request classes override `toString()` with a redacted value (precedent: `ClientInformation`). Nothing from the form is logged. The app stores no password hash; production goes through the identity provider.

**After success:** see §6, D12.

### 4.8 Login-required sheet (Wymagane logowanie)

| Stitch functionality | Behaviour | Component |
|---|---|---|
| Opens over the current screen | From the trainer-profile and gym CTAs; the screen behind keeps its state | `AppBottomSheet` |
| Lock icon, title, explanation | "Zaloguj się, aby kontynuować"; flat soft-accent circle | Same treatment as `EmptyState`'s icon |
| 3 benefits with check icons | Copy per D16 | Rows with a success icon |
| Załóż konto / Zaloguj się | → Auth in that mode, carrying the `pendingIntent` | `PrimaryButton`, `TonalButton` |
| Nie teraz / close | Dismisses; swipe and scrim also dismiss. The separate X is dropped as a duplicate. | `AppTextButton` |

## 5. New guest components

Local to the guest feature, and promoted only when a second feature needs them.

| Component | Built from | Used on |
|---|---|---|
| `TrainerRow`, `TrainerGridCard` | `CardRow` + `Avatar` + `MetaLine` + `StatusChip` | Start, Trainers, Gym detail |
| `GymRow` | `CardRow` + icon tile | Start, Gyms, Trainer profile |
| `GoalTile` | `tile` surface with border + icon + 2 lines | Start |
| `FilterSheet` variants | `AppBottomSheet` + `AppFilterChip` | Trainers, Gyms |
| `PriceList` | `frame` surface + `RowDivider` | Trainer profile |
| `LoginRequiredSheet` | `AppBottomSheet` + buttons | Trainer profile, Gym detail |
| `RoleTile`, `PagerDots`, `MediaTile` | Foundation | Auth, Welcome, Trainer profile |

## 6. Shared and existing code this changes

List these in the handoff, because they affect other views.

| Change | File | Why | Compatibility |
|---|---|---|---|
| Make the create button optional: `createLabel: String? = null`, `onCreate: (() -> Unit)? = null`; without it, no overhang and no centre gap | `shared/.../pl/trainer/ui/SharedBottomBar.kt` | The guest bar has no create action | Existing callers unchanged |
| Add `Avatar(initials, large)`; `Avatar(client)` delegates to it | `shared/.../ui/Components.kt`, export via `:shared:designsystem` | The current avatar requires a `Client` | Existing callers unchanged |
| Add `PasswordField` | `shared/designsystem/.../component/Inputs.kt` | No current field has a visual transformation with a trailing toggle | New API |
| Add `Money(minor: Long, currency: String)` and `LocaleFormats.formatMoney(...)` | `shared/domain/.../format/` | The only money formatter (`formatPln`) is Android-only and PLN-only | New API; `androidApp` may migrate later |
| Add expect/actual `rememberShareAction()` | Guest module | Android `ACTION_SEND`, desktop clipboard | New API |
| Pick the start destination from the session; navigate to the role shell with `popUpTo` on success | `shared/app/.../App.kt` | App currently always starts at `TrainerHomeDestination` | **Behaviour change**, see below |
| Register `guestModule` | `shared/app/.../AppModule.kt` | Koin wiring | |
| Replace `DemoTrainer` with a value from the session where signed in as a trainer | `AppModule.kt` | Its comment says sign-in is not implemented | The demo trainer ID is kept |
| Wire Wyloguj się: currently `onUnavailable` | `androidApp/.../trainerprofile/TrainerProfileScreen.kt`, `TrainerShell` | Otherwise a signed-in demo user can never return to guest | Small change |
| Add the module and dependency | `settings.gradle.kts`, `shared/app/build.gradle.kts` | New `:shared:feature:guest` | |

**Impact on teammates:** after this change the app opens on Welcome, not the trainer home. To protect trainer work:

- `App(initialSession: AuthSession? = null)` starts directly in a role shell when given a session.
- `TrainerIntegrationTest` (which calls `App()` today) passes a demo trainer session.
- The README documents the demo trainer account for manual runs.

## 7. Architecture and data

Follows `CLAUDE.md` §8 and the existing workout-builder structure.

### 7.1 Module

`:shared:feature:guest` (Android + desktop), `commonMain`, package `com.trainingapp.feature.guest`:

```
GuestNavigation.kt   GuestModule.kt   GuestShell.kt
welcome/  home/  trainers/  trainerprofile/  gyms/  gymdetail/  auth/
component/   model/   data/DemoGuestCatalogRepository.kt   data/DemoAuthRepository.kt
```

Dependencies: `:shared:designsystem`, `:shared:domain` (`TimeSource`, `NetworkMonitor`), navigation, lifecycle, serialization, datetime and Koin, all already in the version catalog. **No new third-party dependencies.**

### 7.2 Domain models

Public catalogue data is a **separate model** from `Client` and other private types (architecture: "the public catalogue uses a separate public data view").

```kotlin
data class TrainerSummary(id, name, initials, headline, mainGym: GymRef?, tags: List<String>,
    startingPrice: Offer?, rating: Rating?, verified: Boolean)
data class TrainerProfile(summary, bio, yearsOfExperience: Int?, clientCountLabel: String?,
    specialisations, gyms: List<TrainerGym>, media: List<PublicMedia>,
    offers: List<Offer>, cancellationPolicy: PublicCancellationPolicy?)
data class Offer(id, title, description, price: Money, durationMinutes: Int?, sessionCount: Int?,
    validityText: String?, highlighted: Boolean, seller: Seller)      // G7, G12
data class Rating(average: Double, count: Int)
data class PublicCancellationPolicy(version: Int, summary: String, tiers: List<PolicyTier>)
data class GymSummary(id, name, district, hoursSummary, distanceMeters: Int?, rating: Rating?)
data class GymDetail(summary, address, transitNote, weeklyHours: List<OpeningPeriod>,
    timeZone: String, amenities: List<Amenity>, trainers: List<TrainerSummary>)
data class PublicMedia(id, title, subtitle, state: MediaState, playbackUrl: String?)

data class TrainerQuery(text, goals, gymId, priceRange, districts, availability, sort)
data class Registration(name, email, password, role: StartingRole, acceptedTermsVersion)  // redacted toString
data class AuthSession(userId, displayName, roles: Set<Role>, activeRole: Role)
```

Reuse `MediaState` from `:shared:domain`. Do not create another status enum.

### 7.3 Repositories (explicit demo sources)

```kotlin
interface GuestCatalogRepository {
    suspend fun home(): GuestHome
    suspend fun trainers(query: TrainerQuery): List<TrainerSummary>
    suspend fun filterOptions(): FilterOptions
    suspend fun trainer(id: String): TrainerProfile?
    suspend fun gyms(query: GymQuery): List<GymSummary>
    suspend fun gym(id: String): GymDetail?
}
interface AuthRepository {
    val session: StateFlow<AuthSession?>
    suspend fun logIn(email: String, password: String): AuthResult
    suspend fun register(request: Registration): AuthResult
    suspend fun continueWithGoogle(): AuthResult
    suspend fun logOut()
}
```

- **Demo catalogue:** at least 12 trainers and 6 gyms. Names, gyms and addresses are **synthetic**: Stitch uses real brands (CityFit, McFIT, Zdrofit, Holmes Place), which must not be presented as partners. Filtering and sorting are a pure function in `data/`, so a server can replace them later.
- **Demo auth:** in memory, with reserved `@example.com` accounts documented in the README, never in the UI. It stores no user passwords. Scenarios reuse `DemoScenario` (Normal, Loading, Empty, Error, Offline, Denied).
- Keep `:contract` DTOs out until the backend work starts; the repository interfaces are the integration boundary.

### 7.4 Presentation

MVVM with `StateFlow`, immutable state and explicit actions; screens are `Route` (Koin/navigation) + `Screen(state, onAction)` so they stay previewable.

| ViewModel | Owns |
|---|---|
| `GuestCatalogViewModel` (shell-scoped) | Home data, `TrainerQuery`, view mode, gym query. Shared by tabs, so a Start search or goal tile updates the Trainers tab. |
| `TrainerProfileViewModel(id)`, `GymDetailViewModel(id)` | Load, retry, not-found, login-sheet visibility |
| `AuthViewModel` | Mode, field errors, submit state, failure; emits navigation on success. The password is passed as an argument on submit. |

**Pure logic with tests:** `AuthValidator`, `isOpenAt(hours, zone, instant)`, the trainer filter and sort function, and `searchKey` reuse.

**Pending intent:** `GuestIntent.RequestSession(trainerId?, gymId?)` is serialisable and travels through `AuthDestination`. After sign-in it goes to the role shell, where the booking request screen will consume it. It never creates a request by itself, because a request needs an explicit choice of slot (G10).

**Welcome flag:** `WelcomePreferences` expect/actual (`SharedPreferences` / `java.util.prefs`), no new dependency (D11).

### 7.5 Copy and formatting

- Compose resources in `values` (English) and `values-pl`, the same as `:shared`.
- Plurals through `pluralStringResource` (`42 opinie`, `28 opinii`, `1 opinia`).
- Dates and numbers via `DateFormats`, money via `formatMoney`.
- `4,9` follows the locale.

### 7.6 Domain rules preserved

| Rule (`CLAUDE.md` §7) | Applied here |
|---|---|
| A request is not a booking | CTAs say "poprosić o termin", never "zarezerwować" or "umówić" (D16) |
| Cancellation policies belong to trainers, are versioned, no universal 24 h | Policy text comes from each trainer's data; no text in resources mentions a time limit |
| Money: integer minor units + currency | `Money`; no floating point |
| Package expiry and renewal policies are undecided | Validity is display text; no logic |
| Media never shown as playable before it is ready | Play only for `READY` with a URL |
| Guests see only public profiles; UI hiding is not authorisation | Separate public models; production must enforce this on the server |
| No raw exceptions; server actions are never silently successful offline | Failure mapping in `AuthViewModel`; offline shows "not sent" |

## 8. Accessibility and adaptation

- All touch targets ≥ 48 dp; icon-only actions have labels; decorative icons are `null`-described.
- `heading()` on section titles; `selected` semantics on tabs, chips and toggles; role tiles use `Role.RadioButton` in a `selectableGroup`.
- Status always has text or an icon as well as colour.
- At font scale ≥ 1.5: 2-row bottom bar (already in `SharedBottomBar`), grid view disabled, goal tiles in 1 column, wider chips.
- Long names wrap to 2 lines before ellipsis; prices never get clipped.
- `imePadding` keeps the Auth form usable with the keyboard open.
- Reduced motion and active screen readers stop the Welcome auto-advance.

## 9. States summary

| Screen | Loading | Empty | Error | Offline | Other |
|---|---|---|---|---|---|
| Welcome | — | — | — | Works | |
| Start | Spinner | Message + join CTA | Retry | Banner + last data | Empty sections hidden |
| Trainers / Gyms | Spinner | No results + clear filters | Retry | Banner + last data | |
| Trainer profile / Gym detail | Spinner | Not available + back | Retry | Banner + last data | Media states; CTA stays enabled |
| Auth | — | — | Inline, input kept | "Not sent", input kept | Submitting; field validation; account exists |
| Login-required sheet | — | — | — | Works; the next step shows offline | |

## 10. Open decisions (do not decide silently)

**Conflicts with project rules. The default follows this task's "same functionality" instruction:**

| # | Question | Default in this plan | Alternative |
|---|---|---|---|
| D1 | Ratings and review counts. `CLAUDE.md` §2 excludes them unless asked. Stitch only displays them: no review list or submission exists. | Read-only, nullable; hidden when null. Rating sort included. | Remove the field, sort option and chips |
| D2 | "Zweryfikowany" badge: no verification process exists, so it is an unbacked claim outside demo data | Display-only boolean, label "Zweryfikowany" (no "PRO") | Remove |
| D3 | Promotional content on Start (professionals strip, join panel) | Keep both, simplified to one panel with one CTA | Remove the strip |
| D16 | Stitch copy that promises things we don't have or breaks domain terms (see below) | Reworded; the screen's function is unchanged | Keep the Stitch copy |

Copy changed under D16:

| Stitch | Problem | Replacement |
|---|---|---|
| "spersonalizowane diety" (slide 3), "Dietetyk Sportowy" | Nutrition is out of scope | "Dedykowane plany i stały kontakt z trenerem"; title dropped |
| "natychmiastowy feedback biomechaniczny" (slide 2) | Promises an instant response | "Wysyłaj nagrania ćwiczeń i otrzymuj uwagi trenera" |
| "Gwarancja jakości", "pakiety z gwarancją", "Certyfikowani specjaliści", "zweryfikowana kadra" | Unbacked claims | Removed or neutral |
| "Bezpieczne płatności", "Płatności BLIK" | Payments are not built | "Płatności w jednym miejscu" |
| "estymacja 1RM" | Not required as a guest benefit | "Plany i postępy treningowe" |
| "zarezerwować", "umówić trening" | A request is not a booking | "poprosić o termin" |

**Gaps in Stitch or the project:**

| # | Question | Proposal |
|---|---|---|
| D4 | The quiz prompt has **no quiz screen** in Stitch | Open the Cel filter sheet; text "Dopasuj trenera do swojego celu". A real quiz needs a design. |
| D5 | The Siłownie tab and "Wszystkie" link have **no list screen** in Stitch | Build the list in §4.5 |
| D6 | "W pobliżu" and "1,2 km od Ciebie" require location, which is not available | Demo: fixture distances, no permission requested. Without a distance, the row shows the district and the title becomes "Siłownie". Real location is a separate task. |
| D7 | Map preview: no maps SDK or key, and a static image would be fake | Address + Mapy Google button only |
| D8 | Photos: no image-loading library, Stitch's images are AI-generated, and calendar and clients views use no photos | Initials and icons now. Photos later need a dependency, hosting and consent. The trainer home does use bundled photos, so the team may prefer otherwise. |
| D9 | Video playback: `DemoVideoPlayer` is inside workout-builder, and no public sample clip exists | `:shared:app` passes the player into the guest graph as a composable slot, so guest does not depend on workout-builder. The demo needs one small synthetic clip; without it, tiles show as unavailable. |
| D10 | Google sign-in: identity provider not chosen, and a fake success would mislead | Keep the button; demo shows an inline "Logowanie Google nie jest jeszcze dostępne" |
| D11 | Should Welcome show every launch? Stitch doesn't say | Once per install, using the persisted flag |
| D12 | There is **no client shell** to open after a client signs in | Trainer role → existing trainer host. Client role → simple placeholder explaining the area is coming, with Wyloguj się. |
| D13 | Terms and privacy documents don't exist | Sheet with placeholder text; consent stores a version. Real documents are needed before any real user data. |
| D14 | Trainer registration: onboarding and verification are undesigned | Demo → trainer shell with synthetic data. Real onboarding is separate. |
| D15 | Product name is not final; the launcher label is "Aplikacja trenera", which is wrong for guests | Welcome shows no name, only an icon; the team picks a neutral `app_name` |
| D17 | Package validity ("Ważny 30 dni") touches unresolved G13 | Trainer's own display text; no logic or promises in the app |
| D18 | Voice search: the mic in Stitch is dimmed and has no handler | Leave out at first; optional in Phase 6 via `RecognizerIntent` (Android only). Keyboards already offer voice input. |
| D19 | Forgot password: **missing from Stitch**, yet needed for a real login | Not built in this slice. Provided by the identity provider when connected. |

## 11. Implementation steps

Each phase ends with a runnable app, compiled tests and a commit-sized change.

**Phase 1: foundations**
1. Confirm D1–D3, D5, D8, D12 and D16.
2. Create `:shared:feature:guest`; add shared changes from §6 (bottom bar, avatar, password field, money).
3. Add models, repository interfaces, demo fixtures, filter/sort function and tests.

**Phase 2: shell and entry**
4. `GuestShell`, `guestGraph`, session-based start in `App.kt`; update `TrainerIntegrationTest`.
5. Welcome (§4.1) with the persisted flag.

**Phase 3: browsing**
6. Start (§4.2) and Trainers (§4.3), with shared query state, sheets, sort and list/grid.
7. Gyms list (§4.5).

**Phase 4: details**
8. Trainer profile (§4.4): price list, policy sheet, share, media slot.
9. Gym detail (§4.6): open-now, maps link.
10. Login-required sheet (§4.8) on both screens.

**Phase 5: authentication**
11. Auth (§4.7): validator, form, failure states.
12. Role routing, pending intent, logout wiring, client placeholder.

**Phase 6: finish**
13. English and Polish copy review; previews with synthetic data for each state.
14. Optional D18; full validation (§12); README update (demo accounts, how to run).

## 12. Validation

Run the commands the repository actually configures and report what ran:

```sh
./gradlew :shared:feature:guest:desktopTest :shared:desktopTest :shared:app:desktopTest :shared:domain:jvmTest :desktopApp:classes
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

**Behaviour tests**

- Filter and sort: each filter, combinations, Polish-insensitive search, clear-all, derived counts.
- Start → Trainers: search and goal tile carry over; tab switch keeps state.
- `isOpenAt`: inside and outside hours, overnight and 24 h periods, DST in `Europe/Warsaw`, a device in another time zone.
- `AuthValidator`: e-mail format, 8-character boundary, blank name, missing consent, whitespace.
- `AuthViewModel`: double tap sends one request; input kept after each failure; offline never succeeds; repeat tab switching keeps values; correct route per role; pending intent is delivered and creates nothing by itself.
- Back after sign-in never returns to Auth or guest.
- Media: only `READY` is playable.
- Formatting: money and plurals in Polish and English; `toString()` hides the password.
- **Privacy:** public fixtures contain no fields or text from private models; no hard-coded "24 h" in resources.

**UI tests (desktop Compose, as existing suites)**

- Welcome → browse → trainer → gym → sheet → register → role shell.
- 390 × 844 at 100 % and 320 × 720 at 200 %, in Polish.
- Loading, empty, error and offline variants.
- Save renders to `build/reports/screenshots/` and put them next to `docs/previews/trainer-calendar.png` and `trainer-client-profile.png` to compare colour, type, padding, shapes and action hierarchy.

**Manual, on a device or emulator when available:** keyboard and system bars on Auth, share sheet, maps link, video, TalkBack, 200 % font. Report these as unverified if not run.

## 13. Handoff checklist

- Views implemented and the shared changes from §6, called out explicitly (bottom bar API, avatar, money, `App.kt` start behaviour).
- Routes and repository contracts used.
- Stitch parity: for each table in §4, what was built, changed or left out, with decision numbers.
- Decisions taken for D1–D19, recorded as team decisions.
- Screenshots of the main states next to a trainer screen.
- What was validated versus assumed.
- **Remaining integration gaps:**
  - Catalogue backend and public data view with server authorisation.
  - Identity provider, Google sign-in and password reset.
  - Booking request flow consuming `GuestIntent`.
  - Client shell.
  - Real terms and privacy documents.
  - Location.
  - Public media hosting and share links.
