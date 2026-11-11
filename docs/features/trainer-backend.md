# Trainer backend (`:server`)

Status: implemented on branch `trainer-backend`, 2026-11-11, and merged with the client/guest backend into one server. Every trainer and client screen of the app uses it in remote mode (§8); notifications are derived from the dashboard (no table). Follows [initial-architecture.md](../initial-architecture.md) §5–§11 and [database-design.md](../database-design.md).

## 1. Run locally

**One server, two APIs.** A single process (`:server:run`, port 8080) serves the client/guest API under `/v1/...` (+ `GET /health`) and the trainer API under `/api/v1/...`. The database and the client API are configured by `ServerConfig` (`TRAINER_PROFILE`, `DB_URL`, `DB_APP_USER`/`DB_APP_PASSWORD`, `DB_OWNER_USER`/`DB_OWNER_PASSWORD`, `AUTH_MODE`, ...; see [server/README.md](../../server/README.md)). The trainer API reads its own `AppConfig` only for the values in §2.

Requirements: JDK 17 (not only a JRE) and Docker Desktop.

```sh
docker compose -f server/docker-compose.yml up -d   # PostgreSQL 16 on localhost:5432, db trainer, roles trainer_owner (Flyway) and trainer_app (API)
./gradlew -Ptrainer.ui=false :server:run             # http://localhost:8080
```

Reset the database with `docker compose -f server/docker-compose.yml down -v`. The old root `docker-compose.yml` no longer exists.

On start the server runs the Flyway migrations (history table `public.flyway_schema_history`, because V1 creates the module schemas itself): `V1` initial schema (`docs/database/schema-draft.sql`), `V2__catalogue_public_fields` (client API), `V3__client_information`, and the repeatable `R__grants`. When the database has no users it seeds the demo data with the Kotlin `DemoSeed`, and it imports the global exercise library if it is incomplete. The SQL demo seed `server/src/test/resources/db/dev/R__demo_seed.sql` is used only by the trainer API tests.

Sign in as a demo account and call the API:

```sh
curl -s -X POST localhost:8080/api/v1/dev/token -H 'Content-Type: application/json' -d '{"subject":"demo-trainer"}'
curl -s localhost:8080/api/v1/trainer/dashboard -H "Authorization: Bearer <token>"
```

Demo subjects (`DemoSeed`): `demo-trainer` (Michał Kowalczyk, also holds the client role), `demo-client` (Katarzyna Nowak), `demo-trainer-only`, and the public trainers `tr-*`. All are fictional. The client API accepts `Authorization: Bearer dev.<subject>`; the trainer API needs the JWT from `/api/v1/dev/token`. In the app, log in with `trener@example.com` (any password of at least 8 characters): it maps to `demo-trainer`.

Tests run both suites (the client/guest tests use embedded PostgreSQL; the trainer API tests use Testcontainers, so Docker must be running):

```sh
./gradlew -Ptrainer.ui=false :server:test
```

## 2. Configuration (environment variables)

| Variable | Default (local only) | Purpose |
|---|---|---|
| `PORT` | 8080 | HTTP port |
| `DB_URL`, `DB_APP_USER`, `DB_OWNER_USER`, ... | local docker compose | PostgreSQL (RDS on AWS); read by `ServerConfig`, shared with the client API |
| `AUTH_MODE` | `dev` | `dev`: the server issues demo tokens. Anything else disables `/dev/token`. (`ServerConfig`) |
| `JWT_SECRET` / `JWT_ISSUER` / `JWT_AUDIENCE` | dev values | Token verification; also signs media links |
| `DATA_KEY_ID` / `DATA_KEY` | dev key | AES-256 key (base64, 32 bytes) for private columns |
| `MEDIA_DIR` / `PUBLIC_BASE_URL` | `build/media` / `http://localhost:8080` | Local media storage and link base |
| `RUN_WORKER` | true | Request expiry and outbox publishing |

The trainer API's own `DB_USER`, `DB_PASSWORD` and `DEV_SEED` are no longer used at runtime. Moving to AWS changes only these values (RDS URL, secrets from Secrets Manager, S3 storage implementation, IdP verifier). Production must set every secret.

## 3. Structure

`server/src/main/kotlin/com/trainingapp/server/`

| Package | Contents |
|---|---|
| `platform` | Config, `Db.tx` (one transaction per command), error mapping, outbox, clock, encryption |
| `auth` | JWT, `Caller` (roles from our DB, never from the token), `Access` (relationship + grant checks), `AuditLog` |
| `db` | Exposed table objects for every table and view of the schema |
| `scheduling` | Dashboard, blocks, availability, session lifecycle (`TrainerSessions`) |
| `catalogue` | Versioned cancellation policies, price list, trainer profile |
| `identity` | Clients, notes, client information, invites, ending a relationship |
| `training` | Exercises, immutable plan versions, assignment, client progress reads, library import |
| `billing` | Ledger helpers (fees, charges, credits, refunds), finance summary, cash payments |
| `home`, `messaging`, `media` | Home summary, chat, media upload/status/signed content |
| `worker` | Expiry of unanswered requests and proposals; outbox publishing |
| `routes` | HTTP routes |

