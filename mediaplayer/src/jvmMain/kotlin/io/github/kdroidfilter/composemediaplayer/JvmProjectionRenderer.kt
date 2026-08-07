package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import kotlin.math.floor

internal fun VideoProjectionSettings.usesJvmCanvasProjectionRenderer(textureCrop: VideoTextureCrop): Boolean =
    requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop

private const val JVM_CANVAS_RENDERER_LABEL = "Compose Canvas (Skia)"
private const val JVM_CANVAS_PROJECTION_RENDERER_LABEL = "Compose Canvas -> Skia projection shader"

internal fun VideoProjectionSettings.jvmCanvasRendererLabel(textureCrop: VideoTextureCrop): String =
    if (usesJvmCanvasProjectionRenderer(textureCrop)) {
        JVM_CANVAS_PROJECTION_RENDERER_LABEL
    } else {
        JVM_CANVAS_RENDERER_LABEL
    }

internal fun VideoProjectionSettings.jvmCanvasRendererLabel(
    baseRenderer: String,
    textureCrop: VideoTextureCrop,
): String =
    if (!usesJvmCanvasProjectionRenderer(textureCrop)) {
        baseRenderer
    } else {
        when {
            JVM_CANVAS_PROJECTION_RENDERER_LABEL in baseRenderer -> baseRenderer
            JVM_CANVAS_RENDERER_LABEL in baseRenderer ->
                baseRenderer.replace(JVM_CANVAS_RENDERER_LABEL, JVM_CANVAS_PROJECTION_RENDERER_LABEL)
            "Compose Canvas" in baseRenderer ->
                baseRenderer.replace("Compose Canvas", JVM_CANVAS_PROJECTION_RENDERER_LABEL)
            else -> "$baseRenderer -> Skia projection shader"
        }
    }

@Composable
internal fun JvmProjectedVideoCanvas(
    frame: ImageBitmap,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        if (projection.usesJvmCanvasProjectionRenderer(textureCrop)) {
            runCatching {
                drawProjectedVideoFrame(
                    frame = frame,
                    projection = projection,
                    projectionView = projectionView,
                    textureCrop = textureCrop,
                )
            }.getOrElse {
                drawScaledVideoFrame(frame, contentScale)
            }
        } else {
            drawScaledVideoFrame(frame, contentScale)
        }
    }
}

private fun DrawScope.drawScaledVideoFrame(
    frame: ImageBitmap,
    contentScale: ContentScale,
) {
    drawScaledImage(
        image = frame,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        contentScale = contentScale,
    )
}

private fun DrawScope.drawProjectedVideoFrame(
    frame: ImageBitmap,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f || frame.width <= 0 || frame.height <= 0) return

    val normalized = projection.normalized()
    val normalizedView = projectionView.normalized()
    val plan =
        normalized.toVideoProjectionRenderPlan(
            VideoProjectionRenderOptions(textureCrop = textureCrop),
        )
    frame
        .asSkiaBitmap()
        .makeShader(
            tmx = FilterTileMode.CLAMP,
            tmy = FilterTileMode.CLAMP,
            sampling = SamplingMode.LINEAR,
            localMatrix = null,
        ).use { textureShader ->
            if (plan.stereo) {
                val leftWidth = floor(width / 2f)
                drawProjectedEye(
                    textureShader = textureShader,
                    projection = normalized,
                    projectionView = normalizedView,
                    eyeWindow = plan.leftEyeTexture,
                    frameSize = frame.size,
                    viewport = ProjectionViewport(0f, 0f, leftWidth, height),
                )
                drawProjectedEye(
                    textureShader = textureShader,
                    projection = normalized,
                    projectionView = normalizedView,
                    eyeWindow = plan.rightEyeTexture,
                    frameSize = frame.size,
                    viewport = ProjectionViewport(leftWidth, 0f, width - leftWidth, height),
                )
            } else {
                drawProjectedEye(
                    textureShader = textureShader,
                    projection = normalized,
                    projectionView = normalizedView,
                    eyeWindow = plan.leftEyeTexture,
                    frameSize = frame.size,
                    viewport = ProjectionViewport(0f, 0f, width, height),
                )
            }
        }
}

internal inline fun <T> withJvmProjectionPaint(
    textureShader: Shader,
    configure: (RuntimeShaderBuilder) -> Unit,
    draw: (Paint, Shader) -> T,
): T =
    RuntimeShaderBuilder(jvmProjectionRuntimeEffect).use { builder ->
        builder.child("uTexture", textureShader)
        configure(builder)
        builder.makeShader().use { projectionShader ->
            Paint().use { paint ->
                paint.shader = projectionShader
                draw(paint, projectionShader)
            }
        }
    }

