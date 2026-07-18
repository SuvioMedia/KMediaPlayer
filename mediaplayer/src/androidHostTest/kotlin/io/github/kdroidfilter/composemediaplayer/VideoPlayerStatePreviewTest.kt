package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerStatePreviewTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createStateWithoutInitializedContextProviderDoesNotThrow() {
        val playerState: VideoPlayerState =
            createAndroidVideoPlayerState(
                ensureContextAvailable = { error("ContextProvider unavailable") },
                createState = { error("Player constructor must not run without context") },
            )
        assertIs<PreviewableVideoPlayerState>(playerState)
        playerState.dispose()
    }

    @Test
    fun androidRuntimeAcceptsOnlyPublishedArmAbis() {
        assertTrue(isSupportedAndroidRuntimeAbi(listOf("arm64-v8a")))
        assertTrue(isSupportedAndroidRuntimeAbi(listOf("armeabi-v7a")))
        assertTrue(isSupportedAndroidRuntimeAbi(listOf("x86_64", "arm64-v8a")))
        assertFalse(isSupportedAndroidRuntimeAbi(listOf("x86_64", "x86")))
        assertFalse(isSupportedAndroidRuntimeAbi(emptyList()))
    }
}
