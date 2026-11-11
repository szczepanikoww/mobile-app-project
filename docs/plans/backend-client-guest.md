# Plan: backend for the client and guest views

Status: draft v0.2 · 2026-11-11 · roles: **guest** (signed out) and **client** · based on [initial-architecture.md](../initial-architecture.md) v0.3, [database-design.md](../database-design.md) v0.1 with its DDL [database/schema-draft.sql](../database/schema-draft.sql), and the repository interfaces that exist today

The client and guest views run entirely on demo sources (`DemoGuestCatalogRepository`, `DemoAuthRepository`, `DemoSchedulingStore`, the demo wallet, fake plan/media remotes). This plan describes the server those views need, and how the app swaps each demo source for a remote one **without changing the screens**.

**What changed in v0.2:** the server is now designed against the agreed database draft, not a database of its own. Every endpoint names the tables it reads and writes, which constraint protects it, and what runs in one transaction. Where the app needs data the schema does not have, it is listed as a **schema gap** (§10) with a proposed migration. Nothing here edits `schema-draft.sql` silently. Choices marked *(proposal)* and the items in §12 still need team agreement.

Out of scope here: trainer-only screens (except the few trainer transitions the client flow cannot be tested without, §8.7), the operator panel, invoices (D2), package purchase (D1), Health Connect, iOS.

---

## 1. Screens, interfaces and database schemas

The repository interfaces are the app's contract. The server is shaped so that each method maps to one endpoint, and each endpoint belongs to the module that owns the schema it writes.

| View | Role | App interface (today's demo source) | Module / DB schema written |
|---|---|---|---|
| Log in / register, Google button | Guest | `AuthRepository` (`DemoAuthRepository`) | Identity → `identity`, `coaching.consent_record` |
| Start, Trenerzy, profil trenera, Siłownie, siłownia | Guest | `GuestCatalogRepository` (`DemoGuestCatalogRepository`) | Catalogue → `catalogue` (read only) |
| Login-required sheet → booking sheet | Guest → client | `GuestIntent.RequestSession` (client side only) | — |
| B0 client shell, role switch | Client | `AuthRepository.switchRole` | Identity (read only) |
| B1 Start – klient | Client | `ClientScheduleRepository.load`, `TodayWorkoutSource` | `scheduling`, `training` |
| B2 Kalendarz, session details, requests, cancellation | Client | `ClientScheduleRepository` (all methods) | `scheduling`, `billing` (quotes, fee decisions) |
| B3 Portfel | Client | `WalletRepository` | `billing` |
| A1 live workout, A2 summary | Client | `WorkoutRepository` (local) + sync queue, `TodayWorkoutSource` | `training` |
| A3 Postępy | Client | `WorkoutRepository.history`, `MeasurementRepository` | `training` |
| Technique video | Client | `MediaRemoteDataSource`, `TechniqueVideoOutbox` | `media`, `messaging`, `training.set_log` |
| A6 Czat – klient | Client | `ClientChatRepository`, `ChatMediaSource` | `messaging`, `media` |

Where they live: `shared/feature/guest/.../model/Auth.kt` and `GuestModels.kt`, `shared/src/commonMain/kotlin/pl/trainer/domain/ClientScheduling.kt`, `shared/domain/.../workout/WorkoutRepository.kt`, `shared/data/.../media/MediaRemoteDataSource.kt`, `shared/src/commonMain/kotlin/pl/trainerapp/feature/chat/ChatContract.kt`.

## 2. Technical choices

Following architecture §5–§6 and database-design §1 and §7. Nothing here is implemented yet.

| Concern | Choice | Status |
|---|---|---|
| Server | **Ktor server**, one modular monolith + a worker process from the same codebase | architecture §5 |
| Placement | New Gradle module **`:server`** in this repository, depending on `:contract` (JVM-compatible, no Android deps), so DTOs, `FeeCalculator`, `PolicyRules` and `SearchText` are the same code on both sides | *(proposal)* |
| Database | **PostgreSQL 16+**. Extensions `btree_gist`, `citext`, `pg_trgm` (in V1). | database-design |
| Schema ownership | **Flyway owns the schema.** `schema-draft.sql` becomes `V1__initial_schema.sql` unchanged; every schema gap in §10 is a later `V2__…` migration reviewed like any other change. `flyway.schemas = platform, identity, catalogue, coaching, scheduling, training, messaging, media, billing` (`platform` first, so the history table lives there). | **decided** (DB1, DB2) |
| Data access | **Exposed**, DSL only. Table objects use qualified names (`object Sessions : Table("scheduling.session")`). `SchemaUtils.create` is never called. | **decided** (DB1) |
| Module boundary | One Kotlin package per DB schema (`server/identity`, `server/catalogue`, `server/scheduling`, …). A module's Exposed table objects are `internal`; other modules call its service interface. Reads across schemas for *display* (e.g. a trainer's name in the client schedule) go through a read-model query in the owning module. | *(proposal)*, implements database-design decision 1 |
| DB role | The app connects as a non-superuser role with DML rights only; Flyway runs as a separate owner role. Otherwise the `forbid_update` triggers and constraints could be dropped by the app. | database-design §4 |
| IDs | **UUIDv7** generated in Kotlin and in the app (DB7). Rows created offline keep the phone's id. | *(proposal, DB7)* |
| Auth | External identity provider: Firebase Auth recommended. The API verifies the ID token and maps its subject to `identity.app_user.auth_subject`. | *(proposal, G-auth)* |
| Files | S3-compatible private storage; MinIO locally. Object key = `media_asset.storage_key` (`media/<id>`). | *(proposal)* |
| Jobs | `platform.outbox_event` written in the business transaction, `platform.job` polled with `SELECT … FOR UPDATE SKIP LOCKED` by the worker | database-design |
| Payments | `PaymentProvider` interface; `FakePaymentProvider` (`payment.provider = 'fake'`, method `TEST_MODE`) first, then Stripe test mode (`'stripe'`) | *(proposal)* |
| Push | FCM data messages ("refresh" only, never content); tokens in `platform.push_token` | architecture |
| Local dev | `docker-compose.yml`: `postgres:16-alpine` (the image the DDL was verified on), MinIO, Firebase Auth emulator. Flyway migrates on server start in the `local` profile only. | *(proposal)* |

Note: database-design.md refers to `infrastructure-aws.md` (RDS, S3). That file is not in the repository yet, so hosting stays an open item (G-host).

## 3. Database access rules for the server code

These apply to every module so the guarantees in the schema are actually used, not bypassed.

1. **Every write is one transaction** that includes its `session_event` / `outbox_event` / `job` rows. No "write, then publish".
2. **Constraint violations are business results, not 500s.** One mapper per SQLSTATE:

   | SQLSTATE | Raised by (examples) | API result |
   |---|---|---|
   | `23P01` exclusion violation | `session_no_trainer_overlap`, `session_no_client_overlap` | `409 SLOT_TAKEN` |
   | `23505` unique violation on `operation_id`, message `id` or media `id` | idempotent retries | return the **existing** row (§4 idempotency) |
   | `23505` on `reschedule_one_open`, `trainer_client_open_pair`, `assigned_plan_one_active` | a second open request / relationship / plan | `409 CONFLICT` |
   | `23514` check violation | value outside the DB rules (e.g. media > 100 MB) | `422 VALIDATION` — but the API validates first; a `23514` reaching the mapper is logged as a bug |
   | `P0001` from `forbid_update` | an attempt to edit an immutable row | `500`, always a bug |
   | `40001` serialization failure / `40P01` deadlock | concurrent transactions | retried up to 3 times, then `409 CONFLICT` |

