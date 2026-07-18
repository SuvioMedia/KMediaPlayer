package io.github.kdroidfilter.composemediaplayer.subtitle

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.AttributeSet
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Transparent, non-interactive EGL layer that composites libass images above the video surface. */
@UnstableApi
internal class AndroidAssTextureView :
    TextureView,
    TextureView.SurfaceTextureListener {
    private val controller: AndroidAssController

    @Volatile
    private var pendingPositionUs = 0L

    @Volatile
    private var cropToFill = false

    @Volatile
    private var videoAspectRatio = 0f

    @Volatile
    private var renderThread: RenderThread? = null

    @Volatile
    private var activeSurfaceToken: Any? = null

    constructor(context: Context, controller: AndroidAssController) : this(context, null, controller)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        controller: AndroidAssController,
    ) : super(context, attrs) {
        this.controller = controller
        isOpaque = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    fun requestRender(positionUs: Long) {
        pendingPositionUs = positionUs
        renderThread?.requestRender(positionUs)
    }

    fun updateVideoGeometry(
        cropToFill: Boolean,
        videoAspectRatio: Float,
    ) {
        val normalizedAspectRatio = videoAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 0f
        if (this.cropToFill == cropToFill && this.videoAspectRatio == normalizedAspectRatio) return
        this.cropToFill = cropToFill
        this.videoAspectRatio = normalizedAspectRatio
        renderThread?.updateVideoGeometry(this.cropToFill, this.videoAspectRatio)
    }

    fun releaseOverlay() {
        controller.detachOverlay(this)
        activeSurfaceToken = null
        renderThread?.release()
        renderThread = null
    }

    fun attachOverlay() {
        controller.attachOverlay(this)
        activeSurfaceToken?.let { surfaceToken ->
            if (controller.attachOverlaySurface(this, surfaceToken)) {
                renderThread?.resize(width, height)
            }
        }
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        val surfaceToken = Any()
        activeSurfaceToken = surfaceToken
        controller.attachOverlaySurface(this, surfaceToken)
        RenderThread(
            surfaceTexture = surface,
            width = width,
            height = height,
            cropToFill = cropToFill,
            videoAspectRatio = videoAspectRatio,
            renderer = GlRenderer(controller, this, surfaceToken),
        ).also { thread ->
            renderThread = thread
            thread.startRendering()
            thread.requestRender(pendingPositionUs)
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        activeSurfaceToken = null
        renderThread?.release()
        renderThread = null
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private interface Renderer {
        fun onSurfaceCreated()

        fun onSurfaceChanged(
            width: Int,
            height: Int,
        )

        fun onVideoGeometryChanged(
            cropToFill: Boolean,
            videoAspectRatio: Float,
        )

        fun onDrawFrame(positionUs: Long): Boolean

        fun onSurfaceDestroyed()
    }

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private var cropToFill: Boolean,
        private var videoAspectRatio: Float,
        private val renderer: Renderer,
    ) : HandlerThread("KMediaAssEgl"),
        Handler.Callback {
        private lateinit var handler: Handler
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var lastPositionUs = 0L

        fun startRendering() {
            start()
            handler = Handler(looper, this)
            handler.sendEmptyMessage(MSG_INITIALIZE)
        }

        fun requestRender(positionUs: Long) {
            if (!::handler.isInitialized) return
            handler.removeMessages(MSG_DRAW)
            handler.obtainMessage(MSG_DRAW, positionUs).sendToTarget()
        }

        fun resize(
            width: Int,
            height: Int,
        ) {
            this.width = width
            this.height = height
            if (::handler.isInitialized) handler.sendEmptyMessage(MSG_RESIZE)
        }

        fun updateVideoGeometry(
            cropToFill: Boolean,
            videoAspectRatio: Float,
        ) {
            this.cropToFill = cropToFill
            this.videoAspectRatio = videoAspectRatio
            if (::handler.isInitialized) {
                handler.removeMessages(MSG_RESIZE)
                handler.sendEmptyMessage(MSG_RESIZE)
            }
        }

        fun release() {
            if (!::handler.isInitialized) {
                quitSafely()
                runCatching(surfaceTexture::release)
                return
            }
            handler.removeCallbacksAndMessages(null)
            if (!handler.sendEmptyMessage(MSG_RELEASE)) {
                runCatching(surfaceTexture::release)
            }
        }

        override fun handleMessage(message: Message): Boolean {
            runCatching {
                when (message.what) {
                    MSG_INITIALIZE -> initializeEgl()
                    MSG_DRAW -> draw(message.obj as Long)
                    MSG_RESIZE -> resizeEgl()
                    MSG_RELEASE -> releaseEgl()
                }
            }.onFailure { throwable ->
                logAndroidAssError {
                    "Android ASS EGL thread failed: ${throwable.message ?: throwable::class.simpleName}"
                }
                releaseEgl()
            }
            return true
        }

        private fun initializeEgl() {
            eglDisplay = GlUtil.getDefaultEglDisplay()
            eglContext = GlUtil.createEglContext(eglDisplay)
            eglSurface = GlUtil.createEglSurface(eglDisplay, surfaceTexture, C.COLOR_TRANSFER_SDR, false)
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed with 0x${EGL14.eglGetError().toString(HEX_RADIX)}"
            }
            renderer.onSurfaceCreated()
            renderer.onVideoGeometryChanged(cropToFill, videoAspectRatio)
            renderer.onSurfaceChanged(width, height)
            clearAndSwap()
        }

        private fun resizeEgl() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            renderer.onVideoGeometryChanged(cropToFill, videoAspectRatio)
            renderer.onSurfaceChanged(width, height)
            clearAndSwap()
            draw(lastPositionUs)
        }

        private fun draw(positionUs: Long) {
            lastPositionUs = positionUs
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            if (renderer.onDrawFrame(positionUs)) {
                check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    "eglSwapBuffers failed with 0x${EGL14.eglGetError().toString(HEX_RADIX)}"
                }
            }
        }

        private fun clearAndSwap() {
            GlUtil.clearFocusedBuffers()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun releaseEgl() {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    runCatching { renderer.onSurfaceDestroyed() }
                    runCatching { GlUtil.destroyEglSurface(eglDisplay, eglSurface) }
                    runCatching { GlUtil.destroyEglContext(eglDisplay, eglContext) }
                    runCatching { GlUtil.terminate(eglDisplay) }
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            } finally {
                runCatching(surfaceTexture::release)
                quitSafely()
            }
        }

        private companion object {
            const val MSG_INITIALIZE = 1
            const val MSG_DRAW = 2
            const val MSG_RESIZE = 3
            const val MSG_RELEASE = 4
            const val HEX_RADIX = 16
        }
    }

    private class GlRenderer(
        private val controller: AndroidAssController,
        private val owner: AndroidAssTextureView,
        private val surfaceToken: Any,
    ) : Renderer {
        private lateinit var program: GlProgram
        private var vertexBufferId = 0
        private var textureCoordinateBufferId = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceContainsContent = false
        private var forceNextFrame = true
        private var frameTextureId = 0
        private var frameTextureWidth = 0
        private var frameTextureHeight = 0
        private var cropToFill = false
        private var videoAspectRatio = 0f
        private var cropX = 0
        private var cropY = 0

        override fun onSurfaceCreated() {
            controller.invalidateRenderState()
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val vertexBuffer = VERTICES.toNativeFloatBuffer()
            val textureCoordinateBuffer = TEXTURE_COORDINATES.toNativeFloatBuffer()
            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vertexBufferId = buffers[0]
            textureCoordinateBufferId = buffers[1]

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                VERTICES.size * Float.SIZE_BYTES,
                vertexBuffer,
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, textureCoordinateBufferId)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                TEXTURE_COORDINATES.size * Float.SIZE_BYTES,
                textureCoordinateBuffer,
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            GlUtil.checkGlError()
        }

        override fun onSurfaceChanged(
            width: Int,
            height: Int,
        ) {
            surfaceWidth = width.coerceAtLeast(0)
            surfaceHeight = height.coerceAtLeast(0)
            val (frameWidth, frameHeight) = calculateRenderFrameSize()
            cropX = ((frameWidth - surfaceWidth) / 2).coerceAtLeast(0)
            cropY = ((frameHeight - surfaceHeight) / 2).coerceAtLeast(0)
            controller.updateFrameSize(owner, surfaceToken, frameWidth, frameHeight)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            forceNextFrame = true
        }

        override fun onVideoGeometryChanged(
            cropToFill: Boolean,
            videoAspectRatio: Float,
        ) {
            this.cropToFill = cropToFill
            this.videoAspectRatio = videoAspectRatio
        }

        override fun onDrawFrame(positionUs: Long): Boolean {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return false
            val forced = forceNextFrame
            forceNextFrame = false
            return controller.withRenderFrame(positionUs, force = forced) { frame ->
                drawFrame(frame, forced)
            }
        }

        private fun drawFrame(
            frame: AndroidAssRenderFrame,
            forced: Boolean,
        ): Boolean {
            if (frame === AndroidAssRenderFrame.Unchanged) return false
            if (frame === AndroidAssRenderFrame.Empty && !surfaceContainsContent && !forced) return false

            GlUtil.clearFocusedBuffers()
            surfaceContainsContent = frame is AndroidAssRenderFrame.Pixels
            if (frame is AndroidAssRenderFrame.Pixels) {
                uploadFrameTexture(frame)
                program.use()
                bindProgramBuffers()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextureId)
                GLES20.glViewport(
                    frame.x - cropX,
                    surfaceHeight - frame.y + cropY - frame.height,
                    frame.width,
                    frame.height,
                )
                GLES20.glUniform1i(program.getUniformLocation("u_Texture"), 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GlUtil.checkGlError()
            return true
        }

        override fun onSurfaceDestroyed() {
            controller.updateFrameSize(owner, surfaceToken, 0, 0)
            if (vertexBufferId != 0) GlUtil.deleteBuffer(vertexBufferId)
            if (textureCoordinateBufferId != 0) GlUtil.deleteBuffer(textureCoordinateBufferId)
            if (frameTextureId != 0) GlUtil.deleteTexture(frameTextureId)
            if (::program.isInitialized) program.delete()
            vertexBufferId = 0
            textureCoordinateBufferId = 0
            frameTextureId = 0
            frameTextureWidth = 0
            frameTextureHeight = 0
        }

        private fun uploadFrameTexture(frame: AndroidAssRenderFrame.Pixels) {
            if (frameTextureId == 0) {
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                frameTextureId = textures[0]
                check(frameTextureId != 0) { "Cannot allocate the Android ASS frame texture." }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextureId)
            }

            frame.data.position(0)
            if (frame.width != frameTextureWidth || frame.height != frameTextureHeight) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    frame.width,
                    frame.height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    frame.data,
                )
                frameTextureWidth = frame.width
                frameTextureHeight = frame.height
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    frame.width,
                    frame.height,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    frame.data,
                )
            }
        }

        private fun calculateRenderFrameSize(): Pair<Int, Int> {
            if (!cropToFill || videoAspectRatio <= 0f || surfaceWidth <= 0 || surfaceHeight <= 0) {
                return surfaceWidth to surfaceHeight
            }
            val surfaceAspectRatio = surfaceWidth.toFloat() / surfaceHeight
            return if (videoAspectRatio > surfaceAspectRatio) {
                ceil(surfaceHeight * videoAspectRatio).toInt() to surfaceHeight
            } else {
                surfaceWidth to ceil(surfaceWidth / videoAspectRatio).toInt()
            }
        }

        private fun bindProgramBuffers() {
            val position = program.getAttributeArrayLocationAndEnable("a_Position")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
            GLES20.glVertexAttribPointer(position, POSITION_COMPONENT_COUNT, GLES20.GL_FLOAT, false, 0, 0)

            val textureCoordinate = program.getAttributeArrayLocationAndEnable("a_TexCoord")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, textureCoordinateBufferId)
            GLES20.glVertexAttribPointer(
                textureCoordinate,
                TEXTURE_COORDINATE_COMPONENT_COUNT,
                GLES20.GL_FLOAT,
                false,
                0,
                0,
            )
        }

        private companion object {
            const val POSITION_COMPONENT_COUNT = 2
            const val TEXTURE_COORDINATE_COMPONENT_COUNT = 2
            const val VERTEX_COUNT = 4

            val VERTICES =
                floatArrayOf(
                    -1f,
                    1f,
                    1f,
                    1f,
                    -1f,
                    -1f,
                    1f,
                    -1f,
                )
            val TEXTURE_COORDINATES =
                floatArrayOf(
                    0f,
                    0f,
                    1f,
                    0f,
                    0f,
                    1f,
                    1f,
                    1f,
                )

            const val VERTEX_SHADER =
                """
                attribute vec4 a_Position;
                attribute vec2 a_TexCoord;
                varying vec2 v_TexCoord;
                void main() {
                    gl_Position = a_Position;
                    v_TexCoord = a_TexCoord;
                }
                """

            const val FRAGMENT_SHADER =
                """
                precision mediump float;
                varying vec2 v_TexCoord;
                uniform sampler2D u_Texture;
                void main() {
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                }
                """
        }
    }
}

private fun FloatArray.toNativeFloatBuffer() =
    ByteBuffer
        .allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(this@toNativeFloatBuffer)
            position(0)
        }
