package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Private JNI surface. All handles are owned by [AndroidAssNativeSession]. */
@Keep
internal object AndroidAssNativeBridge {
    private val loadResult = runCatching { System.loadLibrary("kmediaass") }

    val isAvailable: Boolean by lazy {
        loadResult.isSuccess &&
            runCatching {
                nativeVersion() >= MINIMUM_LIBASS_VERSION && nativeSessionHealthCheck()
            }.getOrDefault(false)
    }

    fun requireAvailable() {
        check(isAvailable) {
            "KMediaPlayer requires its bundled libass 0.17.5 or newer Android backend."
        }
    }

    @JvmStatic
    private external fun nativeVersion(): Int

    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeConfigureFonts(
        handle: Long,
        configurationPath: String,
    ): Boolean

    @JvmStatic
    external fun nativeAddFont(
        handle: Long,
        name: String,
        data: ByteArray,
    ): Boolean

    @JvmStatic
    external fun nativeProcessCodecPrivate(
        handle: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean

    @JvmStatic
    external fun nativeProcessData(
        handle: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean

    @JvmStatic
    external fun nativeProcessChunk(
        handle: Long,
        startMs: Long,
        durationMs: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean

    @JvmStatic
    external fun nativeSetCacheLimits(
        handle: Long,
        glyphCount: Int,
        bitmapCacheMiB: Int,
    )

    @JvmStatic
    external fun nativeSetStorageSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    @JvmStatic
    external fun nativeSetFrameSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    /** Metadata: status, x, y, width, height, stride. */
    @JvmStatic
    external fun nativeRender(
        handle: Long,
        timeMs: Long,
        force: Boolean,
        metadata: IntArray,
    ): ByteBuffer?

    @JvmStatic
    external fun nativeClose(handle: Long)

    private fun nativeSessionHealthCheck(): Boolean {
        val handle = nativeCreate()
        if (handle == 0L) return false
        nativeClose(handle)
        return true
    }

    private const val MINIMUM_LIBASS_VERSION = 0x01705000
}