3. **Lock order** for multi-row decisions (prevents deadlocks): `identity.trainer_profile` row → `scheduling.session` row(s) → `billing.charge` rows. Database-design §3 requires the trainer lock before any availability check, because availability rules and `calendar_block` cannot be part of an exclusion constraint.
4. **`revision` is checked in SQL**: `UPDATE scheduling.session SET …, revision = revision + 1 WHERE id = :id AND revision = :rev`. 0 rows → `409 REVISION_CONFLICT`.
5. **Enum mapping** happens in one place per enum: DB `UPPER_SNAKE` text ↔ `:contract` enum ↔ app domain enum. No string literals in queries outside these mappers.
6. **Derived data is read from the views**, never recomputed differently in Kotlin: `billing.charge_balance`, `billing.relationship_overdue`, `billing.package_credit_balance`, `training.personal_record`.
7. **Timestamps the server owns** (`requested_at`, `received_at`, `updated_at`, `settled_at`) are set from the database clock (`now()`), never from the request.
8. **`updated_at` on synced tables** (`workout_log`, `workout_exercise`, `set_log`, `body_measurement`, `exercise`) is set by the server on every write, including tombstones (`deleted_at`).

## 4. API conventions

- **Base path** `/v1`. Public catalogue under `/v1/public/**` (no token). Client endpoints under `/v1/client/**` (token required, caller must have an `identity.client_profile` row). Shared endpoints (`/v1/me`, `/v1/media`, `/v1/policies`, `/v1/conversations`) check access per resource.
- **Identity comes from the token, never from the path.** Each request resolves `auth_subject → app_user.id`; `/v1/client/**` additionally requires `client_profile.user_id = app_user.id`. A deleted user (`deleted_at` set) gets `401`.
- **Money:** `{ "minor": 12000, "currency": "PLN" }` (`contract.money.Money` ↔ `bigint` + `char(3)`).
- **Time:** ISO-8601 UTC instants plus an IANA `timeZone` where the DB stores one (`session.time_zone`, `workout_log.time_zone`, `gym.time_zone`). Reads that drive "today/next" include **`serverNow`** (DB `now()` of the read transaction).
- **Optimistic concurrency:** every mutating session call carries the `revision` it was based on (rule 4 above).
- **Idempotency** uses the schema's keys, not a separate mechanism, wherever a single row is created:

  | Operation | Key in the DB | Retry returns |
  |---|---|---|
  | booking request | `scheduling.session.operation_id` | the same session |
  | reschedule request | `scheduling.reschedule_request.operation_id` | the same request |
  | cancel | `billing.fee_decision.operation_id` (+ `charge.operation_id` derived from it) | the cancelled session |
  | pay | `billing.payment.operation_id` | the same pending/settled payment |
  | chat message | `messaging.message.id` (client UUID) | the same message and `seq` |
  | media upload session | `media.media_asset.id` (client UUID) | the same upload URL / status |
  | workout logs, sets, measurements | client UUID primary keys + `revision` | per-set result (§8.4) |
  | anything else (e.g. account creation, sync batch response) | `platform.idempotency_key (user_id, operation_id)` with `request_hash`; a different body with the same key → `422 IDEMPOTENCY_MISMATCH` | the stored response |

  The app sends the key as `Idempotency-Key` header or `operationId` in the body; the server copies it into the column.
- **Errors** are one JSON shape `{ "code": "...", "message": "..." }` (message is for logs, never shown):

| HTTP / code | App result |
|---|---|
| no connection / timeout | `Failure.Offline`, `AuthResult.Offline`, `CatalogOfflineException` |
| `401` | sign-out, back to Auth |
| `403` / `404` on a private resource | `Failure.Denied` (404 when existence itself is private) |
| `409 REVISION_CONFLICT`, `SLOT_TAKEN`, `POLICY_OUTDATED`, `CONFLICT` | `Failure.Conflict` |
| `410 QUOTE_EXPIRED` | `Failure.QuoteExpired` |
| `422 VALIDATION` (with field codes) | `Failure.InvalidInput` |
| `5xx` | `Failure.Load` / `Failure.Save`, `AuthResult.Unavailable` |

- **No private data in logs.** Request logging prints method, route template, status and duration only. Exposed SQL logging stays **off** outside the `local` profile (statements contain values such as messages or measurements).

## 5. Guest: public catalogue

Backs `GuestCatalogRepository`. No token. Reads **only** `catalogue.*` tables, which contain nothing private (database-design §4 "Public vs private"). The public query module has no access to the `identity` Exposed tables at all, so it cannot join into e-mails or client data by mistake.

| Method | Endpoint | Returns | Tables |
|---|---|---|---|
| `home()` | `GET /v1/public/home` | `GuestHome` | `trainer_listing`, `offer`, `gym`, `trainer_gym` |
| `trainers(query)` | `GET /v1/public/trainers?text=&goal=&gymId=&price=&district=&availability=&sort=` | `List<TrainerSummary>` | + `public_trainer_availability` (gap G-3) |
| `filterOptions()` | `GET /v1/public/trainers/filters` | `FilterOptions` | `gym` (public), distinct `gym.district` |
| `trainer(id)` | `GET /v1/public/trainers/{id}` | `TrainerProfile` or `404` → `null` | + `policy_version`, `policy_tier`, listing media |
| `gyms(query)` | `GET /v1/public/gyms?text=&district=` | `List<GymSummary>` | `gym` |
| `gym(id)` | `GET /v1/public/gyms/{id}` | `GymDetail` or `404` → `null` | `gym`, `trainer_gym`, `trainer_listing` |

Mapping to the database:

