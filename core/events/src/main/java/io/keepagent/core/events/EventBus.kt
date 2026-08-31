package io.keepagent.core.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus (spec §9). Every core module and add-on surfaces its
 * activity here; the Console tab and [EventLog] consume it.
 *
 * Emit is non-suspending and never blocks: the bus is a SharedFlow with a
 * replay window for late subscribers and a drop-oldest buffer for bursts.
 */
class EventBus(private val log: EventLog? = null) {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 200,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live stream; includes recent replayed events for late subscribers. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile
    private var seq = 0L

    fun emit(
        kind: EventKind,
        source: String,
        summary: String,
        detail: Map<String, String> = emptyMap(),
    ) {
        val next = synchronized(this) { ++seq }
        val event = AgentEvent(next, System.currentTimeMillis(), kind, source, summary, detail)
        _events.tryEmit(event)
        log?.append(event)
    }
}
