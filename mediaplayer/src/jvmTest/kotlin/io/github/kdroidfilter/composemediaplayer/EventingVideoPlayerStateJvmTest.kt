package io.github.kdroidfilter.composemediaplayer

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class EventingVideoPlayerStateJvmTest {
    @Test
    fun openFileSourcePreparingUsesPlatformFilePath() =
        runTest {
            val tempFile = Files.createTempFile("compose-media-player-", ".mp4")
            val state = EventingVideoPlayerState(PreviewableVideoPlayerState())
            val events = collectEvents(state)

            try {
                state.openFile(PlatformFile(tempFile.toFile()), InitialPlayerState.PAUSE)
                runCurrent()

                val preparing = assertIs<PlaybackEvent.SourcePreparing>(events.first())
                assertEquals(tempFile.toString(), preparing.uri)
            } finally {
                state.dispose()
                Files.deleteIfExists(tempFile)
            }
        }

    private fun TestScope.collectEvents(state: VideoPlayerState): MutableList<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.playbackEvents.collect { event ->
                events += event
            }
        }
        return events
    }
}
