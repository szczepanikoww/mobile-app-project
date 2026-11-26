# Trainer redesign: working decisions

Status: agreed 2026-11-26; implemented on branch `redesign_trainer` (see "Implementation status" at the end).
Scope: trainer role only. Goal: fewer features, each one simple and working well.

## Navigation

Bottom tabs (exactly four):

| Tab | Purpose |
| --- | --- |
| Start | What's next and what needs the trainer's reaction. |
| Kalendarz | Full schedule. |
| Podopieczni | Client list and client card. |
| Plany | Everything about training plans, plus the exercise library with technique videos. |

- The **Finance tab is removed**. Payments live only as a widget on Start.
- **Plus (FAB)** stays and does the same thing on every tab: add a session, block time, invite a client. It never creates a plan; the Plany tab has its own "new plan" button.
- **Plus menu order:** Nowa sesja, Zablokuj czas, Dodaj podopiecznego (last). Working hours moved to the profile.
- **Dodaj podopiecznego:** share an invitation link (system share sheet). No QR code for now.
- **Chat** stays as the icon in the top bar (as now). Technique videos sent by clients for review are watched in chat.
- **Technique videos in Plany:** every exercise in the base library can have the trainer's own video attached; the trainer can also add their own exercises with video.

## Payment assumption

All sessions are paid by card at booking. A session cannot be booked without payment, so the app never shows paid/unpaid status and the "overdue payment" flag is no longer needed. (This conflicts with the overdue flag described in `CLAUDE.md` §7; record it as a team decision when implementing.)

## Start screen

Order, top to bottom:

1. **Next session** card: time, client, place. Tap opens session details. When there is no more session today, show "Brak sesji na dziś" and the nearest upcoming one.
2. **Wymaga reakcji** widget: count of pending booking and reschedule requests. Tap opens a list; each item shows the change (e.g. "wt 14:00 → czw 16:00") with Akceptuj / Odrzuć. When empty it stays visible with "Brak próśb" (layout does not jump).
3. **Płatności w tym tygodniu** widget: weekly sum. Tap opens a list of entries: date, client, amount. No payment method, no charts or statistics.
4. **Historia sesji** widget: shows how many past sessions still need attendance marked (e.g. "2 do oznaczenia"). Tap opens past sessions only, by date. Unmarked sessions have ✓ Odbyła się / ✗ Nieobecność; marked ones show their status, which can still be changed.

Statuses in history: only **odbyła się** or **nieobecność**.

Removed from Start: current metrics and tiles, the notifications screen, shortcuts that duplicate the plus.

## Visual direction

Accepted 2026-11-26: more contrast and character than the current flat grey boxes.

- **Hero card** for the most important item on a screen (Start: next session): strong teal fill (`#0F766E`), white text, large time, client photo, two soft accent circles in the background. One hero per screen.
- **Real profile photos** of trainer and clients (initials fallback) on Start, Kalendarz and Podopieczni.
- **Coloured icon tiles** in widgets: icon on a pastel container (amber = requests, green = payments, teal = history). Colour always paired with icon and text.
- **Large numbers** for key values (sums, counts).
- **White borderless cards** with 20–24 dp radius on the light grey background, instead of flat grey outlined boxes.
- **Bottom bar:** pill highlight under the active tab; round plus raised above the bar.
- **Plany:** exercise photos and video thumbnails.
- No large stock photos on routine screens.

This deviates from current `CLAUDE.md` rules (flat surfaces, no ornaments, 16 dp card radius) and changes the shared theme for client and guest too. Record it in `CLAUDE.md` and the shared theme when implementing, not per screen.

## Podopieczni

- Tabs: **Podopieczni** and **Archiwum**. All payment-related UI (overdue filter/flag) removed.
- **Zaproś podopiecznego** button stays in this view; it does the same as the plus option (share a permanent trainer link, which the trainer can revoke and regenerate).
- Restyle to the accepted visual direction (photos, white rounded cards).
- Archiving is manual (**Archiwizuj** on the client card) and reversible (**Przywróć**).

### Client card

- Client information at the top.
- Actions: **chat** with this client, **add session** (prefilled with the client), **assign a default plan**.
- **Gyms the client attends:** read from the database (shown, not edited by the trainer).
- At the bottom: **session history including future sessions**. No separate "next session" block.

### Session detail (a planned session)

