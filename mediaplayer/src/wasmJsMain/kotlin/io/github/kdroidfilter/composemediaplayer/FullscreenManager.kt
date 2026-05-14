package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

/**
 * Manages fullscreen functionality for the video player
 */
object FullscreenManager {
    /**
     * Exit fullscreen if document is in fullscreen mode
     */
    @OptIn(ExperimentalWasmJsInterop::class)
    fun exitFullscreen() {
        if (document.fullscreenElement != null) {
            exitDocumentFullscreen { message ->
                webVideoLogger.e { "Failed to exit fullscreen: $message" }
            }
        }
    }

    /**
     * Request fullscreen mode
     */
    fun requestFullScreen(
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        requestDocumentFullscreen(onSuccess, onError)
    }

    /**
     * Toggle fullscreen mode
     * @param isCurrentlyFullscreen Whether the player is currently in fullscreen mode
     * @param onFullscreenChange Callback to update the fullscreen state
     */
    fun toggleFullscreen(
        isCurrentlyFullscreen: Boolean,
        onFullscreenChange: (Boolean) -> Unit,
    ) {
        if (!isCurrentlyFullscreen) {
            requestFullScreen(
                onSuccess = { onFullscreenChange(true) },
                onError = { message ->
                    webVideoLogger.e { "Failed to enter fullscreen: $message" }
                },
            )
        } else {
            exitFullscreen()
            onFullscreenChange(false)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun requestDocumentFullscreen(
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            const element = document.documentElement;
            if (!element || typeof element.requestFullscreen !== "function") {
                onError("Fullscreen API is not available");
            } else {
                try {
                    const result = element.requestFullscreen();
                    if (result && typeof result.then === "function") {
                        result.then(function() {
                            onSuccess();
                        }).catch(function(error) {
                            onError(error && error.message ? error.message : String(error));
                        });
                    } else {
                        onSuccess();
                    }
                } catch (error) {
                    onError(error && error.message ? error.message : String(error));
                }
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun exitDocumentFullscreen(onError: (String) -> Unit): Unit =
    js(
        """
        {
            try {
                const result = document.exitFullscreen();
                if (result && typeof result.catch === "function") {
                    result.catch(function(error) {
                        onError(error && error.message ? error.message : String(error));
                    });
                }
            } catch (error) {
                onError(error && error.message ? error.message : String(error));
            }
        }
        """,
    )
