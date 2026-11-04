package com.trainingapp.feature.workoutlogging.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.trainingapp.domain.common.TimeSource
import kotlinx.coroutines.delay

/**
 * The current time, refreshed a few times per second for the elapsed clock and the rest bar. Both are computed from
 * stored instants, so a pause in ticking (app in background) never makes them wrong.
 */
@Composable
fun rememberNowEpochMs(time: TimeSource = TimeSource.System, periodMs: Long = 250): Long {
    val now by produceState(time.nowEpochMs(), time, periodMs) {
        while (true) {
            value = time.nowEpochMs()
            delay(periodMs)
        }
    }
    return now
}
