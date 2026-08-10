@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import cnames.structs.cmp_vlc_frame
import cnames.structs.cmp_vlc_player
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendAvailability
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendUnavailableException
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.LibVlcFrameDeliveryMode
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_COMMAND_FAILED
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_INCOMPATIBLE_BRIDGE_ABI
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_INITIALIZATION_FAILED
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_INVALID_ARGUMENT
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_LIBRARY_NOT_FOUND
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_OK
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_REQUIRED_SYMBOL_MISSING
import io.github.kdroidfilter.composemediaplayer.libvlc.native.CMP_VLC_SNAPSHOT_FAILED
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_frame_info
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_frame_pixels
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_frame_release
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_acquire_latest_frame
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_create
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_destroy
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_get_snapshot
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_last_error
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_open
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_pause
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_play
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_seek
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_set_loop
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_set_rate
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_set_volume
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_snapshot
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_player_stop
import io.github.kdroidfilter.composemediaplayer.libvlc.native.cmp_vlc_probe
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock

internal data class IosLibVlcRuntimeResolution(
    val bridgePath: String,
    val libVlcPath: String,
    val pluginDirectory: String,
)

internal data class IosLibVlcSnapshot(
    val state: IosLibVlcPlaybackState,
    val mediaGeneration: ULong,
    val positionMicroseconds: Long,
    val durationMicroseconds: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val bufferedPermille: Int,
    val isSeekable: Boolean,
)

internal data class IosLibVlcFrameInfo(
    val serial: ULong,
    val outputGeneration: ULong,
    val ptsMicroseconds: Long,
    val width: Int,
    val height: Int,
    val stride: Int,
    val byteCount: ULong,
    val sourceDynamicRange: Int,
    val premultipliedAlpha: Boolean,
)

internal class IosLibVlcFrame(
    private var native: CPointer<cmp_vlc_frame>?,
    val pixels: COpaquePointer,
    val info: IosLibVlcFrameInfo,
) {
    fun close() {
        val current = native ?: return
        native = null
        cmp_vlc_frame_release(current)
    }
}

internal enum class IosLibVlcPlaybackState {
    IDLE,
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
    ERROR,
}

internal fun inspectIosLibVlcRuntime(): LibVlcBackendAvailability =
    when (val result = resolveIosLibVlcRuntime()) {
        is IosLibVlcRuntimeResult.Available ->
            LibVlcBackendAvailability.Available(
                backend = "KMediaVlc ABI 2 / bundled libVLC 4",
                deliveryMode = LibVlcFrameDeliveryMode.CPU_PULL,
            )
        is IosLibVlcRuntimeResult.Unavailable -> result.availability
    }

internal fun requireIosLibVlcRuntime(): IosLibVlcRuntimeResolution =
    when (val result = resolveIosLibVlcRuntime()) {
        is IosLibVlcRuntimeResult.Available -> result.resolution
        is IosLibVlcRuntimeResult.Unavailable -> throw LibVlcBackendUnavailableException(result.availability)
    }

private sealed interface IosLibVlcRuntimeResult {
    data class Available(
        val resolution: IosLibVlcRuntimeResolution,
    ) : IosLibVlcRuntimeResult

    data class Unavailable(
        val availability: LibVlcBackendAvailability.Unavailable,
    ) : IosLibVlcRuntimeResult
}

