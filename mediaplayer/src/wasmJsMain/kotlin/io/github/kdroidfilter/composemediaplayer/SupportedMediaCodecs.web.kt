package io.github.kdroidfilter.composemediaplayer

/**
 * The Wasm target reports the codecs provided by kmedia-wasm-engine, including its
 * software decoder path. Browser-native `canPlayType` is not the source of truth.
 */
internal actual suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec> =
    MediaCodec.entries.filterTo(mutableSetOf()) { it.type == MediaCodecType.AUDIO }

internal actual suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec> =
    MediaCodec.entries.filterTo(mutableSetOf()) { it.type == MediaCodecType.VIDEO }
