package com.trainingapp.domain.common

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Creates client-side ids. Injected so reducers and tests stay deterministic. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Uuids = IdGenerator { Uuid.random().toString() }
    }
}

fun interface TimeSource {
    fun nowEpochMs(): Long

    companion object {
        val System = TimeSource { Clock.System.now().toEpochMilliseconds() }
    }
}

/** Whether the server can be reached. Server-dependent actions check it and never fake success while offline. */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}

/** The signed-in trainer. Auth is not implemented yet; the app wires a demo session. */
interface CurrentUser {
    val trainerId: String
}
