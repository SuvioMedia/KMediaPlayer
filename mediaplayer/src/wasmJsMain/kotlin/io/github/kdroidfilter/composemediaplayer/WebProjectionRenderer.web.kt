@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

internal fun VideoProjectionSettings.usesWebProjectionRenderer(textureCrop: VideoTextureCrop): Boolean =
    requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop

internal fun createWebProjectionCanvasElement(): HTMLCanvasElement =
    (document.createElement("canvas") as HTMLCanvasElement).apply {
        className = "compose-media-player-projection"
        applyWebProjectionCanvasStyle()
    }

internal fun HTMLCanvasElement.applyWebProjectionCanvasStyle() {
    val wrapper = parentElement as? HTMLElement
    wrapper?.style?.apply {
        setProperty("z-index", "-2", "important")
        setProperty("pointer-events", "none")
        backgroundColor = "black"
        display = "block"
        setProperty("contain", "layout paint style", "important")
    }
    (wrapper?.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")

    style.apply {
        position = "absolute"
        width = "100%"
        height = "100%"
        display = "block"
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        setProperty("contain", "strict", "important")
        setProperty("transform", "translateZ(0)", "important")
    }
}

internal fun HTMLCanvasElement.configureWebProjectionRenderer(
    video: HTMLVideoElement,
    projection: VideoProjectionSettings,
    projectionView: VideoProjectionViewSettings,
    textureCrop: VideoTextureCrop,
    onError: (String) -> Unit,
) {
    val normalized = projection.normalized()
    val normalizedView = projectionView.normalized()
    val plan =
        normalized.toVideoProjectionRenderPlan(
            VideoProjectionRenderOptions(textureCrop = textureCrop),
        )
    configureWebProjectionRenderer(
        canvas = this,
        video = video,
        projectionType = normalized.projectionType.projectionShaderCode,
        fovDegrees = plan.mesh.horizontalFovDegrees,
        stereo = plan.stereo,
        leftLeft = plan.leftEyeTexture.left,
        leftTop = plan.leftEyeTexture.top,
        leftRight = plan.leftEyeTexture.right,
        leftBottom = plan.leftEyeTexture.bottom,
        leftRotation = plan.leftEyeTexture.rotation.ordinal,
        rightLeft = plan.rightEyeTexture.left,
        rightTop = plan.rightEyeTexture.top,
        rightRight = plan.rightEyeTexture.right,
        rightBottom = plan.rightEyeTexture.bottom,
        rightRotation = plan.rightEyeTexture.rotation.ordinal,
        viewYawDegrees = normalizedView.yawDegrees,
        viewPitchDegrees = normalizedView.pitchDegrees,
        viewRollDegrees = normalizedView.rollDegrees,
        viewZoom = normalizedView.zoom,
        onError = onError,
    )
}

internal fun HTMLCanvasElement.disposeWebProjectionRenderer() {
    disposeWebProjectionRenderer(this)
}

@Suppress("LongMethod", "LongParameterList", "UNUSED_PARAMETER")
private fun configureWebProjectionRenderer(
    canvas: HTMLCanvasElement,
    video: HTMLVideoElement,
    projectionType: Int,
    fovDegrees: Float,
    stereo: Boolean,
    leftLeft: Float,
    leftTop: Float,
    leftRight: Float,
    leftBottom: Float,
    leftRotation: Int,
    rightLeft: Float,
    rightTop: Float,
    rightRight: Float,
    rightBottom: Float,
    rightRotation: Int,
    viewYawDegrees: Float,
    viewPitchDegrees: Float,
    viewRollDegrees: Float,
    viewZoom: Float,
    onError: (String) -> Unit,
): Unit =
    js(
        """
        {
            function createShader(gl, type, source) {
                const shader = gl.createShader(type);
                gl.shaderSource(shader, source);
                gl.compileShader(shader);
                if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                    const message = gl.getShaderInfoLog(shader) || "Unknown shader compile error";
                    gl.deleteShader(shader);
                    throw new Error(message);
                }
                return shader;
            }

            function createProgram(gl) {
                const vertexSource = [
                    "attribute vec2 aPosition;",
                    "varying vec2 vUv;",
                    "void main() {",
                    "  vUv = (aPosition + vec2(1.0)) * 0.5;",
                    "  gl_Position = vec4(aPosition, 0.0, 1.0);",
                    "}"
                ].join("\n");

                const fragmentSource = [
                    "precision highp float;",
                    "uniform sampler2D uTexture;",
                    "uniform int uProjectionType;",
                    "uniform float uFovDegrees;",
                    "uniform vec4 uEyeWindow;",
                    "uniform int uRotation;",
                    "uniform float uViewportAspect;",
                    "uniform float uViewYawDegrees;",
                    "uniform float uViewPitchDegrees;",
                    "uniform float uViewRollDegrees;",
                    "uniform float uViewZoom;",
                    "varying vec2 vUv;",
                    "const float PI = 3.14159265358979323846264;",
                    "const float CAMERA_FOV_DEGREES = 95.0;",
                    "vec2 rotateUv(vec2 uv) {",
                    "  if (uRotation == 1) return vec2(1.0 - uv.y, uv.x);",
                    "  if (uRotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);",
                    "  if (uRotation == 3) return vec2(uv.y, 1.0 - uv.x);",
                    "  return uv;",
                    "}",
                    "vec4 sampleLocal(vec2 localUv) {",
                    "  if (localUv.x < 0.0 || localUv.x > 1.0 || localUv.y < 0.0 || localUv.y > 1.0) {",
                    "    return vec4(0.0, 0.0, 0.0, 1.0);",
                    "  }",
                    "  vec2 rotated = rotateUv(localUv);",
                    "  vec2 uv = mix(uEyeWindow.xy, uEyeWindow.zw, rotated);",
                    "  return texture2D(uTexture, uv);",
                    "}",
                    "vec3 rayForScreenUv(vec2 screenUv) {",
                    "  vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);",
                    "  float tanHalfFov = tan(radians(CAMERA_FOV_DEGREES) * 0.5 / max(uViewZoom, 0.01));",
                    "  vec3 direction = normalize(vec3(p.x * uViewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0));",
                    "  float yaw = radians(uViewYawDegrees);",
                    "  float pitch = radians(uViewPitchDegrees);",
                    "  float roll = radians(uViewRollDegrees);",
                    "  float cy = cos(yaw);",
                    "  float sy = sin(yaw);",
                    "  direction = vec3(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);",
                    "  float cp = cos(pitch);",
                    "  float sp = sin(pitch);",
                    "  direction = vec3(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);",
                    "  float cr = cos(roll);",
                    "  float sr = sin(roll);",
                    "  return normalize(vec3(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));",
                    "}",
                    "vec2 eacFaceUv(float sc, float tc, float cellX, float cellY) {",
                    "  vec2 local = vec2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));",
                    "  return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);",
                    "}",
                    "vec2 eacUv(vec3 direction) {",
                    "  vec3 ad = abs(direction);",
                    "  if (ad.z >= ad.x && ad.z >= ad.y) {",
                    "    if (direction.z < 0.0) return eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);",
                    "    return eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);",
                    "  }",
                    "  if (ad.x >= ad.y) {",
                    "    if (direction.x > 0.0) return eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);",
                    "    return eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);",
                    "  }",
                    "  if (direction.y > 0.0) return eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);",
                    "  return eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);",
                    "}",
                    "void main() {",
                    "  vec2 screenUv = vec2(vUv.x, 1.0 - vUv.y);",
                    "  if (uProjectionType == 0) {",
                    "    gl_FragColor = sampleLocal(screenUv);",
                    "    return;",
                    "  }",
                    "  vec3 direction = rayForScreenUv(screenUv);",
                    "  if (uProjectionType == 1 || uProjectionType == 2) {",
                    "    float horizontalFov = radians(max(uFovDegrees, 1.0));",
                    "    float yaw = atan(direction.x, -direction.z);",
                    "    float pitch = asin(clamp(direction.y, -1.0, 1.0));",
                    "    if (abs(yaw) > horizontalFov * 0.5) {",
                    "      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
                    "      return;",
                    "    }",
                    "    gl_FragColor = sampleLocal(vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI));",
                    "    return;",
                    "  }",
                    "  if (uProjectionType >= 3 && uProjectionType <= 6) {",
                    "    float maxTheta = radians(max(uFovDegrees, 1.0)) * 0.5;",
                    "    float theta = acos(clamp(-direction.z, -1.0, 1.0));",
                    "    if (theta > maxTheta) {",
                    "      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
                    "      return;",
                    "    }",
                    "    float phi = atan(direction.y, direction.x);",
                    "    float radius = theta / maxTheta * 0.5;",
                    "    gl_FragColor = sampleLocal(vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius));",
                    "    return;",
                    "  }",
                    "  gl_FragColor = sampleLocal(eacUv(direction));",
                    "}"
                ].join("\n");

                const vertexShader = createShader(gl, gl.VERTEX_SHADER, vertexSource);
                const fragmentShader = createShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
                const program = gl.createProgram();
                gl.attachShader(program, vertexShader);
                gl.attachShader(program, fragmentShader);
                gl.linkProgram(program);
                gl.deleteShader(vertexShader);
                gl.deleteShader(fragmentShader);
                if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
                    const message = gl.getProgramInfoLog(program) || "Unknown shader link error";
                    gl.deleteProgram(program);
                    throw new Error(message);
                }
                return program;
            }

            function createRenderer(canvas) {
                const gl = canvas.getContext("webgl", { alpha: false, antialias: true, preserveDrawingBuffer: false }) ||
                    canvas.getContext("experimental-webgl", { alpha: false, antialias: true, preserveDrawingBuffer: false });
                if (!gl) {
                    throw new Error("WebGL is not available");
                }
                const program = createProgram(gl);
                const positionLocation = gl.getAttribLocation(program, "aPosition");
                const uniforms = {
                    texture: gl.getUniformLocation(program, "uTexture"),
                    projectionType: gl.getUniformLocation(program, "uProjectionType"),
                    fovDegrees: gl.getUniformLocation(program, "uFovDegrees"),
                    eyeWindow: gl.getUniformLocation(program, "uEyeWindow"),
                    rotation: gl.getUniformLocation(program, "uRotation"),
                    viewportAspect: gl.getUniformLocation(program, "uViewportAspect"),
                    viewYawDegrees: gl.getUniformLocation(program, "uViewYawDegrees"),
                    viewPitchDegrees: gl.getUniformLocation(program, "uViewPitchDegrees"),
                    viewRollDegrees: gl.getUniformLocation(program, "uViewRollDegrees"),
                    viewZoom: gl.getUniformLocation(program, "uViewZoom")
                };
                const buffer = gl.createBuffer();
                gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
                gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);

                const texture = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, texture);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));

                return {
                    gl: gl,
                    program: program,
                    buffer: buffer,
                    texture: texture,
                    uniforms: uniforms,
                    positionLocation: positionLocation,
                    video: null,
                    settings: null,
                    animationFrame: 0,
                    disposed: false,
                    uploadErrorReported: false,
                    onError: null
                };
            }

            function resizeCanvas(renderer) {
                const canvas = renderer.canvas;
                const ratio = Math.max(1, globalThis.devicePixelRatio || 1);
                const rect = canvas.getBoundingClientRect();
                const width = Math.max(1, Math.round(rect.width * ratio));
                const height = Math.max(1, Math.round(rect.height * ratio));
                if (canvas.width !== width || canvas.height !== height) {
                    canvas.width = width;
                    canvas.height = height;
                }
            }

            function drawEye(renderer, eye, x, y, width, height) {
                const gl = renderer.gl;
                const settings = renderer.settings;
                gl.viewport(x, y, width, height);
                gl.uniform4f(renderer.uniforms.eyeWindow, eye.left, eye.top, eye.right, eye.bottom);
                gl.uniform1i(renderer.uniforms.rotation, eye.rotation);
                gl.uniform1f(renderer.uniforms.viewportAspect, width / Math.max(1, height));
                gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
            }

            function render(renderer) {
                if (renderer.disposed) return;
                const canvas = renderer.canvas;
                const video = renderer.video;
                const settings = renderer.settings;
                if (!canvas || !canvas.isConnected || !video || !settings) {
                    renderer.animationFrame = requestAnimationFrame(function() { render(renderer); });
                    return;
                }

                resizeCanvas(renderer);
                const gl = renderer.gl;
                gl.useProgram(renderer.program);
                gl.bindBuffer(gl.ARRAY_BUFFER, renderer.buffer);
                gl.enableVertexAttribArray(renderer.positionLocation);
                gl.vertexAttribPointer(renderer.positionLocation, 2, gl.FLOAT, false, 0, 0);
                gl.activeTexture(gl.TEXTURE0);
                gl.bindTexture(gl.TEXTURE_2D, renderer.texture);
                gl.uniform1i(renderer.uniforms.texture, 0);
                gl.uniform1i(renderer.uniforms.projectionType, settings.projectionType);
                gl.uniform1f(renderer.uniforms.fovDegrees, settings.fovDegrees);
                gl.uniform1f(renderer.uniforms.viewYawDegrees, settings.viewYawDegrees);
                gl.uniform1f(renderer.uniforms.viewPitchDegrees, settings.viewPitchDegrees);
                gl.uniform1f(renderer.uniforms.viewRollDegrees, settings.viewRollDegrees);
                gl.uniform1f(renderer.uniforms.viewZoom, settings.viewZoom);
                gl.clearColor(0, 0, 0, 1);
                gl.clear(gl.COLOR_BUFFER_BIT);

                if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
                    try {
                        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
                        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video);
                        canvas.style.display = "block";
                        video.style.opacity = "0";
                        if (video.parentElement) {
                            video.parentElement.style.setProperty("z-index", "-3", "important");
                        }
                        renderer.uploadErrorReported = false;
                    } catch (error) {
                        if (!renderer.uploadErrorReported && renderer.onError) {
                            renderer.uploadErrorReported = true;
                            canvas.style.display = "none";
                            video.style.opacity = "1";
                            if (video.parentElement) {
                                video.parentElement.style.setProperty("z-index", "-2", "important");
                            }
                            renderer.onError(
                                "WebGL projection cannot sample this video. " +
                                "Serve it with CORS headers or disable projection. " +
                                (error && error.message ? error.message : String(error))
                            );
                        }
                    }
                }

                const width = canvas.width;
                const height = canvas.height;
                if (settings.stereo) {
                    const leftWidth = Math.floor(width / 2);
                    drawEye(renderer, settings.leftEye, 0, 0, leftWidth, height);
                    drawEye(renderer, settings.rightEye, leftWidth, 0, width - leftWidth, height);
                } else {
                    drawEye(renderer, settings.leftEye, 0, 0, width, height);
                }

                renderer.animationFrame = requestAnimationFrame(function() { render(renderer); });
            }

            function startRenderer(renderer) {
                if (!renderer.animationFrame) {
                    renderer.animationFrame = requestAnimationFrame(function() { render(renderer); });
                }
            }

            try {
                let renderer = canvas.__composeMediaPlayerProjectionRenderer;
                if (!renderer) {
                    renderer = createRenderer(canvas);
                    renderer.canvas = canvas;
                    canvas.__composeMediaPlayerProjectionRenderer = renderer;
                }
                canvas.style.display = "block";
                video.style.opacity = "0";
                if (video.parentElement) {
                    video.parentElement.style.setProperty("z-index", "-3", "important");
                }
                renderer.video = video;
                renderer.onError = onError;
                renderer.settings = {
                    projectionType: projectionType,
                    fovDegrees: fovDegrees,
                    viewYawDegrees: viewYawDegrees,
                    viewPitchDegrees: viewPitchDegrees,
                    viewRollDegrees: viewRollDegrees,
                    viewZoom: viewZoom,
                    stereo: stereo,
                    leftEye: {
                        left: leftLeft,
                        top: leftTop,
                        right: leftRight,
                        bottom: leftBottom,
                        rotation: leftRotation
                    },
                    rightEye: {
                        left: rightLeft,
                        top: rightTop,
                        right: rightRight,
                        bottom: rightBottom,
                        rotation: rightRotation
                    }
                };
                startRenderer(renderer);
            } catch (error) {
                canvas.style.display = "none";
                video.style.opacity = "1";
                if (video.parentElement) {
                    video.parentElement.style.setProperty("z-index", "-2", "important");
                }
                onError(error && error.message ? error.message : String(error));
            }
        }
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun disposeWebProjectionRenderer(canvas: HTMLCanvasElement): Unit =
    js(
        """
        {
            const renderer = canvas.__composeMediaPlayerProjectionRenderer;
            if (renderer) {
                renderer.disposed = true;
                if (renderer.animationFrame) {
                    cancelAnimationFrame(renderer.animationFrame);
                    renderer.animationFrame = 0;
                }
                const gl = renderer.gl;
                if (gl) {
                    if (renderer.texture) gl.deleteTexture(renderer.texture);
                    if (renderer.buffer) gl.deleteBuffer(renderer.buffer);
                    if (renderer.program) gl.deleteProgram(renderer.program);
                }
                canvas.__composeMediaPlayerProjectionRenderer = null;
            }
        }
        """,
    )
