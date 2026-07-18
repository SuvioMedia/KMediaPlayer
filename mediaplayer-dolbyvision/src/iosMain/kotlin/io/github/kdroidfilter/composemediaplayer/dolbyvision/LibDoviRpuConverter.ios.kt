package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.dolbyvision.native.cmp_dovi_convert_profile7_to81
import io.github.kdroidfilter.composemediaplayer.dolbyvision.native.cmp_dovi_free_buffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
actual class LibDoviRpuConverter actual constructor() : DolbyVisionRpuConverter {
    actual override val isAvailable: Boolean = true

    actual override suspend fun prepare(): Boolean = true

    actual override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult {
        if (rpuNalUnit.isEmpty()) {
            return DolbyVisionRpuConversionResult.Invalid("The Dolby Vision RPU NAL unit is empty.")
        }
        return rpuNalUnit.usePinned { pinnedInput ->
            memScoped {
                val outputLength = alloc<ULongVar>()
                val output =
                    cmp_dovi_convert_profile7_to81(
                        input = pinnedInput.addressOf(0).reinterpret(),
                        input_len = rpuNalUnit.size.convert(),
                        output_len = outputLength.ptr,
                    )
                        ?: return@memScoped DolbyVisionRpuConversionResult.Invalid(
                            "libdovi rejected the RPU or it was not Dolby Vision Profile 7.",
                        )
                val length = outputLength.value.toLong()
                if (length <= 0 || length > MAXIMUM_RPU_BYTES) {
                    cmp_dovi_free_buffer(output, outputLength.value)
                    return@memScoped DolbyVisionRpuConversionResult.Invalid(
                        "libdovi returned an invalid RPU length.",
                    )
                }
                try {
                    DolbyVisionRpuConversionResult.Success(output.readBytes(length.toInt()))
                } finally {
                    cmp_dovi_free_buffer(output, outputLength.value)
                }
            }
        }
    }

    actual fun close() = Unit

    private companion object {
        const val MAXIMUM_RPU_BYTES = 4L * 1024L * 1024L
    }
}
