# Backend (`:server`)

Ktor modular monolith. One process serves the client/guest API (`/v1/...`, `GET /health`) and the trainer API (`/api/v1/...`, see [docs/features/trainer-backend.md](../docs/features/trainer-backend.md)). This README covers the shared setup and the client/guest side. Built from [docs/plans/backend-client-guest.md](../docs/plans/backend-client-guest.md) on the schema in [docs/database/schema-draft.sql](../docs/database/schema-draft.sql). JVM 17, Kotlin 2.2, Ktor 3.3, Exposed 1.2 (DSL only), Flyway, PostgreSQL 16+. DTOs and rules (`FeeCalculator`, `PolicyRules`, `SearchText`) come from `:contract`, so the app and the server share them.

## Run locally

```sh
docker compose -f server/docker-compose.yml up -d   # PostgreSQL 16 with the owner and DML-only app roles (reset: down -v)
./gradlew -Ptrainer.ui=false :server:run             # migrates, seeds demo data, listens on :8080
curl localhost:8080/v1/public/trainers
curl -H "Authorization: Bearer dev.demo-client" localhost:8080/v1/client/schedule
```

The `local` profile (the default) migrates on start, seeds synthetic demo data when the database is empty, runs the worker in-process and enables `/v1/dev/...`. Without Docker, point `DB_URL` at any PostgreSQL 16+ with the roles from [docker/init-roles.sql](docker/init-roles.sql).

| Variable | Default (local) | Meaning |
|---|---|---|
| `TRAINER_PROFILE` | `local` | `local`, `staging`, `production`. Outside `local`, secrets are required and only Firebase tokens are accepted. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/trainer` | |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `trainer_app` | DML-only role used by the API |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `trainer_owner` | Schema owner, used only by Flyway |
| `DB_MIGRATE_ON_START` | `true` in local | |
| `AUTH_MODE` | `dev` | `dev` (`Bearer dev.<subject>`) or `firebase`. Both APIs use the same mode. |
| `FIREBASE_PROJECT_ID` | `demo-trainer` | |
| `MEDIA_STORAGE_DIR` | `build/media-storage` | Local disk object storage |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Base of upload and signed playback URLs |
| `WORKER_INLINE` | `true` | `false` + `./gradlew :server:runWorker` runs the worker as its own process |
| `SEED_DEMO_DATA` | `true` in local | |
| `DB_LOG_SQL` | `false` | Prints SQL with values; local only |

Demo tokens: `dev.demo-client` (Katarzyna Nowak, client), `dev.demo-trainer` (Michał Kowalczyk, trainer and client), `dev.demo-trainer-only`, and `dev.tr-anna`, `dev.tr-piotr`, … for the other public trainers. Every person, gym and address is synthetic.

The trainer API takes the same database settings from the variables above and the same `AUTH_MODE`/`FIREBASE_PROJECT_ID`, so one token works on both APIs. It reads only `MEDIA_SIGNING_KEY` (falls back to `JWT_SECRET`) for signed media links, `DATA_KEY`, `DATA_KEY_ID`, `MEDIA_DIR`, `PUBLIC_BASE_URL` and `RUN_WORKER` itself; its `DB_USER`, `DB_PASSWORD` and `DEV_SEED` are unused at runtime. In `dev` mode `dev.demo-trainer` works directly; `POST /api/v1/dev/token {"subject":"demo-trainer"}` still returns that token for older callers.

### Firebase sign-in locally

[docs/plans/firebase-auth.md](../docs/plans/firebase-auth.md). Sign-in uses the real Firebase project; there is no local emulator.

```sh
docker compose -f server/docker-compose.yml up -d
AUTH_MODE=firebase FIREBASE_PROJECT_ID=<project id> ./gradlew -Ptrainer.ui=false :server:run
```

`./gradlew :server:run` also reads the sign-in settings from `local.properties` (not committed), the file the app uses: `trainer.auth=firebase` sets `AUTH_MODE=firebase` with `FIREBASE_PROJECT_ID` from `firebase.projectId`. Environment variables set in the terminal win.

To sign in to the seeded demo trainers with a real Firebase project, `tools/firebase-link-trainers.ps1` creates a Firebase user `<auth_subject>@example.com` for each seeded account without an e-mail and re-points `app_user.auth_subject` to its UID (preview by default; `-Apply` to run, `-IncludeClients` for clients too). Local development database only.

With a real Firebase project: `TRAINER_PROFILE=staging AUTH_MODE=firebase FIREBASE_PROJECT_ID=<project id>` (plus the secrets the non-local profiles require). The server needs no service-account key: it verifies ID tokens with Google's public keys.

## Test

```sh
./gradlew -Ptrainer.ui=false :server:test
```

This runs both suites. The trainer API tests use Testcontainers (Docker required) and the SQL seed `src/test/resources/db/dev/R__demo_seed.sql`. The client/guest integration tests run on a real PostgreSQL with the same migrations (embedded PostgreSQL by default; set `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD` to use e.g. a `postgres:16-alpine` container in CI). They cover acceptance tests 1 (concurrent confirmations), 2 (webhook dedupe), 3 (offline sync without duplicates), 4 (another relationship's session, conversation and video), 7 (reminder of a moved session), the §3.3 cash example, idempotent retries, quote expiry and revision conflicts, immutability triggers and privacy of public JSON and outbox payloads.

## Layout

One package per database schema: `identity`, `catalogue`, `scheduling`, `billing`, `training`, `messaging`, `media`, `platform` (outbox, jobs, worker, realtime hub). `http` holds auth, error mapping and routes; `dev` the demo seed and the `/v1/dev` trainer transitions. Flyway owns the schema: V1 is the schema draft unchanged, V2 adds the proposed catalogue gaps G-1…G-5, V3 the encrypted client information (trainer API), `R__grants.sql` grants DML to the app role.
