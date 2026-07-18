package io.github.kdroidfilter.composemediaplayer.subtitle

/** A Matroska ASS packet in the form expected by `ass_process_chunk`. */
internal data class AndroidAssChunk(
    val durationMs: Long,
    val payload: ByteArray,
)

/**
 * Media3 prefixes raw Matroska ASS packets with `Dialogue: 0:00:00:00,<duration>,`.
 * libass receives the timing separately and expects the original Matroska payload after
 * those first two fields.
 */
internal fun parseAndroidAssChunk(sample: ByteArray): AndroidAssChunk? {
    if (sample.isEmpty() || sample.size > MAX_ASS_SAMPLE_BYTES) return null
    if (!sample.startsWith(MATROSKA_ASS_SAMPLE_PREFIX)) return null

    val firstComma = MATROSKA_ASS_SAMPLE_PREFIX.lastIndex
    val secondComma = sample.indexOf(COMMA, startIndex = firstComma + 1)
    if (secondComma <= firstComma + 1 || secondComma + 1 >= sample.size) return null

    val durationMs = parseAssTimecodeMs(sample, firstComma + 1, secondComma) ?: return null
    return AndroidAssChunk(
        durationMs = durationMs,
        payload = sample.copyOfRange(secondComma + 1, sample.size),
    )
}

private fun parseAssTimecodeMs(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Long? {
    val values = IntArray(4)
    var valueIndex = 0
    var value = 0
    var hasDigit = false

    for (index in start until end) {
        val byte = bytes[index]
        when {
            byte in ASCII_ZERO..ASCII_NINE -> {
                hasDigit = true
                val digit = byte - ASCII_ZERO
                if (value > (Int.MAX_VALUE - digit) / DECIMAL_RADIX) return null
                value = value * DECIMAL_RADIX + digit
            }

            byte == COLON || byte == DOT -> {
                if (!hasDigit || valueIndex >= values.lastIndex) return null
                values[valueIndex++] = value
                value = 0
                hasDigit = false
            }

            else -> return null
        }
    }
    if (!hasDigit || valueIndex != values.lastIndex) return null
    values[valueIndex] = value

    val hours = values[0]
    val minutes = values[1]
    val seconds = values[2]
    val centiseconds = values[3]
    if (
        minutes !in 0..MAX_MINUTE_OR_SECOND ||
        seconds !in 0..MAX_MINUTE_OR_SECOND ||
        centiseconds !in 0..MAX_CENTISECOND
    ) {
        return null
    }

    return hours * MILLIS_PER_HOUR +
        minutes * MILLIS_PER_MINUTE +
        seconds * MILLIS_PER_SECOND +
        centiseconds * MILLIS_PER_CENTISECOND
}

private fun ByteArray.indexOf(
    value: Byte,
    startIndex: Int = 0,
): Int {
    for (index in startIndex.coerceAtLeast(0) until size) {
        if (this[index] == value) return index
    }
    return -1
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

internal const val MAX_ASS_SAMPLE_BYTES: Int = 2 * 1024 * 1024

private const val DECIMAL_RADIX = 10
private const val MAX_MINUTE_OR_SECOND = 59
private const val MAX_CENTISECOND = 99
private const val MILLIS_PER_CENTISECOND = 10L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private val COMMA = ','.code.toByte()
private val COLON = ':'.code.toByte()
private val DOT = '.'.code.toByte()
private val ASCII_ZERO = '0'.code.toByte()
private val ASCII_NINE = '9'.code.toByte()
private val MATROSKA_ASS_SAMPLE_PREFIX = "Dialogue: 0:00:00:00,".encodeToByteArray()
