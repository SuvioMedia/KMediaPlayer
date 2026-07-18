package io.github.kdroidfilter.composemediaplayer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AndroidProjectionShaderDeviceTest {
    @Test
    fun testProjectionShadersCompileAndLinkInGlesContext() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotSame(EGL14.EGL_NO_DISPLAY, display)

        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttributes =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_NONE,
                )
            assertTrue(EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0))
            assertTrue("No GLES-compatible EGL config", configCount[0] > 0)
            val config = requireNotNull(configs[0])

            val context =
                EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                    0,
                )
            assertNotSame("A GLES 3 context is required", EGL14.EGL_NO_CONTEXT, context)
            val surface =
                EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
            assertNotSame(EGL14.EGL_NO_SURFACE, surface)
            try {
                assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
                val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, ANDROID_PROJECTION_VERTEX_SHADER)
                val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, ANDROID_PROJECTION_FRAGMENT_SHADER)
                val program = GLES20.glCreateProgram()
                try {
                    GLES20.glAttachShader(program, vertexShader)
                    GLES20.glAttachShader(program, fragmentShader)
                    GLES20.glLinkProgram(program)
                    val linkStatus = IntArray(1)
                    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
                    assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, linkStatus[0])
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                } finally {
                    GLES20.glDeleteProgram(program)
                    GLES20.glDeleteShader(fragmentShader)
                    GLES20.glDeleteShader(vertexShader)
                }
            } finally {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    @Test
    fun testProductionHdr10PlusShaderMatchesCpuReferenceForOneAndFourThousandNits() {
        withGles3Pbuffer {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, ANDROID_PROJECTION_VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, ANDROID_PROJECTION_FRAGMENT_SHADER)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, linkStatus[0])

            val vertexArray = IntArray(1)
            val vertexBuffer = IntArray(1)
            val texture = IntArray(1)
            try {
                GLES30.glGenVertexArrays(1, vertexArray, 0)
                GLES30.glBindVertexArray(vertexArray[0])
                GLES20.glGenBuffers(1, vertexBuffer, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer[0])
                val coordinates = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                GLES20.glBufferData(
                    GLES20.GL_ARRAY_BUFFER,
                    coordinates.capacity() * Float.SIZE_BYTES,
                    coordinates,
                    GLES20.GL_STATIC_DRAW,
                )
                val position = GLES20.glGetAttribLocation(program, "aPosition")
                assertTrue("Projection shader must expose aPosition", position >= 0)
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0)

                GLES20.glGenTextures(1, texture, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glUseProgram(program)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uProjectionType"), 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFovDegrees"), 360f)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uEyeWindow"), 0f, 0f, 1f, 1f)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uRotation"), 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewportAspect"), 1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewYawDegrees"), 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewPitchDegrees"), 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewRollDegrees"), 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uViewZoom"), 1f)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uHdr10PlusEnabled"), 1)
                GLES30.glViewport(0, 0, 1, 1)

                listOf(
                    Hdr10PlusGpuCase(sourcePeakNits = 1_000f, targetPeakNits = 600f),
                    Hdr10PlusGpuCase(sourcePeakNits = 4_000f, targetPeakNits = 1_000f),
                ).forEach { case ->
                    val sourceLuminance = case.sourcePeakNits * 0.55f
                    val normalizedInput = sourceLuminance / MAXIMUM_PQ_NITS
                    val input = floatArrayOf(normalizedInput * 1.10f, normalizedInput, normalizedInput * 0.70f)
                    val curve =
                        FloatArray(HDR10_PLUS_CURVE_SAMPLES) { index ->
                            val position = index.toFloat() / (HDR10_PLUS_CURVE_SAMPLES - 1)
                            sqrt(position) * case.targetPeakNits / MAXIMUM_PQ_NITS
                        }
                    GLES30.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES30.GL_RGBA32F,
                        1,
                        1,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_FLOAT,
                        floatBufferOf(input[0], input[1], input[2], 1f),
                    )
                    GLES20.glUniform1f(
                        GLES20.glGetUniformLocation(program, "uHdr10PlusSourcePeakNits"),
                        case.sourcePeakNits,
                    )
                    curve.forEachIndexed { index, value ->
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHdr10PlusCurve$index"), value)
                    }
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    assertEquals("GLES draw failed", GLES20.GL_NO_ERROR, GLES20.glGetError())

                    val pixel = ByteBuffer.allocateDirect(4)
                    GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                    assertEquals("GLES readback failed", GLES20.GL_NO_ERROR, GLES20.glGetError())
                    val expected = applyHdr10PlusCpu(input, case.sourcePeakNits, curve)
                    repeat(3) { channel ->
                        val actualCode = pixel.get(channel).toInt() and BYTE_MASK
                        val expectedCode = (expected[channel].coerceIn(0f, 1f) * BYTE_MASK).roundToInt()
                        assertTrue(
                            "HDR10+ ${case.sourcePeakNits.toInt()}-nit channel $channel: " +
                                "GPU=$actualCode CPU=$expectedCode",
                            abs(actualCode - expectedCode) <= MAXIMUM_READBACK_CODE_ERROR,
                        )
                    }
                }
            } finally {
                if (texture[0] != 0) GLES20.glDeleteTextures(1, texture, 0)
                if (vertexBuffer[0] != 0) GLES20.glDeleteBuffers(1, vertexBuffer, 0)
                if (vertexArray[0] != 0) GLES30.glDeleteVertexArrays(1, vertexArray, 0)
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(fragmentShader)
                GLES20.glDeleteShader(vertexShader)
            }
        }
    }

    private fun withGles3Pbuffer(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotSame(EGL14.EGL_NO_DISPLAY, display)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val attributes =
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_NONE,
                )
            assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0))
            assertTrue("No GLES-compatible EGL config", configCount[0] > 0)
            val config = requireNotNull(configs[0])
            val context =
                EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                    0,
                )
            assertNotSame("A GLES 3 context is required", EGL14.EGL_NO_CONTEXT, context)
            val surface =
                EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
            assertNotSame(EGL14.EGL_NO_SURFACE, surface)
            try {
                assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
                block()
            } finally {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    private fun applyHdr10PlusCpu(
        input: FloatArray,
        sourcePeakNits: Float,
        curve: FloatArray,
    ): FloatArray {
        val luminance = (input[0] * 0.2627f + input[1] * 0.6780f + input[2] * 0.0593f).coerceAtLeast(0f)
        val normalized = (luminance * MAXIMUM_PQ_NITS / sourcePeakNits.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val position = normalized * (curve.size - 1)
        val lower = floor(position).toInt().coerceIn(0, curve.lastIndex)
        val upper = (lower + 1).coerceAtMost(curve.lastIndex)
        val mapped = curve[lower] + (curve[upper] - curve[lower]) * (position - lower)
        val scale = if (luminance > MINIMUM_LUMINANCE) mapped / luminance else 0f
        return FloatArray(3) { channel -> (input[channel] * scale).coerceAtLeast(0f) }
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values).position(0) }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            fail(log)
        }
        return shader
    }

    private data class Hdr10PlusGpuCase(
        val sourcePeakNits: Float,
        val targetPeakNits: Float,
    )

    private companion object {
        const val HDR10_PLUS_CURVE_SAMPLES = 33
        const val MAXIMUM_PQ_NITS = 10_000f
        const val MINIMUM_LUMINANCE = 0.000_000_1f
        const val BYTE_MASK = 0xff
        const val MAXIMUM_READBACK_CODE_ERROR = 2
    }
}
