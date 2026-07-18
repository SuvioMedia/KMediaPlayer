@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer.dolbyvision

internal data class IsoBmffBox(
    val offset: Int,
    val size: Int,
    val headerSize: Int,
    val type: String,
) {
    val contentOffset: Int get() = offset + headerSize
    val endOffset: Int get() = offset + size
}

internal sealed interface IsoBmffParseResult {
    data class Success(
        val boxes: List<IsoBmffBox>,
    ) : IsoBmffParseResult

    data class Failure(
        val message: String,
    ) : IsoBmffParseResult
}

internal fun ByteArray.parseIsoBmffBoxes(
    start: Int = 0,
    end: Int = size,
): IsoBmffParseResult {
    if (start < 0 || end < start || end > size) return IsoBmffParseResult.Failure("Invalid ISO BMFF box range.")
    val boxes = mutableListOf<IsoBmffBox>()
    var cursor = start
    while (cursor < end) {
        if (end - cursor < ISO_BOX_HEADER_BYTES) {
            return IsoBmffParseResult.Failure("Truncated ISO BMFF box header at byte $cursor.")
        }
        val shortSize = readUnsignedInt(cursor)
        val type = readFourCc(cursor + ISO_BOX_TYPE_OFFSET)
        val headerSize: Int
        val boxSize: Long
        when (shortSize) {
            0L -> {
                headerSize = ISO_BOX_HEADER_BYTES
                boxSize = (end - cursor).toLong()
            }
            1L -> {
                if (end - cursor < ISO_EXTENDED_BOX_HEADER_BYTES) {
                    return IsoBmffParseResult.Failure("Truncated extended ISO BMFF box header at byte $cursor.")
                }
                headerSize = ISO_EXTENDED_BOX_HEADER_BYTES
                boxSize = readUnsignedLong(cursor + ISO_BOX_HEADER_BYTES)
            }
            else -> {
                headerSize = ISO_BOX_HEADER_BYTES
                boxSize = shortSize
            }
        }
        if (boxSize < headerSize || boxSize > Int.MAX_VALUE || boxSize > end.toLong() - cursor) {
            return IsoBmffParseResult.Failure("Invalid $type box size $boxSize at byte $cursor.")
        }
        val intSize = boxSize.toInt()
        boxes += IsoBmffBox(cursor, intSize, headerSize, type)
        cursor += intSize
    }
    return IsoBmffParseResult.Success(boxes)
}

internal fun ByteArray.readUnsignedInt(offset: Int): Long {
    requireReadable(offset, UINT32_BYTES)
    return ((this[offset].toLong() and BYTE_MASK_LONG) shl 24) or
        ((this[offset + 1].toLong() and BYTE_MASK_LONG) shl 16) or
        ((this[offset + 2].toLong() and BYTE_MASK_LONG) shl 8) or
        (this[offset + 3].toLong() and BYTE_MASK_LONG)
}

internal fun ByteArray.readSignedInt(offset: Int): Int = readUnsignedInt(offset).toInt()

internal fun ByteArray.readUnsignedLong(offset: Int): Long {
    requireReadable(offset, UINT64_BYTES)
    val high = readUnsignedInt(offset)
    val low = readUnsignedInt(offset + UINT32_BYTES)
    if (high > Int.MAX_VALUE.toLong()) throw IllegalArgumentException("Unsigned 64-bit ISO BMFF value is too large.")
    return (high shl 32) or low
}

internal fun ByteArray.readFourCc(offset: Int): String {
    requireReadable(offset, FOURCC_BYTES)
    return buildString(FOURCC_BYTES) {
        repeat(FOURCC_BYTES) { index -> append((this@readFourCc[offset + index].toInt() and BYTE_MASK).toChar()) }
    }
}

internal fun ByteArray.writeUnsignedInt(
    offset: Int,
    value: Long,
) {
    require(value in 0..UINT32_MAX) { "Value does not fit in an unsigned 32-bit field." }
    requireReadable(offset, UINT32_BYTES)
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.writeSignedInt(
    offset: Int,
    value: Int,
) = writeUnsignedInt(offset, value.toLong() and UINT32_MAX)

internal fun ByteArray.writeUnsignedLong(
    offset: Int,
    value: Long,
) {
    require(value >= 0) { "Value must be non-negative." }
    requireReadable(offset, UINT64_BYTES)
    writeUnsignedInt(offset, value ushr 32)
    writeUnsignedInt(offset + UINT32_BYTES, value and UINT32_MAX)
}

internal fun ByteArray.writeFourCc(
    offset: Int,
    value: String,
) {
    require(value.length == FOURCC_BYTES) { "A four-character code must contain exactly four characters." }
    requireReadable(offset, FOURCC_BYTES)
    repeat(FOURCC_BYTES) { index -> this[offset + index] = value[index].code.toByte() }
}

private fun ByteArray.requireReadable(
    offset: Int,
    count: Int,
) {
    require(offset >= 0 && count >= 0 && offset <= size - count) { "ISO BMFF field exceeds its byte buffer." }
}

internal const val ISO_BOX_HEADER_BYTES = 8
internal const val ISO_BOX_TYPE_OFFSET = 4
internal const val ISO_FULL_BOX_HEADER_BYTES = 4
internal const val UINT32_BYTES = 4
internal const val UINT64_BYTES = 8
internal const val UINT32_MAX = 0xffff_ffffL
private const val ISO_EXTENDED_BOX_HEADER_BYTES = 16
private const val FOURCC_BYTES = 4
private const val BYTE_MASK = 0xff
private const val BYTE_MASK_LONG = 0xffL
