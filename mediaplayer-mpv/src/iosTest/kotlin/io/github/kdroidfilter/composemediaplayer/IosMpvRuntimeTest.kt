package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mpv.iosBundledMpvFrameworkPath
import io.github.kdroidfilter.composemediaplayer.mpv.isLocalIosMpvSource
import platform.Foundation.NSBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosMpvRuntimeTest {
    @Test
    fun bundledModeAutomaticallyProbesTheEmbeddedKMediaMpvFramework() {
        assertEquals(
            "/App/Frameworks/KMediaMpv.framework/KMediaMpv",
            iosBundledMpvFrameworkPath("/App/Frameworks/"),
        )
        assertEquals(null, iosBundledMpvFrameworkPath(null))

        val availability = inspectMpvBackend()
        if (availability is MpvBackendAvailability.Unavailable) {
            assertTrue(
                availability.reason in
                    setOf(
                        MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
                        MpvBackendUnavailableReason.INVALID_RUNTIME,
                    ),
            )
            if (availability.reason == MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING) {
                assertTrue(availability.guidance.contains("KMediaMpv CocoaPod"))
            }
        }
    }

    @Test
    fun bundledIosRuntimeAcceptsOnlyLocalMedia() {
        assertTrue("/private/video.mkv".isLocalIosMpvSource())
        assertTrue("file:///private/video.mkv".isLocalIosMpvSource())
        assertTrue("relative/video.mkv".isLocalIosMpvSource())
        assertEquals(false, "https://example.invalid/video.mkv".isLocalIosMpvSource())
        assertEquals(false, "https:video.mkv".isLocalIosMpvSource())
        assertEquals(false, "rtsp://example.invalid/live".isLocalIosMpvSource())
    }

    @Test
    fun systemModeProbesLinkedOrEmbeddedSymbolsWithoutDownloadingCode() {
        val availability =
            inspectMpvBackend(
                MpvPlaybackOptions(runtimeSource = MpvRuntimeSource.System),
            )

        if (availability is MpvBackendAvailability.Unavailable) {
            assertTrue(
                availability.reason in
                    setOf(
                        MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
                        MpvBackendUnavailableReason.INVALID_RUNTIME,
                    ),
            )
        }
    }

    @Test
    fun explicitIosRuntimeMustStayInsideTheApplicationBundle() {
        val unavailable =
            assertIs<MpvBackendAvailability.Unavailable>(
                inspectMpvBackend(
                    MpvPlaybackOptions(
                        runtimeSource = MpvRuntimeSource.ExplicitPath("/tmp/libmpv.dylib"),
                    ),
                ),
            )

        assertEquals(MpvBackendUnavailableReason.INVALID_RUNTIME, unavailable.reason)
    }

    @Test
    fun existingNonMpvBundleBinaryIsRejectedAsAnInvalidRuntime() {
        val executablePath = checkNotNull(NSBundle.mainBundle.executablePath)
        val unavailable =
            assertIs<MpvBackendAvailability.Unavailable>(
                inspectMpvBackend(
                    MpvPlaybackOptions(
                        runtimeSource = MpvRuntimeSource.ExplicitPath(executablePath),
                    ),
                ),
            )

        assertEquals(MpvBackendUnavailableReason.INVALID_RUNTIME, unavailable.reason)
    }

    @Test
    fun subtitleFontsMustRemainInsideAnIosApplicationContainer() {
        val unavailable =
            assertIs<MpvBackendAvailability.Unavailable>(
                inspectMpvBackend(
                    MpvPlaybackOptions(
                        subtitleFontsDirectory = "/tmp",
                        runtimeSource = MpvRuntimeSource.System,
                    ),
                ),
            )

        assertEquals(MpvBackendUnavailableReason.INVALID_RUNTIME, unavailable.reason)
    }
}
