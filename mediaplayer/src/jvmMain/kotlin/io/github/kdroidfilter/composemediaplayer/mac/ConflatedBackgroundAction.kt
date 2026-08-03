package io.github.kdroidfilter.composemediaplayer.mac

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Runs an action off the caller thread while collapsing bursts into the latest pending request. */
internal class ConflatedBackgroundAction(
    private val scope: CoroutineScope,
    private val action: () -> Unit,
) {
    private val requested = AtomicBoolean(false)
    private val workerActive = AtomicBoolean(false)

    fun request() {
        requested.set(true)
        startWorker()
    }

    private fun startWorker() {
        if (!workerActive.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (requested.getAndSet(false)) {
                    action()
                }
            } finally {
                workerActive.set(false)
                // Cover a request racing the worker's final empty check.
                if (requested.get()) startWorker()
            }
        }
    }
}