Wire DTOs live in `:contract` (`com.trainingapp.contract.api`), so the app and the server share them. Enum values equal the database values.

## 4. API (`/api/v1`, bearer token, trainer role)

Conventions: a "client id" is the trainer–client **relationship id** (as in the app). Session commands carry the `revision` the caller saw and answer `409 CONFLICT` when it is stale. Creating commands carry an `operationId` so a retry returns the first result. Errors: `{code, message, details}` with `INVALID_INPUT` 422, `UNAUTHENTICATED` 401, `DENIED` 403, `NOT_FOUND` 404 (also for another trainer's data), `CONFLICT` 409, `QUOTE_EXPIRED` 409.

| Area | Endpoints (prefix `/trainer`) | App boundary it replaces |
|---|---|---|
| Account | `POST /dev/token` (dev only), `GET /me` | `AuthRepository` (demo) |
| Calendar | `GET /dashboard?from&to`, `POST /blocks`, `DELETE /blocks/{id}`, `GET`/`PUT /availability` | `TrainerRepository.load/addBlock/removeBlock`, profile availability |
| Sessions | `POST /sessions`, `POST /sessions/{id}/accept\|reject\|propose\|cancel\|complete\|no-show`, `GET /sessions/{id}/review`, `GET /sessions/{id}/check-in` | `TrainerRepository` commands, B4, A4 |
| Policy | `GET /policy`, `GET /policy/versions/{id}`, `POST /policy`, `PUT`/`DELETE /clients/{id}/policy-override` | `PolicyRepository` (B5) |
| Clients | `GET /clients`, `GET /clients/{id}`, `POST /clients/{id}/end`, notes `GET`/`POST /clients/{id}/notes`, `PUT`/`DELETE /clients/{id}/notes/{noteId}`, `PUT /clients/{id}/note`, `GET /clients/{id}/information`, invites `GET`/`POST /invites`, `DELETE /invites/{code}` | `TrainerRepository.saveNote/loadClientInformation` |
| Profile | `GET`/`PUT /profile`, `GET /gyms`, `GET`/`POST /offers`, `POST /offers/{id}/archive` | `TrainerProfileRepository` |
| Training | `GET /exercises?updatedSince`, `PUT /exercises/{id}`, `POST /exercises/{id}/hide\|unhide`, `GET /plans`, `GET /plans/{id}/versions/latest\|{n}`, `POST /plans`, `POST /plans/{id}/versions`, `POST /plans/{id}/archive`, `GET`/`POST /clients/{id}/assigned-plan`, `GET /clients/{id}/workouts\|records\|measurements` | `PlanRemoteDataSource`, `ExerciseRemoteDataSource` |
| Home and finance | `GET /home`, `GET /finance?period=WEEK\|MONTH\|YEAR`, `GET /clients/{id}/balance`, `POST /payments/cash` | `TrainerHomeRepository`, `TrainerFinanceRepository` |
| Chat | `GET /conversations`, `GET /conversations/{id}/messages?afterSeq`, `POST /conversations/{id}/messages`, `POST /conversations/{id}/read` | `TrainerChatsRepository` |
| Media | `POST /media/upload-sessions`, `GET /media/{id}`; signed `PUT`/`GET /media/{id}/content?token=` (no bearer) | `MediaRemoteDataSource` |

## 5. Behaviour that preserves the domain rules

- **No double booking:** the trainer row is locked for calendar changes and the database `EXCLUDE` constraints have the final word (concurrent bookings: one wins, others 409). Confirmed sessions block `end + buffer`.
- **Requests are not bookings:** `REQUESTED` blocks nothing; unanswered requests become `EXPIRED` at their start (worker). A reschedule proposal never moves the session until it is accepted, and acceptance checks the proposal's base revision.
- **Fees:** computed with `:contract`'s `FeeCalculator` (the same code as the app) at the server receipt time of the request, from the policy version the session snapshotted. Trainer cancellation records a 0 fee and refunds money already received. Late-reschedule fees can be fully waived (D8). A no-show is possible only after start + grace period. `EXEMPT` clients pay no fees.
- **Ledger:** charges, fee decisions, allocations and attendance are insert-only (database triggers). A cash session is charged at check-in; package sessions reserve credits on confirmation and use them on the outcome.
- **Privacy:** another trainer's data answers 404; client information, workouts, records, measurements and technique videos need an active relationship and the client's grant, and every such read (allowed or denied) is written to `platform.audit_record`. Client information is AES-GCM encrypted. Logs contain method, path and status only; outbox payloads contain ids only.

## 6. Decisions to confirm with the team

| # | Decision taken here | Why |
|---|---|---|
| TB1 | Client information (birth date, body size, goal, injuries, limitations) gets its own encrypted table `coaching.client_information` (V2). **The client enters it**; the trainer only reads it with the `HEALTH_QUESTIONNAIRE` grant. The trainer app's edit form should become read-only. | The schema draft had no table; it is health data (Art. 9). |
| TB2 | Dev authentication: HS256 tokens issued by the server for seeded accounts. The identity provider is still open. | Lets both apps integrate now; only `auth/Auth.kt` changes later. |
| TB3 | A cash session's `SESSION` charge is created at check-in; an online session gets one at check-in if the payment flow has not created it. Unallocated cash stays as a credit on the payment and is not applied to later charges automatically. | Charges are append-only; charging at booking would need corrections for every cancellation. |
| TB4 | A session the trainer creates directly is `CONFIRMED` with `policy_accepted_at` = creation time. | G10 lets trainers create sessions; client consent at that moment is a product question. |
| TB5 | Package fees with `USE_CREDIT`: a no-show uses the no-show percent of one session's credits; a late-reschedule fee is recorded as a `PACKAGE_CREDIT` charge without deducting credits yet. | G13 is still open. |
| TB6 | A trainer may propose a new time for a `REQUESTED` session too (stored as a reschedule proposal). | The app's `proposeTime` is offered for requests. |
| TB7 | Exercise library images are not served by the server yet; the app keeps its bundled image keys. | `exercise_image` requires media rows. |

## 7. Not done yet

- Trainer notifications in the app stay on demo data (no table in the schema).
- Identity provider (sign-in uses dev tokens: `dev.<subject>` for `/v1`, the dev JWT for `/api/v1`); the session is not kept across app restarts.
- Persistent outbox for technique-video messages (retries are lost if the process dies); WebSocket live updates (screens refresh instead); a server-side "video reviewed" marker; the client chat unread badge.
- Workout sync gaps: `assignedPlanId` is not sent, logs edited on another device are not merged.
- Real identity provider, Stripe test mode, invoices, push notifications (FCM), WebSocket chat, S3 storage and video processing (the local processor marks uploads READY at once), HTTP range requests for video seeking.
- Trainer notifications list (no table in the schema), payouts, iCal feed.

## 8. Connecting the app

The app talks to the server only when it is given an address; without one it keeps the in-memory demo data, so nobody needs a running backend.

```sh
docker compose -f server/docker-compose.yml up -d && ./gradlew -Ptrainer.ui=false :server:run   # server on :8080
./gradlew :androidApp:installDebug -Ptrainer.backend=local   # emulator reaches the computer at 10.0.2.2:8080
./gradlew :desktopApp:run -Ptrainer.backend=local            # desktop, localhost:8080
```

- One switch, `-Ptrainer.backend=local` (or a full `http(s)://` URL; default `demo`), connects the guest/client and trainer sources. `AppPlatform.trainerApiUrl` derives from `backend.baseUrl`; the former `-Ptrainer.apiUrl` / `TRAINER_API_URL` app switch is gone. Debug Android builds allow plain HTTP only to `10.0.2.2`, `localhost` and `127.0.0.1`; release builds require HTTPS.
- `pl.trainer.data.remote` (in `:shared`): `TrainerApiClient` (Ktor; a dev token for the signed-in trainer, `demo-trainer` by default, renewed once on 401), mappers from `:contract` DTOs to the app models, `RemoteTrainerRepository` and `RemotePolicyRepository`. Server errors map to the app's `Failure` values (409 → `Conflict`, `QUOTE_EXPIRED` → `QuoteExpired`, 422 → `InvalidInput`, 401/403 → `Denied`, no connection → `Offline`).
- The calendar opens on the server's date (`TrainerDashboard.today`) instead of the fixed demo date.
- Client information is read-only for the trainer when connected (TB1): the edit button is hidden and saving is refused.
- Also on the server when connected: home (`RemoteTrainerHomeRepository`), finance, profile with availability and the hourly rate (changing the rate creates a new single-session offer and archives the old one), chat (`RemoteTrainerChatsRepository`), plan publishing, exercise edits and exercise demo video uploads (`com.trainingapp.app.remote`). Each keeps its last successful answer in memory and shows it as offline when the server is unreachable.
- Known gaps: profile settings, phone, location and payout figures have no endpoint and stay in memory; finance has no payout or goal data; home shows unread messages as a total only; a client's technique video still goes through the demo media source (the trainer API accepts exercise demo videos only), so in connected mode a video recorded in the client view does not reach the trainer's chat.
- The client views use the `/v1` client API in remote mode: `RemoteAuthRepository`, `RemoteClientScheduleRepository`, `RemoteWalletRepository`, `PublicPolicyRepository` (other trainers' policies), `RemoteTodayWorkoutSource` + `WorkoutSyncer` (local logs and measurements pushed on reconnect and every 5 minutes), `RemoteClientChatRepository`, `RemoteClientMediaRemoteDataSource` (resumable technique-video upload) and `RemoteTechniqueVideoOutbox`. The trainer home lists client videos from the server (`RemoteTechniqueVideoReviews`). All are bound in `remoteModule` (AppModule.kt).
- Tests: `RemoteTrainerRepositoryTest`, `RemoteFeatureRepositoriesTest`, `RemoteTrainerChatsRepositoryTest`, `RemoteTrainingSourcesTest` (scripted server, run always) and `LiveTrainerApiTest` (only with env `TRAINER_API_URL=http://localhost:8080` set; it rejects the seeded booking request, so use a development database).
