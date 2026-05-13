package io.github.kdroidfilter.composemediaplayer

import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec> =
    withContext(Dispatchers.Default) {
        androidAudioCodecs
    }

internal actual suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec> =
    withContext(Dispatchers.Default) {
        androidVideoCodecs
    }

private val androidAudioCodecs: Set<MediaCodec> by lazy {
    codecsOfType(MediaCodecType.AUDIO) + MediaCodec.PCM
}

private val androidVideoCodecs: Set<MediaCodec> by lazy {
    codecsOfType(MediaCodecType.VIDEO)
}

private val androidDecoderMimeTypes: Set<String> by lazy {
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .map { it.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
}

private fun codecsOfType(type: MediaCodecType): Set<MediaCodec> =
    MediaCodec.entries
        .asSequence()
        .filter { it.type == type }
        .filter { codec ->
            codec.mimeTypes.any { mimeType -> mimeType.lowercase() in androidDecoderMimeTypes }
        }.toSet()