- **Visibility:** a trainer appears only when `trainer_listing.is_public = true`; a gym only when `gym.is_public = true`; an offer only when `offer.is_public AND archived_at IS NULL`. A non-public trainer id returns `404`, never `403`.
- **Name:** the guest model needs `TrainerSummary.name`, but the name lives in `identity.app_user.display_name`, next to the e-mail. Proposal: a public `display_name` column on `trainer_listing` (gap G-1), written by the Identity module when the trainer publishes or renames.
- **Tags / goals:** `tags` ← `trainer_listing.specialties`. `goals` (enum `Goal`) has no column yet (gap G-2).
- **Gyms and districts:** `trainer_gym` gives `gymIds`; trainer `districts` = districts of those gyms. `gym.district` and the main-gym flag are missing (gaps G-4, G-5).
- **Availability buckets** (`Morning/Afternoon/Evening/Weekend`) are derived from `scheduling.availability_rule` in local time. Because the public module must not read `scheduling`, the Scheduling module exposes only the buckets through a view `catalogue.public_trainer_availability(trainer_id, bucket)` (gap G-3).
- **Prices:** `Offer.title` ← `offer.name`, `durationMinutes` ← `duration_minutes`, `sessionCount` ← `sessions_per_period`, `seller` ← `offer.seller` (only `TRAINER` in the DB; the app's `Seller.Gym` is never produced). `startingPrice` = cheapest public `SINGLE_SESSION` offer. `description`, `highlighted`, `validityText` have no columns: `null` / `false` until gap G-6 is decided. `validityText` stays `null` (DB6: package credits never expire).
- **Policy on the profile:** `PublicCancellationPolicy` = the trainer's highest `policy_version.version` with its `policy_tier` rows, rendered with `PolicyRules` from `:contract`. Never a hard-coded rule.
- **Ratings, "Zweryfikowany", years of experience, client-count label:** no columns in the schema and no decision (guest D1/D2). The API returns `rating = null`, `verified = false`, `yearsOfExperience = null`, `clientCountLabel = null`, and the sort `Rating` falls back to price. The client count is **not** derived from `trainer_client` (it would leak relationship data).
- **Gym detail:** `address` ← `street, postal_code city`; `weeklyHours` ← `gym.opening_hours` JSON; `hoursSummary` formatted on the server per locale; `timeZone` ← `gym.time_zone`. `amenities` and `transitNote` have no columns (gap G-7). `distanceMeters = null` until location is decided (D6); `latitude/longitude` exist for later.
- **Public media:** trainer profile media has no table linking it to the listing (gap G-8). Until then `media = []`. When added, only `media_asset.state = 'READY'` gets a `playbackUrl`.
- **Search** mirrors `CatalogSearch.kt`: text is folded with `contract.text.SearchText`. Proposal: a stored `search_text` column on `trainer_listing` and `gym` (filled on write, like `training.exercise_name.search_text`) with a `pg_trgm` GIN index (part of gaps G-1/G-4). Price filter uses the fixed `PriceRange` buckets on `startingPrice`.
- **Caching:** `Cache-Control: public, max-age=60` and an `ETag`; the app keeps the last result for offline. Rate limiting per IP on `/v1/public/**`.

## 6. Guest → client: identity and registration

Backs `AuthRepository`. Credentials go to the identity provider; the API never sees a password.

| Step | App | Server and tables |
|---|---|---|
| Log in | IdP sign-in → ID token | `GET /v1/me` → `AuthSession` from `app_user` + presence of `trainer_profile` / `client_profile` rows (= roles, G4) |
| Register | IdP `createUser` (→ `AccountExists` on duplicate), then `POST /v1/accounts { operationId, name, startingRole, locale, timeZone, acceptedTermsVersion, acceptedPrivacyVersion }` | One transaction: `app_user (auth_subject, email, display_name, locale, time_zone)`, the role row (`client_profile` or `trainer_profile`), and append-only `coaching.consent_record` rows `(TERMS, GRANTED, <version>)` and `(PRIVACY_POLICY, GRANTED, <version>)`. Idempotent: `auth_subject` is `UNIQUE`, so a retry returns the existing account. |
| Google | IdP Google sign-in | `GET /v1/me`; `404 NO_ACCOUNT` → the app finishes with `POST /v1/accounts` |
| Add the other role later | — | `POST /v1/me/roles/client` inserts `client_profile` (idempotent by PK) |
| Switch role | `switchRole(role)` | Local only. No "active role" column exists; every `/v1/client/**` call simply requires the `client_profile` row. |
| Push token | after sign-in | `PUT /v1/me/push-tokens/{token} { platform }` → upsert `platform.push_token` (token is the PK, so a token moving to another account is re-assigned). Push locale = `app_user.locale`. |
| Log out | IdP sign-out, clear caches | `DELETE /v1/me/push-tokens/{token}` |
| Locale change | settings | `PATCH /v1/me { locale, timeZone }` (`locale` limited to `pl`/`en` by the DB) |
| Account deletion (G9) | later slice | `DELETE /v1/me` sets `app_user.deleted_at`, anonymises `display_name`/`email`, revokes `access_grant`s and push tokens; ledger rows stay (no cascades, database-design §4) |

`InvalidCredentials` never says whether the e-mail exists. While auth is mocked locally, `auth_subject` holds the seeded demo id (column comment in the DDL).

**`GuestIntent.RequestSession(trainerId)`** stays client-side: after sign-in the shell opens the booking sheet, and nothing is written until the client sends a request (§8.1).

## 7. Read models the client endpoints share

- **`TrainerRef`** (id, name, initials): `trainer_profile.user_id` + `app_user.display_name`. `TrainerRef.id` is the trainer's **user id**; the relationship id stays internal to the server.
- **Caller's relationships:** `identity.trainer_client WHERE client_id = :me`. `ACTIVE` grants full access; `PENDING` (created by a first booking request, R1) allows only that request's session; `ENDED` gives read-only history (R2).

## 8. Client endpoints

### 8.1 Scheduling (B1, B2)

Backs `ClientScheduleRepository`. The server is the only place that changes `scheduling.session.status`.

**Status mapping** (one mapper; the DB uses the 8-state set from `pl/trainer/domain/Models.kt`, conflict C1):

| `session.status` | App `SessionStatus` | Note |
|---|---|---|
| `REQUESTED` | `Requested` | booking request; blocks nothing (DB4) |
| `CONFIRMED` | `Confirmed` | + open `reschedule_request` → `Session.proposal` (`proposer_role` → `ProposalAuthor`) |
| `REJECTED`, `EXPIRED` | `Rejected`, `Expired` | |
| `CANCELLED_BY_CLIENT`, `CANCELLED_BY_TRAINER` | same | a withdrawn booking request is `CANCELLED_BY_CLIENT`, as in the demo store |
| `COMPLETED`, `NO_SHOW` | same | written by trainer check-in (`scheduling.attendance`) |

| Method | Endpoint | Database work |
|---|---|---|
| `load()` | `GET /v1/client/schedule?from=&to=` | `session WHERE client_id = :me AND starts_at` in window (index `session_client_time`; default −30/+90 days), left join the open `reschedule_request` (`status IN ('PENDING','COUNTER_PROPOSED')`), `TrainerRef`s, `serverNow`. Payment shown from the snapshot (`payment_kind`, `price_minor`, `currency`). |
| `bookingOptions(trainerId)` | `GET /v1/client/trainers/{trainerId}/booking-options` | Trainer must be public or related. Current `policy_version` (highest `version`), price = `client_price_override` for an existing relationship, else the cheapest public `SINGLE_SESSION` offer; places = names of `trainer_gym` gyms; time zone = the trainer's `app_user.time_zone`. |
| `availableSlots(trainerId, date, excludeSessionId)` | `GET /v1/client/trainers/{trainerId}/slots?date=&excludeSessionId=` | `availability_rule` for that ISO weekday and `valid_from/valid_to`, expanded in the rule's `time_zone` (DST-safe), minus `calendar_block` (`deleted_at IS NULL`; **only times, never `title` or `kind`**), minus sessions in `CONFIRMED/COMPLETED/NO_SHOW` using `[starts_at, blocked_until)`, minus the excluded session. Slot length = offer `duration_minutes`. |
| `requestBooking(req)` | `POST /v1/client/booking-requests` + `Idempotency-Key` | **One transaction:** (1) `409 POLICY_OUTDATED` if `acceptedPolicyVersionId` is not the trainer's highest version; (2) find the open `trainer_client`, or insert one `PENDING / CLIENT_REQUEST` (R1; `trainer_client_open_pair` makes concurrent first requests safe — on `23505` re-read it); (3) insert `session` `REQUESTED` with `operation_id = key`, price/currency/`payment_kind`/`offer_id` snapshots, `policy_version_id`, `policy_accepted_at = now()`, `blocked_until = ends_at + session_buffer_minutes`, `time_zone`, `gym_id` or `place_note`, `created_by = me`; (4) `session_event REQUESTED`; (5) `job` `request-expiry:<session>` at the R4 time; (6) `outbox_event session.requested`. Retry with the same key → the same session. |
| `withdrawRequest(id, rev)` | `POST /v1/client/sessions/{id}/withdraw { revision }` | `REQUESTED` → `CANCELLED_BY_CLIENT`, `closed_at`, `session_event CANCELLED`, cancel the expiry job. Or, for the client's own open reschedule: `reschedule_request.status = 'WITHDRAWN'` and the session's `revision + 1`. |
| `previewRescheduleFee(id, rev)` | `GET /v1/client/sessions/{id}/reschedule-fee?revision=` | `FeeCalculator` with the session's `policy_version_id` (or the override); `204` when free or `late_reschedule_is_cancel = false`. Nothing is stored. |
| `requestReschedule(req)` | `POST /v1/client/sessions/{id}/reschedule-requests { revision, start, end, operationId }` | Session must be `CONFIRMED` at `revision`. Insert `reschedule_request` (`PENDING`, `proposer_role = 'CLIENT'`, `base_revision`, `received_at = now()`), plus a `billing.fee_quote` `LATE_RESCHEDULE` linked via `fee_quote_id` when the policy proposes a fee. `reschedule_one_open` → `409` if one is already open. Session `revision + 1`; **status and time stay as they are.** The trainer decides the fee (full waiver only, D8). |
| `answerProposal(id, rev, accept)` | `POST /v1/client/sessions/{id}/proposal/answer { revision, accept }` | Reject: `reschedule_request.status = 'REJECTED'`, `decided_by/at`, session `revision + 1`. **Accept** (database-design §3, one transaction): lock `trainer_profile` → lock `session FOR UPDATE`, check `revision` and `base_revision` → check the new time against `availability_rule` and `calendar_block` in code → `UPDATE session SET starts_at, ends_at, blocked_until, revision + 1` (the exclusion constraints reject a clash: `23P01` → `409 SLOT_TAKEN`, original time unchanged) → request `ACCEPTED` → `session_event MOVED` with `old_starts_at/old_ends_at` → cancel reminder jobs of the old revision, create new ones → outbox. A trainer's proposal never creates a fee for the client (G11). Package sessions keep their `credit_reservation` (it is keyed by `session_id`, so nothing moves). |
| `quoteCancellation(id, rev)` | `POST /v1/client/sessions/{id}/cancellation-quotes { revision }` | Inputs: the session's `policy_version_id` with its tiers, `catalogue.client_policy_override` (`EXEMPT` → fee 0; `OTHER_POLICY` → that version), free late cancellations used **this month in the session's `time_zone`** = count of `fee_decision.used_free_cancellation` for the relationship, `received_at = now()`. `FeeCalculator` from `:contract` computes the fee. Insert `billing.fee_quote` (`CLIENT_CANCEL`, `session_revision`, `tier_hours_before`, `fee_percent`, `amount_minor`, `uses_free_cancellation`, `expires_at = now() + R8`). |
| `cancel(id, rev, quoteId)` | `POST /v1/client/sessions/{id}/cancel { revision, quoteId, operationId }` | **One transaction:** lock session; quote must belong to this session and `session_revision = revision` (`409`) and `expires_at > now()` (`410`). Session → `CANCELLED_BY_CLIENT`, `closed_at`, `revision + 1`, `session_event CANCELLED`; insert `fee_decision` (`operation_id`, copied from the quote, `decided_by = null` = automatic). Then by `payment_kind`: **ONLINE/CASH** with fee > 0 → `charge LATE_CANCELLATION_FEE` (`settlement` = `ONLINE`/`CASH`, `fee_decision_id`, `due_at` per R10); **PACKAGE** → the open `credit_reservation` becomes `CONSUMED` with `consumed_units = fee_percent` (or `RELEASED` when 0), unless `package_fee_mode = 'CHARGE_MONEY'`, which writes a money charge instead. Cancel reminder jobs; outbox `session.cancelled`. |
| `policy(versionId)` | `GET /v1/policies/{versionId}` | Allowed when the caller has a session with that `policy_version_id`, or it is the current version of a public trainer. Immutable (trigger) → `Cache-Control: max-age=31536000, immutable`. |
| `updates` | §8.6 | — |

Worker jobs (all in `platform.job`, `dedupe_key` prevents duplicates):

- `request-expiry:<session>` → `REQUESTED` → `EXPIRED` with `session_event` `actor_id = null`; also expires open `reschedule_request`s past their time.
- `reminder:<session>:<revision>:24h` → before sending, the job checks the session still has that revision and is `CONFIRMED`, so a moved session never gets the old reminder (acceptance test 7).

### 8.2 Wallet and payments (B3)

Backs `WalletRepository`. The ledger is append-only (`forbid_update` on `charge`, `allocation`, `adjustment`, `fee_decision`).

| Method | Endpoint | Database work |
|---|---|---|
| `load()` | `GET /v1/client/wallet` | For every relationship of the caller: `package_agreement` + `package_credit_balance`; `charge` + `charge_balance`; `payment` + `refund`; `serverNow`. |
| `pay(chargeId, method, key)` | `POST /v1/client/charges/{id}/payments { method }` + `Idempotency-Key` | **One transaction:** lock the `charge` row; it must be the caller's, `settlement = 'ONLINE'`, `open_minor > 0` in `charge_balance`, and have no allocation to a `PENDING` payment (`409`). Insert `payment` (`PENDING`, `amount_minor = open_minor`, `method`, `provider`, `operation_id = key`) and `allocation` (`payment → charge`, `operation_id` derived from the key). Then call the provider with the same key and store `provider_ref`. Returns `Pending` + the client secret. **Never returns Paid.** |
| — | `POST /v1/webhooks/{provider}` | Verify signature; `INSERT … ON CONFLICT DO NOTHING` into `billing.provider_event (provider, event_id)`; if new, in the same transaction set `payment.status = 'SUCCEEDED'` and `settled_at` (or `FAILED`), set `processed_at`. Out-of-order events: re-fetch the intent from the provider before applying. Acceptance test 2. |

Mapping to the app models:

- **`ChargeState`** comes from the views, not from Kotlin rules: `Settled` when `charge_balance.open_minor <= 0`; `PaymentPending` when an allocation points to a `PENDING` payment; otherwise `Unpaid`. Because `charge_balance` only counts `SUCCEEDED` payments, an allocation can be written when the payment starts; a `FAILED` payment's allocation simply never counts.
- **`ChargeReason`** ← `charge.kind`: `LATE_CANCELLATION_FEE` → `LateCancellation`, `LATE_RESCHEDULE_FEE` → `LateReschedule`, `NO_SHOW_FEE` → `NoShow`. `SESSION` and `PACKAGE_PERIOD` charges have no app value yet (gap G-9).
- **`ChargeKind`** ← `charge.settlement` (`ONLINE`, `CASH`, `PACKAGE_CREDIT`). Package fees taken from credits have no money charge: the wallet builds them from `fee_decision` + `credit_reservation` (`packagePercent = consumed_units`).
- **`PaymentState`** ← `payment.status`; `Refunded` when `SUCCEEDED` refunds sum to the payment amount. **`PaymentMethod`** ← `payment.method` (the DB has the union `CASH, CARD, BLIK, PRZELEWY24, TEST_MODE`, conflict C2).
- **`SessionPackage`**: `total` = granted units / 100, `used` = used units / 100 from `package_credit_balance`; `validUntil = null` always (DB6: no expiry).
- **Cash charges** are shown but not payable; the trainer records cash in the finance flow, which inserts a `CASH` payment `SUCCEEDED` + allocations (the §3.3 example: one 180 zł payment settling a 60 zł fee and a 120 zł session).
- `FakePaymentProvider`: the worker confirms the payment after a delay through the **same** webhook code path (a synthetic `provider_event` with `provider = 'fake'`).
- Daily reconciliation job: `payment` `PENDING` older than N minutes → ask the provider.
- The trainer client list's overdue flag reads `billing.relationship_overdue.has_overdue` only; it never receives amounts.

### 8.3 Today's workout and plans (B1, A1)

`TodayWorkoutSource` is the integration point; `PlanVersionDto` is already in `:contract`.

| Need | Endpoint | Database work |
|---|---|---|
| Today's workout | `GET /v1/client/today` → `{ planVersion, dayId, assignedPlanId, sessionId?, place? }` or `204` | The client's single `assigned_plan` with `status = 'ACTIVE'` (DB5, index `assigned_plan_one_active`). If it is pinned (`session_id`) to today's session → that plan; otherwise the first `plan_day` (by `position`) without a `FINISHED` `workout_log` for this assignment (R5). "Today" is computed in the client's `app_user.time_zone` with the server clock. |
| Assigned plans (offline cache) | `GET /v1/client/assigned-plans` | `assigned_plan` rows of the caller (all statuses, so history still resolves) + their `plan_version`, assembled into `PlanVersionDto` from `plan_day` → `planned_exercise` → `planned_set` ordered by `position`. Versions are immutable (triggers) → cache by `plan_version.id` forever. |
| Exercises used by those plans | `GET /v1/exercises?ids=` and `GET /v1/exercises?updatedSince=` | `training.exercise` where `owner_trainer_id IS NULL` (global) or owned by a trainer with an `ACTIVE`/`ENDED` relationship to the caller, + `exercise_name` / `exercise_instruction` in the caller's locale, `status = 'ACTIVE'` (hidden ones only by id, for history). Uses `exercise_sync` and `updated_at`. |

### 8.4 Workout logging sync and progress (A1–A3)

The app already saves every set locally. The server implements the sync of architecture §9.2 on the schema's client-UUID keys.

**Push:** `POST /v1/client/workout-sync { operations: [...] }`. Each operation: `operationId`, `type`, entity id (the phone's UUID), `baseRevision`, payload. Applied **one operation per savepoint**, so one conflict does not roll back the rest. Response per operation: `applied` (new revision), `duplicate`, or `conflict` + the server's current row.

| Operation | SQL |
|---|---|
| `UpsertLog` | `INSERT INTO training.workout_log … ON CONFLICT (id) DO UPDATE SET …, revision = revision + 1, updated_at = now() WHERE workout_log.revision <= :base AND workout_log.client_id = :me` |
| `UpsertExercise` | same on `training.workout_exercise` (no `revision` column: last write wins, gap G-10) |
| `UpsertSet` | same on `training.set_log`; `workout_log_id` and `exercise_id` are repeated from the parent (denormalised in the DDL) |
| `DeleteSet` / `DeleteExercise` | `UPDATE … SET deleted_at = now(), updated_at = now(), revision = revision + 1 WHERE id = :id AND revision <= :base` (tombstone, never `DELETE`) |
| `FinishLog` | `state = 'FINISHED'`, `finished_at` (a CHECK ties them together) |
| `DiscardLog` | `state = 'DISCARDED'` + `deleted_at` — the app needs `WorkoutState.DISCARDED` (database-design §5) |

- **Duplicates:** a retried upsert with the same body hits the same primary key; 0 rows updated at a revision the server already has → `duplicate`, not `conflict`. A retried whole batch is replayed from `platform.idempotency_key` (`endpoint = 'workout-sync'`). Acceptance test 3.
- **Ownership checks** before writing: `workout_log.client_id = :me`; a referenced `session_id` must be the caller's session; `assigned_plan_id` must be the caller's assignment, and `(plan_version_id, plan_day_id)` must exist (composite FK to `plan_day`). Exercises must be visible to the caller (§8.3).
- **Order:** the phone sends a log before its exercises and sets (FKs). The sync worker groups operations by workout.

**Pull:** `GET /v1/client/workouts?since=<cursor>` returns logs, exercises and sets with `updated_at > cursor` (indexes `workout_log_sync`, `set_log_workout`), tombstones included. The cursor is `(updated_at, id)`; the server subtracts a small overlap window (e.g. 5 s) because concurrent transactions can commit out of `updated_at` order, and the app ignores rows whose `revision` it already has.

**Measurements:** `GET /v1/client/measurements?since=` and `PUT /v1/client/measurements/{id}` (idempotent by the phone's UUID) on `training.body_measurement`. `kind` ∈ `BODY_WEIGHT, WAIST, HIPS`, `value_thousandths` = thousandths of the app's unit (kg / cm). Delete = tombstone.

**Progress and PRs:** A3 keeps computing from local history. The server has `training.personal_record` (best working set, warm-ups ignored) for the trainer's progress views and for a new device; the client does not need a PR endpoint in this plan. The rest timer is local only.

### 8.5 Chat and technique video (A6, technique video)

Backs `ClientChatRepository`, `ChatMediaSource`, `TechniqueVideoOutbox`, `MediaRemoteDataSource`.

| Need | Endpoint | Database work |
|---|---|---|
| Client's conversations | `GET /v1/client/conversations` | `messaging.conversation` per relationship of the caller; unread = messages from the other party with `seq > read_marker.last_read_seq`. The conversation row is created when the relationship becomes `ACTIVE` (trainer flow; the dev route does it in S8). |
| Thread | `GET /v1/conversations/{relationshipId}/messages?afterSeq=` | `message WHERE conversation_id = … AND seq > :afterSeq ORDER BY seq` (unique `(conversation_id, seq)`), + `message_attachment` → `media_asset` status. |
| Send text | `POST /v1/conversations/{relationshipId}/messages { id, text }` | One transaction: `UPDATE conversation SET last_seq = last_seq + 1 RETURNING last_seq` → insert `message (id = client UUID, seq, sender_id, body)` → outbox `message.created` (ids only). `23505` on `message.id` → return the stored message. |
| Send technique video | same, `{ id, text?, mediaId, workoutLogId, setLogId }` | As above, plus `message.workout_log_id`, `message.set_log_id`, `message_attachment (position 0, media_id)`, and `set_log.technique_media_id = mediaId` (a sync-style update with the Training module). The set and the media row must already exist on the server (FKs). The weight/reps shown with the video are read from `set_log`, not copied into the message. |
| Mark read | `POST /v1/conversations/{relationshipId}/read { upToSeq }` | Upsert `read_marker`, `last_read_seq = greatest(old, :upToSeq)`. |
| Live updates | `WS /v1/ws` → `{ type: "message", relationshipId, seq }` | Fed from the outbox; the app then fetches by `afterSeq`. |
| Upload session | `POST /v1/media/upload-sessions` (`CreateUploadSessionRequest` + `relationshipId`) | Insert `media_asset (id = mediaId, owner_user_id = me, relationship_id, kind, state = 'UPLOADING', content_type, size_bytes, storage_key = 'media/<id>', retain_until = now() + 90 days for TECHNIQUE_VIDEO)`. The DB rejects size > 100 MB and a private kind without a relationship. Idempotent by `id`. Returns a tus upload URL. |
| Upload | tus `PATCH` to storage/proxy | Resumable from the server's offset. |
| Status / playback | `GET /v1/media/{id}` → `MediaStatusDto` | Caller must be `owner_user_id` or the other party of `relationship_id`; a signed `playbackUrl` only when `state = 'READY'`. |

- **Client send order for a technique video** (the outbox already queues it): 1) workout sync of the log and the set, 2) upload session + upload, 3) the message. A message whose set is not yet synced gets `409 DEPENDENCY_MISSING` and stays queued; it is never shown as sent.
- **Worker:** on upload complete → `PROCESSING`; checks container, `duration_ms <= 60000` and size → `READY` (+ `ready_at`, `thumbnail_key`, `width/height`) or `FAILED` with `failure_reason` (`TOO_LARGE`, `TOO_LONG`, `UNSUPPORTED_TYPE`, `MISSING_OBJECT`, `PROCESSING_ERROR`). `UPLOADING` rows older than 24 h → `FAILED MISSING_OBJECT` (index `media_asset_cleanup`). Retention job deletes objects with `retain_until < now()` and sets `deleted_at` (index `media_asset_retention`).
- **App mapping:** the server state set is `UPLOADING, PROCESSING, READY, FAILED`; `RemoteMediaState` needs `UPLOADING` (C3); the phone-only `QUEUED` never reaches the server; the local `owner_trainer_id` column maps to `owner_user_id`.
- Messages have a client UUID and a server `seq`; stored messages are the truth, WebSocket/push only speed things up. Push text never contains message content.

