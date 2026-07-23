@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.ass

import cnames.structs.KMediaAssRenderer
import io.github.kdroidfilter.composemediaplayer.ass.native.KMEDIA_ASS_RENDER_EMPTY
import io.github.kdroidfilter.composemediaplayer.ass.native.KMEDIA_ASS_RENDER_ERROR
import io.github.kdroidfilter.composemediaplayer.ass.native.KMEDIA_ASS_RENDER_PIXELS
import io.github.kdroidfilter.composemediaplayer.ass.native.KMediaAssFrame
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_frame_copy_cg_image
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_library_version
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_renderer_create
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_renderer_destroy
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_renderer_render_rgba
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_renderer_set_track
import io.github.kdroidfilter.composemediaplayer.ass.native.kmedia_ass_shared_runtime_id
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGImageRelease
import platform.UIKit.UIImage

internal class AppleAssNativeSession private constructor(
    private var handle: CPointer<KMediaAssRenderer>?,
) {
    fun render(
        width: Int,
        height: Int,
        timeMs: Long,
    ): AppleAssRenderedFrame? =
        memScoped {
            val current = handle ?: return@memScoped null
            val frame = alloc<KMediaAssFrame>()
            val status =
                kmedia_ass_renderer_render_rgba(
                    current,
                    width,
                    height,
                    timeMs,
                    frame.ptr,
                )
            when (status) {
                KMEDIA_ASS_RENDER_EMPTY -> return@memScoped null
                KMEDIA_ASS_RENDER_ERROR -> error("The shared libass renderer failed to render a frame.")
                KMEDIA_ASS_RENDER_PIXELS -> Unit
                else -> error("The shared libass renderer returned an unknown frame status: $status")
            }

            val imageRef =
                checkNotNull(kmedia_ass_frame_copy_cg_image(frame.ptr)) {
                    "The shared libass renderer could not copy its rendered frame."
                }
            try {
                AppleAssRenderedFrame(
                    image = UIImage.imageWithCGImage(imageRef),
                    x = frame.x,
                    y = frame.y,
                    width = frame.width,
                    height = frame.height,
                    canvasWidth = width,
                    canvasHeight = height,
                )
            } finally {
                CGImageRelease(imageRef)
            }
        }

    fun close() {
        val current = handle ?: return
        handle = null
        kmedia_ass_renderer_destroy(current)
    }

    companion object {
        val isRuntimeAvailable: Boolean by lazy {
            runCatching {
                check(kmedia_ass_library_version().toLong() == REQUIRED_LIBASS_VERSION)
                check(
                    kmedia_ass_shared_runtime_id()?.toKString() == REQUIRED_ASS_RUNTIME_ID,
                ) { "composemediaplayer-ass targets another KMediaAssRuntime ID." }
                val renderer =
                    checkNotNull(kmedia_ass_renderer_create()) {
                        "Could not create an Apple libass renderer."
                    }
                kmedia_ass_renderer_destroy(renderer)
            }.isSuccess
        }

        fun create(script: ByteArray): AppleAssNativeSession {
            require(script.isNotEmpty()) { "The ASS/SSA script is empty." }
            require(script.size <= MAX_ASS_SCRIPT_BYTES) { "The ASS/SSA script exceeds 64 MiB." }
            val renderer =
                checkNotNull(kmedia_ass_renderer_create()) {
                    "Could not create an Apple libass renderer."
                }
            val configured =
                script.usePinned { pinned ->
                    kmedia_ass_renderer_set_track(
                        renderer,
                        pinned.addressOf(0).reinterpret(),
                        script.size.convert(),
                    ) != 0
                }
            if (!configured) {
                kmedia_ass_renderer_destroy(renderer)
                error("Could not load the ASS/SSA script into libass.")
            }
            return AppleAssNativeSession(renderer)
        }

        private const val REQUIRED_LIBASS_VERSION = 0x01705000L
        private const val REQUIRED_ASS_RUNTIME_ID = "kmediaass-0.17.5-36443523f0148567"
        private const val MAX_ASS_SCRIPT_BYTES = 64 * 1024 * 1024
    }
}

internal data class AppleAssRenderedFrame(
    val image: UIImage,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
)
