package com.trainingapp.server.common

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/** PostgreSQL stores microseconds: compare and store client instants at that precision. */
fun Instant.toMicros(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1000 * 1000)

fun OffsetDateTime.toKotlin(): Instant = toInstant().toKotlinInstant()
fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(toJavaInstant(), ZoneOffset.UTC)
fun Instant.toJava(): java.time.Instant = toJavaInstant()
fun java.time.Instant.toKotlin(): Instant = toKotlinInstant()

/**
 * The database clock of the current transaction (`now()` is the transaction start time in PostgreSQL). Server-owned
 * timestamps (`requested_at`, `received_at`, …) and every "today/next" decision use it, never the request or the JVM
 * clock (plan §3 rule 7).
 */
fun JdbcTransaction.dbNow(): Instant {
    var result: OffsetDateTime? = null
    exec("SELECT now()") { rs -> if (rs.next()) result = rs.getObject(1, OffsetDateTime::class.java) }
    return result!!.toKotlin()
}

/** UUIDv7 (time-ordered) ids for rows the server creates (DB7). Rows created on the phone keep the phone's id. */
object Ids {
    private val random = SecureRandom()

    fun v7(nowMs: Long = System.currentTimeMillis()): Uuid {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }

    /**
     * A stable id derived from another id, e.g. the charge `operation_id` derived from a fee decision's
     * `operation_id`, so a retried command derives the same key and hits the same unique constraint.
     */
    fun derived(base: Uuid, purpose: String): Uuid {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$base:$purpose".encodeToByteArray())
        val bytes = digest.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }
}

/** The JSON configuration used for API bodies and jsonb columns. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun initialsOf(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