private fun DrawScope.drawProjectedEye(
    textureShader: Shader,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    eyeWindow: VideoTextureWindow,
    frameSize: Size,
    viewport: ProjectionViewport,
) {
    if (viewport.width <= 0f || viewport.height <= 0f) return

    withJvmProjectionPaint(
        textureShader = textureShader,
        configure = { builder ->
            builder.uniform("uProjectionType", projection.projectionType.projectionShaderCode)
            builder.uniform("uFovDegrees", projection.fovDegrees)
            builder.uniform("uSourceSize", frameSize.width, frameSize.height)
            builder.uniform("uEyeWindow", eyeWindow.left, eyeWindow.top, eyeWindow.right, eyeWindow.bottom)
            builder.uniform("uRotation", eyeWindow.rotation.ordinal)
            builder.uniform("uViewport", viewport.left, viewport.top, viewport.width, viewport.height)
            builder.uniform("uViewYawDegrees", projectionView.yawDegrees)
            builder.uniform("uViewPitchDegrees", projectionView.pitchDegrees)
            builder.uniform("uViewRollDegrees", projectionView.rollDegrees)
            builder.uniform("uViewZoom", projectionView.zoom)
        },
        draw = { paint, _ ->
            drawContext.canvas.skiaCanvas.drawRect(
                Rect.makeXYWH(viewport.left, viewport.top, viewport.width, viewport.height),
                paint,
            )
        },
    )
}

private val ImageBitmap.size: Size
    get() = Size(width.toFloat(), height.toFloat())

private data class ProjectionViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal val jvmProjectionRuntimeEffect: RuntimeEffect by lazy {
    RuntimeEffect.makeForShader(JVM_PROJECTION_SHADER)
}

private const val JVM_PROJECTION_SHADER =
    """
    uniform shader uTexture;
    uniform int uProjectionType;
    uniform float uFovDegrees;
    uniform float2 uSourceSize;
    uniform float4 uEyeWindow;
    uniform int uRotation;
    uniform float4 uViewport;
    uniform float uViewYawDegrees;
    uniform float uViewPitchDegrees;
    uniform float uViewRollDegrees;
    uniform float uViewZoom;

    const float PI = 3.14159265358979323846264;
    const float CAMERA_FOV_DEGREES = 95.0;

    float2 rotateUv(float2 uv) {
        if (uRotation == 1) return float2(1.0 - uv.y, uv.x);
        if (uRotation == 2) return float2(1.0 - uv.x, 1.0 - uv.y);
        if (uRotation == 3) return float2(uv.y, 1.0 - uv.x);
        return uv;
    }

    half4 sampleLocal(float2 localUv) {
        if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {
            return half4(0.0, 0.0, 0.0, 1.0);
        }
        float2 rotated = rotateUv(localUv);
        float2 uv = mix(uEyeWindow.xy, uEyeWindow.zw, rotated);
        return uTexture.eval(uv * uSourceSize);
    }

    float3 rayForScreenUv(float2 screenUv) {
        float2 p = float2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
        float viewportAspect = uViewport.z / max(1.0, uViewport.w);
        float tanHalfFov = tan((CAMERA_FOV_DEGREES * PI / 180.0) * 0.5 / max(uViewZoom, 0.01));
        float3 direction = normalize(float3(p.x * viewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0));
        float yaw = uViewYawDegrees * PI / 180.0;
        float pitch = uViewPitchDegrees * PI / 180.0;
        float roll = uViewRollDegrees * PI / 180.0;
        float cy = cos(yaw);
        float sy = sin(yaw);
        direction = float3(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);
        float cp = cos(pitch);
        float sp = sin(pitch);
        direction = float3(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);
        float cr = cos(roll);
        float sr = sin(roll);
        return normalize(float3(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));
    }

    float2 eacFaceUv(float sc, float tc, float cellX, float cellY) {
        float2 local = float2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
        return float2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
    }

    float2 eacUv(float3 direction) {
        float3 ad = abs(direction);
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

    half4 main(float2 coord) {
        float2 screenUv = float2((coord.x - uViewport.x) / max(1.0, uViewport.z), (coord.y - uViewport.y) / max(1.0, uViewport.w));
        if (uProjectionType == 0) {
            return sampleLocal(screenUv);
        }

        float3 direction = rayForScreenUv(screenUv);
        if (uProjectionType == 1 || uProjectionType == 2) {
            float horizontalFov = max(uFovDegrees, 1.0) * PI / 180.0;
            float yaw = atan(direction.x, -direction.z);
            float pitch = asin(clamp(direction.y, -1.0, 1.0));
            if (abs(yaw) > horizontalFov * 0.5) {
                return half4(0.0, 0.0, 0.0, 1.0);
            }
            return sampleLocal(float2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI));
        }

        if (uProjectionType >= 3 && uProjectionType <= 6) {
            float maxTheta = max(uFovDegrees, 1.0) * PI / 180.0 * 0.5;
            float theta = acos(clamp(-direction.z, -1.0, 1.0));
            if (theta > maxTheta) {
                return half4(0.0, 0.0, 0.0, 1.0);
            }
            float phi = atan(direction.y, direction.x);
            float radius = theta / maxTheta * 0.5;
            return sampleLocal(float2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius));
        }

        return sampleLocal(eacUv(direction));
    }
    """
