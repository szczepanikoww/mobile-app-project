# Plan: Trainer finance screen (Finanse)

Status: plan only, nothing implemented · 2026-10-28 · local working file (git-ignored)

Sources: `CLAUDE.md` §2–§9, `docs/context.md`, `docs/initial-architecture.md` §2 (G7, G8, G12, G13, G14), §3.3, §4, §8, §10.5, §11, §12, and the Stitch screen "Finanse" (`354405fa…`).

---

## 1. Purpose and scope

**Role:** trainer, viewing their own finances. Requires the trainer role and finance permission (G14); a user who is also a client never sees another trainer's figures.

**Purpose:** show the trainer, for a chosen period, what they delivered, what they're owed and what they've actually received, with the figures kept separate (architecture §8 and G8). Then let them act on what's outstanding.

**In scope**

- The Finance destination of the trainer's top-level navigation.
- Period selector: week / month / year (D1).
- Separate figures (architecture §8):
  1. **Sessions delivered:** count of completed sessions.
  2. **Value of sessions:** sum of session price snapshots.
  3. **Late fees:** cancellation/no-show fee charges.
  4. **Outstanding:** charges past due and not fully allocated (amount + number of clients).
  5. **Money collected:** payments received (online confirmed + cash recorded).
  6. **Payouts:** Stripe payouts to the trainer (status + amount), when Stripe is connected.
- List of recent ledger activity (payments, fees, refunds), with status.
- Entry point to the clients-with-outstanding list.
- Entry point to "Record cash payment" (the flow itself is its own task; see §4).
- Loading, populated, empty, error, offline and "pending confirmation" states.

**Out of scope (do not build here)**

- Card/BLIK/P24 checkout, invoice issuing, KSeF, package/subscription management (G13 unresolved), payout scheduling or instant payouts, tax reports.
- Revenue goals and goal progress bars ("Cel tygodniowy: 4 000 zł" in Stitch). Not in the requirements.
- Period-over-period comparison ("+18%", "+520 zł vs last week"). Optional later (D3).
- Per-weekday revenue bar chart. Optional later (D3).

## 2. What we take from Stitch and what we simplify

| Stitch element | Decision | Reason |
|---|---|---|
| Title "Finanse" + small "PODSUMOWANIE ZYSKÓW" + big "Panel Finansowy" | **One title** in the shell top app bar ("Finance"). No second heading. | `CLAUDE.md` §4–§5 |
| Week / Month / Year segmented control | **Keep**, as shared chips/segmented control | Period is essential to read figures |
| Hero card "Przychód łączny 3 480 zł" (total revenue) + goal bar + comparison | **Replace** with a hero showing **Money collected** for the period. Drop the goal bar; comparison deferred (D3). "Total revenue" is ambiguous: it mixes charges and payments, which §8 requires to stay separate. | Architecture §8, G8 |
| Weekday bar chart with a glowing orange bar | **Defer** (D3). If added, a flat chart via shared chart style, with a text alternative. | `CLAUDE.md` §3, §6 |
| "Metryki operacyjne": 6 tiles (sessions, value, late fees, outstanding, collected, paid out) | **Keep the figures**, as a compact 2-column grid or a single grouped list on one surface. Each has a label, value and a short sublabel. | Matches architecture §8 exactly |
| "Zaległości 240 zł" in red | **Keep**, using the error text/container pair + icon, never color alone. Amount is allowed here: this *is* the authorized finance flow. | `CLAUDE.md` §4, §7 |
| "Ostatnie transakcje" with client, product, method, amount, status icon | **Keep**, as rows on one surface. Show status as label/icon (Confirmed, Pending, Failed, Refunded), not just a small badge. | `CLAUDE.md` §6–§7 |
| Green "+960 zł" amounts | Amount colour neutral; status carries the meaning. Refunds show "−". | Colour not the only signal |
| "Dostępne do natychmiastowej wypłaty · Wypłać" (instant payout) | **Drop the button.** Show payout info read-only (next/last payout from Stripe). Instant payouts are out of scope. | Not in requirements; Stripe Express pays out automatically |
| Chat + bell icons, orange avatar in top bar | Shell-owned global actions only | `CLAUDE.md` §5 |
| Centre "PLAN" FAB + bottom nav | Shell-owned | `CLAUDE.md` §5 |

## 3. Layout (top to bottom)

Gutter 20 dp, section gap 24 dp, card padding 16 dp, radius 16 dp (shared tokens). All amounts formatted from integer minor units + currency.

