package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Clock

internal class PlaybackEventDispatcher(
    initialMediaSessionId: Long = 0L,
) {
    private val stateLock = PlatformLock()
    private val _events =
        MutableSharedFlow<PlaybackEvent>(
            replay = 0,
            extraBufferCapacity = EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private var currentMediaSessionId: Long = initialMediaSessionId

    val mediaSessionId: Long
        get() = stateLock.withLock { currentMediaSessionId }

    fun nextMediaSessionId(): Long =
        stateLock.withLock {
            currentMediaSessionId += 1
            currentMediaSessionId
        }

    fun emit(factory: (Long, Long) -> PlaybackEvent) {
        stateLock.withLock {
            _events.tryEmit(factory(currentMediaSessionId, Clock.System.now().toEpochMilliseconds()))
        }
    }

    fun emitForSession(
        sessionId: Long,
        factory: (Long, Long) -> PlaybackEvent,
    ) {
        stateLock.withLock {
            _events.tryEmit(factory(sessionId, Clock.System.now().toEpochMilliseconds()))
        }
    }

    companion object {
        private const val EVENT_BUFFER_CAPACITY = 64
    }
}
