package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

internal class AndroidProjectionGlSurfaceView(
    context: Context,
) : GLSurfaceView(context) {
    interface Callback {
        fun onVideoSurfaceCreated(surface: Surface)

        fun onProjectionRendererError(message: String) = Unit
    }

    var callback: Callback? = null
    val videoSurface: Surface? get() = projectionRenderer.currentSurface
    private val projectionRenderer = ProjectionRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(projectionRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun configure(
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
    ) {
        queueEvent {
            projectionRenderer.configure(
                projection = projection,
                projectionView = projectionView,
                textureCrop = textureCrop,
            )
        }
        requestRender()
    }

    fun releaseRenderer() {
        queueEvent { projectionRenderer.release() }
    }

    override fun onDetachedFromWindow() {
        releaseRenderer()
        super.onDetachedFromWindow()
    }

    private inner class ProjectionRenderer : Renderer {
        private val textureTransform = FloatArray(TEXTURE_TRANSFORM_SIZE)
        private val quadVertices = fullscreenQuadVertices()
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var textureId = 0
        private var program = 0
        private var positionLocation = -1
        private var textureLocation = -1
        private var projectionTypeLocation = -1
        private var fovDegreesLocation = -1
        private var eyeWindowLocation = -1
        private var rotationLocation = -1
        private var viewportAspectLocation = -1
        private var textureTransformLocation = -1
        private var viewYawDegreesLocation = -1
        private var viewPitchDegreesLocation = -1
        private var viewRollDegreesLocation = -1
        private var viewZoomLocation = -1
        private var viewportWidth = 1
        private var viewportHeight = 1
        private var projection = VideoProjectionSettings()
        private var projectionView = VideoProjectionViewSettings()
        private var textureCrop = VideoTextureCrop()
        private var rendererErrorReported = false
        val currentSurface: Surface? get() = surface

        override fun onSurfaceCreated(
            gl: GL10?,
            config: EGLConfig?,
        ) {
            runCatching {
                program = createProgram()
                loadLocations()
                textureId = createExternalTexture()
                surfaceTexture =
                    SurfaceTexture(textureId).apply {
                        setDefaultBufferSize(DEFAULT_VIDEO_TEXTURE_WIDTH, DEFAULT_VIDEO_TEXTURE_HEIGHT)
                        setOnFrameAvailableListener { requestRender() }
                    }
                surface =
                    Surface(surfaceTexture).also { videoSurface ->
                        post { callback?.onVideoSurfaceCreated(videoSurface) }
                    }
                GLES20.glClearColor(0f, 0f, 0f, 1f)
            }.onFailure { error ->
                reportRendererError("Android projection renderer init failed: ${error.message.orEmpty()}")
            }
        }

        override fun onSurfaceChanged(
            gl: GL10?,
            width: Int,
            height: Int,
        ) {
            viewportWidth = max(1, width)
            viewportHeight = max(1, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (program == 0 || textureId == 0) return
            runCatching {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(textureTransform)
                drawFrame()
            }.onFailure { error ->
                reportRendererError("Android projection draw failed: ${error.message.orEmpty()}")
            }
        }

        fun configure(
            projection: VideoProjectionSettings,
            projectionView: VideoProjectionViewSettings,
            textureCrop: VideoTextureCrop,
        ) {
            this.projection = projection.normalized()
            this.projectionView = projectionView.normalized()
            this.textureCrop = textureCrop.normalized()
        }

        fun release() {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun drawFrame() {
            val plan =
                projection.toVideoProjectionRenderPlan(
                    VideoProjectionRenderOptions(textureCrop = textureCrop),
                )
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureLocation, 0)
            GLES20.glUniform1i(projectionTypeLocation, projection.projectionType.projectionShaderCode)
            GLES20.glUniform1f(fovDegreesLocation, plan.mesh.horizontalFovDegrees)
            GLES20.glUniformMatrix4fv(textureTransformLocation, 1, false, textureTransform, 0)
            GLES20.glUniform1f(viewYawDegreesLocation, projectionView.yawDegrees)
            GLES20.glUniform1f(viewPitchDegreesLocation, projectionView.pitchDegrees)
            GLES20.glUniform1f(viewRollDegreesLocation, projectionView.rollDegrees)
            GLES20.glUniform1f(viewZoomLocation, projectionView.zoom)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

            if (plan.stereo) {
                val leftWidth = viewportWidth / 2
                drawEye(plan.leftEyeTexture, 0, 0, leftWidth, viewportHeight)
                drawEye(plan.rightEyeTexture, leftWidth, 0, viewportWidth - leftWidth, viewportHeight)
            } else {
                drawEye(plan.leftEyeTexture, 0, 0, viewportWidth, viewportHeight)
            }

            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        private fun drawEye(
            textureWindow: VideoTextureWindow,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            GLES20.glViewport(x, y, width, height)
            GLES20.glUniform4f(
                eyeWindowLocation,
                textureWindow.left,
                textureWindow.top,
                textureWindow.right,
                textureWindow.bottom,
            )
            GLES20.glUniform1i(rotationLocation, textureWindow.rotation.ordinal)
            GLES20.glUniform1f(viewportAspectLocation, width.toFloat() / max(1, height).toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, FULLSCREEN_QUAD_VERTEX_COUNT)
        }

        private fun loadLocations() {
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
            projectionTypeLocation = GLES20.glGetUniformLocation(program, "uProjectionType")
            fovDegreesLocation = GLES20.glGetUniformLocation(program, "uFovDegrees")
            eyeWindowLocation = GLES20.glGetUniformLocation(program, "uEyeWindow")
            rotationLocation = GLES20.glGetUniformLocation(program, "uRotation")
            viewportAspectLocation = GLES20.glGetUniformLocation(program, "uViewportAspect")
            textureTransformLocation = GLES20.glGetUniformLocation(program, "uTextureTransform")
            viewYawDegreesLocation = GLES20.glGetUniformLocation(program, "uViewYawDegrees")
            viewPitchDegreesLocation = GLES20.glGetUniformLocation(program, "uViewPitchDegrees")
            viewRollDegreesLocation = GLES20.glGetUniformLocation(program, "uViewRollDegrees")
            viewZoomLocation = GLES20.glGetUniformLocation(program, "uViewZoom")
        }

        private fun reportRendererError(message: String) {
            if (rendererErrorReported) return
            rendererErrorReported = true
            androidVideoLogger.e { message }
            post { callback?.onProjectionRendererError(message) }
        }
    }
}

private fun createProgram(): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, ANDROID_PROJECTION_VERTEX_SHADER)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, ANDROID_PROJECTION_FRAGMENT_SHADER)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
        val message = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteProgram(program)
        error("Projection shader link failed: $message")
    }
    return program
}