private fun resolveIosLibVlcRuntime(): IosLibVlcRuntimeResult {
    val frameworks =
        NSBundle.mainBundle.privateFrameworksPath
            ?: return unavailable(
                LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
                "The iOS application has no private Frameworks directory for KMediaVlc.",
            )
    val resolution =
        IosLibVlcRuntimeResolution(
            bridgePath = "$frameworks/KMediaVlc.framework/KMediaVlc",
            libVlcPath = "$frameworks/KMediaVlcLibVlc.framework/KMediaVlcLibVlc",
            pluginDirectory = frameworks,
        )
    val requiredBinaries =
        listOf(
            resolution.bridgePath,
            resolution.libVlcPath,
            "$frameworks/KMediaVlcCore.framework/KMediaVlcCore",
            "$frameworks/libvmem_plugin.framework/libvmem_plugin",
            "$frameworks/libaudiounit_ios_plugin.framework/libaudiounit_ios_plugin",
        )
    if (requiredBinaries.any { path -> !NSFileManager.defaultManager.isReadableFileAtPath(path) }) {
        return unavailable(
            LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            "The app-bundled KMediaVlc XCFramework graph is incomplete.",
        )
    }
    val status =
        cmp_vlc_probe(
            resolution.bridgePath,
            resolution.libVlcPath,
            resolution.pluginDirectory,
        )
    return if (status == CMP_VLC_OK.toInt()) {
        IosLibVlcRuntimeResult.Available(resolution)
    } else {
        IosLibVlcRuntimeResult.Unavailable(status.toAvailability())
    }
}

private fun unavailable(
    reason: LibVlcBackendUnavailableReason,
    guidance: String,
): IosLibVlcRuntimeResult.Unavailable =
    IosLibVlcRuntimeResult.Unavailable(
        LibVlcBackendAvailability.Unavailable(reason, guidance),
    )

private fun Int.toAvailability(): LibVlcBackendAvailability.Unavailable =
    when (this) {
        CMP_VLC_LIBRARY_NOT_FOUND.toInt() ->
            LibVlcBackendAvailability.Unavailable(
                LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
                "The app-bundled KMediaVlc bridge could not be loaded.",
            )
        CMP_VLC_REQUIRED_SYMBOL_MISSING.toInt(),
        CMP_VLC_INCOMPATIBLE_BRIDGE_ABI.toInt(),
        CMP_VLC_INVALID_ARGUMENT.toInt(),
        CMP_VLC_SNAPSHOT_FAILED.toInt(),
        ->
            LibVlcBackendAvailability.Unavailable(
                LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                "The app-bundled KMediaVlc bridge does not match the required ABI.",
            )
        CMP_VLC_INITIALIZATION_FAILED.toInt(),
        CMP_VLC_COMMAND_FAILED.toInt(),
        ->
            LibVlcBackendAvailability.Unavailable(
                LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                "The app-bundled KMediaVlc/libVLC graph failed its CPU-pull initialization probe.",
            )
        else ->
            LibVlcBackendAvailability.Unavailable(
                LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                "The app-bundled KMediaVlc runtime returned an unknown probe status.",
            )
    }

