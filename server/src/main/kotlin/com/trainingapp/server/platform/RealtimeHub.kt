package com.trainingapp.server.platform

import com.trainingapp.contract.chat.LiveEventDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Live "something changed" signals for open WebSockets, fed by the worker from the outbox. The stored rows stay the
 * truth: a missed signal only delays a refresh. In-process: with a separate worker process the app falls back to
 * push and refresh-on-resume.
 */
class RealtimeHub {
    private val flows = ConcurrentHashMap<Uuid, MutableSharedFlow<LiveEventDto>>()

    fun subscribe(userId: Uuid): SharedFlow<LiveEventDto> = flows.computeIfAbsent(userId) {
        MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }.asSharedFlow()

    fun publish(userId: Uuid, event: LiveEventDto) {
        flows[userId]?.tryEmit(event)
    }
}
