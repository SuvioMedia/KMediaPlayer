package io.github.kdroidfilter.composemediaplayer.mac

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConflatedBackgroundActionTest {
    @Test
    fun actionRunsOffTheRequestingThread() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mac-native-refresh") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val completed = CountDownLatch(1)
            val actionThread = AtomicReference<Thread>()
            val requestingThread = Thread.currentThread()
            val worker =
                ConflatedBackgroundAction(scope) {
                    actionThread.set(Thread.currentThread())
                    completed.countDown()
                }

            try {
                worker.request()

                assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertNotEquals(requestingThread, actionThread.get())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun burstDuringAnActiveRunProducesOneFollowUpRun() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mac-native-refresh") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val firstRunStarted = CountDownLatch(1)
            val releaseFirstRun = CountDownLatch(1)
            val twoRunsCompleted = CountDownLatch(2)
            val executionCount = AtomicInteger(0)
            val worker =
                ConflatedBackgroundAction(scope) {
                    if (executionCount.incrementAndGet() == 1) {
                        firstRunStarted.countDown()
                        assertTrue(releaseFirstRun.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                    twoRunsCompleted.countDown()
                }

            try {
                worker.request()
                assertTrue(firstRunStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                repeat(100) { worker.request() }
                releaseFirstRun.countDown()

                assertTrue(twoRunsCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val queueDrained = CountDownLatch(1)
                executor.execute(queueDrained::countDown)
                assertTrue(queueDrained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(2, executionCount.get())
            } finally {
                releaseFirstRun.countDown()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