internal class IosLibVlcEngine private constructor(
    private var player: CPointer<cmp_vlc_player>?,
) {
    private val lock = NSLock()

    fun open(
        location: String,
        headers: Map<String, String>,
        autoplay: Boolean,
    ): Boolean =
        withPlayer { current ->
            memScoped {
                val entries = headers.entries.flatMap { (name, value) -> listOf(name, value) }
                val nativeHeaders =
                    if (entries.isEmpty()) {
                        null
                    } else {
                        allocArray<CPointerVar<ByteVar>>(entries.size).also { array ->
                            entries.forEachIndexed { index, value ->
                                array[index] = value.cstr.getPointer(this)
                            }
                        }
                    }
                cmp_vlc_player_open(
                    current,
                    location,
                    nativeHeaders,
                    entries.size.toULong(),
                    autoplay,
                ) == CMP_VLC_OK.toInt()
            }
        }

    fun play(): Boolean = withPlayer { cmp_vlc_player_play(it) == CMP_VLC_OK.toInt() }

    fun pause(): Boolean = withPlayer { cmp_vlc_player_pause(it) == CMP_VLC_OK.toInt() }

    fun stop(): Boolean = withPlayer { cmp_vlc_player_stop(it) == CMP_VLC_OK.toInt() }

    fun seek(
        timeMicroseconds: Long,
        fast: Boolean,
    ): Boolean = withPlayer { cmp_vlc_player_seek(it, timeMicroseconds, fast) == CMP_VLC_OK.toInt() }

    fun setVolume(value: Float): Boolean = withPlayer { cmp_vlc_player_set_volume(it, value) == CMP_VLC_OK.toInt() }

    fun setRate(value: Float): Boolean = withPlayer { cmp_vlc_player_set_rate(it, value) == CMP_VLC_OK.toInt() }

    fun setLoop(value: Boolean): Boolean = withPlayer { cmp_vlc_player_set_loop(it, value) == CMP_VLC_OK.toInt() }

    fun snapshot(): IosLibVlcSnapshot =
        withPlayer { current ->
            memScoped {
                val snapshot = alloc<cmp_vlc_player_snapshot>()
                check(cmp_vlc_player_get_snapshot(current, snapshot.ptr) == CMP_VLC_OK.toInt()) {
                    "KMediaVlc rejected the iOS snapshot request."
                }
                IosLibVlcSnapshot(
                    state = snapshot.state.toPlaybackState(),
                    mediaGeneration = snapshot.media_generation,
                    positionMicroseconds = snapshot.position_microseconds,
                    durationMicroseconds = snapshot.duration_microseconds,
                    videoWidth = snapshot.video_width.toInt(),
                    videoHeight = snapshot.video_height.toInt(),
                    bufferedPermille = snapshot.buffered_permille.toInt(),
                    isSeekable = snapshot.seekable,
                )
            }
        }

    fun acquireLatestFrame(): IosLibVlcFrame? =
        withPlayer { current ->
            memScoped {
                val info = alloc<cmp_vlc_frame_info>()
                val frame = cmp_vlc_player_acquire_latest_frame(current, info.ptr) ?: return@memScoped null
                val byteCount = alloc<ULongVar>()
                val pixels = cmp_vlc_frame_pixels(frame, byteCount.ptr)
                if (pixels == null || byteCount.value < info.byte_count) {
                    cmp_vlc_frame_release(frame)
                    return@memScoped null
                }
                IosLibVlcFrame(
                    native = frame,
                    pixels = pixels,
                    info =
                        IosLibVlcFrameInfo(
                            serial = info.serial,
                            outputGeneration = info.output_generation,
                            ptsMicroseconds = info.pts_microseconds,
                            width = info.width.toInt(),
                            height = info.height.toInt(),
                            stride = info.stride.toInt(),
                            byteCount = byteCount.value,
                            sourceDynamicRange = info.source_dynamic_range,
                            premultipliedAlpha = info.premultiplied_alpha,
                        ),
                )
            }
        }

    fun lastError(): String? = withPlayer { cmp_vlc_player_last_error(it)?.toKString() }

    fun close() {
        lock.lock()
        try {
            val current = player ?: return
            player = null
            cmp_vlc_player_destroy(current)
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> withPlayer(block: (CPointer<cmp_vlc_player>) -> T): T {
        lock.lock()
        return try {
            block(checkNotNull(player) { "The KMediaVlc iOS player is closed." })
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun create(runtime: IosLibVlcRuntimeResolution): IosLibVlcEngine {
            val statusAndPlayer =
                memScoped {
                    val status = alloc<IntVar>()
                    val player =
                        cmp_vlc_player_create(
                            runtime.bridgePath,
                            runtime.libVlcPath,
                            runtime.pluginDirectory,
                            status.ptr,
                        )
                    status.value to player
                }
            val player =
                statusAndPlayer.second
                    ?: throw LibVlcBackendUnavailableException(statusAndPlayer.first.toAvailability())
            return IosLibVlcEngine(player)
        }
    }
}

private fun Int.toPlaybackState(): IosLibVlcPlaybackState =
    IosLibVlcPlaybackState.entries.getOrNull(this)
        ?: throw IllegalStateException("KMediaVlc returned an unknown iOS playback state.")
