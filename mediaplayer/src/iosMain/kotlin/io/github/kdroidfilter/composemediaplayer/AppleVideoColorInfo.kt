@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "UNCHECKED_CAST")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAssetTrack
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFPropertyListRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_DCI_P3
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_EBU_3213
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_ITU_R_2020
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_ITU_R_709_2
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_P3_D65
import platform.CoreMedia.kCMFormatDescriptionColorPrimaries_SMPTE_C
import platform.CoreMedia.kCMFormatDescriptionExtension_ColorPrimaries
import platform.CoreMedia.kCMFormatDescriptionExtension_ContentLightLevelInfo
import platform.CoreMedia.kCMFormatDescriptionExtension_FullRangeVideo
import platform.CoreMedia.kCMFormatDescriptionExtension_MasteringDisplayColorVolume
import platform.CoreMedia.kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms
import platform.CoreMedia.kCMFormatDescriptionExtension_TransferFunction
import platform.CoreMedia.kCMFormatDescriptionExtension_YCbCrMatrix
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_ITU_R_2020
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_ITU_R_709_2
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_Linear
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_sRGB
import platform.CoreMedia.kCMFormatDescriptionYCbCrMatrix_ITU_R_2020
import platform.CoreMedia.kCMFormatDescriptionYCbCrMatrix_ITU_R_601_4
import platform.CoreMedia.kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2
import platform.CoreMedia.kCMVideoCodecType_DolbyVisionHEVC
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreMedia.kCMVideoCodecType_MPEG2Video
import platform.CoreMedia.kCMVideoCodecType_MPEG4Video
import platform.Foundation.NSSelectorFromString

internal fun AVAssetTrack.toAppleVideoColorInfo(): VideoColorInfo {
    val descriptions = performSelector(NSSelectorFromString("formatDescriptions")) as? List<*>
    val description = descriptions?.firstOrNull() as? CMFormatDescriptionRef ?: return VideoColorInfo()
    val mediaSubType = CMFormatDescriptionGetMediaSubType(description)
    val transferValue = description.extension(kCMFormatDescriptionExtension_TransferFunction)
    val transfer =
        when {
            transferValue.sameAs(kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ) -> VideoColorTransfer.PQ
            transferValue.sameAs(kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG) -> VideoColorTransfer.HLG
            transferValue.sameAs(kCMFormatDescriptionTransferFunction_Linear) -> VideoColorTransfer.LINEAR
            transferValue.sameAs(kCMFormatDescriptionTransferFunction_sRGB) -> VideoColorTransfer.SRGB
            transferValue.sameAs(kCMFormatDescriptionTransferFunction_ITU_R_709_2) ||
                transferValue.sameAs(kCMFormatDescriptionTransferFunction_ITU_R_2020) -> VideoColorTransfer.SDR
            else -> VideoColorTransfer.UNKNOWN
        }
    val isDolbyVision = mediaSubType == kCMVideoCodecType_DolbyVisionHEVC || mediaSubType == DVHE_CODEC
    val dynamicRange =
        when {
            isDolbyVision -> VideoDynamicRange.DOLBY_VISION
            transfer == VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
            transfer == VideoColorTransfer.HLG -> VideoDynamicRange.HLG
            transfer != VideoColorTransfer.UNKNOWN -> VideoDynamicRange.SDR
            mediaSubType == kCMVideoCodecType_H264 ||
                mediaSubType == kCMVideoCodecType_MPEG4Video ||
                mediaSubType == kCMVideoCodecType_MPEG2Video -> VideoDynamicRange.SDR
            else -> VideoDynamicRange.UNKNOWN
        }

    val primariesValue = description.extension(kCMFormatDescriptionExtension_ColorPrimaries)
    val primaries =
        when {
            primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_ITU_R_2020) -> VideoColorPrimaries.BT2020
            primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_ITU_R_709_2) -> VideoColorPrimaries.BT709
            primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_P3_D65) ||
                primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_DCI_P3) -> VideoColorPrimaries.DISPLAY_P3
            primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_EBU_3213) -> VideoColorPrimaries.BT601_625
            primariesValue.sameAs(kCMFormatDescriptionColorPrimaries_SMPTE_C) -> VideoColorPrimaries.BT601_525
            else -> VideoColorPrimaries.UNKNOWN
        }
    val matrixValue = description.extension(kCMFormatDescriptionExtension_YCbCrMatrix)
    val matrix =
        when {
            matrixValue.sameAs(kCMFormatDescriptionYCbCrMatrix_ITU_R_2020) -> VideoColorMatrix.BT2020_NCL
            matrixValue.sameAs(kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2) -> VideoColorMatrix.BT709
            matrixValue.sameAs(kCMFormatDescriptionYCbCrMatrix_ITU_R_601_4) -> VideoColorMatrix.BT601
            else -> VideoColorMatrix.UNKNOWN
        }

    val mastering = description.extension(kCMFormatDescriptionExtension_MasteringDisplayColorVolume).dataBytes()
    val contentLight = description.extension(kCMFormatDescriptionExtension_ContentLightLevelInfo).dataBytes()
    val dolbyVisionInfo = if (isDolbyVision) description.dolbyVisionInfo() else null
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = if (dynamicRange.isHdr) 10 else null,
        primaries = primaries,
        transfer = transfer,
        matrix = matrix,
        range =
            if (description.extension(kCMFormatDescriptionExtension_FullRangeVideo).sameAs(kCFBooleanTrue)) {
                VideoColorRange.FULL
            } else {
                VideoColorRange.LIMITED
            },
        masteringDisplay = mastering?.toMasteringDisplayMetadata(),
        contentLightLevel = contentLight?.toContentLightLevelMetadata(),
        dolbyVision =
            if (isDolbyVision) {
                dolbyVisionInfo ?: DolbyVisionInfo()
            } else {
                null
            },
    )
}

