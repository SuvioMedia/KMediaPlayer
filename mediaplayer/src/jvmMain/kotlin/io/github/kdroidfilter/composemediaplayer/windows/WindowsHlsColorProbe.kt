package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.JvmDecodedVideoColorSignalCodec
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.requestHeadersLineString

internal object WindowsHlsColorProbe {
    fun probe(
        uri: String,
        requestHeaders: Map<String, String>,
    ): VideoColorInfo? {
        val loaded =
            runCatching { WindowsHlsResourceReader.readPlaylist(uri, requestHeaders) }.getOrNull()
                ?: return null
        inferColorInfo(loaded.content)?.let { return it }

        initializationSegmentLocator(loaded.content)
            ?.let { locator -> WindowsHlsResourceReader.readInitializationSegment(loaded, locator) }
            ?.let(::inferFragmentedMp4InitializationColor)
            ?.let { return it }

        val probeLocators =
            listOfNotNull(
                initializationSegmentLocator(loaded.content),
                firstMediaSegmentLocator(loaded.content),
            )
        return probeLocators.firstNotNullOfOrNull { locator ->
            probeSegmentColor(loaded, locator)
        }
    }

    private fun probeSegmentColor(
        loaded: LoadedHlsPlaylist,
        locator: String,
    ): VideoColorInfo? {
        val resource = WindowsHlsResourceReader.resolveResource(loaded, locator) ?: return null
        return JvmDecodedVideoColorSignalCodec
            .decode(
                runCatching {
                    WindowsNativeBridge.nProbeVideoColorInfo(
                        resource.uri.toString(),
                        resource.requestHeaders.requestHeadersLineString(),
                    )
                }.getOrNull(),
            )?.mergeInto(VideoColorInfo())
            ?.takeIf { it.dynamicRange != VideoDynamicRange.UNKNOWN }
    }

    internal fun inferColorInfo(playlist: String): VideoColorInfo? {
        val variants =
            playlist
                .lineSequence()
                .map(String::trim)
                .filter { it.startsWith(STREAM_INFO_PREFIX, ignoreCase = true) }
                .map { parseAttributes(it.substringAfter(':')) }
                .toList()
        if (variants.isEmpty()) return null

        val everyVariantIsEightBitAvc =
            variants.all { attributes ->
                val videoRange = attributes[VIDEO_RANGE_ATTRIBUTE]
                if (videoRange != null && !videoRange.equals("SDR", ignoreCase = true)) return@all false

                val codecs =
                    attributes[CODECS_ATTRIBUTE]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?: return@all false
                val videoCodecs = codecs.filter(::isVideoCodec)
                videoCodecs.isNotEmpty() && videoCodecs.all(::isEightBitAvcCodec)
            }
        if (!everyVariantIsEightBitAvc) return null

        return VideoColorInfo(
            dynamicRange = VideoDynamicRange.SDR,
            bitDepth = 8,
            transfer = VideoColorTransfer.SDR,
        )
    }

    internal fun firstMediaSegmentLocator(playlist: String): String? {
        if (playlist.lineSequence().any { it.trim().startsWith(STREAM_INFO_PREFIX, ignoreCase = true) }) {
            return null
        }
        return playlist
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line -> line.isNotEmpty() && !line.startsWith('#') }
    }

    internal fun initializationSegmentLocator(playlist: String): String? =
        playlist
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith(MAP_PREFIX, ignoreCase = true) }
            ?.substringAfter(':')
            ?.let(::parseAttributes)
            ?.get(URI_ATTRIBUTE)

    internal fun inferFragmentedMp4InitializationColor(bytes: ByteArray): VideoColorInfo? =
        FragmentedMp4InitializationColorProbe.infer(bytes)

    private fun parseAttributes(value: String): Map<String, String> =
        ATTRIBUTE_REGEX
            .findAll(value)
            .associate { match ->
                match.groupValues[1].uppercase() to match.groupValues[2].trim().trim('"')
            }

    private fun isVideoCodec(codec: String): Boolean =
        VIDEO_CODEC_PREFIXES.any { prefix -> codec.startsWith(prefix, ignoreCase = true) }

    private fun isEightBitAvcCodec(codec: String): Boolean {
        val normalized = codec.lowercase()
        if (!normalized.startsWith("avc1.") && !normalized.startsWith("avc3.")) return false
        val profile = normalized.substringAfter('.', "").take(2).toIntOrNull(16) ?: return false
        return profile in EIGHT_BIT_AVC_PROFILES
    }

    private const val STREAM_INFO_PREFIX = "#EXT-X-STREAM-INF:"
    private const val MAP_PREFIX = "#EXT-X-MAP:"
    private const val CODECS_ATTRIBUTE = "CODECS"
    private const val URI_ATTRIBUTE = "URI"
    private const val VIDEO_RANGE_ATTRIBUTE = "VIDEO-RANGE"
    private val ATTRIBUTE_REGEX = Regex("([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)")
    private val VIDEO_CODEC_PREFIXES = setOf("avc1", "avc3", "hev1", "hvc1", "dvhe", "dvh1", "av01", "vp09")
    private val EIGHT_BIT_AVC_PROFILES =
        setOf(
            AVC_PROFILE_BASELINE,
            AVC_PROFILE_MAIN,
            AVC_PROFILE_EXTENDED,
            AVC_PROFILE_HIGH,
        )
    private const val AVC_PROFILE_BASELINE = 0x42
    private const val AVC_PROFILE_MAIN = 0x4d
    private const val AVC_PROFILE_EXTENDED = 0x58
    private const val AVC_PROFILE_HIGH = 0x64
}
