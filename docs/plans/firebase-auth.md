# Plan: Firebase Authentication (log in, register, Google, token refresh)

Status: implemented on branch `logowanie_firebase` (F-1 to F-3 and F-5; F-4 dropped; F-6 not started) · 2026-11-18 · see §13

**2026-11-18: the Firebase Auth emulator was removed.** Sign-in goes through the real Firebase project only (`trainer.auth=firebase`, `AUTH_MODE=firebase`); `dev` stays for tests and offline demo data.

Sources: `CLAUDE.md` §5–§9; `docs/initial-architecture.md` §5 (auth row); `docs/plans/backend-client-guest.md` §6, §11, §12 (slice S1), §14 (G-auth) and §15; `docs/plans/guest-views.md` §4.7 and D19; the code listed in §1.

**Goal:** replace the demo sign-in (`Bearer dev.<subject>` and `POST /api/v1/dev/token`) with Firebase Authentication. The user registers and logs in with Firebase; the app sends the Firebase **ID token** to our Ktor API; the API verifies it and maps its `sub` to `identity.app_user.auth_subject`. Roles, relationships and consents stay in our database. The API never sees a password.

**Out of scope:** account deletion (G9), e-mail verification enforcement, iOS, phone/Apple sign-in, push tokens (separate work), and any change to the login/register screens beyond the states listed in §6.

---

## 1. Current state (verified in code, 2026-11-17)

| Area | File | State |
|---|---|---|
| Client/guest API (`/v1`) verification | `server/.../http/Auth.kt` | **Ready.** `TokenVerifier.from(AuthConfig)` has `DevTokenVerifier`, `FirebaseTokenVerifier` (RS256, Google JWKS, issuer `https://securetoken.google.com/<project>`, audience = project id). Selected with `AUTH_MODE` = `dev` / `firebase` and `FIREBASE_PROJECT_ID` (default `demo-trainer`). |
| Startup guard | `server/.../Application.kt` (`main`) | Outside the `local` profile only `AUTH_MODE=firebase` is accepted. |
| Trainer API (`/api/v1`) verification | `server/.../auth/Auth.kt`, `platform/AppConfig.kt`, `routes/CommonRoutes.kt` | **Not ready.** A second, separate mechanism: the server signs its own HS256 JWTs (`TokenService`) and issues them at `POST /api/v1/dev/token`. `ApplicationCall.subject()` reads a `JWTPrincipal`. Configured by `AppConfig.auth`, not by `ServerConfig.auth`. |
| Account creation | `server/.../identity/IdentityService.kt` | **Ready.** `POST /v1/accounts` is idempotent by `auth_subject` and stores `email` from the token. `GET /v1/me` → `404 NO_ACCOUNT` when the IdP user has no account yet. |
| App auth contract | `shared/feature/guest/.../model/Auth.kt` | `AuthRepository` (`session`, `logIn`, `register`, `continueWithGoogle`, `logOut`, `switchRole`) and `AuthResult` (`Success`, `InvalidCredentials`, `AccountExists`, `Offline`, `Unavailable`, `NotSupported`). Fits Firebase as is. |
| App remote auth | `shared/feature/guest/.../data/RemoteAuthRepository.kt` | Demo only: maps the e-mail to a subject (demo accounts or an FNV hash), ignores the password, sends `dev.<subject>`. `continueWithGoogle()` → `NotSupported`. |
| Client API token | `shared/data/.../remote/ApiClient.kt`, `shared/app/.../AppModule.kt` | `token: () -> String?` (non-suspending), wired to `"dev.${session.userId}"`. |
| Trainer API token | `shared/src/.../pl/trainer/data/remote/TrainerApiClient.kt` | Fetches a token from `dev/token` for `subject()`; on `401` renews once. |
| Session persistence | — | **None.** The session lives in a `MutableStateFlow`; the app starts signed out after a restart. |
| Local environment | `server/docker-compose.yml` | Postgres only (no Firebase emulator; sign-in uses the real project). |

## 2. How Firebase Auth fits

```
App ──(1) e-mail+password / Google ID token──► Firebase Auth (Identity Toolkit)
App ◄─(2) idToken (JWT, 1 h) + refreshToken ──
App ──(3) Authorization: Bearer <idToken> ───► Ktor API ──(4) verify RS256 via Google JWKS
                                                         └─(5) sub → app_user.auth_subject → roles
App ──(6) refreshToken ──► securetoken.googleapis.com ──► new idToken (before expiry or after a 401)
```

## 3. Decisions to confirm before implementation

