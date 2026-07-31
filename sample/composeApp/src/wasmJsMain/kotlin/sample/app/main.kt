import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import sample.app.App
import sample.app.createKMediaWasmDualOpusMkvBlobUrl
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

@OptIn(ExperimentalWasmJsInterop::class)
private fun queryParameter(name: String): String? =
    js("new URLSearchParams(globalThis.location.search).get(name)")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val requestedVideoUrl = queryParameter("video")?.takeIf { it.isNotBlank() }
    val initialVideoUrl =
        requestedVideoUrl
            ?: createKMediaWasmDualOpusMkvBlobUrl()
    val initialProjection =
        queryParameter("projection")
            ?.let { requested ->
                VideoProjectionType.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            }?.let { projectionType -> VideoProjectionSettings(projectionType = projectionType) }
            ?: VideoProjectionSettings()
    val playbackOptions =
        VideoPlaybackOptions(
            dynamicRangePolicy =
                queryParameter("dynamicRangePolicy")
                    ?.uppercase()
                    ?.let { runCatching { DynamicRangePolicy.valueOf(it) }.getOrNull() }
                    ?: DynamicRangePolicy.AUTO,
            dolbyVisionPolicy =
                queryParameter("dolbyVisionPolicy")
                    ?.uppercase()
                    ?.let { runCatching { DolbyVisionPolicy.valueOf(it) }.getOrNull() }
                    ?: DolbyVisionPolicy.AUTO,
            extensions = listOf(AssSubtitleExtension()),
            projection = initialProjection,
        )
    ComposeViewport {
        hideLoader()
        App(
            initialVideoUrl = initialVideoUrl,
            demoSubtitleEnabled = false,
            initialMuted = queryParameter("muted")?.toBooleanStrictOrNull() ?: false,
            initialLoop = queryParameter("loop")?.toBooleanStrictOrNull() ?: false,
            playbackOptions = playbackOptions,
            initialProjection = initialProjection,
        )
    }
}

// Function to hide the loader and show the app
fun hideLoader() {
    val loader = document.getElementById("loader") as? HTMLElement
    val app = document.getElementById("app") as? HTMLElement

    loader?.style?.display = "none" // Hide the loader
    app?.style?.display = "block"   // Show the app
}
