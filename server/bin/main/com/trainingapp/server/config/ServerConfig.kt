package com.trainingapp.server.config

/**
 * Server settings, read from environment variables. Defaults suit the `local` profile with docker-compose.
 * Secrets have no production default: a non-local profile refuses to start without them.
 */
data class ServerConfig(
    val profile: Profile,
    val port: Int,
    val db: DbConfig,
    val auth: AuthConfig,
    val media: MediaConfig,
    val payments: PaymentConfig,
    /** Run the worker loops inside the API process (local profile, single-instance hosting). */
    val workerInline: Boolean,
    /** Insert the synthetic demo data on start when the database has no users. Local only. */
    val seedDemoData: Boolean,
) {
    val isLocal: Boolean get() = profile == Profile.LOCAL

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val profile = Profile.parse(env["TRAINER_PROFILE"] ?: "local")
            val local = profile == Profile.LOCAL
            fun secret(name: String, localDefault: String): String =
                env[name] ?: if (local) localDefault else error("$name must be set outside the local profile")
            val dbUrl = env["DB_URL"] ?: "jdbc:postgresql://localhost:5432/trainer"
            return ServerConfig(
                profile = profile,
                port = env["PORT"]?.toInt() ?: 8080,
                db = DbConfig(
                    url = dbUrl,
                    appUser = env["DB_APP_USER"] ?: "trainer_app",
                    appPassword = secret("DB_APP_PASSWORD", "trainer_app"),
                    ownerUser = env["DB_OWNER_USER"] ?: "trainer_owner",
                    ownerPassword = secret("DB_OWNER_PASSWORD", "trainer_owner"),
                    migrateOnStart = env["DB_MIGRATE_ON_START"]?.toBooleanStrict() ?: local,
                    logSql = local && env["DB_LOG_SQL"]?.toBooleanStrict() == true,
                ),
                auth = AuthConfig(
                    mode = AuthMode.parse(env["AUTH_MODE"] ?: if (local) "dev" else "firebase"),
                    firebaseProjectId = env["FIREBASE_PROJECT_ID"] ?: "demo-trainer",
                ),
                media = MediaConfig(
                    storageDir = env["MEDIA_STORAGE_DIR"] ?: "build/media-storage",
                    publicBaseUrl = env["PUBLIC_BASE_URL"] ?: "http://localhost:${env["PORT"] ?: "8080"}",
                    signingKey = secret("MEDIA_SIGNING_KEY", "local-media-signing-key"),
                    playbackTtlSeconds = 15 * 60,
                ),
                payments = PaymentConfig(
                    provider = env["PAYMENT_PROVIDER"] ?: "fake",
                    fakeWebhookSecret = secret("FAKE_PAYMENT_WEBHOOK_SECRET", "local-fake-webhook-secret"),
                    fakeConfirmDelaySeconds = env["FAKE_PAYMENT_DELAY_SECONDS"]?.toLong() ?: 3,
                ),
                workerInline = env["WORKER_INLINE"]?.toBooleanStrict() ?: true,
                seedDemoData = local && (env["SEED_DEMO_DATA"]?.toBooleanStrict() ?: true),
            )
        }
    }
}

enum class Profile {
    LOCAL, STAGING, PRODUCTION;

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Unknown TRAINER_PROFILE '$value'")
    }
}

data class DbConfig(
    val url: String,
    /** DML-only role the API connects as (database-design §4). */
    val appUser: String,
    val appPassword: String,
    /** Schema owner, used only by Flyway. */
    val ownerUser: String,
    val ownerPassword: String,
    val migrateOnStart: Boolean,
    /** Statements contain private values, so SQL logging is local only. */
    val logSql: Boolean,
)

enum class AuthMode {
    /** `Authorization: Bearer dev.<subject>` — local development and tests only. */
    DEV,

    /** Firebase ID tokens verified against Google's public keys. */
    FIREBASE,

    /** Unsigned tokens from the Firebase Auth emulator. Local only. */
    FIREBASE_EMULATOR;

    companion object {
        fun parse(value: String) = when (value.lowercase()) {
            "dev" -> DEV
            "firebase" -> FIREBASE
            "firebase-emulator", "emulator" -> FIREBASE_EMULATOR
            else -> error("Unknown AUTH_MODE '$value'")
        }
    }
}

data class AuthConfig(val mode: AuthMode, val firebaseProjectId: String)

data class MediaConfig(
    val storageDir: String,
    val publicBaseUrl: String,
    val signingKey: String,
    val playbackTtlSeconds: Long,
)

data class PaymentConfig(
    /** `fake` (synthetic provider, confirmed by the worker through the webhook path) — Stripe test mode later. */
    val provider: String,
    val fakeWebhookSecret: String,
    val fakeConfirmDelaySeconds: Long,
)
