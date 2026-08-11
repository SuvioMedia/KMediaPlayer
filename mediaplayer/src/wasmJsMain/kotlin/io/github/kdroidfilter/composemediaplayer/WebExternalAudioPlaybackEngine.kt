@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Browser audio transport kept separate from the visual Wasm engine surface. */
internal class WebExternalAudioPlaybackEngine : ExternalAudioPlaybackEngine {
    private var audioElement: HTMLAudioElement? = null
    private var playbackRejected = false

    override val isReady: Boolean
        get() = audioElement?.readyState?.let { it >= HAVE_CURRENT_DATA } == true

    override val isPlaying: Boolean
        get() = audioElement?.let { !it.paused && !it.ended } == true

    override val currentTime: Duration
        get() = audioElement?.currentTime?.takeIf(Double::isFinite)?.seconds ?: Duration.ZERO

    override val hasError: Boolean
        get() = playbackRejected || audioElement?.error != null

    override fun prepare(
        track: ExternalAudioTrack,
        position: Duration,
        volume: Float,
        playbackSpeed: Float,
        loop: Boolean,
    ) {
        require(track.requestHeaders.isEmpty()) {
            "Browser external audio requires a self-authorizing URL without request headers."
        }
        release()
        playbackRejected = false
        val element = document.createElement("audio") as HTMLAudioElement
        element.preload = "auto"
        element.src = track.source.uri
        element.volume = volume.coerceIn(0f, 1f).toDouble()
        element.playbackRate = playbackSpeed.toDouble()
        element.loop = loop
        element.load()
        runCatching { element.currentTime = position.inWholeMilliseconds.toDouble() / MILLIS_PER_SECOND }
        audioElement = element
    }

    override fun play() {
        val element = audioElement ?: return
        playAudioElement(element) {
            if (audioElement === element) playbackRejected = true
        }
    }

    override fun pause() {
        audioElement?.pause()
    }

    override fun seekTo(time: Duration) {
        runCatching {
            audioElement?.currentTime = time.inWholeMilliseconds.coerceAtLeast(0L).toDouble() / MILLIS_PER_SECOND
        }
    }

    override fun setVolume(value: Float) {
        audioElement?.volume = value.coerceIn(0f, 1f).toDouble()
    }

    override fun setPlaybackSpeed(value: Float) {
        audioElement?.playbackRate = value.toDouble()
    }

    override fun setLoop(value: Boolean) {
        audioElement?.loop = value
    }

    override fun release() {
        val element = audioElement ?: return
        element.pause()
        element.removeAttribute("src")
        element.load()
        audioElement = null
        playbackRejected = false
    }

    override fun dispose() = release()

    private companion object {
        const val HAVE_CURRENT_DATA = 2
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

@Suppress("UNUSED_PARAMETER")
private fun playAudioElement(
    element: HTMLAudioElement,
    onRejected: () -> Unit,
): Unit =
    js(
        """
        {
            try {
                const result = element.play();
                if (result && typeof result.catch === "function") {
                    result.catch(function() { onRejected(); });
                }
            } catch (_) {
                onRejected();
            }
        }
        """,
    )
