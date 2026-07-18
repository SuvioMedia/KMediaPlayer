package io.github.kdroidfilter.composemediaplayer.util

/**
 * Lightweight platform detection using System properties.
 * Replaces com.sun.jna.Platform to avoid the JNA dependency.
 */
internal object CurrentPlatform {
    enum class OS { WINDOWS, MAC, LINUX }

    val os: OS by lazy {
        val name = System.getProperty("os.name", "").lowercase()
        when {
            name.contains("win") -> OS.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OS.MAC
            else -> OS.LINUX
        }
    }

    val architecture: String by lazy {
        System.getProperty("os.arch", "").lowercase()
    }

    val isSupportedMacOsArchitecture: Boolean
        get() = os != OS.MAC || architecture.isSupportedMacOsArchitecture()
}

internal fun String.isSupportedMacOsArchitecture(): Boolean = lowercase() in setOf("aarch64", "arm64")
