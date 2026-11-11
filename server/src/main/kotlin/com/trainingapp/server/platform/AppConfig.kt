package com.trainingapp.server.platform

import org.jetbrains.exposed.v1.core.and
import java.util.Base64

/**
 * Server settings, read from environment variables so the same build runs locally (docker compose) and on AWS.
 * Defaults are for local development only; production must set every secret explicitly.
 */
data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    /** Loads the synthetic demo users and data (db/dev). Never enable in production. */
    val devSeed: Boolean,
    val auth: AuthConfig,
    /** AES-256 key for private columns such as client information. KMS later. */
    val dataKeyId: String,
    val dataKey: ByteArray,
    /** Where uploaded media is stored in local development (S3 later). */
    val mediaDir: String,
    /** Public base URL used in signed media links. */
    val publicBaseUrl: String,
    /** Run the background sweeper (request expiry, outbox). Tests drive it by hand. */
    val runWorker: Boolean = true,
) {
    companion object {
        /** 32 bytes, local development only. */
        private const val DEV_KEY = "ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZCE="

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(name: String, default: String) = env[name]?.takeIf { it.isNotBlank() } ?: default
            val authMode = get("AUTH_MODE", "dev")
            val config = AppConfig(
                port = get("PORT", "8080").toInt(),
                dbUrl = get("DB_URL", "jdbc:postgresql://localhost:5432/trainer"),
                dbUser = get("DB_USER", "trainer"),
                dbPassword = get("DB_PASSWORD", "trainer-dev"),
                devSeed = get("DEV_SEED", if (authMode == "dev") "true" else "false").toBoolean(),
                auth = AuthConfig(
                    mode = authMode,
                    jwtSecret = get("JWT_SECRET", "dev-only-secret-change-me-0123456789"),
                    jwtIssuer = get("JWT_ISSUER", "trainer-app-dev"),
                    jwtAudience = get("JWT_AUDIENCE", "trainer-app"),
                ),
                dataKeyId = get("DATA_KEY_ID", "dev-1"),
                dataKey = Base64.getDecoder().decode(get("DATA_KEY", DEV_KEY)),
                mediaDir = get("MEDIA_DIR", "build/media"),
                publicBaseUrl = get("PUBLIC_BASE_URL", "http://localhost:8080"),
                runWorker = get("RUN_WORKER", "true").toBoolean(),
            )
            require(config.dataKey.size == 32) { "DATA_KEY must be 32 bytes, base64" }
            return config
        }
    }
}

/**
 * [mode] "dev": the server signs its own HS256 tokens (POST /api/v1/dev/token) for the seeded demo users.
 * "jwt": verifies HS256 tokens from [jwtIssuer]; switching to the chosen identity provider (RS256/JWKS) only changes
 * the verifier in Auth.kt. Endpoints only see [com.trainingapp.server.auth.Caller].
 */
data class AuthConfig(val mode: String, val jwtSecret: String, val jwtIssuer: String, val jwtAudience: String) {
    val isDev get() = mode == "dev"
}
