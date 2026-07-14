package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Guards the JVM descriptors published by v1.0.15 while the 1.x line evolves additively. */
class V1015BinaryCompatibilityTest {
    @Test
    fun playerCapabilitiesKeepsItsV1015DataClassShape() {
        val type = PlayerCapabilities::class.java
        assertNotNull(
            type.getDeclaredConstructor(
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                HdrCapabilities::class.java,
                Set::class.java,
            ),
        )
        assertEquals(Boolean::class.javaPrimitiveType, type.getDeclaredMethod("component1").returnType)
        assertEquals(Boolean::class.javaPrimitiveType, type.getDeclaredMethod("component2").returnType)
        assertEquals(HdrCapabilities::class.java, type.getDeclaredMethod("component3").returnType)
        assertEquals(Set::class.java, type.getDeclaredMethod("component4").returnType)
        assertNotNull(
            type.getDeclaredMethod(
                "copy",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                HdrCapabilities::class.java,
                Set::class.java,
            ),
        )
        assertEquals(Boolean::class.javaPrimitiveType, type.getDeclaredMethod("getSupportsHls").returnType)
    }

    @Test
    fun factoryAndRememberKeepTheirV1015ReturnDescriptors() {
        val platformFacade = Class.forName("io.github.kdroidfilter.composemediaplayer.VideoPlayerState_jvmKt")
        val factory =
            platformFacade.getDeclaredMethod(
                "createVideoPlayerState",
                AudioMode::class.java,
                CacheConfig::class.java,
                VideoPlaybackOptions::class.java,
            )
        assertEquals(VideoPlayerState::class.java, factory.returnType)

        val commonFacade = Class.forName("io.github.kdroidfilter.composemediaplayer.VideoPlayerStateKt")
        val remember =
            commonFacade.getDeclaredMethod(
                "rememberVideoPlayerState",
                AudioMode::class.java,
                CacheConfig::class.java,
                VideoPlaybackOptions::class.java,
                Composer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        assertEquals(VideoPlayerState::class.java, remember.returnType)
    }

    @Test
    fun surfaceKeepsItsV1015VideoPlayerStateDescriptor() {
        val surfaceFacade = Class.forName("io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceKt")
        assertNotNull(
            surfaceFacade.getDeclaredMethod(
                "VideoPlayerSurface",
                VideoPlayerState::class.java,
                Modifier::class.java,
                ContentScale::class.java,
                Function2::class.java,
                Composer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
        )
        assertTrue(VideoPlayerState::class.java.isAssignableFrom(PreviewableVideoPlayerState::class.java))
    }

    @Test
    fun trackSelectionResultKeepsTheClosedV1015VariantSet() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.github.kdroidfilter.composemediaplayer.TrackSelectionResult\$Pending")
        }
    }
}
