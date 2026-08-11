package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import java.util.ServiceLoader

/** Color and surface options shared with optional desktop backend artifacts. */
@Stable
public data class OptionalDesktopPlaybackBackendOptions(
    public val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.AUTO,
    public val dolbyVisionPolicy: DolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    public val desktopVideoSurfaceMode: DesktopVideoSurfaceMode =
        DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
)

/**
 * Service-provider contract used by backends which are deliberately absent from the core graph.
 *
 * Implementations must not load native code from their constructor or from [create]. Native
 * availability belongs in [DesktopPlaybackBackend.inspectAvailability], so discovering an
 * optional artifact remains a read-only operation.
 */
public interface OptionalDesktopPlaybackBackendProvider {
    public val providerId: String

    public fun create(options: OptionalDesktopPlaybackBackendOptions): DesktopPlaybackBackend
}

/** Discovers optional backend artifacts visible to the active application class loader. */
public fun loadOptionalDesktopPlaybackBackends(
    options: OptionalDesktopPlaybackBackendOptions = OptionalDesktopPlaybackBackendOptions(),
): List<DesktopPlaybackBackend> {
    val classLoader =
        Thread.currentThread().contextClassLoader
            ?: OptionalDesktopPlaybackBackendProvider::class.java.classLoader
    val providers =
        ServiceLoader
            .load(OptionalDesktopPlaybackBackendProvider::class.java, classLoader)
            .toList()
            .sortedBy { provider -> provider.providerId }
    require(providers.all { provider -> provider.providerId.isNotBlank() }) {
        "Optional desktop backend provider ids must be non-blank."
    }
    require(providers.map { provider -> provider.providerId }.distinct().size == providers.size) {
        "Optional desktop backend provider ids must be unique."
    }
    return providers.map { provider -> provider.create(options) }
}