private fun compileShader(
    type: Int,
    source: String,
): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compileStatus = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
    if (compileStatus[0] == 0) {
        val message = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        error("Projection shader compile failed: $message")
    }
    return shader
}

private fun createExternalTexture(): Int {
    val textureIds = IntArray(1)
    GLES20.glGenTextures(1, textureIds, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return textureIds[0]
}

private fun fullscreenQuadVertices(): FloatBuffer =
    ByteBuffer
        .allocateDirect(FULLSCREEN_QUAD_COORDS.size * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_QUAD_COORDS)
            position(0)
        }

private val FULLSCREEN_QUAD_COORDS =
    floatArrayOf(
        -1f,
        -1f,
        1f,
        -1f,
        -1f,
        1f,
        1f,
        1f,
    )

private const val ANDROID_PROJECTION_VERTEX_SHADER =
    """
    attribute vec2 aPosition;
    varying vec2 vUv;

    void main() {
        vUv = (aPosition + vec2(1.0)) * 0.5;
        gl_Position = vec4(aPosition, 0.0, 1.0);
    }
    """

private const val ANDROID_PROJECTION_FRAGMENT_SHADER =
    """
    #extension GL_OES_EGL_image_external : require
    precision highp float;

    uniform samplerExternalOES uTexture;
    uniform int uProjectionType;
    uniform float uFovDegrees;
    uniform vec4 uEyeWindow;
    uniform int uRotation;
    uniform float uViewportAspect;
    uniform mat4 uTextureTransform;
    uniform float uViewYawDegrees;
    uniform float uViewPitchDegrees;
    uniform float uViewRollDegrees;
    uniform float uViewZoom;
    varying vec2 vUv;

    const float PI = 3.14159265358979323846264;
    const float CAMERA_FOV_DEGREES = 95.0;

    vec2 rotateUv(vec2 uv) {
        if (uRotation == 1) return vec2(1.0 - uv.y, uv.x);
        if (uRotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
        if (uRotation == 3) return vec2(uv.y, 1.0 - uv.x);
        return uv;
    }

    vec4 sampleLocal(vec2 localUv) {
        if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {
            return vec4(0.0, 0.0, 0.0, 1.0);
        }
        vec2 rotated = rotateUv(localUv);
        vec2 uv = mix(uEyeWindow.xy, uEyeWindow.zw, rotated);
        vec4 transformed = uTextureTransform * vec4(uv, 0.0, 1.0);
        return texture2D(uTexture, transformed.xy);
    }

    vec3 rayForScreenUv(vec2 screenUv) {
        vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
        float tanHalfFov = tan(radians(CAMERA_FOV_DEGREES) * 0.5 / max(uViewZoom, 0.01));
        vec3 direction = normalize(vec3(p.x * uViewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0));
        float yaw = radians(uViewYawDegrees);
        float pitch = radians(uViewPitchDegrees);
        float roll = radians(uViewRollDegrees);
        float cy = cos(yaw);
        float sy = sin(yaw);
        direction = vec3(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);
        float cp = cos(pitch);
        float sp = sin(pitch);
        direction = vec3(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);
        float cr = cos(roll);
        float sr = sin(roll);
        return normalize(vec3(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));
    }

    vec2 eacFaceUv(float sc, float tc, float cellX, float cellY) {
        vec2 local = vec2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
        return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
    }

    vec2 eacUv(vec3 direction) {
        vec3 ad = abs(direction);
        if (ad.z >= ad.x && ad.z >= ad.y) {
            if (direction.z < 0.0) return eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
            return eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
        }
        if (ad.x >= ad.y) {
            if (direction.x > 0.0) return eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
            return eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
        }
        if (direction.y > 0.0) return eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
        return eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
    }

    void main() {
        vec2 screenUv = vec2(vUv.x, 1.0 - vUv.y);
        if (uProjectionType == 0) {
            gl_FragColor = sampleLocal(screenUv);
            return;
        }
        vec3 direction = rayForScreenUv(screenUv);
        if (uProjectionType == 1 || uProjectionType == 2) {
            float horizontalFov = radians(max(uFovDegrees, 1.0));
            float yaw = atan(direction.x, -direction.z);
            float pitch = asin(clamp(direction.y, -1.0, 1.0));
            if (abs(yaw) > horizontalFov * 0.5) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            gl_FragColor = sampleLocal(vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI));
            return;
        }
        if (uProjectionType >= 3 && uProjectionType <= 6) {
            float maxTheta = radians(max(uFovDegrees, 1.0)) * 0.5;
            float theta = acos(clamp(-direction.z, -1.0, 1.0));
            if (theta > maxTheta) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            float phi = atan(direction.y, direction.x);
            float radius = theta / maxTheta * 0.5;
            gl_FragColor = sampleLocal(vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius));
            return;
        }
        gl_FragColor = sampleLocal(eacUv(direction));
    }
    """

private const val TEXTURE_TRANSFORM_SIZE = 16
private const val FULLSCREEN_QUAD_VERTEX_COUNT = 4
private const val FLOAT_BYTES = 4
private const val DEFAULT_VIDEO_TEXTURE_WIDTH = 3840
private const val DEFAULT_VIDEO_TEXTURE_HEIGHT = 2160
