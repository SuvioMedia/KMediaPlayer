package io.github.kdroidfilter.composemediaplayer.dolbyvision

/** HEVC NAL framing used by demuxed MP4/Matroska samples and Annex-B elementary streams. */
enum class HevcNalUnitFormat {
    ANNEX_B,
    LENGTH_PREFIXED_4,
}

sealed interface HevcRpuRewriteResult {
    data class Success(
        val payload: ByteArray,
        val replacedRpus: Int,
        val discardedEnhancementLayerNals: Int,
    ) : HevcRpuRewriteResult

    data class Failure(
        val message: String,
    ) : HevcRpuRewriteResult
}

/**
 * Replaces HEVC UNSPEC-62 NAL units without decoding or re-encoding picture data.
 *
 * Platform demuxers associate [convertedRpus] with samples by PTS before calling this function;
 * this rewriter then enforces one-for-one replacement in elementary-stream order.
 */
object HevcDolbyVisionRpuRewriter {
    fun rewrite(
        payload: ByteArray,
        format: HevcNalUnitFormat,
        convertedRpus: List<ByteArray>,
        discardEnhancementLayer: Boolean = false,
        maximumOutputBytes: Int = DEFAULT_MAXIMUM_OUTPUT_BYTES,
    ): HevcRpuRewriteResult {
        if (maximumOutputBytes <= 0) {
            return HevcRpuRewriteResult.Failure("maximumOutputBytes must be positive.")
        }
        if (payload.size > maximumOutputBytes) {
            return HevcRpuRewriteResult.Failure("The input HEVC payload exceeds the configured output limit.")
        }
        val normalizedRpus = ArrayList<ByteArray>(convertedRpus.size)
        for (rpu in convertedRpus) {
            val normalized = rpu.withoutAnnexBStartCode()
            if (!normalized.isUnspec62NalUnitWithoutStartCode()) {
                return HevcRpuRewriteResult.Failure("A converted RPU is not an HEVC UNSPEC-62 NAL unit.")
            }
            normalizedRpus += normalized
        }
        return when (format) {
            HevcNalUnitFormat.ANNEX_B ->
                rewriteAnnexB(payload, normalizedRpus, discardEnhancementLayer, maximumOutputBytes)
            HevcNalUnitFormat.LENGTH_PREFIXED_4 ->
                rewriteLengthPrefixed(payload, normalizedRpus, discardEnhancementLayer, maximumOutputBytes)
        }
    }

    @Suppress("ReturnCount")
    private fun rewriteAnnexB(
        payload: ByteArray,
        convertedRpus: List<ByteArray>,
        discardEnhancementLayer: Boolean,
        maximumOutputBytes: Int,
    ): HevcRpuRewriteResult {
        val firstStartCode = payload.findStartCode(fromIndex = 0)
        if (firstStartCode == null) {
            return HevcRpuRewriteResult.Failure("The HEVC payload has no Annex-B start code.")
        }
        val output = BoundedByteArrayBuilder(maximumOutputBytes)
        if (!output.append(payload, 0, firstStartCode.offset)) return outputLimitFailure()
        var current: StartCode? = firstStartCode
        var replaced = 0
        var discardedEnhancementNals = 0
        while (current != null) {
            val nalStart = current.offset + current.length
            val next = payload.findStartCode(nalStart)
            val nalEnd = next?.offset ?: payload.size
            if (nalEnd - nalStart < HEVC_NAL_HEADER_BYTES) {
                return HevcRpuRewriteResult.Failure("The Annex-B stream contains an empty or truncated NAL unit.")
            }
            if (discardEnhancementLayer && payload.isUnspec63NalAt(nalStart)) {
                discardedEnhancementNals++
            } else if (payload.isUnspec62NalAt(nalStart)) {
                if (!output.append(payload, current.offset, nalStart)) return outputLimitFailure()
                val replacement =
                    convertedRpus.getOrNull(replaced) ?: return rpuCountFailure(replaced + 1, convertedRpus.size)
                if (!output.append(replacement)) return outputLimitFailure()
                replaced++
            } else {
                if (!output.append(payload, current.offset, nalStart) || !output.append(payload, nalStart, nalEnd)) {
                    return outputLimitFailure()
                }
            }
            current = next
        }
        if (replaced != convertedRpus.size) return rpuCountFailure(replaced, convertedRpus.size)
        return HevcRpuRewriteResult.Success(output.toByteArray(), replaced, discardedEnhancementNals)
    }