```
┌ Top app bar (shell) ─ "Finance" ──────────────────── [global actions] ┐
│                                                                       │
│ [ Week | Month | Year ]           27 Oct – 2 Nov 2026                 │
│                                                                       │
│ ┌ Collected this week ─────────────────────────────────────────────┐ │
│ │ 3 240,00 zł                                                       │ │
│ │ ⏳ 120,00 zł pending confirmation                                 │ │
│ └───────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│ Summary                                                               │
│ ┌──────────────────────────┬──────────────────────────┐               │
│ │ Sessions delivered   24  │ Value of sessions 2 880 zł│              │
│ │ Late fees  180 zł (3)    │ ⚠ Outstanding 240 zł (2) >│              │
│ │ Payouts    2 950 zł      │                          │               │
│ │ Last: yesterday ✓        │                          │               │
│ └──────────────────────────┴──────────────────────────┘               │
│                                                                       │
│ [ Record cash payment ]            (primary action; see §4)           │
│                                                                       │
│ Recent activity                                        See all >      │
│  (av) Katarzyna Nowak   8-session package · BLIK   960,00 zł  ✓ Paid  │
│  (av) Tomek Wójcik      Session · Cash             120,00 zł  ✓ Paid  │
│  (av) Piotr Szymański   Late cancellation 50%       60,00 zł  ⏳ Pending│
└───────────────────────────────────────────────────────────────────────┘
```

- **Hero = money collected**, because it is the figure trainers check most and it is unambiguous. Pending (client-side success, no webhook yet) is shown separately, never added to collected.
- **Outstanding** is the only tappable tile: opens the list of clients with overdue charges (finance flow; amounts allowed there).
- A package payment counts once, when paid (architecture §8). The UI shows it as one activity row, not per session.
- **Payouts** tile: hidden or shows "Connect Stripe to receive online payments" when the trainer has no connected account (D4). University phase: Stripe test mode, show a "Test mode" label so no one mistakes figures for real money (D5).
- If the period has multiple currencies (unlikely, PLN only for now), show one row per currency; never sum across currencies.

## 4. Interactions

| Action | Behaviour |
|---|---|
| Change period | Re-fetch summary for that period. Keep the previous figures visible with a loading indicator; period boundaries come from the server in the trainer's IANA zone, with the locale's first day of week. |
| Tap **Outstanding** | Opens the outstanding-charges list (separate screen or part of this task, D2). |
| Tap activity row | Opens the transaction detail (charge, payment, allocations, method, status, invoice reference if any). |
| See all | Full activity list with pagination and filter by type (separate screen, can reuse rows). |
| **Record cash payment** | Opens a full-screen form (longer editing task, `CLAUDE.md` §5): client → amount (numeric input, minor units) → date → optional note. The server allocates to oldest outstanding charges (architecture §3.3 cash example); the UI shows the proposed allocation the server returns before confirmation. Label the confirm button specifically: "Record 180,00 zł from Marek L." Submitting is idempotent (client operation ID); offline → "Not sent", never success. **Likely a separate task;** this plan only reserves the entry point (D6). |
| Pull to refresh | Re-fetch. |

No fee-bearing or destructive actions start from this screen in this scope. Waivers and corrections happen in the session/fee flow (architecture §3.2) and appear here as activity rows.

## 5. States

| State | Content |
|---|---|
| Loading (no cache) | Skeleton for hero, grid and three rows. |
| Populated | As in §3. |
| Empty period | Figures show 0 with "No sessions or payments in this period". Activity list: short explanation. Action: "Open calendar" or switch period. |
| New trainer, no price list / no clients | Explanation + one action (e.g. "Set your prices"), if that route exists (D4). |
| Pending confirmation | Online payments awaiting webhook shown as "Pending" with icon + text; excluded from collected. |
| Failed payment | Row with "Failed" label + error container colours; tap for details. |
| Error (no cache) | "Couldn't load finance data" + Retry. No raw exception text. |
| Offline with cache | Cached figures + banner "Offline · updated 10:42". Record cash payment disabled with explanation. |
| Stripe not connected | Payouts tile replaced by an informational row (D4). |

## 6. Navigation and routes

- Top-level destination: trainer **Finance** (reference grouping Start / Calendar / Clients / Finance; follow the implemented navigation contract).
- Outgoing (placeholders until implemented): outstanding-charges list, transaction detail, activity list, record cash payment, client profile.
- Incoming: from trainer home "Needs attention → overdue payment" goes to the client profile, not here (home shows only the yes/no flag). Deep link to Finance with a preset period is optional.

## 7. Architecture and data

Follows `CLAUDE.md` §8, architecture §8, §10.2 (`payments` feature module, or a separate `finance` module: D7). Nothing below exists yet.

**Presentation**

- `TrainerFinanceRoute` → `TrainerFinanceScreen(state, onAction)` (previewable, no services).
- `TrainerFinanceViewModel` exposes `StateFlow<TrainerFinanceUiState>`, actions: SelectPeriod, Refresh, OpenOutstanding, OpenTransaction, SeeAllActivity, RecordCashPayment.
- UI state: `period`, `periodRange`, `summary` (nullable while loading), `recentActivity`, `isOffline`, `lastUpdated`, `loadStatus`, `isStripeConnected`, `isTestMode`.