private fun CMFormatDescriptionRef.extension(key: CFStringRef?): CFPropertyListRef? =
    CMFormatDescriptionGetExtension(this, key)

private fun CFPropertyListRef?.sameAs(expected: CPointer<out CPointed>?): Boolean =
    this != null && expected != null && this == expected

private fun CFPropertyListRef?.dataBytes(): ByteArray? {
    val value = this ?: return null
    val data: CFDataRef = value.reinterpret()
    val size = CFDataGetLength(data).toInt()
    val bytes = CFDataGetBytePtr(data) ?: return null
    if (size <= 0 || size > MAX_STATIC_METADATA_BYTES) return null
    return ByteArray(size) { index -> bytes[index].toByte() }
}

private fun CMFormatDescriptionRef.dolbyVisionInfo(): DolbyVisionInfo? {
    val atoms = extension(kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms) ?: return null
    val dictionary: CFDictionaryRef = atoms.reinterpret()
    return DOLBY_VISION_CONFIGURATION_KEYS
        .firstNotNullOfOrNull { key -> dictionary.dolbyVisionConfigurationBytes(key) }
        ?.toAppleDolbyVisionInfo()
}

private fun CFDictionaryRef.dolbyVisionConfigurationBytes(key: CFStringRef): ByteArray? {
    val value = CFDictionaryGetValue(this, key) ?: return null
    val data: CFDataRef = value.reinterpret()
    val size = CFDataGetLength(data).toInt()
    if (size !in MIN_DOLBY_VISION_CONFIGURATION_BYTES..MAX_DOLBY_VISION_CONFIGURATION_BYTES) return null
    val bytes = CFDataGetBytePtr(data) ?: return null
    return ByteArray(size) { index -> bytes[index].toByte() }
}

internal fun ByteArray.toAppleDolbyVisionInfo(): DolbyVisionInfo? {
    if (size < MIN_DOLBY_VISION_CONFIGURATION_BYTES) return null
    val profile = (this[2].toInt() and 0xff) ushr 1
    if (profile <= 0) return null
    val flags = this[3].toInt() and 0xff
    val level = ((this[2].toInt() and 0x01) shl 5) or ((flags ushr 3) and 0x1f)
    val rpuPresent = flags and 0x04 != 0
    val enhancementLayerPresent = flags and 0x02 != 0
    val baseLayerPresent = flags and 0x01 != 0
    val compatibilityId = getOrNull(4)?.toInt()?.ushr(4)?.and(0x0f)
    return DolbyVisionInfo(
        profile = profile,
        level = level,
        hasRpu = rpuPresent,
        enhancementLayer =
            if (enhancementLayerPresent) {
                DolbyVisionEnhancementLayer.UNKNOWN
            } else {
                DolbyVisionEnhancementLayer.NONE
            },
        hasHdr10CompatibleBaseLayer =
            baseLayerPresent &&
                (profile == DOLBY_VISION_PROFILE_7 || compatibilityId == HDR10_DOVI_COMPATIBILITY_ID),
        hasHlgCompatibleBaseLayer = baseLayerPresent && compatibilityId == HLG_DOVI_COMPATIBILITY_ID,
    )
}

private fun ByteArray.toMasteringDisplayMetadata(): MasteringDisplayMetadata? {
    if (size < 24) return null
    return MasteringDisplayMetadata(
        redX = uint16Be(8) / 50_000f,
        redY = uint16Be(10) / 50_000f,
        greenX = uint16Be(0) / 50_000f,
        greenY = uint16Be(2) / 50_000f,
        blueX = uint16Be(4) / 50_000f,
        blueY = uint16Be(6) / 50_000f,
        whiteX = uint16Be(12) / 50_000f,
        whiteY = uint16Be(14) / 50_000f,
        minLuminanceNits = uint32Be(20) / 10_000f,
        maxLuminanceNits = uint32Be(16) / 10_000f,
    )
}

private fun ByteArray.toContentLightLevelMetadata(): ContentLightLevelMetadata? {
    if (size < 4) return null
    return ContentLightLevelMetadata(
        maxContentLightLevelNits = uint16Be(0).takeIf { it > 0 },
        maxFrameAverageLightLevelNits = uint16Be(2).takeIf { it > 0 },
    )
}

private fun ByteArray.uint16Be(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

private fun ByteArray.uint32Be(offset: Int): Float =
    (
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
    ).toFloat()

private val VideoDynamicRange.isHdr: Boolean
    get() = this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

private const val DVHE_CODEC: UInt = 0x64766865u
private const val MAX_STATIC_METADATA_BYTES = 4_096
private const val DOLBY_VISION_PROFILE_7 = 7
private const val HDR10_DOVI_COMPATIBILITY_ID = 1
private const val HLG_DOVI_COMPATIBILITY_ID = 4
private const val MIN_DOLBY_VISION_CONFIGURATION_BYTES = 4
private const val MAX_DOLBY_VISION_CONFIGURATION_BYTES = 64
private val DOLBY_VISION_CONFIGURATION_KEYS =
    listOf("dvcC", "dvvC", "dvwC").mapNotNull {
        CFStringCreateWithCString(null, it, kCFStringEncodingUTF8)
    }