    @Suppress("ReturnCount")
    private fun rewriteLengthPrefixed(
        payload: ByteArray,
        convertedRpus: List<ByteArray>,
        discardEnhancementLayer: Boolean,
        maximumOutputBytes: Int,
    ): HevcRpuRewriteResult {
        val output = BoundedByteArrayBuilder(maximumOutputBytes)
        var cursor = 0
        var replaced = 0
        var discardedEnhancementNals = 0
        while (cursor < payload.size) {
            if (payload.size - cursor < LENGTH_PREFIX_BYTES) {
                return HevcRpuRewriteResult.Failure("The HEVC length prefix is truncated.")
            }
            val nalLength = payload.readNalUnsignedInt(cursor)
            cursor += LENGTH_PREFIX_BYTES
            if (nalLength <= 0 || nalLength > payload.size.toLong() - cursor) {
                return HevcRpuRewriteResult.Failure("The HEVC NAL length exceeds the remaining sample payload.")
            }
            val nalEnd = cursor + nalLength.toInt()
            if (nalEnd - cursor < HEVC_NAL_HEADER_BYTES) {
                return HevcRpuRewriteResult.Failure("The HEVC sample contains a truncated NAL unit.")
            }
            if (discardEnhancementLayer && payload.isUnspec63NalAt(cursor)) {
                discardedEnhancementNals++
            } else if (payload.isUnspec62NalAt(cursor)) {
                val replacement =
                    convertedRpus.getOrNull(replaced) ?: return rpuCountFailure(replaced + 1, convertedRpus.size)
                if (!output.appendUnsignedInt(replacement.size) ||
                    !output.append(replacement)
                ) {
                    return outputLimitFailure()
                }
                replaced++
            } else {
                if (!output.appendUnsignedInt(nalLength.toInt()) || !output.append(payload, cursor, nalEnd)) {
                    return outputLimitFailure()
                }
            }
            cursor = nalEnd
        }
        if (replaced != convertedRpus.size) return rpuCountFailure(replaced, convertedRpus.size)
        return HevcRpuRewriteResult.Success(output.toByteArray(), replaced, discardedEnhancementNals)
    }

    private fun rpuCountFailure(
        streamRpus: Int,
        convertedRpus: Int,
    ) = HevcRpuRewriteResult.Failure(
        "RPU count mismatch: the HEVC payload contains $streamRpus replacement position(s), " +
            "but $convertedRpus converted RPU(s) were supplied.",
    )

    private fun outputLimitFailure() =
        HevcRpuRewriteResult.Failure("The rewritten HEVC payload exceeds the configured output limit.")

    private const val DEFAULT_MAXIMUM_OUTPUT_BYTES = 64 * 1024 * 1024
}

private data class StartCode(
    val offset: Int,
    val length: Int,
)

private fun ByteArray.findStartCode(fromIndex: Int): StartCode? {
    var index = fromIndex.coerceAtLeast(0)
    while (index <= size - SHORT_START_CODE_LENGTH) {
        if (this[index] == ZERO && this[index + SECOND_BYTE_OFFSET] == ZERO) {
            if (this[index + THIRD_BYTE_OFFSET] == ONE) return StartCode(index, SHORT_START_CODE_LENGTH)
            if (
                index <= size - LONG_START_CODE_LENGTH &&
                this[index + THIRD_BYTE_OFFSET] == ZERO &&
                this[index + FOURTH_BYTE_OFFSET] == ONE
            ) {
                return StartCode(index, LONG_START_CODE_LENGTH)
            }
        }
        index++
    }
    return null
}

private fun ByteArray.withoutAnnexBStartCode(): ByteArray =
    when {
        size >= LONG_START_CODE_LENGTH &&
            this[0] == ZERO &&
            this[SECOND_BYTE_OFFSET] == ZERO &&
            this[THIRD_BYTE_OFFSET] == ZERO &&
            this[FOURTH_BYTE_OFFSET] == ONE -> copyOfRange(LONG_START_CODE_LENGTH, size)
        size >= SHORT_START_CODE_LENGTH &&
            this[0] == ZERO &&
            this[SECOND_BYTE_OFFSET] == ZERO &&
            this[THIRD_BYTE_OFFSET] == ONE -> copyOfRange(SHORT_START_CODE_LENGTH, size)
        else -> this
    }

