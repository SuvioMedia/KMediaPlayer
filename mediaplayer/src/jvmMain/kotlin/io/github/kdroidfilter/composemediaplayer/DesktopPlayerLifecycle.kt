package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun normalizeUnixLocalFileUriForPlayback(uri: String): String {
    if (!uri.startsWith("file:", ignoreCase = true)) return uri

    val parsed =
        runCatching { URI(uri) }
            .getOrNull()
            ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?: return uri.removePrefix("file:").removePrefix("//")
    val authority = parsed.authority
    if (!authority.isNullOrBlank() && !authority.equals("localhost", ignoreCase = true)) {
        return "//$authority${parsed.path.orEmpty()}"
    }

    val localUri =
        if (authority.equals("localhost", ignoreCase = true)) {
            URI("file", null, parsed.path, null)
        } else {
            parsed
        }
    return runCatching { Path.of(localUri).toString() }
        .getOrElse { parsed.path ?: uri.removePrefix("file:").removePrefix("//") }
}

/**
 * Serializes desktop media commands and owns the terminal transition to disposed.
 *
 * Source transitions are latest-wins: starting a newer transition cancels the previous one and
 * increments a generation checked before and after the operation. Control operations are serialized
 * with source transitions but do not invalidate the current source.
 */
internal class DesktopPlayerLifecycle(
    private val operationScope: CoroutineScope,
    private val cleanupScope: CoroutineScope,
) {
    private val stateLock = Any()
    private val operationMutex = Mutex()
    private val disposed = AtomicBoolean(false)
    private val sourceGeneration = AtomicLong(0L)
    private var sourceJob: Job? = null
    private val sourceBoundControlJobs = mutableSetOf<Job>()

    val isDisposed: Boolean
        get() = disposed.get()

    fun ensureUsable() {
        check(!isDisposed) { "VideoPlayerState has been disposed" }
    }

    fun isCurrentSource(generation: Long): Boolean = !isDisposed && sourceGeneration.get() == generation

    /** Commits non-suspending source state atomically against scheduling a newer source. */
    fun commitCurrentSource(
        generation: Long,
        block: () -> Unit,
    ): Boolean =
        synchronized(stateLock) {
            if (!isCurrentSource(generation)) return@synchronized false
            block()
            true
        }

    suspend fun ensureCurrentSource(generation: Long) {
        if (!isCurrentSource(generation)) {
            throw CancellationException("Media source operation was superseded")
        }
    }

    fun launchSourceOperation(
        onScheduled: (generation: Long) -> Unit = {},
        block: suspend (generation: Long) -> Unit,
    ): Job {
        ensureUsable()

        lateinit var newJob: Job
        synchronized(stateLock) {
            ensureUsable()
            val generation = sourceGeneration.incrementAndGet()
            onScheduled(generation)
            val previousJob = sourceJob
            sourceBoundControlJobs.forEach(Job::cancel)
            sourceBoundControlJobs.clear()
            newJob =
                operationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    operationMutex.withLock {
                        if (!isCurrentSource(generation)) return@withLock
                        block(generation)
                    }
                }
            sourceJob = newJob
            previousJob?.cancel()
        }
        newJob.start()
        return newJob
    }

    fun launchControlOperation(block: suspend () -> Unit): Job {
        ensureUsable()
        return operationScope.launch {
            operationMutex.withLock {
                if (!isActive || isDisposed) return@withLock
                block()
            }
        }
    }

    /**
     * Launches a control operation tied to the source that is current when the call is scheduled.
     * A newer source transition cancels the job; the captured generation lets callers gate any
     * non-suspending UI/error commit after a native call.
     */
    fun launchSourceBoundControlOperation(
        onScheduled: (generation: Long) -> Unit = {},
        block: suspend (generation: Long) -> Unit,
    ): Job {
        ensureUsable()

        lateinit var newJob: Job
        synchronized(stateLock) {
            ensureUsable()
            val generation = sourceGeneration.get()
            val prerequisiteSourceJob = sourceJob
            onScheduled(generation)
            newJob =
                operationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        // A control scheduled immediately after a source transition must not race
                        // ahead if dispatcher threads start the two lazy jobs out of order.
                        prerequisiteSourceJob?.join()
                        operationMutex.withLock {
                            ensureCurrentSource(generation)
                            block(generation)
                            ensureCurrentSource(generation)
                        }
                    } finally {
                        synchronized(stateLock) {
                            sourceBoundControlJobs.remove(newJob)
                        }
                    }
                }
            sourceBoundControlJobs += newJob
        }
        newJob.start()
        return newJob
    }

    /**
     * Marks the lifecycle terminal immediately, cancels all operation-scope work, then performs
     * cleanup from an independent scope. Repeated calls are idempotent.
     */
    fun dispose(cleanup: suspend () -> Unit): Job? {
        if (!disposed.compareAndSet(false, true)) return null

        sourceGeneration.incrementAndGet()
        synchronized(stateLock) {
            sourceJob?.cancel()
            sourceJob = null
            sourceBoundControlJobs.forEach(Job::cancel)
            sourceBoundControlJobs.clear()
        }

        val operationRootJob = operationScope.coroutineContext[Job]
        operationRootJob?.cancel()
        return cleanupScope.launch {
            operationRootJob?.cancelAndJoin()
            operationMutex.withLock { cleanup() }
        }
    }
}
