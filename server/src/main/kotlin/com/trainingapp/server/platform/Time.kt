package com.trainingapp.server.platform

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** The server clock. Fees, expiry and eligibility always use it, never a device time. Tests pass a settable clock. */
fun interface ServerClock {
    fun now(): Instant

    companion object {
        val System = ServerClock { kotlin.time.Clock.System.now().toMicros() }
    }
}

/** PostgreSQL stores microseconds; truncating keeps a stored instant equal to the one the server computed. */
fun Instant.toMicros(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1000 * 1000)

fun OffsetDateTime.toInstantK(): Instant = toInstant().toKotlinInstant()
fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(toJavaInstant(), ZoneOffset.UTC)
