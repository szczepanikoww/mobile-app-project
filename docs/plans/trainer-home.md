# Plan: Trainer home screen (Start)

Status: plan only, nothing implemented · 2026-10-28 · local working file (git-ignored)

Sources: `CLAUDE.md` §2–§9, `docs/context.md`, `docs/initial-architecture.md` §2 (G4, G10, G11, G14), §6, §7, §8, §9, §12, and the Stitch screens "Aplikacja Trenera Personalnego" (`9acd328f…`, the linked trainer home reference) and "Pulpit Trenera (Home) – Jasny" (`7a34ccf8…`).

---

## 1. Purpose and scope

**Role:** trainer (the user's active role is trainer; a user may also hold the client role, so the screen reads the active role and does not assume a global one).

**Purpose:** one screen that answers "what do I do next?":

1. the next session and its one main action;
2. what's left of today's schedule;
3. items that need the trainer's decision or attention.

**In scope**

- The Start destination of the trainer's top-level navigation.
- Next session, today's remaining sessions, a "To decide" list (booking requests, reschedule requests), and a "Needs attention" list (technique videos to review, unread conversations, clients with an overdue-payment flag).
- Loading, populated, empty, error and offline states.
- Navigation to the existing (or future) detail destinations listed in §6.

**Out of scope (do not build here)**

- The calendar, client profile, chat, video player, check-in flow and request detail screens. The home screen only links to them.
- Package/subscription requests ("Nowy pakiet" card in Stitch): G13 is unresolved.
- Weekly statistics tiles ("Ten tydzień"). Metrics belong on Finance/Calendar unless the team asks for them here (see §9, decision D3).
- Nutrition ("Pytanie o dietę" in the mockup is only sample text), ratings, promotions, calorie estimates.

## 2. What we take from Stitch and what we simplify

| Stitch element | Decision | Reason (`CLAUDE.md`) |
|---|---|---|
| Light surface, orange accent, rounded cards | **Keep**, through the shared theme | §3, §4 |
| Second header with greeting + duplicated chat and bell buttons below the top bar | **Drop the duplicate.** The shell's top app bar renders the title and global actions once. A short greeting may be the screen title ("Hej, Michał"). | §5: render titles and actions once |
| Large stock photo on the next-session card and on every carousel card | **Replace with a client avatar** (photo if the client has one, initials otherwise). No hero imagery. | §4: photos only for identity or exercise content |
| Horizontal image carousels for "Today" and "To decide" | **Vertical compact lists** of rows. Easier to scan, works with font scaling and screen readers. | §3: essential information first; §6 |
| Glass/blur effects, glows, orange quarter-circle overlays, heavy shadows | **Drop** | §4: flat surfaces, no glass or glow |
| Tiny uppercase labels ("WIDEO TECHNIKI", "POTWIERDZONA") | **Sentence-case labels** in `supportingLabel` (12/16) or status chips | §4 |
| "Check-in" filled + "Napisz" secondary on the next-session card | **Keep the pair**: one filled primary, one secondary | §3: one dominant action |
| Accept (✓) / reject (✕) icon buttons on request cards | **Keep as row actions** with text labels or accessible descriptions; accept opens a confirmation sheet | §5, §6, §7 |
| Centre "+" FAB inside the bottom bar | **Not part of this screen.** The FAB belongs to the app shell; whether it exists is a navigation decision (D2). | §5: shell owns navigation |
| Bottom navigation (Start / Kalendarz / Klienci / Finanse) | **Shell-owned**, not rendered by the feature | §5 |
| Red dot + "Zaległa płatność" card | **Keep only as a yes/no flag** per client, never with an amount | §7 client list rule, G14 |

## 3. Layout (top to bottom)

Gutter 20 dp, section gap 24 dp, related-item gap 8–12 dp, card padding 16 dp, card radius 16 dp (shared shape tokens).

```
┌ Top app bar (shell) ─ "Start"/greeting ─────── [chat] [bell] [avatar] ┐
│                                                                       │
│ Next session                                    (section title 20/26) │
│ ┌───────────────────────────────────────────────────────────────────┐ │
│ │ (avatar) Kasia Nowak                      [✓ Confirmed]           │ │
│ │          Today, 10:00–11:00 · in 45 min                           │ │
│ │          Siłownia Centrum · Plan: Push A                          │ │
│ │ [  Check-in  (primary) ]   [  Message (secondary) ]               │ │
│ └───────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│ Today · 4 more                                       See calendar >   │
│  12:30  (av) Tomek W.     Leg day             [✓ Confirmed]       >   │
│  14:00  (av) Magda B.     Full body           [⏳ Requested]      >   │
│  …                                                                    │
│                                                                       │
│ To decide (3)                                                         │
│  (av) Ola Kowalska   Booking request · Thu 18:00      [Reject][Accept]│
│  (av) Marek W.       Reschedule · Fri 9:00 → 11:00    [Reject][Accept]│
│                                                                       │
│ Needs attention                                                       │
│  (thumb) Technique video · Deadlift · Ania K.                     >   │
│  (av)    2 unread messages · Piotr S.                             >   │
│  (av)    Overdue payment · Marek L.                               >   │
└───────────────────────────────────────────────────────────────────────┘
```

- Rows in a section share one surface with separators instead of a separate card per row (`CLAUDE.md` §3).
- **Next session card:** the only elevated/feature card. Its primary action depends on state:
  - check-in window open (server-provided flag) → **Check-in**;
  - otherwise → **Details** (or no primary, just tap the card). Do not compute the check-in window from device time.
- **Today list:** sessions after the next one, ordered by start time; shows status chip with icon + label (confirmed, requested, cancelled). Cap at ~5 rows, then "See calendar".
- **To decide:** booking requests and reschedule requests where the trainer is the one who must answer. Request ≠ booking: a booking request row never looks confirmed. A reschedule row shows original → proposed time; the original session stays confirmed.
- **Needs attention:** only items with a clear next action. Video rows appear only when media is `READY`; never show an unfinished upload as playable.
- Hide a whole section when it is empty, except when *everything* is empty (see §5).

## 4. Interactions

| Action | Behaviour |
|---|---|
| Tap next-session card / today row | Navigate to session details (route owned by calendar/scheduling feature). |
| **Check-in** | Navigates to the check-in flow (separate task). Disabled with a reason when offline: check-in needs the server. |
| **Message** | Opens the 1:1 conversation with that client. |
| **Accept** booking request | Bottom sheet: client, date/time in context time zone, location, price snapshot if available. Button "Confirm session". Submitting → button shows progress, duplicate taps ignored. Server success → row disappears, snackbar "Session confirmed". Conflict → inline error "This slot is no longer free" with option to open the request. |
| **Accept** reschedule request | Same sheet, shows original → proposed time, any fee quote from the server (fresh quote, never computed locally), and the waiver option only if the supported workflow allows it. Copy makes clear the original stays until the server confirms. |
| **Reject** | Short confirmation sheet ("Reject request from Ola Kowalska for Thu 18:00?"), optional reason if the contract supports it. |
| Tap video row | Opens the technique-video review in chat/client context. |
| Tap unread row | Opens the conversation. |
| Tap overdue row | Opens the client profile (finance details stay in the finance flow). |
| Pull to refresh | Re-fetches the summary. |

Every server-dependent action shows: idle → submitting → success / failed (retry) / conflict. Offline attempts are shown as "not sent", never as successful.

## 5. States

| State | Content |
|---|---|
| Loading (first open, no cache) | Skeleton placeholders for the next-session card and two list rows. |
| Populated | As in §3. |
| Empty day | "No more sessions today" + next upcoming session if one exists, or action "Open calendar". |
| Fully empty (new trainer) | Short explanation + one action, e.g. "Add availability" or "Invite a client" (pick whichever route exists; D4). |
| Error (no cache) | "Couldn't load your day" + Retry. No raw exception text. |
| Error with cache / offline | Cached content + inline banner "Offline · updated 10:42". Accept/Reject/Check-in disabled with explanation. |
| Partial failure | A failed section shows an inline retry; the rest of the screen still renders. |
| Action submitting / conflict | Per §4. |

## 6. Navigation and routes

- Top-level destination: trainer **Start** (reference grouping Start / Calendar / Clients / Finance; follow the implemented navigation contract once it exists).
- Outgoing routes (placeholders until implemented, named by responsibility, not final class names): session details, check-in, booking-request details, reschedule-request details, conversation, technique-video review, client profile, calendar.
- If a destination is not implemented yet, the row is still rendered but navigation goes to a shared "not available yet" placeholder or is disabled. Do not build those screens inside this task.

## 7. Architecture and data

Follows `CLAUDE.md` §8 and architecture §10.2. Nothing below exists yet.

**Presentation**

- `TrainerHomeRoute` (DI/navigation aware) → `TrainerHomeScreen(state, onAction)` (previewable, no services).
- `TrainerHomeViewModel` exposes `StateFlow<TrainerHomeUiState>` and handles `TrainerHomeAction` (Refresh, OpenSession, CheckIn, Message, AcceptRequest, RejectRequest, OpenAttentionItem, …) plus one-off events (navigation, snackbar).
- UI state is immutable: `nextSession`, `todaySessions`, `pendingDecisions`, `attentionItems`, `isOffline`, `lastUpdated`, per-section load status, `submittingRequestIds`.

**Domain / data**

- Use case `ObserveTrainerHome(trainerId)` combines scheduling, messaging, media and billing-flag sources, or reads a single server summary endpoint (decision D1).
- Use cases `AcceptBookingRequest`, `RejectBookingRequest`, `AcceptRescheduleRequest`, `RejectRescheduleRequest` live in the scheduling domain and are reused by Calendar, not duplicated here.
- Reuse shared models from `:contract`: session status, request status (Requested, Confirmed, Rejected, Cancelled, Completed, …), reschedule negotiation status, media state, overdue flag. No local enum copies.
- Times: UTC instants + IANA zone; display formatted with the shared locale-aware formatter ("Today, 10:00–11:00", "in 45 min"). Relative "in 45 min" uses a shared clock abstraction for display only, never for policy decisions.
- Cache: last summary stored locally so the screen opens offline (read-only).

**Privacy**

- No health data, coaching notes or chat content on this screen beyond what the trainer's own relationship allows. Unread row shows sender + count; message preview only if the chat feature already shows previews (D5).
- Overdue: yes/no flag only.
- Previews and tests use synthetic names.

## 8. Shared foundations this screen needs

The repo has no scaffold yet. Before (or as the first part of) this task, establish the minimal shared pieces from `CLAUDE.md` §4–§5. Do not create feature-local substitutes.

| Shared piece | Needed for |
|---|---|
| Theme: color roles, typography styles (26/20/17/16/14/12 sp), spacing scale, shape tokens, IBM Plex Sans or one system-sans fallback | Everything |
| App scaffold + trainer navigation (Start, Calendar, Clients, Finance) + top app bar | Screen shell |
| Primary / secondary / text / icon buttons with loading state | Next-session card, sheets |
| List row (avatar, title, supporting text, trailing slot) | Today, To decide, Needs attention |
| Session summary card | Next session (also reused on client home) |
| Status chip (icon + label + semantic color) | Session/request status |
| Avatar (photo / initials) and media thumbnail (with state) | Rows |
| Bottom sheet, snackbar, inline banner | Accept/reject, feedback, offline |
| Loading skeleton, empty state, error state | §5 |
| Locale-aware date/time formatter, string resources (PL + EN) | All text |

Any of these changes affects other screens: list them in the handoff.

## 9. Open decisions (do not decide silently)

| # | Question | Proposal |
|---|---|---|
| D1 | One `GET trainer/home` summary endpoint vs. combining several repositories on the client | Summary endpoint (fewer calls, server-side ordering) with reuse of shared DTOs |
| D2 | Does the trainer shell have a central "+" action, and what does it create? | Shell/navigation decision; not part of this screen |
| D3 | Weekly metrics tiles on home? | Leave out; Finance and Calendar own metrics |
| D4 | Empty-state action for a new trainer | "Add availability" if that flow exists, otherwise "Invite a client" |
| D5 | Show message preview text in the unread row? | No preview (only count) until chat feature decides |
| D6 | Check-in window: when does "Check-in" become available? | Server-provided flag on the session; trainer policy/grace period from architecture §3 |
| — | G11: trainer-initiated moves are proposals | Reschedule rows must support both directions |

## 10. Implementation steps

1. Confirm task scope, D1–D6 and that shared foundations (§8) either exist or are part of this task.
2. Create/extend shared theme and components listed in §8 (minimal APIs).
3. Define UI state, actions and events; write the ViewModel with fake repositories.
4. Build `TrainerHomeScreen` with previews for: populated, empty day, fully empty, loading, error, offline, submitting, conflict, long Polish names, font scale 1.5×, small screen (360 dp).
5. Wire scheduling use cases for accept/reject (shared with Calendar).
6. Add string resources (PL, EN).
7. Integrate the route into trainer navigation; stub missing destinations.
8. Tests and validation (§11).

## 11. Validation

- **Unit tests (ViewModel/use cases):** state for each data combination; accept/reject transitions (submitting → success / failure / conflict); double tap does not send two requests; offline makes server actions unavailable; reschedule shows original kept until success; overdue item carries no amount.
- **UI checks:** compare with another implemented screen for colors, typography, padding, shapes, action hierarchy; TalkBack labels for icon actions and status chips; 48×48 dp targets; contrast ≥ 4.5:1 (no small white text on `#FF6B00`).
- Run only the build/lint/tests the repo actually configures; report what was run.

## 12. Handoff checklist

- Role/view implemented, shared changes made (theme, components, navigation), routes and contracts used.
- Screenshots of the main states.
- Validation performed vs. assumptions; remaining integration gaps (stubbed routes, fake repositories, open decisions).
