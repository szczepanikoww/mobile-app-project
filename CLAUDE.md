# Project instructions for AI coding agents

These instructions apply to the entire repository. The team develops different screens in parallel; every screen must feel like part of the same application. Reuse the shared design system and interaction patterns before introducing new ones.

## 1. Read before making changes

1. Read this file and [docs/context.md](docs/context.md).
2. Read the relevant sections of [docs/initial-architecture.md](docs/initial-architecture.md).
3. Inspect the current project structure, shared theme, reusable components, navigation, and a neighboring screen before implementing a view.
4. Follow the current task's scope. The full product vision is not a requirement to build every feature on every screen.

Project references:

- [Original product requirements](https://docs.google.com/document/d/1XUDMV4pDoRUby3vLS8j193C49wSvSukPWTFc7P4oNYQ/edit?tab=t.0).
- [Stitch UI reference](https://stitch.google.com/projects/10185753988111686080).
- [Trainer home reference](https://stitch.google.com/preview/10185753988111686080?node-id=9acd328f840f4cadb3c924fe2a4600da).
- [Client home reference](https://stitch.google.com/preview/10185753988111686080?node-id=dc7266cd82054bb29fee2f24aa0c5029).

**Repository status when these instructions were created:** documentation only, with no application scaffold, shared theme, or runnable build yet. Do not describe proposed modules, dependencies, or commands as already implemented. Inspect the repository again on each task as this will change.

**Decision status:** `initial-architecture.md` is a draft, including proposals, alternatives, open questions G1–G15, and placeholder targets. Use it as the proposed technical direction, not evidence that every choice has been agreed. Follow explicit task requirements and later recorded team decisions; report material conflicts. Do not silently decide unresolved product policies while implementing a view.

## 2. Product context and scope

An Android-first personal-training application for trainers, clients (trainees), and guests, with iOS planned later. A user may eventually hold both trainer and client roles; screens must use the active role and relationship context rather than assuming one global role.

| Audience | Main responsibilities |
| --- | --- |
| Trainer | Calendar and availability; session requests, rescheduling, cancellation and attendance; own clients and coaching notes; exercise library, plans and templates; planned versus completed workouts; progress; one-to-one chat and technique videos; finance. |
| Client | Upcoming sessions and requests; today's plan and workout logging; previous results and rest timer; history, records and measurements; chat and technique videos; payments, invoices, profile, health questionnaire and consents. |
| Guest | Browse public trainers, gyms and prices. Authentication is required for booking requests, workout logging, private messaging and account management. |

Technique video is a core workflow, not an optional decorative extra. Workout logging is inspired by Hevy's practical set-entry workflow; it does not establish our visual identity.

The architecture proposes an initial end-to-end slice: browse a trainer → sign up/connect → request/confirm a session → assign a plan → log sets offline → upload technique video → trainer check-in → cancellation fee/cash settlement. Implement only the part assigned in the task, preserving its integration points.

Health integrations (Garmin/Health Connect and Apple Health) and iOS are later work. Other features may also be deferred by the team's current scope. Do not add nutrition plans, ratings, verification systems, promotional sections, estimated calories, or other features merely because a Stitch mockup contains them. Placeholder brand names such as “Kinetic Apex” and “Kinetic Horizon” are not a finalized product name.

## 3. Design principles

**The target is a minimal, calm, practical fitness app. Users should immediately understand what matters and what they can do next.**

- Stitch establishes the visual direction, not a screen-by-screen implementation specification. Keep its light surfaces, warm orange accents, strong hierarchy, and rounded components; simplify its content, oversized imagery, repeated controls and decorative effects.
- Give each screen one clear purpose and one dominant next action where appropriate. Repeated row actions are allowed; competing full-width primary actions are not the default.
- Show essential information first. Put advanced fields and secondary actions in detail views, expandable sections, menus or sheets.
- Use whitespace, typography and grouping before adding containers, borders or shadows. Do not wrap every row in a separate card or nest cards unnecessarily.
- Home screens prioritize the next session, today's workout, or items requiring attention. Additional metrics and discovery content belong only where the task calls for them.
- Trainer, client and guest views share one visual language. Role differences change content and permissions, not the theme.
- Do not introduce a screen-specific palette, font, icon family, corner-radius system, navigation bar, or custom button style.

## 4. Shared visual baseline

The values below are a repository baseline derived from the linked Stitch references, with mobile sizing and accessibility rules added for implementation. They are not an existing code API or a pixel-perfect translation of all mockups. Once implemented, consume them through one shared theme; record agreed changes here and update shared components together.

### Colors

| Semantic role | Light baseline | Usage |
| --- | --- | --- |
| Background | `#F8F9FA` | Main screen background. |
| Surface | `#FFFFFF` | Cards, sheets and elevated content. |
| Subtle surface | `#F8FAFC` | Secondary grouping and neutral controls. |
| Main text | `#0F172A` | Titles and primary content. |
| Secondary text | `#565E74` | Supporting information. |
| Outline | `#E2E8F0` | Subtle separators and boundaries. |
| Brand accent | `#FF6B00` | Highlights, selected accents and accent containers. |
| Strong brand | `#A04100` | Readable brand text and filled primary controls. |
| Soft accent / text | `#FFF0E6` / `#572000` | Tinted brand surfaces with dark text. |
| Success / container | `#15803D` / `#DCFCE7` | Confirmed, completed or successfully saved states. |
| Warning | `#D97706` | Attention indicators; check contrast for text. |
| Error accent | `#EF4444` | Error indicators; not an automatic text/background pair. |
| Error container / text | `#FFDAD6` / `#93000A` | Readable error messages and destructive context. |

- Use semantic theme roles rather than hard-coded hex values inside feature screens. Centralize any additional foreground/container pairs.
- Bright orange with small white text does not provide sufficient contrast. Use white on the strong brand color, or dark text on a suitable orange/tinted container, verified for contrast.
- Status must have a label or icon as well as color. Keep brand emphasis distinct from warning and error semantics.
- Start with the light reference. If the app supports dark mode, use the shared dark theme consistently; do not invent it separately for a feature.

### Typography, spacing and shape

- Reference typeface: **IBM Plex Sans**, with regular, semibold and bold weights. Use the shared font resource and support Polish characters. Until it is bundled, use one shared system-sans fallback; do not download fonts at screen runtime or choose a different family per view.
- Define named typography styles centrally. Baseline font size/line height in `sp`: screen title `26/32`, section title `20/26`, item title `17/22`, body `16/24`, compact body `14/20`, supporting label `12/16`. Use semibold/bold for hierarchy and regular for body copy.
- Reserve larger headlines for onboarding or a truly prominent value. Do not copy the reference's tiny uppercase labels for essential instructions or form values.
- Use `dp` for layout and `sp` for text. Shared spacing scale: **4, 8, 12, 16, 20, 24, 32 dp**. Default screen gutter: **20 dp**; card padding: **16 dp**; section gap: **24 dp**; related-item gap: **8–12 dp**.
- Baseline radii: **16 dp** for cards and inputs; **24–32 dp** for larger sheets or feature cards; pill shapes for chips and rounded buttons. Expose these through shared shape tokens, not local constants.
- Prefer flat surfaces and subtle borders or low elevation. Avoid heavy shadows, glow effects, glass effects and ornamental gradients.
- Use one Material-style icon set, generally outlined, at a consistent size (usually **24 dp**) with at least **48 × 48 dp** interactive targets. Use actual vector icons, not icon-font ligature strings or emoji as interface controls.
- Use photos and video thumbnails only when they convey identity or exercise content. Keep aspect ratios and placeholders consistent; avoid large stock-photo sections on routine task screens.

## 5. Shared components and navigation

Before building a feature, reuse or extend the shared equivalent of:

- App scaffold, top app bar, role-aware navigation and content insets.
- Primary, secondary, text, icon and destructive buttons.
- Text inputs, numeric inputs, search, selectors and validation messages.
- Cards/list rows, session summaries, avatars and status chips.
- Dialogs, bottom sheets, snackbars and inline feedback.
- Loading, empty, error, offline and synchronization states.
- Workout set rows, media thumbnails and upload progress where relevant.

These are component responsibilities, not prescribed class names or an instruction to build an unused component library. Follow established naming and placement once code exists. Introduce a missing shared primitive when the current feature needs it, with a small reusable API.

- The application shell owns global navigation and top-level destinations. Feature screens must not render their own competing bottom navigation or recreate the entire shell.
- Top-level navigation is role-aware and stable. The references suggest trainer Start/Calendar/Clients/Finance and client Start/Calendar/Workout/Progress/Wallet; these are reference groupings, not finalized routes. Follow the implemented navigation contract and recorded team decisions.
- Detail screens use the shared back-navigation pattern. Render titles, avatars and actions once; do not copy the mockups' duplicated headers or notification controls.
- Use sheets/dialogs for short contextual decisions, and full screens for longer editing tasks. Preserve input when validation or a request fails.
- Keep actions reachable above the keyboard and system bars. Scroll content clear of fixed buttons and bottom navigation. Do not reproduce the browser preview's phone frame or fixed viewport height.

## 6. Interaction, content and accessibility

- Each data screen needs appropriate loading, populated, empty and recoverable error states. Add offline, submitting, pending, permission-denied and conflict states where the workflow requires them.
- Empty states give a short explanation and an applicable next action. Errors explain what failed and offer retry where safe. Avoid raw exception messages.
- Prevent duplicate submissions while an action is running. Keep the distinction between local save, queued sync, request sent and server confirmation visible.
- Explain destructive or fee-bearing actions before confirmation. Show the session, consequence, quoted amount and currency; label the confirmation specifically, such as “Cancel session (60 zł)”.
- Keep user-facing copy concise and consistent. English code and documentation do not imply English-only UI. Reuse the app's selected locale and extract user-facing text into localization resources; the product targets Polish and English.
- Format dates, times, numbers, currencies and units using shared locale-aware formatters. Store instants in UTC with the relevant IANA time zone; display times in context. Do not hard-code mockup dates or rely on the device's time for policy decisions.
- Use clear domain terms consistently: a booked coaching **session**, an exercise **workout log**, a **plan**, a **booking request**, and a **reschedule request** are different concepts.
- Support font scaling, small screens, long names and Polish text without clipping essential information. Inputs need persistent labels, appropriate keyboards and inline validation.
- Provide screen-reader semantics, meaningful descriptions for icon-only actions, sensible focus order and visible focus. Decorative imagery should not be announced as actionable content.
- Target contrast of at least **4.5:1** for normal text and **3:1** for large text and essential control graphics. Never rely on color alone or reduce font size just to make a layout fit.
- Keep motion subtle and respect reduced-motion preferences where available. Prioritize fast set entry and reliable feedback during workouts.

## 7. Domain rules the UI must preserve

The following are implementation guardrails from the requirements and proposed architecture. Check the relevant architecture section and current contracts when implementing a workflow; unresolved policy details remain team decisions.

- **Scheduling:** a client's request is not a confirmed booking. During reschedule negotiation, the original session stays confirmed until acceptance succeeds. Show requested, confirmed, rejected, cancelled and completed states accurately; the server resolves availability conflicts.
- **Cancellation:** policies belong to trainers and are versioned. Show a fresh server quote before fee-bearing actions; do not hard-code a universal 24-hour rule. Trainer cancellation does not charge the client. The trainer may waive fees according to the supported workflow.
- **Payments:** money uses integer minor units plus a currency code. Charges, money received, outstanding debt and payouts are different values. A client-side success callback does not prove settlement; show pending until authoritative confirmation. Use test mode during the university phase.
- **Client list:** show the overdue-payment yes/no flag. Detailed financial amounts belong in the appropriate authorized finance flow, not the general client list.
- **Plans:** assigned plan versions and completed workout logs are separate. Editing a template must not rewrite historical assignments or actual results. Independent workouts need not have a booked session.
- **Workout logging:** persist every set locally and survive process death. Cached plans, history and logging support offline work. Show pending/failed/conflicting synchronization; retry must not duplicate sets. The rest timer is based on a stored end time.
- **Server-dependent actions:** bookings, payments and fee-bearing changes need server confirmation. An offline attempt is unsent/pending, never silently successful.
- **Chat and media:** show delivery/upload progress and recoverable failures. Media can be uploading, processing, ready or failed; never present an unfinished upload as playable. Technique-video capture, attachment and review must remain accessible in the relevant flows.
- **Privacy:** guest browsing exposes only public profiles. Private records require role and trainer–client relationship checks on the server. Health questionnaires, injuries, measurements, coaching notes, chat and technique videos are private; never place them in logs, analytics, push text, public fixtures or calendar-feed descriptions.
- Use synthetic examples in previews and tests. UI hiding is not authorization. Do not implement a new policy for package expiry, rollover, subscription renewal or relationship access just to fill a mockup.

## 8. Technical direction

The draft proposes Kotlin Multiplatform (`shared`, `androidApp`, later `iosApp`) and Compose with APIs compatible with future shared UI. Presentation follows MVVM/unidirectional data flow: `ViewModel` + `StateFlow`, immutable UI state and explicit events.

- Screens render state and emit actions. Put business decisions in use cases/domain rules, and persistence/network work behind repositories. Do not call databases, payment SDKs or backend endpoints directly from composables.
- Separate a navigation/DI-aware route from reusable screen content where useful. Keep screen content previewable with state and callbacks rather than requiring live services.
- Keep Android-only APIs behind platform boundaries; do not introduce Android-only dependencies into `commonMain`.
- Draft choices include Koin, Ktor, kotlinx.serialization, kotlinx-datetime, SQLDelight (Room remains an alternative), a Ktor modular backend and PostgreSQL, and shared DTOs/rules in `:contract`. Inspect actual build files and team decisions before adding any dependency; do not pin versions copied from the draft.
- Reuse domain models, DTOs, repositories and shared state machines. Avoid independent copies of booking status enums, fee calculations or money/date formatting inside different views.
- Use deterministic fixtures for previews or an explicit demo data source. Keep fixtures separate from production repositories and do not imply an unconnected screen has live integration.

## 9. Team workflow and completion checks

1. Identify the assigned view, active role, entry/exit routes, required states, shared components and data contracts before editing.
2. Reuse the closest implemented screen and central tokens. If shared foundations are absent and needed, establish the minimal shared theme/scaffold first instead of a feature-local substitute.
3. Keep changes within the feature and necessary integration points. Preserve teammates' uncommitted work and avoid unrelated formatting, dependency updates or restructuring.
4. Changes to shared theme tokens, component APIs, navigation or domain contracts affect other views: make them explicit in the change summary, keep compatibility where practical, and update affected callers. Significant product/design changes should be recorded as team decisions rather than introduced silently in one feature.
5. Validate the view against another screen for color, typography, padding, shapes, action hierarchy, navigation and state feedback. Check small-screen layout, larger font settings, keyboard/insets and long localized text when runnable tooling is available.
6. Run the build/lint/tests actually configured in the repository and relevant to the change. Cover meaningful behavior such as state transitions, validation, offline persistence or duplicate submission; do not add tests that merely restate layout constants. No build commands exist at documentation-only initialization: do not invent successful checks.
7. In the handoff, state the implemented role/view, shared changes, routes/contracts used, validation performed, and any remaining integration gaps. Include a screenshot or preview when the UI can be rendered. Clearly distinguish verified behavior from assumptions.

**Maintenance:** `AGENTS.md` is identical to`CLAUDE.md`.

