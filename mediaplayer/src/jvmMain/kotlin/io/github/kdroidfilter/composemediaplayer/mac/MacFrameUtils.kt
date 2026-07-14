package io.github.kdroidfilter.composemediaplayer.mac

import java.nio.ByteBuffer

private const val BGRA_BYTES_PER_PIXEL = 4L
private const val FRAME_HASH_SAMPLE_COUNT = 200L

internal fun calculateFrameHash(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowBytes: Int,
): Int {
    if (width <= 0 || height <= 0 || rowBytes <= 0) return 0
    if (rowBytes.toLong() < width.toLong() * BGRA_BYTES_PER_PIXEL) return 0
    if (buffer.capacity().toLong() < rowBytes.toLong() * height.toLong()) return 0

    val pixelCount = width.toLong() * height.toLong()
    var hash = 1
    var step = if (pixelCount <= FRAME_HASH_SAMPLE_COUNT) 1L else pixelCount / FRAME_HASH_SAMPLE_COUNT
    // Avoid repeatedly sampling one x column when the linear step is a multiple of the width.
    if (width > 1 && step % width.toLong() == 0L) step++

    var index = 0L
    while (index < pixelCount) {
        val x = index % width.toLong()
        val y = index / width.toLong()
        val byteOffset = y * rowBytes.toLong() + x * BGRA_BYTES_PER_PIXEL
        hash = 31 * hash + buffer.getInt(byteOffset.toInt())
        index += step
    }
    return hash
}

internal fun copyBgraFrame(
    src: ByteBuffer,
    dst: ByteBuffer,
    width: Int,
    height: Int,
    srcBytesPerRow: Int,
    dstRowBytes: Int,
) {
    require(width > 0) { "width must be > 0 (was $width)" }
    require(height > 0) { "height must be > 0 (was $height)" }
    val pixelRowBytes = width * 4
    require(srcBytesPerRow >= pixelRowBytes) {
        "srcBytesPerRow ($srcBytesPerRow) must be >= pixelRowBytes ($pixelRowBytes)"
    }
    require(dstRowBytes >= pixelRowBytes) {
        "dstRowBytes ($dstRowBytes) must be >= pixelRowBytes ($pixelRowBytes)"
    }

    val requiredSrcBytes = srcBytesPerRow.toLong() * height.toLong()
    val requiredDstBytes = dstRowBytes.toLong() * height.toLong()
    require(src.capacity().toLong() >= requiredSrcBytes) {
        "src buffer too small: ${src.capacity()} < $requiredSrcBytes"
    }
    require(dst.capacity().toLong() >= requiredDstBytes) {
        "dst buffer too small: ${dst.capacity()} < $requiredDstBytes"
    }

    val srcBuf = src.duplicate()
    val dstBuf = dst.duplicate()
    srcBuf.rewind()
    dstBuf.rewind()

    // Fast path: both buffers have the same layout — single bulk copy
    if (srcBytesPerRow == pixelRowBytes && dstRowBytes == pixelRowBytes) {
        val totalBytes = pixelRowBytes.toLong() * height.toLong()
        srcBuf.limit(totalBytes.toInt())
        dstBuf.limit(totalBytes.toInt())
        dstBuf.put(srcBuf)
        return
    }

    // Slow path: different strides — copy row by row
    val srcCapacity = srcBuf.capacity()
    val dstCapacity = dstBuf.capacity()
    for (row in 0 until height) {
        val srcPos = row * srcBytesPerRow
        srcBuf.limit(srcCapacity)
        srcBuf.position(srcPos)
        srcBuf.limit(srcPos + pixelRowBytes)

        val dstPos = row * dstRowBytes
        dstBuf.limit(dstCapacity)
        dstBuf.position(dstPos)
        dstBuf.limit(dstPos + pixelRowBytes)

        dstBuf.put(srcBuf)
    }
}
