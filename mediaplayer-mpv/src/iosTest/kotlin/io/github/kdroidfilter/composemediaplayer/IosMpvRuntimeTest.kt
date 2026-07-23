package io.github.kdroidfilter.composemediaplayer

import platform.Foundation.NSBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosMpvRuntimeTest {
    @Test
    fun bundledModeProbesTheExactCocoaPodsRuntimeWithoutDownloadingCode() {
        val availability = inspectMpvBackend()

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