### 8.6 "Something changed" signal (`updates: StateFlow<Long>`)

`ClientScheduleRepository.updates` and `WalletRepository.updates` refresh screens when the trainer acts. Remote implementation, in order of delivery:

1. Refresh on screen resume and pull-to-refresh (no extra server code).
2. The worker reads `platform.outbox_event` (`published_at IS NULL`, index `outbox_unpublished`), maps `aggregate_type` → `{ kind: "schedule" | "wallet" | "chat" }`, sends an FCM data push to the other party's `push_token`s, and sets `published_at`.
3. The chat WebSocket carries the same events while the app is open.

### 8.7 Trainer transitions the client flow depends on

Owned by the trainer backend plan, listed here because the client flow cannot be tested end to end without them. Each one's database effect:

| Transition | Database effect |
|---|---|
| Confirm a booking request | lock trainer → availability check → session `CONFIRMED` (exclusion constraints apply now), `confirmed_at`, `blocked_until` recomputed; relationship `PENDING → ACTIVE` + `started_at` + default `access_grant`s + `messaging.conversation`; package: `credit_reservation RESERVED 100` from the oldest period with units; reminder jobs |
| Reject a request | `REJECTED`, `closed_at`; a `PENDING` relationship with no other session stays `PENDING` (R1) |
| Propose a new time | `reschedule_request (proposer_role = 'TRAINER')` |
| Waive a fee | `adjustment (WAIVER)` on the charge, or `fee_decision.waived_minor` when decided before charging (full only, D8) |
| Check-in / no-show | `attendance` row → session `COMPLETED` / `NO_SHOW`; `SESSION` charge (R10); no-show fee via `fee_quote NO_SHOW` + `fee_decision`; package: reservation `CONSUMED` |
| Assign a plan | old `ACTIVE` → `REPLACED`, insert new `assigned_plan ACTIVE` in one transaction |
| Record cash | `payment CASH SUCCEEDED` + `allocation`s |

