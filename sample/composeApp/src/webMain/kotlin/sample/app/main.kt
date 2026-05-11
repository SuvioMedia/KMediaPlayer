import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import sample.app.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    configureAssRendererAssets()

    ComposeViewport {
        hideLoader()
        App()
    }
}

private fun configureAssRendererAssets() {
    AssSubtitleRendererConfig.scriptUrl = "libass/subtitles-octopus.js"
    AssSubtitleRendererConfig.workerUrl = "libass/subtitles-octopus-worker.js"
    AssSubtitleRendererConfig.legacyWorkerUrl = "libass/subtitles-octopus-worker-legacy.js"
    AssSubtitleRendererConfig.fallbackFontUrl = "libass/default.woff2"
}

// Function to hide the loader and show the app
fun hideLoader() {
    val loader = document.getElementById("loader") as? HTMLElement
    val app = document.getElementById("app") as? HTMLElement

    loader?.style?.display = "none" // Hide the loader
    app?.style?.display = "block"   // Show the app
}
