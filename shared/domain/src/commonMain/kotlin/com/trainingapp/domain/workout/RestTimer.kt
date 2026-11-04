package com.trainingapp.domain.workout

/**
 * The rest after a ticked set. Only the end time is stored, never a countdown, so the time left is right after the
 * app comes back from the background or is restarted. The in-app bar and the system notification read the same [endsAtEpochMs].
 */
data class RestTimer(
    val endsAtEpochMs: Long,
    /** The set whose tick started the rest. */
    val afterSetId: String,
    /** The length the rest started with plus adjustments; used for progress only. */
    val totalSeconds: Int,
) {
    fun remainingMs(nowEpochMs: Long): Long = (endsAtEpochMs - nowEpochMs).coerceAtLeast(0)

    /** Whole seconds left, rounded up, so "0:01" shows until the rest is really over. */
    fun remainingSeconds(nowEpochMs: Long): Int = ((remainingMs(nowEpochMs) + 999) / 1000).toInt()

    fun isOver(nowEpochMs: Long): Boolean = nowEpochMs >= endsAtEpochMs

    /** −15 s / +15 s. The end never moves before [nowEpochMs]. */
    fun adjusted(deltaSeconds: Int, nowEpochMs: Long): RestTimer {
        val end = maxOf(nowEpochMs, endsAtEpochMs + deltaSeconds * 1000L)
        return copy(endsAtEpochMs = end, totalSeconds = (totalSeconds + deltaSeconds).coerceAtLeast(0))
    }

    companion object {
        const val DEFAULT_SECONDS = 90
        const val ADJUST_STEP_SECONDS = 15

        fun start(afterSetId: String, seconds: Int, nowEpochMs: Long) =
            RestTimer(nowEpochMs + seconds * 1000L, afterSetId, seconds)
    }
}
