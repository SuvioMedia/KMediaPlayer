@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendAvailability
import io.github.kdroidfilter.composemediaplayer.LibVlcFrameDeliveryMode
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.LibVlcRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoProjectionDisplayMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import io.github.kdroidfilter.composemediaplayer.createLibVlcVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.inspectLibVlcBackend
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntimeResolution
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcRenderEngine
import io.github.shusek.kmediavlc.runtime.desktop.VlcRuntimeCapabilities
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealLibVlcVrProjectionIntegrationTest {
    @Test
    fun realBundledCpuFramePassesThroughStereoProjectionShader() {
        val configured = configuredInputsOrSkip()
        val runtime = configured.runtimeResolution()
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect360,
                stereoLayout = VideoStereoLayout.SideBySide,
                displayMode = VideoProjectionDisplayMode.Stereo,
            )
        val options =
            LibVlcPlaybackOptions(
                runtimeSource = LibVlcRuntimeSource.Resolved(runtime),
                projection = projection,
                projectionViewControlMode = VideoProjectionViewControlMode.MANUAL,
            )
        val availability = inspectLibVlcBackend(options)
        assertTrue(availability is LibVlcBackendAvailability.Available)
        assertEquals(
            LibVlcFrameDeliveryMode.CPU_PULL,
            availability.deliveryMode,
        )

        val state = createLibVlcVideoPlayerState(options) as LibVlcVideoPlayerState
        try {
            assertFalse(state.usesGpuTexture)
            assertTrue(state.projectionRequiresCpuCanvas)
            state.openUri(configured.fixture.toUri().toString(), InitialPlayerState.PLAY)
            await("three real libVLC CPU frames") {
                (state.diagnostics.renderedVideoFrames ?: 0L) >= 3 && state.currentCpuFrame.value != null
            }
            state.pause()
            assertTrue(
                state.renderingInfo.videoRenderer?.contains(
                    "libVLC 4 CPU pull (SDR) -> Compose Canvas -> Skia projection shader",
                ) == true,
            )

            val sourceFrame = assertNotNull(state.currentCpuFrame.value).asSkiaBitmap().makeClone()
            sourceFrame.use { frame ->
                assertEquals(320, frame.width)
                assertEquals(180, frame.height)
                val forward = render(frame, projection, yawDegrees = 0f)
                val turned = render(frame, projection, yawDegrees = 90f)
                assertTrue(forward.leftNonBlackPixels > MINIMUM_VISIBLE_PIXELS)
                assertTrue(forward.rightNonBlackPixels > MINIMUM_VISIBLE_PIXELS)
                assertNotEquals(forward.leftHash, forward.rightHash)
                assertNotEquals(forward.completeHash, turned.completeHash)
            }
        } finally {
            state.dispose()
        }
    }

    private fun render(
        frame: Bitmap,
        projection: VideoProjectionSettings,
        yawDegrees: Float,
    ): RenderSignature =
        Surface.makeRasterN32Premul(OUTPUT_WIDTH, OUTPUT_HEIGHT).use { surface ->
            surface.canvas.clear(OPAQUE_BLACK)
            invokeProjectionRenderer(
                canvas = surface.canvas,
                frame = frame,
                projection = projection,
                projectionView = VideoProjectionViewSettings(yawDegrees = yawDegrees),
            )
            Bitmap().use { output ->
                assertTrue(output.allocN32Pixels(OUTPUT_WIDTH, OUTPUT_HEIGHT, false))
                assertTrue(surface.readPixels(output, 0, 0))
                output.signature()
            }
        }

    private fun invokeProjectionRenderer(
        canvas: Canvas,
        frame: Bitmap,
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
    ) {
        PROJECTION_RENDERER.invoke(
            null,
            canvas,
            frame,
            projection,
            projectionView,
            VideoTextureCrop(),
            OUTPUT_WIDTH.toFloat(),
            OUTPUT_HEIGHT.toFloat(),
        )
    }

    private fun Bitmap.signature(): RenderSignature {
        var completeHash = FNV_OFFSET_BASIS
        var leftHash = FNV_OFFSET_BASIS
        var rightHash = FNV_OFFSET_BASIS
        var leftNonBlackPixels = 0
        var rightNonBlackPixels = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = getColor(x, y)
                completeHash = (completeHash xor color.toLong()) * FNV_PRIME
                if (x < width / 2) {
                    leftHash = (leftHash xor color.toLong()) * FNV_PRIME
                    if (color and RGB_MASK != 0) leftNonBlackPixels++
                } else {
                    rightHash = (rightHash xor color.toLong()) * FNV_PRIME
                    if (color and RGB_MASK != 0) rightNonBlackPixels++
                }
            }
        }
        return RenderSignature(
            completeHash = completeHash,
            leftHash = leftHash,
            rightHash = rightHash,
            leftNonBlackPixels = leftNonBlackPixels,
            rightNonBlackPixels = rightNonBlackPixels,
        )
    }

    private fun configuredInputsOrSkip(): AcceptanceInputs {
        val values =
            listOf(
                System.getProperty(RUNTIME_DIRECTORY_PROPERTY),
                System.getProperty(BRIDGE_PATH_PROPERTY),
                System.getProperty(FIXTURE_PROPERTY),
            )
        assumeTrue(
            "Real bundled libVLC VR acceptance inputs were not configured.",
            values.any { it != null },
        )
        assertTrue(values.all { it != null }, "Real bundled libVLC VR acceptance inputs are incomplete.")
        val runtimeDirectory = Path.of(requireNotNull(values[0])).toAbsolutePath().normalize()
        val bridge = Path.of(requireNotNull(values[1])).toAbsolutePath().normalize()
        val fixture = Path.of(requireNotNull(values[2])).toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(runtimeDirectory) && !Files.isSymbolicLink(runtimeDirectory))
        assertTrue(Files.isRegularFile(bridge) && !Files.isSymbolicLink(bridge))
        assertTrue(Files.isRegularFile(fixture) && !Files.isSymbolicLink(fixture))
        return AcceptanceInputs(runtimeDirectory, bridge, fixture)
    }

    private fun AcceptanceInputs.runtimeResolution(): VlcDesktopRuntimeResolution {
        val hostName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val (libVlcRelativePath, renderEngine) =
            when {
                hostName.contains("mac") || hostName.contains("darwin") ->
                    "bin/libvlc.12.dylib" to VlcRenderEngine.OPENGL
                hostName.contains("linux") ->
                    "bin/libvlc.so.12" to VlcRenderEngine.GLES2
                hostName.contains("win") ->
                    "bin/libvlc.dll" to VlcRenderEngine.D3D11
                else -> error("Unsupported desktop host for real libVLC VR acceptance: $hostName")
            }
        val libVlc = runtimeDirectory.resolve(libVlcRelativePath)
        val plugins = runtimeDirectory.resolve("lib/vlc/plugins")
        assertTrue(Files.isRegularFile(libVlc) && !Files.isSymbolicLink(libVlc))
        assertTrue(Files.isDirectory(plugins) && !Files.isSymbolicLink(plugins))
        return VlcDesktopRuntimeResolution(
            bridge,
            libVlc,
            plugins,
            "libvlc-vr-acceptance",
            VlcRuntimeCapabilities(
                4,
                2,
                "4.0.0-dev",
                VLC_REVISION,
                setOf(VlcFrameDeliveryMode.GPU_PUSH, VlcFrameDeliveryMode.CPU_PULL),
                setOf(renderEngine),
                false,
            ),
        )
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), "Timed out waiting for $description.")
    }

    private data class AcceptanceInputs(
        val runtimeDirectory: Path,
        val bridge: Path,
        val fixture: Path,
    )

    private data class RenderSignature(
        val completeHash: Long,
        val leftHash: Long,
        val rightHash: Long,
        val leftNonBlackPixels: Int,
        val rightNonBlackPixels: Int,
    )

    companion object {
        private const val RUNTIME_DIRECTORY_PROPERTY = "kmediavlc.vr.runtimeDirectory"
        private const val BRIDGE_PATH_PROPERTY = "kmediavlc.vr.bridgePath"
        private const val FIXTURE_PROPERTY = "kmediavlc.vr.fixture"
        private const val VLC_REVISION = "b5536cdea24b313ba9215eacfbd7fa3295d7f3ee"
        private const val OUTPUT_WIDTH = 256
        private const val OUTPUT_HEIGHT = 128
        private const val MINIMUM_VISIBLE_PIXELS = 500
        private const val OPAQUE_BLACK = -0x1000000
        private const val RGB_MASK = 0x00ffffff
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        private val PROJECTION_RENDERER =
            Class
                .forName(
                    "io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopProjectionRendererKt",
                ).getMethod(
                    "drawDesktopProjectedFrame",
                    Canvas::class.java,
                    Bitmap::class.java,
                    VideoProjectionSettings::class.java,
                    VideoProjectionViewSettings::class.java,
                    VideoTextureCrop::class.java,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                )
    }
}