**Domain / data**

- `FinanceRepository.getTrainerSummary(period)` → `TrainerFinanceSummary` DTO in `:contract`:
  `sessionsDelivered: Int`, `sessionsValue: Money`, `lateFees: Money` + `lateFeeCount`, `outstanding: Money` + `outstandingClientCount`, `collected: Money`, `pendingConfirmation: Money`, `payouts: PayoutSummary?`, `range: (start, end, zone)`.
- `FinanceRepository.getRecentActivity(limit)` → ledger activity DTOs (type, client, description, method, amount, status, occurredAt).
- **All aggregation on the server** from the append-only ledger (charges, payments, allocations, refunds, adjustments). The app never sums transactions itself or derives "collected" from charges.
- `Money(amountMinor: Long, currency: String)` and one shared money formatter (`CLAUDE.md` §8: no per-view copies). Same for the date-range formatter.
- Cache the last summary per period for offline read-only display.

**Privacy and security**

- Only the trainer's own figures; server checks role + relationship + finance permission. UI hiding is not authorization.
- No health data. Activity descriptions use product/session names only.
- No amounts in push text or logs. Fixtures use synthetic names and amounts.

## 8. Shared foundations this screen needs

Same baseline as trainer home (theme, scaffold, navigation, buttons, list rows, avatar, status chip, banners, loading/empty/error), plus:

| Shared piece | Needed for |
|---|---|
| Segmented control / filter chips | Period selector |
| Metric tile (label, value, sublabel, optional status icon, optional tap) | Summary grid; reusable on client Wallet |
| `Money` model + locale-aware money formatter (`3 240,00 zł` / `PLN 3,240.00`) | All amounts |
| Date-range formatter | Period label |
| Payment/charge status chip mapping (Paid, Pending, Failed, Refunded, Waived) from shared enums | Activity rows; reused by client Wallet |

List these in the handoff as shared changes.

## 9. Open decisions (do not decide silently)

| # | Question | Proposal |
|---|---|---|
| D1 | Periods: week/month/year only, or custom range? Week start Monday? | Week/month/year; week start from locale (Monday for PL) computed on the server in the trainer's zone |
| D2 | Outstanding list: own screen or sheet? | Own screen (it lists many clients and links to cash recording) |
| D3 | Add period comparison and per-day chart later? | Not in v1; revisit after core figures work |
| D4 | What does Finance show before Stripe Connect onboarding? | Cash-only figures + informational "Connect Stripe" row; onboarding is a separate task |
| D5 | Visible "Test mode" label during the university phase? | Yes |
| D6 | Is "Record cash payment" part of this task? | Entry point only; the form is a separate task (it is in the phase-1 slice) |
| D7 | Module placement: `payments` vs new `finance` feature | Team decision; follow existing structure once code exists |
| G8 | Exact definitions of each figure | Use architecture §8 list; confirm "sessions value" counts completed sessions only, and whether no-shows count |
| G12 / G13 / G14 | Seller identity, package semantics, trainer visibility of amounts | Not decided here; packages show only as paid activity rows |

## 10. Implementation steps

1. Confirm scope and D1–D7, G8 definitions with the team.
2. Ensure the shared baseline from trainer home exists; add the pieces in §8.
3. Define `Money`, `TrainerFinanceSummary`, activity DTOs and status enums in the shared contract (or reuse if already present).
4. ViewModel with a fake `FinanceRepository` and deterministic fixtures (separate from production code).
5. `TrainerFinanceScreen` with previews: populated, empty period, pending, failed row, outstanding > 0, no Stripe, offline, loading, error, long Polish names, font scale 1.5×, 360 dp width.
6. String resources (PL, EN), money/date formatting through shared formatters.
7. Integrate the route into trainer navigation; stub outgoing destinations.
8. Tests and validation (§11).

## 11. Validation

- **Unit tests:** period switching (state keeps previous data while loading); pending payments never included in collected; formatter output for PL and EN with minor units (e.g. `12345` → `123,45 zł`); multiple currencies not summed; offline disables cash recording; empty period state.
- **Contract test (when the backend exists):** summary figures match the ledger for the acceptance cases in architecture §11 (duplicate/out-of-order webhooks settle once; cash 180 zł example allocates 60 + 120).
- **UI checks:** compare with trainer home and another screen for colour, type, spacing, shapes; outstanding uses icon + label, not just red; TalkBack reads each tile as "label, value"; contrast ≥ 4.5:1; 48×48 dp targets.
- Run only the build/lint/tests the repo actually configures; report what was run.

## 12. Handoff checklist

- Role/view implemented, shared changes (theme, components, `Money`, formatters, navigation), routes and contracts used.
- Screenshots of the main states.
- Verified behaviour vs. assumptions; remaining gaps (fake repository, stubbed routes, open decisions, figure definitions pending G8).