Until they exist, a `/v1/dev/**` route group (enabled only in the `local` profile) or the seed script runs these transitions **through the same service code**, never by raw SQL, so the constraints and events are exercised.

## 9. Authorization and privacy

Enforced on the server for every request; UI hiding is not authorization. Row-level security is not used for now (database-design §4), so every check below is in the Ktor service layer and tested per endpoint.

1. **Public endpoints** read only `catalogue` rows with `is_public = true`; the module cannot reference `identity`, `coaching`, `scheduling`, `training`, `messaging` or `billing` tables.
2. **Client endpoints** filter every query by the caller's `client_profile.user_id` (`session.client_id`, `workout_log.client_id`, `body_measurement.client_id`, `assigned_plan.client_id`, or `trainer_client.client_id` for relationship-keyed rows).
3. **Relationship-scoped resources** (sessions, conversations, technique videos, plans, charges) require the caller to be a party of `trainer_client`; the trainer side additionally needs the active `access_grant` scope (`WORKOUT_HISTORY`, `MEASUREMENTS`, `TECHNIQUE_VIDEOS`, `HEALTH_QUESTIONNAIRE`). Ended relationship → read-only history per R2, no new requests or messages.
4. Access to another client's or trainer's resource returns `404`/`403` and writes `platform.audit_record (action = 'ACCESS_DENIED', subject_type, subject_id, outcome = 'DENIED')` — ids only (acceptance test 4).
5. `outbox_event.payload` and `job.payload` carry ids only. Health questionnaire, injuries, measurements, notes, chat and videos never appear in logs, analytics, push text or `platform.calendar_feed` output. `health_questionnaire.answers_encrypted` is encrypted by the API (not part of this plan's slices).
6. Slots expose only free time: `calendar_block.title/kind` and other clients' sessions never leave the server.

## 10. Schema gaps found while mapping the app to the DDL

The DDL is the agreed starting point; these are **proposed follow-up migrations** (`V2__…`), to be reviewed with the database owner. None of them changes an existing table's meaning.

| # | Needed by | Gap | Proposal |
|---|---|---|---|
| G-1 | `TrainerSummary.name`, search | Public name is only in `identity.app_user` (next to the e-mail) | `trainer_listing.display_name text NOT NULL`, `search_text text` + trigram index; Identity writes it on publish/rename |
| G-2 | Goal filter | No column for `Goal` (`Reduction, Strength, Mobility, Competition`) | `trainer_listing.goals text[]` with a CHECK on allowed values |
| G-3 | Availability filter | Buckets would need a public read of `scheduling.availability_rule` | View `catalogue.public_trainer_availability(trainer_id, bucket)` owned by Scheduling, exposing buckets only |
| G-4 | District filter | `catalogue.gym` has no district | `gym.district text`, `gym.search_text text` + trigram index |
| G-5 | `TrainerSummary.mainGym`, `TrainerGym.main` | `trainer_gym` has no main flag | `trainer_gym.is_main boolean` + partial unique index per trainer |
| G-6 | `Offer.description`, `highlighted` | no columns | `offer.description text CHECK (length <= 300)`, `offer.highlighted boolean DEFAULT false` — or drop the fields from the guest UI (team call) |
| G-7 | `GymDetail.amenities`, `transitNote` | no columns | `gym.amenities text[]` (CHECK on `Amenity`), `gym.transit_note text` — or drop from the UI |
| G-8 | `TrainerProfile.media` | no link between a listing and its public media; `media_asset.kind` has no profile kind | `catalogue.listing_media(trainer_id, position, media_id, title)` + kind `PROFILE_VIDEO`, or keep `media = []` for now |
| G-9 | Wallet | `ChargeReason` has no value for `SESSION` / `PACKAGE_PERIOD` charges | App change: add `Session`, `PackagePeriod` to `ChargeReason` (affects B3 and trainer finance) |
| G-10 | Sync conflicts on exercise order/notes | `workout_exercise` has no `revision` | Add `revision int NOT NULL DEFAULT 1`, like `set_log` |
| G-11 | Technique video upload | `CreateUploadSessionRequest` has no `relationshipId`, but the DB requires it for private kinds | Add `relationshipId: String?` to the contract DTO |

Also from database-design §5–§6, to do in the matching slice: one `SessionStatus` in `:contract` (C1), one `PaymentMethod` (C2), `UPLOADING` in `RemoteMediaState` (C3), `WorkoutState.DISCARDED`, local `owner_trainer_id` → `owner_user_id`.

## 11. Changes in the app

The screens and view models stay as they are; only the data layer and DI change.

- **DTOs in `:contract`** (`schedule/`, `wallet/`, `catalog/`, `chat/`, `sync/`, `account/`), `@Serializable`, shared with `:server`. App domain models stay in their modules; mappers DTO ↔ domain live next to the remote repositories. Neither the SQLDelight layout nor the server tables leak into the DTOs (database-design §5).
- **Ktor client** in `:shared:data` (commonMain), with an auth plugin that attaches and refreshes the IdP token, and one error mapper per §4.
- New implementations: `RemoteGuestCatalogRepository`, `FirebaseAuthRepository` (platform bits behind an interface), `RemoteClientScheduleRepository`, `RemoteWalletRepository`, `RemoteTodayWorkoutSource`, `HttpMediaRemoteDataSource`, `RemoteClientChatRepository`, and a `WorkoutSyncWorker` (WorkManager on Android) that drains the operation queue in the order of §8.4/§8.5.
- **IDs:** the app generates UUIDv7 for every row it creates offline (logs, exercises, sets, measurements, media, messages) and for every `operationId`.
- **Demo sources stay** for previews, tests and `:desktopApp:runPreview`. Koin picks demo or remote via one config flag (`trainer.backend=demo|local|staging`).
- Offline: catalogue and schedule keep the last response in SQLDelight; server-dependent actions (booking, cancel, pay) are never queued silently — they fail with `Offline` and the screen shows "nie wysłano" as it does today.

## 12. Delivery slices

Each slice ends with the app talking to the real local server and database for those screens.

| # | Slice | Contents | Done when |
|---|---|---|---|
| S0 | Foundations | `:server` module, Ktor skeleton, Flyway with **`V1__initial_schema.sql` = `schema-draft.sql`**, Exposed + HikariCP, owner/app DB roles, docker-compose, Testcontainers (`postgres:16-alpine`), SQLSTATE → error mapper (§3), request logging without bodies, outbox + job runner, CI job | `GET /health` in CI; V1 migrates in a test container; the database-design smoke checks run as integration tests |
| S1 | Identity | IdP verification, `/v1/me`, `/v1/accounts` (+ `consent_record`), roles, push tokens, `FirebaseAuthRepository`, seed accounts matching the demo e-mails (`auth_subject` = seed id) | Log in / register / Google against the emulator; `AccountExists`, `InvalidCredentials`, `Offline` paths; register retry creates one user |
| S2 | Public catalogue | migration with gaps G-1…G-7 (as agreed), the 6 catalogue endpoints, seed of the 12 synthetic trainers / 6 gyms, offers and policy versions, `RemoteGuestCatalogRepository` | Guest screens show server data; filter results equal `CatalogSearch` for the same fixtures (shared test cases); privacy test passes |
| S3 | Client schedule (read) | schedule, booking options, slots from `availability_rule`/`calendar_block`, policy versions; enum mapper (C1) | B1/B2 render from the server with `serverNow`; slots never contain block titles |
| S4 | Booking & reschedule | booking request (+ R1 relationship), withdraw, reschedule request, proposal answer, expiry job, dev routes for confirm/reject/propose | Acceptance test 1 (two concurrent confirms → one `23P01` → `409`); "original stays confirmed"; `reschedule_one_open`; idempotent booking retry |
| S5 | Cancellation fees | fee quotes, cancel, `fee_decision`, fee charges, package credit consumption, client policy override | Acceptance test 6 (app fee = server fee for every preset, same `FeeCalculator`); quote expiry / revision conflict; free cancellations counted in the session's zone |
| S6 | Wallet & payments | wallet read from the billing views, pay with `FakePaymentProvider`, webhook path with `provider_event`, then Stripe test mode; dev route for cash | Acceptance test 2 (duplicate/out-of-order webhooks settle once); the app never shows Paid before the webhook; the §3.3 cash example clears the overdue flag |
| S7 | Today + workout sync | today, assigned plans, exercises, sync endpoint, history pull, measurements, Android sync worker, `DISCARDED` | Acceptance test 3 (offline log, process killed, back online: nothing lost or duplicated); stale set write reported as a conflict |
| S8 | Chat & technique video | conversations, messages with `seq`, WebSocket, upload sessions (tus), media worker, signed playback, retention job | Video goes UPLOADING → PROCESSING → READY in the trainer's view; acceptance test 4 for another relationship's video; message retry keeps one `seq` |
| S9 | Push & reminders | FCM data pushes from the outbox, reminders tied to revisions | Acceptance test 7 (moved session → old reminder not sent) |

S2 and S3 can be built in parallel after S1. S7/S8 are independent of S4–S6 and can go to a second person. S0 must land first because every slice depends on the V1 migration and the error mapper.

## 13. Tests that matter

- **Integration tests on real Postgres** (Testcontainers, the same V1 migration), not mocks and not H2, for: the exclusion constraints, the accept-reschedule transaction, quote expiry, idempotent booking / payment / sync / message creation, webhook dedupe, `forbid_update` on every immutable table, the `charge_balance` and `relationship_overdue` views.
- **Authorization tests** per endpoint group: guest without token, client A reading client B, ended relationship, trainer of another client; each denied call writes one `audit_record`.
- **Contract tests:** the app's mappers decode the server's real JSON (serialised with `:contract` on the server, decoded with the same DTOs in the app test). Enum mapper tests cover every DB `CHECK` value.
- **Privacy tests:** public endpoints' JSON contains no field outside the public models; logs contain no request bodies or SQL values; `outbox_event.payload` contains ids only.
- Do not test layout constants or re-test `FeeCalculator` itself (already covered in `:contract`).

## 14. Open decisions

Do not decide these silently while implementing.

| # | Question | Proposal |
|---|---|---|
| G-auth | Identity provider (architecture §5 lists Firebase, Supabase, Keycloak) | Firebase Auth: free tier, emulator, Google sign-in and password reset included |
| G-host | Where does the server run for the course? `infrastructure-aws.md` is referenced by database-design but not in the repository | One EU host + managed Postgres 16 (RDS per database-design); local docker-compose for development |
| R1 | Booking with a trainer the client has no relationship with (G1) | First booking request creates `trainer_client` `PENDING / CLIENT_REQUEST`; the trainer confirming activates it. Invite codes (`identity.invite_code`) later. |
| R2 | What a client sees after a relationship ends | Past sessions, own workout logs and payments stay visible; chat read-only; no new requests |
| R3 | Do concurrent booking requests block each other? | **Decided (DB4): no.** Only confirmed sessions block time. |
| R4 | Request expiry time | Until the slot start or 48 h, whichever comes first |
| R5 | How "today's workout" is chosen | Pinned plan of today's session, else the first unfinished day of the one active assignment (DB5) |
| R6 | Public media hosting | Exercise demos via CDN path; anything private only via signed URLs |
| R7 | Stripe Connect per trainer in the university phase? | No: one platform test account (`trainer_profile.payment_account_ref` stays null) |
| R8 | Quote validity | 5 minutes |
| R9 | DB5 across trainers: a client with two trainers still has one active plan? | Database-design asks to confirm; the plan follows the index as written |
| R10 | When is a `SESSION` charge written, and its `due_at`? | At check-in (`COMPLETED`), so a cancellation never needs a reversing adjustment; online due 7 days after, cash due at the session. Fee charges due on creation. |
| R11 | Schema gaps G-1…G-11 (§10) | Agree which become migrations and which UI fields are dropped instead |
| — | Unchanged from architecture | G13 (renewal, pauses, extra-session price), D1 package purchase, D2 invoices (`invoice_ref` stays unused), guest D1/D2 (ratings, verified) stay unresolved; the API exposes only display data for them |

## 15. Implementation status (2026-11-11)

The server side of slices S0–S8 is implemented in [`:server`](../../server/README.md); S9 is partial.

App side (§11), started: `ApiClient` (Ktor client, CIO engine) and `BackendConfig` in `:shared:data`, `RemoteGuestCatalogRepository` in the guest feature, and `trainer.backend` (`demo` default, `local`, or a URL) for desktop (`-Ptrainer.backend=local`) and Android (`BuildConfig.TRAINER_BACKEND`; debug builds allow plain HTTP to `10.0.2.2`/`localhost`). `remoteModule` is loaded after the demo modules and overrides only the catalogue. Verified with MockEngine contract tests and an end-to-end test against a running server (`RemoteCatalogAgainstServerTest`, skipped without `TRAINER_E2E_URL`). Sign-in, sessions, wallet, workouts and chat still use the demo sources; booking from a server trainer goes through the demo scheduling store with the trainer data from the catalogue. The policy wording on the trainer profile is built in the app in Polish, like the demo source.

| Slice | Server status |
|---|---|
| S0 | Done: `:server` module, Flyway (V1 = `schema-draft.sql` unchanged), Exposed DSL + HikariCP, owner and DML-only roles (`R__grants.sql`), SQLSTATE mapper, request logging without bodies, outbox + job runner (`SKIP LOCKED`), docker-compose. |
| S1 | Done for `dev` tokens and Firebase ID-token verification (JWKS). `/v1/me`, `/v1/accounts` (+ consents), client role, push tokens. Not tested against the Firebase emulator. |
| S2 | Done with V2 (gaps G-1…G-5 as a **proposal**); G-6…G-8 return null / false / []. 12 synthetic trainers and 6 gyms seeded. ETag / `Cache-Control`, rate limit. |
| S3–S5 | Done: schedule, booking options, DST-safe slots, booking requests (R1, R4), withdraw, reschedule (client and trainer), proposal answers, quotes (R8) and cancellation with fee decisions, charges and package credits; dev routes for confirm / reject / propose / accept / trainer cancel / check-in / no-show. |
| S6 | Done with `FakePaymentProvider` only (webhook path, signature, dedupe, out-of-order re-fetch, cash recording). Stripe test mode is not implemented. |
| S7 | Done: today, assigned plans, exercises, per-operation sync with batch replay, pull with overlap window, measurements. |
| S8 | Done: conversations, messages with `seq`, read markers, WebSocket refresh events, tus-style resumable upload, MP4 duration check, signed playback, retention job. Storage is local disk; the S3/MinIO adapter is not written. |
| S9 | Partial: outbox → WebSocket and a logging `PushSender`; revision-checked reminders. FCM sending is not implemented. |

Decisions taken while implementing, to review with the team:

- **Flyway history table in `public`, not `platform`.** V1 creates the module schemas itself (`CREATE SCHEMA identity`, …), so Flyway must not pre-create them (`createSchemas=false`) and cannot place its table in a schema that does not exist yet. This keeps V1 identical to the draft; the `flyway.schemas` line in §2 does not hold as written.
- **V2 adds G-1…G-5** (public name and search text, goals, district, main gym, availability-bucket view). They are needed so the public module never reads `identity` or `scheduling`; R11 is still open, and V2 can be changed before the first shared deployment.
- **Contract changes (shared):** new DTO packages `api`, `account`, `catalog`, `schedule`, `wallet`, `sync`, `chat`; `RemoteMediaState.UPLOADING` (C3, the one exhaustive `when` in `LocalMediaRepository` updated); optional `CreateUploadSessionRequest.relationshipId` (G-11); `UploadSessionDto`. `contract.schedule.SessionStatus` is the 8-state set (C1); the app's own enums are not migrated yet.
- **Booking payment kind:** when the request does not say, a package with a free credit is used, otherwise ONLINE. The DDL comment "`payment_account_ref` null = cash only" was not applied (R7 uses one platform test account).
- **Featured trainers** on the guest home are the 4 cheapest public trainers until featuring is defined.
- **Dev tokens are `dev.<subject>`**: the Bearer scheme allows token68 characters only, so `dev:` is not used.
- **Tests use embedded PostgreSQL** (Docker was not available) instead of Testcontainers; `TEST_DATABASE_URL` points them at a `postgres:16-alpine` container in CI.
