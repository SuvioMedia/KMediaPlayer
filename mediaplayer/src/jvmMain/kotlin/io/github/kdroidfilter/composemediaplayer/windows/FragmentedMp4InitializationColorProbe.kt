package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange

internal object FragmentedMp4InitializationColorProbe {
    fun infer(bytes: ByteArray): VideoColorInfo? {
        val avcProfile =
            findMp4BoxPayload(bytes, AVC_CONFIGURATION_BOX)
                ?.takeIf { payload ->
                    payload.size >= AVC_CONFIGURATION_MINIMUM_PAYLOAD_BYTES &&
                        (payload[AVC_CONFIGURATION_VERSION_OFFSET].toInt() and BYTE_MASK) ==
                        AVC_CONFIGURATION_VERSION
                }?.get(AVC_PROFILE_OFFSET)
                ?.toInt()
                ?.and(BYTE_MASK)
        val eightBitAvc = avcProfile in EIGHT_BIT_AVC_PROFILES
        val colorInformationPayload = findMp4BoxPayload(bytes, COLOR_INFORMATION_BOX)
        val transferCode =
            if (
                colorInformationPayload != null &&
                colorInformationPayload.size >= COLOR_INFORMATION_MINIMUM_PAYLOAD_BYTES &&
                (colorInformationPayload.hasPrefix(NCLX_TYPE) || colorInformationPayload.hasPrefix(NCLC_TYPE))
            ) {
                colorInformationPayload.readUnsignedShort(NCLX_TRANSFER_OFFSET)
            } else {
                null
            }
        val transfer =
            when (transferCode) {
                in SDR_NCLX_TRANSFER_CODES -> VideoColorTransfer.SDR
                NCLX_TRANSFER_PQ -> VideoColorTransfer.PQ
                NCLX_TRANSFER_HLG -> VideoColorTransfer.HLG
                else -> VideoColorTransfer.UNKNOWN
            }
        val dynamicRange =
            when (transfer) {
                VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
                VideoColorTransfer.HLG -> VideoDynamicRange.HLG
                VideoColorTransfer.SDR -> VideoDynamicRange.SDR
                else -> if (eightBitAvc) VideoDynamicRange.SDR else VideoDynamicRange.UNKNOWN
            }
        if (dynamicRange == VideoDynamicRange.UNKNOWN) return null

        return VideoColorInfo(
            dynamicRange = dynamicRange,
            bitDepth = 8.takeIf { eightBitAvc },
            transfer = transfer.takeUnless { it == VideoColorTransfer.UNKNOWN } ?: VideoColorTransfer.SDR,
        )
    }

    private fun findMp4BoxPayload(
        bytes: ByteArray,
        type: ByteArray,
    ): ByteArray? {
        for (typeOffset in MP4_BOX_SIZE_BYTES until bytes.size - type.size) {
            mp4BoxPayloadAt(bytes, type, typeOffset)?.let { return it }
        }
        return null
    }

    private fun mp4BoxPayloadAt(
        bytes: ByteArray,
        type: ByteArray,
        typeOffset: Int,
    ): ByteArray? {
        if (!bytes.matchesAt(typeOffset, type)) return null
        val boxOffset = typeOffset - MP4_BOX_SIZE_BYTES
        val boxSize = bytes.readUnsignedInt(boxOffset) ?: return null
        val payloadOffset = typeOffset + type.size
        val boxEnd = boxOffset.toLong() + boxSize
        if (
            boxSize < MP4_BOX_HEADER_BYTES ||
            boxEnd > bytes.size.toLong() ||
            payloadOffset.toLong() > boxEnd
        ) {
            return null
        }
        return bytes.copyOfRange(payloadOffset, boxEnd.toInt())
    }

    private fun ByteArray.matchesAt(
        offset: Int,
        expected: ByteArray,
    ): Boolean = expected.indices.all { index -> this[offset + index] == expected[index] }

    private fun ByteArray.hasPrefix(expected: ByteArray): Boolean =
        size >= expected.size && expected.indices.all { index -> this[index] == expected[index] }

