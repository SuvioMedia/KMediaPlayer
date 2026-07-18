package io.github.kdroidfilter.composemediaplayer.dolbyvision

import android.os.Build
import androidx.annotation.Keep

@Keep
actual class LibDoviRpuConverter actual constructor() : DolbyVisionRpuConverter {
    actual override val isAvailable: Boolean
        get() = nativeLibraryLoaded

    actual override suspend fun prepare(): Boolean = isAvailable

    actual override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult {
        if (rpuNalUnit.isEmpty()) {
            return DolbyVisionRpuConversionResult.Invalid("The Dolby Vision RPU NAL unit is empty.")
        }
        if (!nativeLibraryLoaded) {
            return DolbyVisionRpuConversionResult.Unavailable(
                "No compatible arm64-v8a or armeabi-v7a composemediaplayer libdovi library was found.",
            )
        }
        return runCatching { nativeConvertProfile7To81(rpuNalUnit) }
            .fold(
                onSuccess = { converted ->
                    if (converted == null) {
                        DolbyVisionRpuConversionResult.Invalid(
                            "libdovi rejected the RPU or it was not Dolby Vision Profile 7.",
                        )
                    } else {
                        DolbyVisionRpuConversionResult.Success(converted)
                    }
                },
                onFailure = { error ->
                    DolbyVisionRpuConversionResult.Invalid(
                        "libdovi conversion failed: ${error.message ?: error::class.simpleName}",
                    )
                },
            )
    }

    actual fun close() = Unit

    @Keep
    private external fun nativeConvertProfile7To81(rpuNalUnit: ByteArray): ByteArray?

    private companion object {
        val nativeLibraryLoaded: Boolean by lazy {
            isSupportedAndroidAbi() &&
                runCatching { System.loadLibrary("composemediaplayer_libdovi") }.isSuccess
        }

        fun isSupportedAndroidAbi(): Boolean =
            Build.SUPPORTED_ABIS.any { abi ->
                abi == "arm64-v8a" || abi == "armeabi-v7a"
            }
    }
}
