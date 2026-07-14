package io.github.kdroidfilter.composemediaplayer

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventingVideoPlayerStateConcurrencyTest {
    @Test
    fun lateCompletionOfOlderOpenCannotReplaceNewerEventSession() =
        runBlocking {
            val delegate = BlockingVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = CopyOnWriteArrayList<PlaybackEvent>()
            val collector =
                launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    state.playbackEvents.collect { event -> events += event }
                }
            val executor = Executors.newSingleThreadExecutor()

            try {
                val oldOpen = executor.submit { state.openUri(OLD_URI) }
                assertTrue(delegate.oldOpenEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                state.openUri(NEW_URI)
                delegate.allowOldOpenToFinish.countDown()
                oldOpen.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                withTimeout(TIMEOUT_MILLIS) {
                    while (events.none { it is PlaybackEvent.SourceLoaded && it.mediaSessionId == 2L }) {
                        delay(POLL_INTERVAL_MILLIS)
                    }
                }

                assertEquals(2L, state.mediaSessionId)
                assertEquals(
                    listOf(1L, 2L),
                    events.filterIsInstance<PlaybackEvent.SourcePreparing>().map { it.mediaSessionId },
                )
                assertEquals(
                    listOf(1L),
                    events.filterIsInstance<PlaybackEvent.SourceReleased>().map { it.mediaSessionId },
                )
                assertEquals(
                    listOf(2L),
                    events.filterIsInstance<PlaybackEvent.SourceLoaded>().map { it.mediaSessionId },
                )
            } finally {
                delegate.allowOldOpenToFinish.countDown()
                state.dispose()
                collector.cancelAndJoin()
                executor.shutdownNow()
            }
        }

    @Test
    fun concurrentReleaseEmitsExactlyOneTerminalEvent() =
        runBlocking {
            val delegate = BlockingVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = CopyOnWriteArrayList<PlaybackEvent>()
            val collector =
                launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    state.playbackEvents.collect { event -> events += event }
                }
            val executor = Executors.newFixedThreadPool(2)

            try {
                state.openUri(NEW_URI)
                delegate.blockReleases = true
                val releases = List(2) { executor.submit { state.releaseSource() } }
                assertTrue(delegate.releaseCallsEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                delegate.allowReleasesToFinish.countDown()
                releases.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

                assertEquals(2L, state.mediaSessionId)
                assertEquals(
                    listOf(1L),
                    events.filterIsInstance<PlaybackEvent.SourceReleased>().map { it.mediaSessionId },
                )
            } finally {
                delegate.allowReleasesToFinish.countDown()
                state.dispose()
                collector.cancelAndJoin()
                executor.shutdownNow()
            }
        }

    private class BlockingVideoPlayerState : VideoPlayerState by PreviewableVideoPlayerState(hasMedia = false) {
        val oldOpenEntered = CountDownLatch(1)
        val allowOldOpenToFinish = CountDownLatch(1)
        val releaseCallsEntered = CountDownLatch(2)
        val allowReleasesToFinish = CountDownLatch(1)

        @Volatile
        private var mediaLoaded = false

        @Volatile
        var blockReleases = false

        override val hasMedia: Boolean
            get() = mediaLoaded

        override fun openUri(
            uri: String,
            initializePlayerState: InitialPlayerState,
            requestHeaders: Map<String, String>,
        ) {
            if (uri == OLD_URI) {
                oldOpenEntered.countDown()
                assertTrue(allowOldOpenToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
            mediaLoaded = true
        }

        override fun openFile(
            file: PlatformFile,
            initializePlayerState: InitialPlayerState,
        ) = Unit

        override fun releaseSource() {
            if (blockReleases) {
                releaseCallsEntered.countDown()
                assertTrue(allowReleasesToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
            mediaLoaded = false
        }
    }

    private companion object {
        private const val OLD_URI = "https://example.test/old.mp4"
        private const val NEW_URI = "https://example.test/new.mp4"
        private const val TIMEOUT_SECONDS = 5L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val POLL_INTERVAL_MILLIS = 10L
    }
}
