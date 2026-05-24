package io.github.kdroidfilter.composemediaplayer

import java.io.Closeable

internal enum class ExternalHlsFallbackBackend(
    val displayName: String,
) {
    FFMPEG("ffmpeg"),
    VLC("VLC"),
}

internal data class StartedExternalHlsFallback(
    val backend: ExternalHlsFallbackBackend,
    val fallback: Closeable,
    val source: HlsFallbackSource,
)

internal object ExternalHlsFallbackSupport {
    suspend fun needsContainerFallback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Boolean = JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders)

    fun isDisabled(): Boolean {
        val configured =
            System.getProperty("composemediaplayer.hlsFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK")
                ?: System.getProperty("composemediaplayer.macos.ffmpegFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK")
                ?: "true"
        return configured.equals("false", ignoreCase = true) ||
            configured == "0" ||
            configured.equals("off", ignoreCase = true)
    }

    fun selectBackend(requiresSubtitleRendering: Boolean): ExternalHlsFallbackBackend {
        val configured =
            (
                System.getProperty("composemediaplayer.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
                    ?: System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
                    ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
                    ?: "auto"
            ).lowercase()

        return when (configured) {
            "vlc" -> ExternalHlsFallbackBackend.VLC
            "ffmpeg" -> ExternalHlsFallbackBackend.FFMPEG
            else -> {
                val vlcPath = ExternalVlcLocator.findVlc()
                val ffmpegPath =
                    if (requiresSubtitleRendering) {
                        ExternalFfmpegLocator.findFfmpegWithSubtitles()
                    } else {
                        ExternalFfmpegLocator.findFfmpeg()
                    }

                when {
                    requiresSubtitleRendering && vlcPath != null -> ExternalHlsFallbackBackend.VLC
                    ffmpegPath != null -> ExternalHlsFallbackBackend.FFMPEG
                    vlcPath != null -> ExternalHlsFallbackBackend.VLC
                    else -> ExternalHlsFallbackBackend.FFMPEG
                }
            }
        }
    }

    suspend fun start(
        uri: String,
        requestHeaders: Map<String, String>,
        selectedAudioStreamIndex: Int?,
        selectedSubtitleStreamIndex: Int?,
        startTimeSeconds: Double,
    ): StartedExternalHlsFallback {
        if (isDisabled()) {
            externalHlsFallbackDisabled()
        }

        val backend = selectBackend(requiresSubtitleRendering = selectedSubtitleStreamIndex != null)
        val fallback =
            when (backend) {
                ExternalHlsFallbackBackend.VLC -> {
                    val vlcPath =
                        ExternalVlcLocator.findVlc()
                            ?: missingVlcFallback()
                    ExternalVlcHlsFallback(vlcPath)
                }
                ExternalHlsFallbackBackend.FFMPEG -> {
                    val requiresSubtitleFilter = selectedSubtitleStreamIndex != null
                    val ffmpegPath =
                        if (requiresSubtitleFilter) {
                            ExternalFfmpegLocator.findFfmpegWithSubtitles()
                        } else {
                            ExternalFfmpegLocator.findFfmpeg()
                        }
                            ?: missingFfmpegFallback(requiresSubtitleFilter)
                    ExternalFfmpegHlsFallback(ffmpegPath)
                }
            }

        return runCatching {
            val source =
                when (fallback) {
                    is ExternalVlcHlsFallback ->
                        fallback.start(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = selectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
                            startTimeSeconds = startTimeSeconds,
                        )
                    is ExternalFfmpegHlsFallback ->
                        fallback.start(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = selectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
                            startTimeSeconds = startTimeSeconds,
                        )
                    else -> error("Unsupported external HLS fallback backend")
                }
            StartedExternalHlsFallback(backend = backend, fallback = fallback, source = source)
        }.getOrElse { error ->
            fallback.close()
            externalHlsFallbackFailed(backend, error)
        }
    }

    private fun externalHlsFallbackDisabled(): Nothing =
        throw UnsupportedOperationException(
            "The external HLS fallback is disabled. Enable it with " +
                "composemediaplayer.hlsFallback=true or COMPOSE_MEDIA_PLAYER_HLS_FALLBACK=true.",
        )

    private fun missingVlcFallback(): Nothing =
        throw UnsupportedOperationException(
            "The external HLS fallback backend is set to VLC, but VLC was not found. " +
                "Install VLC or set composemediaplayer.vlc=/path/to/vlc " +
                "or COMPOSE_MEDIA_PLAYER_VLC. ComposeMediaPlayer does not bundle VLC.",
        )

    private fun missingFfmpegFallbackMessage(requiresSubtitleFilter: Boolean): String =
        if (requiresSubtitleFilter) {
            "Embedded subtitle rendering through the external HLS fallback requires ffmpeg " +
                "with libass and the subtitles filter enabled, or VLC fallback. " +
                "Install ffmpeg/VLC or set composemediaplayer.ffmpeg=/path/to/ffmpeg " +
                "or COMPOSE_MEDIA_PLAYER_FFMPEG."
        } else {
            "No external HLS fallback backend was found. Install ffmpeg or VLC, or set " +
                "composemediaplayer.ffmpeg=/path/to/ffmpeg or COMPOSE_MEDIA_PLAYER_FFMPEG."
        }

    private fun missingFfmpegFallback(requiresSubtitleFilter: Boolean): Nothing =
        throw UnsupportedOperationException(missingFfmpegFallbackMessage(requiresSubtitleFilter))

    private fun externalHlsFallbackFailed(
        backend: ExternalHlsFallbackBackend,
        cause: Throwable,
    ): Nothing =
        throw UnsupportedOperationException(
            "Failed to prepare media through external ${backend.displayName} HLS fallback: ${cause.message}",
            cause,
        )

    fun hasSubtitleRenderer(): Boolean =
        when (selectBackend(requiresSubtitleRendering = true)) {
            ExternalHlsFallbackBackend.VLC -> ExternalVlcLocator.findVlc() != null
            ExternalHlsFallbackBackend.FFMPEG -> ExternalFfmpegLocator.findFfmpegWithSubtitles() != null
        }

    fun isExternalHlsAudioTrackId(id: String): Boolean =
        id.startsWith(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)

    fun isExternalHlsSubtitleTrackId(id: String): Boolean =
        id.startsWith(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)

    fun externalHlsTrackStreamIndex(id: String): Int? =
        when {
            id.startsWith(
                EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX)
            id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX) -> id.removePrefix(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)
            id.startsWith(
                EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX,
            ) -> id.removePrefix(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)
            else -> null
        }?.toIntOrNull()
}
