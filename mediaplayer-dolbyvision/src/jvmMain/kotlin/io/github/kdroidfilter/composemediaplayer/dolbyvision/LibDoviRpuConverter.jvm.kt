package io.github.kdroidfilter.composemediaplayer.dolbyvision

actual class LibDoviRpuConverter actual constructor() : DolbyVisionRpuConverter {
    private val delegate = JvmLibDoviRpuConverter()

    actual override val isAvailable: Boolean
        get() = delegate.isAvailable

    actual override suspend fun prepare(): Boolean = delegate.prepare()

    actual override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
        delegate.convertProfile7To81(rpuNalUnit)

    actual fun close() = delegate.close()
}