    private fun ByteArray.readUnsignedInt(offset: Int): Long? {
        if (offset < 0 || offset + MP4_BOX_SIZE_BYTES > size) return null
        return ((this[offset].toLong() and BYTE_MASK_LONG) shl MOST_SIGNIFICANT_BYTE_SHIFT) or
            ((this[offset + FIRST_BYTE_OFFSET].toLong() and BYTE_MASK_LONG) shl SECOND_BYTE_SHIFT) or
            ((this[offset + SECOND_BYTE_OFFSET].toLong() and BYTE_MASK_LONG) shl THIRD_BYTE_SHIFT) or
            (this[offset + THIRD_BYTE_OFFSET].toLong() and BYTE_MASK_LONG)
    }

    private fun ByteArray.readUnsignedShort(offset: Int): Int =
        ((this[offset].toInt() and BYTE_MASK) shl THIRD_BYTE_SHIFT) or
            (this[offset + FIRST_BYTE_OFFSET].toInt() and BYTE_MASK)

    private const val AVC_PROFILE_BASELINE = 0x42
    private const val AVC_PROFILE_MAIN = 0x4d
    private const val AVC_PROFILE_EXTENDED = 0x58
    private const val AVC_PROFILE_HIGH = 0x64
    private const val AVC_CONFIGURATION_VERSION = 1
    private const val AVC_CONFIGURATION_VERSION_OFFSET = 0
    private const val AVC_PROFILE_OFFSET = 1
    private const val AVC_CONFIGURATION_MINIMUM_PAYLOAD_BYTES = 2
    private const val BYTE_MASK = 0xff
    private const val BYTE_MASK_LONG = 0xffL
    private const val FIRST_BYTE_OFFSET = 1
    private const val SECOND_BYTE_OFFSET = 2
    private const val THIRD_BYTE_OFFSET = 3
    private const val MOST_SIGNIFICANT_BYTE_SHIFT = 24
    private const val SECOND_BYTE_SHIFT = 16
    private const val THIRD_BYTE_SHIFT = 8
    private const val MP4_BOX_SIZE_BYTES = 4
    private const val MP4_BOX_HEADER_BYTES = 8L
    private const val COLOR_INFORMATION_MINIMUM_PAYLOAD_BYTES = 10
    private const val NCLX_TRANSFER_OFFSET = 6
    private const val NCLX_TRANSFER_BT709 = 1
    private const val NCLX_TRANSFER_GAMMA_22 = 4
    private const val NCLX_TRANSFER_GAMMA_28 = 5
    private const val NCLX_TRANSFER_BT601 = 6
    private const val NCLX_TRANSFER_SMPTE_240 = 7
    private const val NCLX_TRANSFER_SRGB = 13
    private const val NCLX_TRANSFER_BT2020_10 = 14
    private const val NCLX_TRANSFER_BT2020_12 = 15
    private const val NCLX_TRANSFER_PQ = 16
    private const val NCLX_TRANSFER_HLG = 18
    private val SDR_NCLX_TRANSFER_CODES =
        setOf(
            NCLX_TRANSFER_BT709,
            NCLX_TRANSFER_GAMMA_22,
            NCLX_TRANSFER_GAMMA_28,
            NCLX_TRANSFER_BT601,
            NCLX_TRANSFER_SMPTE_240,
            NCLX_TRANSFER_SRGB,
            NCLX_TRANSFER_BT2020_10,
            NCLX_TRANSFER_BT2020_12,
        )
    private val EIGHT_BIT_AVC_PROFILES =
        setOf(
            AVC_PROFILE_BASELINE,
            AVC_PROFILE_MAIN,
            AVC_PROFILE_EXTENDED,
            AVC_PROFILE_HIGH,
        )
    private val AVC_CONFIGURATION_BOX = "avcC".encodeToByteArray()
    private val COLOR_INFORMATION_BOX = "colr".encodeToByteArray()
    private val NCLX_TYPE = "nclx".encodeToByteArray()
    private val NCLC_TYPE = "nclc".encodeToByteArray()
}