| # | Question | Proposal |
|---|---|---|
| F1 | How the app calls Firebase | **Firebase Auth REST API through Ktor in `commonMain`** (`identitytoolkit.googleapis.com/v1/accounts:*`, `securetoken.googleapis.com/v1/token`). It works on Android and desktop, adds no dependency (Ktor is already used), and is easy to test with `MockEngine`. Alternatives: the official Android SDK behind an interface (automatic refresh, but desktop stays on demo), or GitLive `firebase-kotlin-sdk` (a new dependency the team has not chosen). |
| F2 | One mechanism for both APIs | **Yes.** The trainer API uses the same `TokenVerifier` as `/v1`. `POST /api/v1/dev/token` and `TokenService` stay only for `AUTH_MODE=dev` (tests), or are removed in favour of `dev.<subject>`. |
| F3 | Session restore after an app restart | **Yes**, needed for a real login: persist the refresh token in platform-secure storage and restore the session on start through `GET /v1/me`. |
| F4 | Firebase account exists but our account does not (failed `POST /v1/accounts`, or first Google sign-in) | New result `AuthResult.NeedsAccount(name, email)`. The screen opens the register tab with e-mail (and name from Google) prefilled and asks only for name, starting role and consent, then calls `completeAccount(...)`. No account is created silently without consent. |
| F5 | Google sign-in | Android only, through Credential Manager. Desktop keeps `NotSupported`. Separate slice (F-5). |
| F6 | Password reset (guest-views D19) | Optional slice: `accounts:sendOobCode` (`PASSWORD_RESET`) behind a "Nie pamiętasz hasła?" text button. Needs a design decision; not part of the first delivery. |
| F7 | E-mail verification | Not required in the university phase. The server does not check `email_verified`. |
| F8 | Firebase project | One project for staging (test mode). Local work uses the same project (no emulator). |

## 4. Server changes (slice F-1)

1. **One verifier for both APIs.** Authenticate the trainer routes with the existing bearer provider (`AUTH_API`, `TokenVerifier`) instead of `trainerJwt(tokens)`:
   - `auth/Auth.kt`: `ApplicationCall.subject()` reads `principal<TokenIdentity>()` instead of `JWTPrincipal`.
   - Trainer routes use `authenticate(AUTH_API)` (or keep the `AUTH` name as an alias to the same provider so route files do not change).
   - `AuthMode.DEV` keeps working: `DevTokenVerifier` accepts `dev.<subject>` on both APIs.
