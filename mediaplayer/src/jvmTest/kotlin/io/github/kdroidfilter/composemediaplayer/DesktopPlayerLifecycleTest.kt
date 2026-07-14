package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlayerLifecycleTest {
    @Test
    fun unixFileUriNormalizationDecodesEscapedPathSegments() {
        val directory = Files.createTempDirectory("compose media player ")
        val file = Files.createFile(directory.resolve("sample video.mp4"))
        try {
            assertEquals(file.toString(), normalizeUnixLocalFileUriForPlayback(file.toUri().toString()))
            assertEquals(
                file.toString(),
                normalizeUnixLocalFileUriForPlayback("file://localhost${file.toUri().rawPath}"),
            )
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun newestSourceOperationWins() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val calls = mutableListOf<String>()

            lifecycle.launchSourceOperation { calls += "old" }
            lifecycle.launchSourceOperation { calls += "new" }
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("new"), calls)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun staleSourceCannotCommitAfterNewSourceIsScheduled() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            var oldCommitted = true
            var newRan = false

            lifecycle.launchSourceOperation { generation ->
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    oldCommitted = lifecycle.commitCurrentSource(generation) {}
                }
            }
            testScheduler.runCurrent()
            oldStarted.await()

            lifecycle.launchSourceOperation { newRan = true }
            releaseOld.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertFalse(oldCommitted)
            assertTrue(newRan)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun sourceBoundControlIsSkippedWhenANewerSourceIsScheduled() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            var controlRan = false

            lifecycle.launchSourceBoundControlOperation { controlRan = true }
            lifecycle.launchSourceOperation {}
            testScheduler.advanceUntilIdle()

            assertFalse(controlRan)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun runningSourceBoundControlIsCancelledAndCannotCommitForANewerSource() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val controlStarted = CompletableDeferred<Unit>()
            val releaseControl = CompletableDeferred<Unit>()
            var controlCommitted = true

            lifecycle.launchSourceBoundControlOperation { generation ->
                withContext(NonCancellable) {
                    controlStarted.complete(Unit)
                    releaseControl.await()
                    controlCommitted = lifecycle.commitCurrentSource(generation) {}
                }
            }
            testScheduler.runCurrent()
            controlStarted.await()

            lifecycle.launchSourceOperation {}
            releaseControl.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertFalse(controlCommitted)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun nativeSeekCannotOverlapInstallingAReplacementSource() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val seekStarted = CompletableDeferred<Unit>()
            val releaseNativeSeek = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var nativeSource = "old"

            lifecycle.launchSourceBoundControlOperation {
                withContext(NonCancellable) {
                    events += "seek-start-$nativeSource"
                    seekStarted.complete(Unit)
                    releaseNativeSeek.await()
                    events += "seek-end-$nativeSource"
                }
            }
            testScheduler.runCurrent()
            seekStarted.await()

            lifecycle.launchSourceOperation {
                nativeSource = "new"
                events += "source-installed-$nativeSource"
            }
            testScheduler.runCurrent()

            assertEquals(listOf("seek-start-old"), events)
            releaseNativeSeek.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("seek-start-old", "seek-end-old", "source-installed-new"),
                events,
            )
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun sourceBoundSeekCannotOvertakeAnAlreadyScheduledSourceOnAReorderingDispatcher() {
        val dispatcher = LifoTestDispatcher()
        val operationScope = CoroutineScope(dispatcher + SupervisorJob())
        val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
        val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
        val events = mutableListOf<String>()

        lifecycle.launchSourceOperation { events += "source-installed" }
        lifecycle.launchSourceBoundControlOperation { events += "seek" }

        dispatcher.runNext()
        assertTrue(events.isEmpty(), "Seek must wait for the source job even when dispatched first")
        dispatcher.runNext()
        assertEquals(listOf("source-installed"), events)
        dispatcher.runNext()
        assertEquals(listOf("source-installed", "seek"), events)

        operationScope.cancel()
        cleanupScope.cancel()
    }

    @Test
    fun sourceBoundControlsAreSerialized() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var secondRan = false

            lifecycle.launchSourceBoundControlOperation {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            lifecycle.launchSourceBoundControlOperation { secondRan = true }
            testScheduler.runCurrent()

            assertTrue(firstStarted.isCompleted)
            assertFalse(secondRan)
            releaseFirst.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertTrue(secondRan)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun disposeCancelsSourceBoundControlBeforeCleanup() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val controlStarted = CompletableDeferred<Unit>()
            var controlCompleted = false
            var cleaned = false

            lifecycle.launchSourceBoundControlOperation {
                controlStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
                controlCompleted = true
            }
            testScheduler.runCurrent()
            controlStarted.await()

            lifecycle.dispose { cleaned = true }
            testScheduler.advanceUntilIdle()

            assertFalse(controlCompleted)
            assertTrue(cleaned)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun disposeCancelsOperationsBeforeCleanupAndIsIdempotent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operationScope = CoroutineScope(dispatcher + SupervisorJob())
            val cleanupScope = CoroutineScope(dispatcher + SupervisorJob())
            val lifecycle = DesktopPlayerLifecycle(operationScope, cleanupScope)
            val operationStarted = CompletableDeferred<Unit>()
            var operationCompleted = false
            var cleaned = false

            lifecycle.launchSourceOperation {
                operationStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
                operationCompleted = true
            }
            testScheduler.runCurrent()
            operationStarted.await()

            val cleanupJob = lifecycle.dispose { cleaned = true }
            assertTrue(lifecycle.isDisposed)
            assertEquals(null, lifecycle.dispose { error("must not run") })
            testScheduler.advanceUntilIdle()

            assertFalse(operationCompleted)
            assertTrue(cleaned)
            assertTrue(cleanupJob?.isCompleted == true)
            operationScope.cancel()
            cleanupScope.cancel()
        }

    @Test
    fun commandsAfterDisposeAreRejected() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val lifecycle =
                DesktopPlayerLifecycle(
                    CoroutineScope(dispatcher + Job()),
                    CoroutineScope(dispatcher + Job()),
                )
            lifecycle.dispose {}

            val error = assertFailsWith<IllegalStateException> { lifecycle.launchControlOperation {} }
            assertEquals("VideoPlayerState has been disposed", error.message)
            val sourceBoundError =
                assertFailsWith<IllegalStateException> {
                    lifecycle.launchSourceBoundControlOperation {}
                }
            assertEquals("VideoPlayerState has been disposed", sourceBoundError.message)
        }
}

private class LifoTestDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.addLast(block)
    }

    fun runNext() {
        tasks.removeLast().run()
    }
}
