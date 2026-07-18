package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.kdroid.androidcontextprovider.ContextProvider

/** Queries the actual MediaCodec profile table instead of treating every DV decoder as P7-capable. */
@UnstableApi
internal fun Format.queryAndroidDolbyVisionDecoderCapabilities(): DecoderColorCapabilities {
    if (toVideoColorInfo().dynamicRange != VideoDynamicRange.DOLBY_VISION) {
        return DecoderColorCapabilities()
    }
    val decoderInfos =
        runCatching {
            MediaCodecUtil.getDecoderInfos(
                MimeTypes.VIDEO_DOLBY_VISION,
                false,
                false,
            )
        }.getOrNull() ?: return DecoderColorCapabilities()
    val context = ContextProvider.getContext()
    val sourceDolbyVision = toVideoColorInfo().dolbyVision
    val sourceLevel = sourceDolbyVision?.level ?: DEFAULT_DOLBY_VISION_LEVEL
    val profilesToQuery =
        buildSet {
            sourceDolbyVision?.profile?.let(::add)
            add(DOLBY_VISION_PROFILE_7)
            add(DOLBY_VISION_PROFILE_8)
        }
    val supportedProfiles =
        profilesToQuery.filterTo(mutableSetOf()) { profile ->
            val candidate =
                buildUpon()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.${profile.toString().padStart(2, '0')}.${sourceLevel.toString().padStart(2, '0')}")
                    .build()
            decoderInfos.any { decoder -> decoder.isFormatSupported(context, candidate) }
        }
    return DecoderColorCapabilities(
        isKnown = true,
        supportedDynamicRanges =
            if (supportedProfiles.isEmpty()) emptySet() else setOf(VideoDynamicRange.DOLBY_VISION),
        maxBitDepth = colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: DOLBY_VISION_BIT_DEPTH,
        supportedDolbyVisionProfiles = supportedProfiles,
        isDolbyVisionProfileSupportKnown = true,
    )
}

private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8
private const val DEFAULT_DOLBY_VISION_LEVEL = 6
private const val DOLBY_VISION_BIT_DEPTH = 10
