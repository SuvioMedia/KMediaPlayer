package io.github.kdroidfilter.composemediaplayer

import android.os.Build

internal val SUPPORTED_ANDROID_RUNTIME_ABIS: Set<String> =
    setOf(
        "arm64-v8a",
        "armeabi-v7a",
    )

internal fun isSupportedAndroidRuntimeAbi(abis: Iterable<String>): Boolean =
    abis.any(SUPPORTED_ANDROID_RUNTIME_ABIS::contains)

@Suppress("DEPRECATION")
internal fun currentAndroidRuntimeIsSupported(): Boolean {
    val reportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
    if (reportedAbis.isNotEmpty()) {
        return isSupportedAndroidRuntimeAbi(reportedAbis)
    }

    val legacyAbis = listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2).filter(String::isNotBlank)
    // Android devices always report at least one ABI. Robolectric's Build shadow can leave
    // both modern and legacy fields unset, so keep architecture checks testable there.
    return legacyAbis.isEmpty() || isSupportedAndroidRuntimeAbi(legacyAbis)
}

internal fun requireSupportedAndroidRuntime() {
    if (!currentAndroidRuntimeIsSupported()) {
        throw UnsupportedOperationException(
            "Compose Media Player for Android requires arm64-v8a or armeabi-v7a.",
        )
    }
}