2. **One configuration.** The trainer API takes its auth mode from `ServerConfig.auth`. Remove `AppConfig.auth` (`jwtSecret`, `jwtIssuer`, `jwtAudience`) or derive it, so `AUTH_MODE=firebase` cannot leave the trainer API on HS256.
3. **`/api/v1/dev/token`:** keep it only in `AuthMode.DEV`, returning `dev.<subject>` (so `TrainerTestSupport` and the app's dev mode keep working), or delete it once the app stops calling it. `TokenService` then goes away.
4. **Startup guard** stays: outside `local`, only `firebase`.
5. **WebSocket `/v1/ws`** already uses `currentUser`; it works with any bearer token the provider accepts. No change.
6. **Logging:** `PrivacyCallLogging` must never print the `Authorization` header. Add a test.

Tests: trainer endpoint accepts a valid token from a fake `TokenVerifier` and rejects an invalid one (`401`); `dev/token` is not routed outside `dev`; `FirebaseTokenVerifier` rejects a wrong audience, issuer or expired token (sign test tokens with a local RSA key and a stub JWKS provider).

## 5. Shared token plumbing in the app (slice F-2)

A shared change: it affects every remote repository, so it ships as one change with all callers updated.

1. **`TokenProvider`** in `:shared:data` (`commonMain`):
   ```kotlin
   interface TokenProvider {
       /** The current bearer token, refreshed if it expires within 5 minutes; null when signed out. */
       suspend fun token(forceRefresh: Boolean = false): String?
   }
   ```
   Implementations: `DevTokenProvider` (`dev.<subject>`) and `FirebaseTokenProvider` (§6).
2. **`ApiClient`**: `token: () -> String?` becomes `suspend () -> String?` (or takes a `TokenProvider`). On `401`, call `token(forceRefresh = true)` once and retry. Requests with a body must be repeatable for the retry.
3. **`TrainerApiClient`**: remove the `dev/token` fetch and the `subject` parameter; use the same `TokenProvider`. The existing "renew once on 401" logic maps to `forceRefresh = true`.
4. **`AppModule`**: one `single<TokenProvider>` chosen by configuration (§8), injected into both clients.
5. **Single flight:** concurrent requests that find an expired token share one refresh (`Mutex`), as `TrainerApiClient` does now.

Tests (`commonTest`, `MockEngine`): `401` → one refresh → retry succeeds; a second `401` → `Denied`/`Unavailable`, no loop; parallel calls trigger one refresh.

## 6. Firebase sign-in in the app (slice F-3)

### 6.1 `FirebaseAuthApi` (`commonMain`, Ktor)

A thin REST client. The base URLs are configurable (tests point them at a fake):

| Call | Endpoint | Used by |
|---|---|---|
| Sign up | `POST identitytoolkit.googleapis.com/v1/accounts:signUp?key=<API key>` `{email, password, returnSecureToken: true}` | `register` |
| Sign in | `POST .../accounts:signInWithPassword?key=…` | `logIn` |
| Google | `POST .../accounts:signInWithIdp?key=…` `{postBody: "id_token=<google>&providerId=google.com", requestUri, returnSecureToken: true}` | `continueWithGoogle` (F-5) |
| Refresh | `POST securetoken.googleapis.com/v1/token?key=…` `grant_type=refresh_token&refresh_token=…` | `FirebaseTokenProvider` |
| Reset (optional) | `POST .../accounts:sendOobCode?key=…` `{requestType: "PASSWORD_RESET", email}` | F6 |


**Error mapping** (Firebase returns `error.message` codes):

| Firebase | `AuthResult` |
|---|---|
| `EMAIL_EXISTS` | `AccountExists` |
| `INVALID_LOGIN_CREDENTIALS`, `EMAIL_NOT_FOUND`, `INVALID_PASSWORD`, `USER_DISABLED` | `InvalidCredentials` (never reveals which) |
| `WEAK_PASSWORD` | Should not happen: the app's validator already requires ≥ 8 characters. → `Unavailable` |
| `TOO_MANY_ATTEMPTS_TRY_LATER` | `Unavailable` (or a new `TooManyAttempts` if the team wants a specific message) |
| I/O error, timeout | `Offline` |
| Other / 5xx | `Unavailable` |
| Refresh: `TOKEN_EXPIRED`, `USER_NOT_FOUND`, `INVALID_REFRESH_TOKEN` | Session ends: clear storage, `session = null` (back to guest views) |

### 6.2 `FirebaseAuthRepository : AuthRepository`

Replaces `RemoteAuthRepository` in Firebase modes. It keeps `RemoteAuthRepository`'s `/v1/me` and `/v1/accounts` logic and swaps its identity part.

- **`logIn`**: `signInWithPassword` → store tokens → `GET /v1/me`. `404 NO_ACCOUNT` → `NeedsAccount(email)` (F4).
- **`register`**: `signUp` → store tokens → `POST /v1/accounts` (existing body, new `operationId`). If `signUp` says `EMAIL_EXISTS`, try `signInWithPassword` with the same password: success plus `404 NO_ACCOUNT` means an earlier registration stopped half way, so finish it with `POST /v1/accounts`. Otherwise → `AccountExists`. This satisfies S1's "register retry creates one user".
- **Offline after `signUp`:** the Firebase account exists, ours does not. Return `Offline`; the next log in or register finishes it (above). Offline never counts as success.
- **`completeAccount(name, role, termsVersion)`** (new method for F4): `POST /v1/accounts` with the current token.
- **`logOut`**: clear tokens and in-memory session. Firebase REST has no server-side sign-out; the refresh token is simply forgotten. (Later: `DELETE /v1/me/push-tokens/{token}`.)
- **`switchRole`**: unchanged, local only.
- **`AuthSession.userId`** stays the auth subject (now the Firebase UID), as in today's remote mode. **Audit** every use of `session.userId` (`SessionCurrentUser`, `RemoteCurrentClient`, wallet and schedule repositories) to confirm none treats it as our database UUID.

### 6.3 Token storage and session restore (F3)

- `interface SecureTokenStore { suspend fun read(): StoredTokens?; suspend fun write(t: StoredTokens); suspend fun clear() }` in `commonMain`. Store the refresh token, ID token, expiry and the last `AuthSession` (for an offline start).
- **Android:** AES-GCM key in the Android Keystore, ciphertext in private `SharedPreferences`/DataStore. (`androidx.security:security-crypto` is deprecated; do not add it.)
- **Desktop:** in-memory (dev tool only), or a file in the user directory if the team needs restarts there.
- **On start:** stored tokens → restore the session immediately (shows the role shell, works offline for cached features), then refresh and `GET /v1/me` in the background to update roles. A rejected refresh token signs the user out.
- Tokens, passwords and e-mails never reach logs, analytics or crash reports (`Registration.toString` already redacts).

### 6.4 UI states (minimal, in the existing auth screen)

- `NeedsAccount`: switch to the register tab with the e-mail prefilled and locked; password field hidden; button "Dokończ rejestrację". Copy goes into the localization resources (pl/en).
- The existing error messages cover the rest (`guest-views.md` §4.7).
- Duplicate submissions are already blocked by `AuthViewModel`; keep it so.

Tests (`commonTest`): error mapping table; register → `POST /v1/accounts` once; register retry after a half-finished registration → one account; `NeedsAccount` path; refresh failure → signed out; restore from storage; nothing secret in `toString`.

## 7. Local environment (slice F-4): dropped

The Auth emulator and its seeded demo accounts were removed on 2026-11-18. Local work signs in to the real Firebase project: register normally, or link the seeded demo trainers with `tools/firebase-link-trainers.ps1`. Run the server with `AUTH_MODE=firebase FIREBASE_PROJECT_ID=<project id>` (see `server/README.md`).

## 8. Configuration

| Property | Values | Where |
|---|---|---|
| `trainer.auth` (Gradle) | `dev` (default, today's behaviour), `firebase` | `androidApp/build.gradle.kts` → `BuildConfig`, passed into `AppModule` like `TRAINER_BACKEND` |
| `firebase.apiKey` | Web API key from the Firebase console | `local.properties` (not secret, but project specific; not committed) |
| `firebase.webClientId` | OAuth web client id (Google sign-in) | `local.properties` |
| Server `AUTH_MODE`, `FIREBASE_PROJECT_ID` | existing | env |

`google-services.json` is needed only if the official SDK or Credential Manager setup requires it; the REST approach does not. Do not commit service-account keys; the server does not need one to verify tokens.

## 9. Google sign-in (slice F-5, Android)

1. Firebase console: enable Google; add the debug (and later release) SHA-1 to the Android app.
2. Android: Credential Manager with `GetGoogleIdOption(serverClientId = webClientId)` → Google ID token. Lives in `androidApp` (or `androidMain`) behind `interface GoogleIdTokenSource { suspend fun idToken(): String? }`, because it needs an `Activity`.
3. `continueWithGoogle()`: Google ID token → `signInWithIdp` → `GET /v1/me` → `Success`, or `NeedsAccount(name, email)` → completion (F4).
4. User cancels → no error, stay on the screen. No Google account / Play Services missing → `NotSupported` message.
5. Desktop: `NotSupported` (unchanged).

Dependencies (`androidx.credentials`, `com.google.android.libraries.identity.googleid`): pick current versions at implementation time; add them to `libs.versions.toml`.

## 10. Delivery slices and order

| Slice | Content | Done when |
|---|---|---|
| F-0 | Team confirms F1–F8; Firebase project created; Email/Password enabled | Decisions recorded in this file |
| F-1 | Server: one verifier for both APIs, one config, `dev/token` limited to dev (§4) | Server tests green; trainer and client APIs accept the same token |
| F-2 | App: `TokenProvider`, suspend token in `ApiClient`, `TrainerApiClient` without `dev/token` (§5) | All existing tests green with `DevTokenProvider`; app works unchanged in `dev` |
| F-4 | Dropped: Firebase emulator and seeded demo accounts (§7) | |
| F-3 | `FirebaseAuthApi`, `FirebaseAuthRepository`, secure storage, restore, `NeedsAccount` (§6) | Log in / register / log out / restart against the real project on an Android emulator; tests from §6 green |
| F-5 | Google sign-in (§9) | Google sign-in on a device with the staging project |
| F-6 | Optional: password reset (F6) | After a design decision |

F-1 and F-2 can be done in parallel; F-4 before F-3 makes F-3 testable end to end.

## 11. Manual verification checklist

- Register a new client and a new trainer; each lands in its role shell; `app_user` has the Firebase UID as `auth_subject`.
- Wrong password and unknown e-mail show the same message.
- Register with an existing e-mail → "account exists" with the log-in shortcut.
- Airplane mode during register → "Nie wysłano"; retry later creates exactly one account.
- Kill the app and start again → still signed in; after the refresh token is revoked in the console → back to the guest views.
- Wait more than an hour (or shorten the expiry in a test) → requests keep working through a refresh.
- Trainer views (calendar, clients, finance) load with a Firebase token, not `dev/token`.
- Logcat and server logs contain no tokens, passwords or e-mails.

## 12. Risks

- **Shared change in `ApiClient`/`TrainerApiClient`** touches every remote repository; land F-2 separately and run all `commonTest`/`desktopTest` suites.
- **`session.userId` semantics** (§6.2 audit): a hidden assumption that it is our UUID would break trainer views only in Firebase mode.
- **REST token refresh** is our code, not the SDK's: the single-flight refresh and sign-out on a rejected refresh token need tests.

## 13. Implementation status (2026-11-17)

Decisions F1–F8 were accepted as proposed in §3.

| Slice | State | Where |
|---|---|---|
| F-1 | Done. The trainer API authenticates with the shared `TokenVerifier` (`trainerBearer`), its config takes `AUTH_MODE`/`FIREBASE_PROJECT_ID` from the server config, `TokenService` (HS256) is gone, `/api/v1/dev/token` returns `dev.<subject>` in dev only. Media links are signed with `MEDIA_SIGNING_KEY` (fallback `JWT_SECRET`). | `server/.../auth/Auth.kt`, `http/Auth.kt`, `platform/AppConfig.kt`, `TrainerModule.kt` |
| F-2 | Done. `TokenProvider` in `:shared:domain`; `ApiClient` (one retry after `401` for `get`/`send`, none for `raw`) and `TrainerApiClient` use it. | `domain/auth/TokenProvider.kt`, `data/remote/ApiClient.kt`, `pl/trainer/data/remote/TrainerApiClient.kt` |
| F-3 | Done. `FirebaseAuthApi` (REST), `FirebaseTokenProvider` (single-flight refresh 5 min before expiry), `TokenStore` (Android: AES-GCM key in the Keystore), `FirebaseAuthRepository`, `AuthResult.NeedsAccount`/`Cancelled`/`TooManyAttempts`, account completion form, session restore + background `/v1/me` revalidation, return to sign-in when a session ends by itself. | `:shared:data` `data/auth/`, `:shared:feature:guest`, `shared/app/.../AppModule.kt`, `App.kt`, `androidApp/.../auth/` |
| F-4 | Dropped (2026-11-18). No emulator: the image, seed, compose service, `emulator` mode in the app and `firebase-emulator` mode on the server were removed. | |
| F-5 | Code done (Credential Manager + `signInWithIdp`); needs a real Firebase project, SHA-1 and web client id to try on a device. | `androidApp/.../CredentialManagerGoogleIdTokens.kt` |
| F-6 | Not started (optional). | |

Verified: server tests (all 69, incl. `TokenVerifierTest`, `TrainerAuthTest`); `FirebaseAuthRepositoryTest` (16) and `AuthCompletionViewModelTest` (3) with a fake Firebase and server; `RemoteTrainerRepositoryTest` token renewal; `:androidApp:assembleDebug`.

Verified by hand on 2026-11-18 (real Firebase project, Android emulator): Google registration and sign-in; e-mail registration for client and trainer; same message for a wrong password and an unknown e-mail; registration with an existing e-mail; log out and back in; airplane mode during registration followed by a retry; restart with the session kept, and return to the guest views once the user is disabled.

Not verified yet: trainer and client views with a Firebase token (checklist §11), token refresh after an hour, and logs free of tokens and e-mails.

Notes:
- **Android HTTP engine:** Ktor CIO's TLS calls the trust manager without the host name, which Android rejects when the app has a domain-specific network security config (the debug `10.0.2.2` exception): every HTTPS call to Firebase failed as "offline". `AppPlatform.createHttpEngine()` gives Android OkHttp for the backend and Firebase clients (`HttpEngines`); desktop keeps CIO. Verified on the emulator against a real Firebase project.
- **Request encoding:** `FirebaseAuthApi` must encode defaults (`returnSecureToken`, `requestUri`). Without `returnSecureToken` the real Firebase answers a password sign-in with no refresh token (the old emulator always sent one), so log-in failed as "service unavailable" while registration worked. The fake Firebase in `FirebaseAuthRepositoryTest` now follows the real behaviour. Verified on the emulator: log in, restart, still signed in.
- `:server:run` reads `trainer.auth` and `firebase.projectId` from `local.properties`; environment variables win.
- `trener-klient@example.com` is not a Firebase account: in Firebase one e-mail is one user, and `trener@example.com` (`demo-trainer`) already holds both roles in the dev data.
- A physical phone cannot reach the server at `10.0.2.2`; debug builds allow plain HTTP only to `10.0.2.2`/`localhost` (use `adb reverse`).
- Firebase's default "one account per e-mail" lets a Google sign-in take over an unverified password account with the same e-mail.
- Pre-existing failures on `main`, unchanged by this work: `TrainerFinanceViewModelTest` (number spacing) and two `GuestIntegrationTest` cases waiting for "12 wyników".