- Assign a training plan to the session. A new session automatically gets a copy of the client's default plan. The plan is a starting point: the trainer then writes it out in detail for this client and this session (sets, reps, load), without changing the template.
- Comments field for the session.

## Plany

Two entry widgets:

1. **Moje plany:** list of plan names + **Dodaj plan**. The configurator: add exercises from the library, set sets, reps, rest and an optional default load; save and name the plan. **Decided:** build on matbem's branch `origin/workout-ui-refactor-aws-setup` (2026-11-25, `shared/feature/workout-builder`: exercise cards, settings sheet, rest sheet); merge it first.
2. **Biblioteka:** tabs **Baza ćwiczeń** and **Moje ćwiczenia**.
   - Moje ćwiczenia: plus to create an exercise (name, default reps, description, optional video).
   - Exercise from the base: the trainer can add their own note ("Od trenera") and their own video, which becomes the main media for that exercise in place of the base images. (Base exercises currently have images and `demoMediaId`, no animations.)

## Profil trenera (avatar in the top bar)

Settings screen removed; only the profile remains:

- First and last name, contact details, description, photos about the trainer (a gallery, not only the profile photo).
- **Moje siłownie:** the trainer picks their gyms from the database list; they are suggested as the place when adding a session.
- **Dostępność / godziny pracy** (moved here from the plus).
- No payment settings.
- Log out stays at the bottom (assumed).

## Next

## Kalendarz

Visual direction accepted (mockup 2026-11-26). Same structure as now (month, week strip, day/week toggle, session list), but each entry type looks distinct:

- Week strip: selected day as a dark teal pill, dots for sessions (amber dot = request), weekend greyed.
- Day header with session count and working hours.
- Finished session dimmed with green ✓; next session as the teal hero card; regular session as a white card with photo and place.
- Request: amber, dashed outline. Blocked time: grey stripes with a lock. Free gaps: dashed "Wolne 10:00–12:00", tap to add a session there. Red "now" line.

Decided: Akceptuj/Odrzuć also inline in the calendar; week view is a list of days with session cards, not an hour grid.

## Implementation status (branch `redesign_trainer`)

Merged first: `origin/workout-ui-refactor-aws-setup` (matbem, plan editor) and `demo-fixes` (plan assignment, plan pull, client auto-refresh; its dev-token session persistence was left out because the Firebase sign-in already restores sessions).

Implemented:

| Area | Where |
| --- | --- |
| Four tabs, plus menu (session, block time, invite) | `pl/trainerapp/shell/AppShell.kt`, `pl/trainer/ui/TrainerApp.kt` |
| Visual primitives (hero, white cards, icon tiles, portraits, nav pill) | `pl/trainer/ui/Visuals.kt`, `SharedBottomBar.kt` |
| Start + requests, payments, history lists | `pl/trainer/ui/StartScreen.kt` |
| Calendar restyle, inline accept/reject, free gaps, now line | `pl/trainer/ui/CalendarScreen.kt` |
| Trainees, archive/restore, client card, session details (attendance, plan, comment) | `ClientsScreen.kt`, `DetailScreens.kt` |
| Invite sheet with the permanent link (share, copy, new link) | `TrainerApp.kt`, `RemoteInviteLinks` / `DemoInviteLinks` |
| Plans tab, plan section (assign default / session plan, write out for a session) | `shared/app/.../TrainerPlans.kt` |
| Profile: data, gyms, working hours, log out | `pl/trainerapp/feature/trainerprofile/` |
| Server: archive/restore (`archived_at`), permanent invites, session comments | `V7__trainer_redesign.sql`, `TrainerClientsService`, `TrainerSessions` |

Server contract changes: `ClientSummaryDto.archived`, `TrainerDashboardDto.sessionComments`, `SessionCommentRequest`, `CreateInviteRequest.permanent()`; routes `POST trainer/clients/{id}/archive|restore`, `PUT trainer/sessions/{id}/comment`.

Known gaps:

- The client side of the invite link (a screen that opens `InviteBaseUrl` and redeems the code) does not exist; `InviteBaseUrl` is a placeholder domain.
- A plan assigned to a session also becomes the client's current plan: the server keeps one ACTIVE plan per client.
- Attendance cannot be changed once marked (the server only allows it from CONFIRMED).
- The trainer's own note and video on a base exercise, and the photo gallery in the profile, need server storage that does not exist yet.
- The client's gyms in the client card need data the server does not have yet.