private fun ByteArray.isUnspec62NalUnitWithoutStartCode(): Boolean = size >= HEVC_NAL_HEADER_BYTES && isUnspec62NalAt(0)

private fun ByteArray.isUnspec62NalAt(offset: Int): Boolean =
    ((this[offset].toInt() and UNSIGNED_BYTE_MASK) ushr HEVC_NAL_TYPE_SHIFT) and HEVC_NAL_TYPE_MASK ==
        HEVC_UNSPEC_62_NAL_TYPE

private fun ByteArray.isUnspec63NalAt(offset: Int): Boolean =
    ((this[offset].toInt() and UNSIGNED_BYTE_MASK) ushr HEVC_NAL_TYPE_SHIFT) and HEVC_NAL_TYPE_MASK ==
        HEVC_UNSPEC_63_NAL_TYPE

private fun ByteArray.readNalUnsignedInt(offset: Int): Long =
    ((this[offset].toLong() and UNSIGNED_BYTE_MASK_LONG) shl HIGH_BYTE_SHIFT) or
        ((this[offset + SECOND_BYTE_OFFSET].toLong() and UNSIGNED_BYTE_MASK_LONG) shl MIDDLE_BYTE_SHIFT) or
        ((this[offset + THIRD_BYTE_OFFSET].toLong() and UNSIGNED_BYTE_MASK_LONG) shl LOW_BYTE_SHIFT) or
        (this[offset + FOURTH_BYTE_OFFSET].toLong() and UNSIGNED_BYTE_MASK_LONG)

private class BoundedByteArrayBuilder(
    private val maximumBytes: Int,
) {
    private var data = ByteArray(minOf(INITIAL_CAPACITY, maximumBytes))
    private var size = 0

    fun append(bytes: ByteArray): Boolean = append(bytes, 0, bytes.size)

    fun append(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        val count = end - start
        if (count < 0 || start < 0 || end > bytes.size || count > maximumBytes - size) return false
        if (!ensureCapacity(size + count)) return false
        bytes.copyInto(data, destinationOffset = size, startIndex = start, endIndex = end)
        size += count
        return true
    }

    fun appendUnsignedInt(value: Int): Boolean {
        if (value < 0 || LENGTH_PREFIX_BYTES > maximumBytes - size) return false
        if (!ensureCapacity(size + LENGTH_PREFIX_BYTES)) return false
        data[size++] = (value ushr HIGH_BYTE_SHIFT).toByte()
        data[size++] = (value ushr MIDDLE_BYTE_SHIFT).toByte()
        data[size++] = (value ushr LOW_BYTE_SHIFT).toByte()
        data[size++] = value.toByte()
        return true
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensureCapacity(required: Int): Boolean {
        if (required > maximumBytes) return false
        if (required <= data.size) return true
        var newSize = data.size.coerceAtLeast(MINIMUM_CAPACITY)
        while (newSize < required) {
            newSize = minOf(maximumBytes, newSize * CAPACITY_GROWTH_FACTOR)
            if (newSize < required && newSize == maximumBytes) return false
        }
        data = data.copyOf(newSize)
        return true
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}

private const val LENGTH_PREFIX_BYTES = 4
private const val HEVC_NAL_HEADER_BYTES = 2
private const val HEVC_NAL_TYPE_MASK = 0x3f
private const val HEVC_UNSPEC_62_NAL_TYPE = 62
private const val HEVC_UNSPEC_63_NAL_TYPE = 63
private const val HEVC_NAL_TYPE_SHIFT = 1
private const val SHORT_START_CODE_LENGTH = 3
private const val LONG_START_CODE_LENGTH = 4
private const val SECOND_BYTE_OFFSET = 1
private const val THIRD_BYTE_OFFSET = 2
private const val FOURTH_BYTE_OFFSET = 3
private const val HIGH_BYTE_SHIFT = 24
private const val MIDDLE_BYTE_SHIFT = 16
private const val LOW_BYTE_SHIFT = 8
private const val UNSIGNED_BYTE_MASK = 0xff
private const val UNSIGNED_BYTE_MASK_LONG = 0xffL
private const val MINIMUM_CAPACITY = 1
private const val CAPACITY_GROWTH_FACTOR = 2
private const val ZERO: Byte = 0
private const val ONE: Byte = 1
