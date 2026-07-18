package io.github.kdroidfilter.composemediaplayer.consumer.extensions

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublishedExtensionIsolationTest {
    @Test
    fun optionalExtensionsDoNotPullTheDefaultPlayer() {
        assertTrue(publishedCommonExtensionStatuses().isNotEmpty())
        assertTrue(publishedDesktopBridgeExtension().id.isNotBlank())
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions")
        }
    }
}
