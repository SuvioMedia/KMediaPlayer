@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

/**
 * Configures the progressive WebGPU HDR or controlled HDR-to-SDR projection path.
 *
 * The implementation deliberately reports success only after the browser retains the requested
 * `rgba16float` configuration and the first GPU submission completes. HDR output additionally requires
 * extended tone mapping on a standard color-managed canvas and a high-dynamic-range display. WebGPU
 * converts the tagged PQ/HLG frame into the canvas working space without clamping extended values.
 * The decoded video frame is imported on every animation frame because HTML video external textures
 * expire at the end of the importing task.
 */
@Suppress("LongMethod", "LongParameterList", "UNUSED_PARAMETER")
internal fun configureWebGpuProjectionRenderer(
    canvas: HTMLCanvasElement,
    video: HTMLVideoElement,
    canvasColorSpace: String,
    sourceColorSpace: String,
    outputHdr: Boolean,
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
    sourcePeakNits: Float,
    gamutLutRgba16fBytes: Uint8Array,
    gamutLutEdge: Int,
    onConfigured: () -> Unit,
    onUnavailable: (String) -> Unit,
): Unit =
    js(
        """
        {
            const settings = {
                projectionType: projectionType,
                fovDegrees: fovDegrees,
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
                },
                viewYawDegrees: viewYawDegrees,
                viewPitchDegrees: viewPitchDegrees,
                viewRollDegrees: viewRollDegrees,
                viewZoom: viewZoom,
                sourcePeakNits: sourcePeakNits
            };

            const current = canvas.__composeMediaPlayerProjectionRenderer;
            if (current &&
                (current.kind === "webgpu-color" || current.kind === "webgpu-color-initializing") &&
                current.outputHdr === outputHdr &&
                current.requestedCanvasColorSpace === canvasColorSpace) {
                current.video = video;
                current.settings = settings;
                current.sourceColorSpace = sourceColorSpace;
                current.onConfigured = onConfigured;
                current.onUnavailable = onUnavailable;
                current.configurationRevision = (current.configurationRevision || 0) + 1;
                current.configuredReported = false;
                current.configuredReportPending = false;
                if (current.confirmationTimeout) globalThis.clearTimeout(current.confirmationTimeout);
                current.confirmationTimeout = globalThis.setTimeout(function() {
                    if (current.fail) {
                        current.fail(new Error("No confirmed color-managed canvas frame was presented within 10 seconds."));
                    }
                }, 10000);
                return;
            }
            if (current) {
                onUnavailable("The projection canvas is already bound to a different graphics context.");
                return;
            }

            const generation = (canvas.__composeMediaPlayerProjectionGeneration || 0) + 1;
            canvas.__composeMediaPlayerProjectionGeneration = generation;
            const renderer = {
                kind: "webgpu-color-initializing",
                generation: generation,
                canvas: canvas,
                video: video,
                settings: settings,
                onConfigured: onConfigured,
                onUnavailable: onUnavailable,
                context: null,
                device: null,
                pipeline: null,
                sampler: null,
                uniformBuffer: null,
                animationFrame: 0,
                confirmationTimeout: 0,
                configuredReported: false,
                configuredReportPending: false,
                configurationRevision: 1,
                failureReported: false,
                disposed: false,
                requestedCanvasColorSpace: canvasColorSpace,
                canvasColorSpace: canvasColorSpace,
                canvasFormat: "rgba16float",
                sourceColorSpace: sourceColorSpace,
                outputHdr: outputHdr,
                sourcePeakNits: sourcePeakNits,
                gamutLutEdge: gamutLutEdge,
                gamutLutTexture: null,
                gamutLutView: null,
                gamutLutSampler: null,
                renderedFrames: 0,
                inFlightVideoFrames: new Set()
            };
            canvas.__composeMediaPlayerProjectionRenderer = renderer;

            function publishRendererDiagnostics(state) {
                canvas.setAttribute("data-kmp-renderer", "webgpu-color");
                canvas.setAttribute("data-kmp-renderer-state", state);
                canvas.setAttribute(
                    "data-kmp-output-dynamic-range",
                    renderer.outputHdr ? "HDR" : "SDR"
                );
                canvas.setAttribute("data-kmp-canvas-format", renderer.canvasFormat);
                canvas.setAttribute("data-kmp-canvas-color-space", renderer.canvasColorSpace);
                canvas.setAttribute(
                    "data-kmp-tone-mapping",
                    renderer.outputHdr ? "extended" : "standard"
                );
                canvas.setAttribute("data-kmp-rendered-frames", String(renderer.renderedFrames));
                canvas.setAttribute(
                    "data-kmp-configuration-confirmed",
                    renderer.configuredReported ? "true" : "false"
                );
            }
            publishRendererDiagnostics("initializing");

            function isCurrent() {
                return !renderer.disposed && !renderer.failureReported &&
                    canvas.__composeMediaPlayerProjectionRenderer === renderer &&
                    canvas.__composeMediaPlayerProjectionGeneration === renderer.generation;
            }

            function errorText(error) {
                if (!error) return "Unknown WebGPU error";
                return error.message ? error.message : String(error);
            }

            function configurationIsConfirmed(context) {
                if (!context) return false;
                if (typeof context.getConfiguration !== "function") return !renderer.outputHdr;
                const configuration = context.getConfiguration();
                const baseConfirmed = !!configuration &&
                    configuration.format === renderer.canvasFormat &&
                    configuration.colorSpace === renderer.canvasColorSpace;
                if (!baseConfirmed || !renderer.outputHdr) return baseConfirmed;
                return !!configuration.toneMapping &&
                    configuration.toneMapping.mode === "extended" &&
                    globalThis.matchMedia("(dynamic-range: high)").matches;
            }

            function fail(error) {
                if (!isCurrent() || renderer.failureReported) return;
                renderer.failureReported = true;
                if (renderer.animationFrame) {
                    globalThis.cancelAnimationFrame(renderer.animationFrame);
                    renderer.animationFrame = 0;
                }
                if (renderer.confirmationTimeout) {
                    globalThis.clearTimeout(renderer.confirmationTimeout);
                    renderer.confirmationTimeout = 0;
                }
                if (renderer.context && typeof renderer.context.unconfigure === "function") {
                    try { renderer.context.unconfigure(); } catch (_) {}
                }
                renderer.inFlightVideoFrames.forEach(function(frame) {
                    try { frame.close(); } catch (_) {}
                });
                renderer.inFlightVideoFrames.clear();
                canvas.style.display = "none";
                publishRendererDiagnostics("failed");
                const route = renderer.outputHdr ? "HDR projection" : "controlled HDR-to-SDR rendering";
                const message = "WebGPU " + route + " is unavailable: " + errorText(error);
                renderer.onUnavailable(message);
            }
            renderer.fail = fail;

            const shaderSource = `
struct ProjectionSettings {
    left_window: vec4<f32>,
    right_window: vec4<f32>,
    view: vec4<f32>,
    projection: vec4<f32>,
    rotations: vec4<f32>,
    color: vec4<f32>,
};

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@group(0) @binding(0) var video_texture: texture_external;
@group(0) @binding(1) var video_sampler: sampler;
@group(0) @binding(2) var<uniform> settings: ProjectionSettings;
@group(0) @binding(3) var gamut_lut: texture_3d<f32>;
@group(0) @binding(4) var gamut_sampler: sampler;

const PI: f32 = 3.14159265358979323846;
const CAMERA_FOV_DEGREES: f32 = 95.0;
const HDR_REFERENCE_WHITE_NITS: f32 = 203.0;

fn pq_eotf_scalar(encoded: f32) -> f32 {
    let m1 = 2610.0 / 16384.0;
    let m2 = 2523.0 / 32.0;
    let c1 = 3424.0 / 4096.0;
    let c2 = 2413.0 / 128.0;
    let c3 = 2392.0 / 128.0;
    let powered = pow(clamp(encoded, 0.0, 1.0), 1.0 / m2);
    return pow(
        max((powered - c1) / max(c2 - c3 * powered, 0.000001), 0.0),
        1.0 / m1
    ) * 10000.0;
}

fn pq_oetf_scalar(nits: f32) -> f32 {
    let m1 = 2610.0 / 16384.0;
    let m2 = 2523.0 / 32.0;
    let c1 = 3424.0 / 4096.0;
    let c2 = 2413.0 / 128.0;
    let c3 = 2392.0 / 128.0;
    let powered = pow(clamp(nits, 0.0, 10000.0) / 10000.0, m1);
    return pow((c1 + c2 * powered) / (1.0 + c3 * powered), m2);
}

fn tone_map_bt2390(luminance_nits: f32, source_peak: f32, target_peak: f32) -> f32 {
    let bounded_source_peak = max(source_peak, 1.0);
    let bounded_target_peak = max(target_peak, 1.0);
    if (bounded_target_peak >= bounded_source_peak) {
        return min(luminance_nits, bounded_source_peak);
    }
    let source_peak_code = pq_oetf_scalar(bounded_source_peak);
    let normalized_target = clamp(pq_oetf_scalar(bounded_target_peak) / max(source_peak_code, 0.000001), 0.0, 1.0);
    let knee = clamp(1.5 * normalized_target - 0.5, 0.0, 1.0);
    let input_code = clamp(pq_oetf_scalar(luminance_nits) / max(source_peak_code, 0.000001), 0.0, 1.0);
    if (input_code <= knee || knee >= 1.0) {
        return min(luminance_nits, bounded_target_peak);
    }
    let t = clamp((input_code - knee) / max(1.0 - knee, 0.000001), 0.0, 1.0);
    let t2 = t * t;
    let t3 = t2 * t;
    let output_code =
        (2.0 * t3 - 3.0 * t2 + 1.0) * knee +
        (t3 - 2.0 * t2 + t) * (1.0 - knee) +
        (-2.0 * t3 + 3.0 * t2) * normalized_target;
    return min(pq_eotf_scalar(clamp(output_code * source_peak_code, 0.0, 1.0)), bounded_target_peak);
}

fn s_rgb_eotf(encoded: vec3<f32>) -> vec3<f32> {
    let magnitude = abs(encoded);
    let low = magnitude / 12.92;
    let high = pow((magnitude + vec3<f32>(0.055)) / 1.055, vec3<f32>(2.4));
    return sign(encoded) * select(high, low, magnitude <= vec3<f32>(0.04045));
}

fn s_rgb_oetf(linear: vec3<f32>) -> vec3<f32> {
    let bounded = max(linear, vec3<f32>(0.0));
    let low = bounded * 12.92;
    let high = vec3<f32>(1.055) * pow(bounded, vec3<f32>(1.0 / 2.4)) - vec3<f32>(0.055);
    return select(high, low, bounded <= vec3<f32>(0.0031308));
}

fn linear_srgb_to_bt2020(linear: vec3<f32>) -> vec3<f32> {
    return vec3<f32>(
        0.6274039 * linear.r + 0.3292830 * linear.g + 0.0433131 * linear.b,
        0.0690973 * linear.r + 0.9195404 * linear.g + 0.0113623 * linear.b,
        0.0163914 * linear.r + 0.0880133 * linear.g + 0.8955953 * linear.b
    );
}

fn hash_noise(position: vec2<f32>) -> f32 {
    return fract(sin(dot(position, vec2<f32>(12.9898, 78.233))) * 43758.5453);
}

fn color_manage(encoded: vec3<f32>, source_uv: vec2<f32>) -> vec3<f32> {
    if (settings.color.z > 0.5) {
        return encoded;
    }
    let linear_bt2020 = linear_srgb_to_bt2020(s_rgb_eotf(encoded));
    let linear_nits = max(linear_bt2020, vec3<f32>(0.0)) * HDR_REFERENCE_WHITE_NITS;
    let luminance = max(dot(linear_nits, vec3<f32>(0.2627, 0.6780, 0.0593)), 0.000001);
    let mapped = tone_map_bt2390(
        luminance,
        max(settings.color.y, HDR_REFERENCE_WHITE_NITS),
        HDR_REFERENCE_WHITE_NITS
    );
    let normalized_bt2020 = clamp(
        linear_nits * (mapped / luminance) / HDR_REFERENCE_WHITE_NITS,
        vec3<f32>(0.0),
        vec3<f32>(1.0)
    );
    let edge = settings.color.w;
    let coordinates = (normalized_bt2020 * (edge - 1.0) + vec3<f32>(0.5)) / edge;
    let linear_bt709 = textureSampleLevel(gamut_lut, gamut_sampler, coordinates, 0.0).rgb;
    let first_noise = hash_noise(source_uv * 16384.0 + settings.view.xy);
    let second_noise = hash_noise(source_uv.yx * 32768.0 + settings.view.zw + vec2<f32>(19.19, 7.73));
    let tpdf_dither = (first_noise + second_noise - 1.0) / 255.0;
    return clamp(s_rgb_oetf(linear_bt709) + vec3<f32>(tpdf_dither), vec3<f32>(0.0), vec3<f32>(1.0));
}

@vertex
fn vertex_main(@builtin(vertex_index) index: u32) -> VertexOutput {
    var positions = array<vec2<f32>, 4>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>(1.0, -1.0),
        vec2<f32>(-1.0, 1.0),
        vec2<f32>(1.0, 1.0)
    );
    let position = positions[index];
    var output: VertexOutput;
    output.position = vec4<f32>(position, 0.0, 1.0);
    output.uv = vec2<f32>((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
    return output;
}

fn rotate_uv(uv: vec2<f32>, rotation: f32) -> vec2<f32> {
    if (rotation > 0.5 && rotation < 1.5) {
        return vec2<f32>(1.0 - uv.y, uv.x);
    }
    if (rotation >= 1.5 && rotation < 2.5) {
        return vec2<f32>(1.0 - uv.x, 1.0 - uv.y);
    }
    if (rotation >= 2.5) {
        return vec2<f32>(uv.y, 1.0 - uv.x);
    }
    return uv;
}

fn sample_local(local_uv: vec2<f32>, eye: f32) -> vec4<f32> {
    if (local_uv.x < 0.0 || local_uv.x > 1.0 || local_uv.y < 0.0 || local_uv.y > 1.0) {
        return vec4<f32>(0.0, 0.0, 0.0, 1.0);
    }
    var eye_window = settings.left_window;
    var rotation = settings.rotations.x;
    if (eye > 0.5) {
        eye_window = settings.right_window;
        rotation = settings.rotations.y;
    }
    let rotated = rotate_uv(local_uv, rotation);
    let source_uv = mix(eye_window.xy, eye_window.zw, rotated);
    let sampled = textureSampleBaseClampToEdge(video_texture, video_sampler, source_uv);
    return vec4<f32>(color_manage(sampled.rgb, source_uv), sampled.a);
}

fn ray_for_screen_uv(screen_uv: vec2<f32>) -> vec3<f32> {
    let p = vec2<f32>(screen_uv.x * 2.0 - 1.0, 1.0 - screen_uv.y * 2.0);
    let tan_half_fov = tan(CAMERA_FOV_DEGREES * PI / 360.0 / max(settings.view.w, 0.01));
    var direction = normalize(vec3<f32>(p.x * settings.projection.z * tan_half_fov, p.y * tan_half_fov, -1.0));

    let yaw = settings.view.x * PI / 180.0;
    let pitch = settings.view.y * PI / 180.0;
    let roll = settings.view.z * PI / 180.0;
    let cy = cos(yaw);
    let sy = sin(yaw);
    direction = vec3<f32>(cy * direction.x + sy * direction.z, direction.y, -sy * direction.x + cy * direction.z);
    let cp = cos(pitch);
    let sp = sin(pitch);
    direction = vec3<f32>(direction.x, cp * direction.y - sp * direction.z, sp * direction.y + cp * direction.z);
    let cr = cos(roll);
    let sr = sin(roll);
    return normalize(vec3<f32>(cr * direction.x - sr * direction.y, sr * direction.x + cr * direction.y, direction.z));
}

fn eac_face_uv(sc: f32, tc: f32, cell_x: f32, cell_y: f32) -> vec2<f32> {
    let local = vec2<f32>(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
    return vec2<f32>((cell_x + local.x) / 3.0, (cell_y + local.y) / 2.0);
}

fn eac_uv(direction: vec3<f32>) -> vec2<f32> {
    let absolute_direction = abs(direction);
    if (absolute_direction.z >= absolute_direction.x && absolute_direction.z >= absolute_direction.y) {
        if (direction.z < 0.0) {
            return eac_face_uv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
        }
        return eac_face_uv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
    }
    if (absolute_direction.x >= absolute_direction.y) {
        if (direction.x > 0.0) {
            return eac_face_uv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
        }
        return eac_face_uv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
    }
    if (direction.y > 0.0) {
        return eac_face_uv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
    }
    return eac_face_uv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
}

@fragment
fn fragment_main(input: VertexOutput) -> @location(0) vec4<f32> {
    var screen_uv = input.uv;
    var eye = 0.0;
    if (settings.projection.w > 0.5) {
        if (screen_uv.x < 0.5) {
            screen_uv.x = screen_uv.x * 2.0;
        } else {
            screen_uv.x = (screen_uv.x - 0.5) * 2.0;
            eye = 1.0;
        }
    }

    let projection_type = settings.projection.x;
    if (projection_type < 0.5) {
        return sample_local(screen_uv, eye);
    }

    let direction = ray_for_screen_uv(screen_uv);
    if (projection_type < 2.5) {
        let horizontal_fov = max(settings.projection.y, 1.0) * PI / 180.0;
        let yaw = atan2(direction.x, -direction.z);
        let pitch = asin(clamp(direction.y, -1.0, 1.0));
        if (abs(yaw) > horizontal_fov * 0.5) {
            return vec4<f32>(0.0, 0.0, 0.0, 1.0);
        }
        return sample_local(vec2<f32>(yaw / horizontal_fov + 0.5, 0.5 - pitch / PI), eye);
    }

    if (projection_type < 6.5) {
        let max_theta = max(settings.projection.y, 1.0) * PI / 360.0;
        let theta = acos(clamp(-direction.z, -1.0, 1.0));
        if (theta > max_theta) {
            return vec4<f32>(0.0, 0.0, 0.0, 1.0);
        }
        let phi = atan2(direction.y, direction.x);
        let radius = theta / max_theta * 0.5;
        return sample_local(vec2<f32>(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius), eye);
    }

    return sample_local(eac_uv(direction), eye);
}
`;

            function createPipelineDescriptor(module, canvasFormat) {
                return {
                    layout: "auto",
                    vertex: { module: module, entryPoint: "vertex_main" },
                    fragment: {
                        module: module,
                        entryPoint: "fragment_main",
                        targets: [{ format: canvasFormat }]
                    },
                    primitive: { topology: "triangle-strip" }
                };
            }

            function resizeCanvas(activeRenderer) {
                const ratio = Math.max(1, globalThis.devicePixelRatio || 1);
                const rect = activeRenderer.canvas.getBoundingClientRect();
                const limit = activeRenderer.device.limits.maxTextureDimension2D;
                const width = Math.min(limit, Math.max(1, Math.round(rect.width * ratio)));
                const height = Math.min(limit, Math.max(1, Math.round(rect.height * ratio)));
                if (activeRenderer.canvas.width !== width || activeRenderer.canvas.height !== height) {
                    activeRenderer.canvas.width = width;
                    activeRenderer.canvas.height = height;
                }
            }

            function writeSettings(activeRenderer) {
                const value = activeRenderer.settings;
                const eyeWidth = value.stereo ? activeRenderer.canvas.width * 0.5 : activeRenderer.canvas.width;
                const aspect = eyeWidth / Math.max(1, activeRenderer.canvas.height);
                const data = new Float32Array([
                    value.leftEye.left, value.leftEye.top, value.leftEye.right, value.leftEye.bottom,
                    value.rightEye.left, value.rightEye.top, value.rightEye.right, value.rightEye.bottom,
                    value.viewYawDegrees, value.viewPitchDegrees, value.viewRollDegrees, value.viewZoom,
                    value.projectionType, value.fovDegrees, aspect, value.stereo ? 1 : 0,
                    value.leftEye.rotation, value.rightEye.rotation, 0, 0,
                    0, value.sourcePeakNits, activeRenderer.outputHdr ? 1 : 0,
                    activeRenderer.gamutLutEdge
                ]);
                activeRenderer.device.queue.writeBuffer(
                    activeRenderer.uniformBuffer,
                    0,
                    data.buffer,
                    data.byteOffset,
                    data.byteLength
                );
            }

            function render(activeRenderer) {
                if (!isCurrent() || activeRenderer.failureReported) return;
                if (!activeRenderer.canvas.isConnected) {
                    activeRenderer.animationFrame = globalThis.requestAnimationFrame(function() { render(activeRenderer); });
                    return;
                }
                if (!configurationIsConfirmed(activeRenderer.context)) {
                    fail(new Error("The active canvas no longer reports the requested color configuration."));
                    return;
                }
                const activeVideo = activeRenderer.video;
                if (!activeVideo || activeVideo.readyState < 2 || activeVideo.videoWidth <= 0 || activeVideo.videoHeight <= 0) {
                    activeRenderer.animationFrame = globalThis.requestAnimationFrame(function() { render(activeRenderer); });
                    return;
                }

                let webCodecsFrame = null;
                try {
                    resizeCanvas(activeRenderer);
                    writeSettings(activeRenderer);
                    if (typeof globalThis.VideoFrame === "function" &&
                        activeRenderer.inFlightVideoFrames.size < 3) {
                        try {
                            webCodecsFrame = new globalThis.VideoFrame(activeVideo, {
                                timestamp: Math.max(0, Math.round(activeVideo.currentTime * 1000000))
                            });
                            activeRenderer.inFlightVideoFrames.add(webCodecsFrame);
                        } catch (_) {
                            webCodecsFrame = null;
                        }
                    }
                    const externalTexture = activeRenderer.device.importExternalTexture({
                        source: webCodecsFrame || activeVideo,
                        colorSpace: activeRenderer.sourceColorSpace
                    });
                    const bindGroup = activeRenderer.device.createBindGroup({
                        layout: activeRenderer.pipeline.getBindGroupLayout(0),
                        entries: [
                            { binding: 0, resource: externalTexture },
                            { binding: 1, resource: activeRenderer.sampler },
                            { binding: 2, resource: { buffer: activeRenderer.uniformBuffer } },
                            { binding: 3, resource: activeRenderer.gamutLutView },
                            { binding: 4, resource: activeRenderer.gamutLutSampler }
                        ]
                    });
                    const encoder = activeRenderer.device.createCommandEncoder();
                    const pass = encoder.beginRenderPass({
                        colorAttachments: [{
                            view: activeRenderer.context.getCurrentTexture().createView(),
                            clearValue: { r: 0, g: 0, b: 0, a: 1 },
                            loadOp: "clear",
                            storeOp: "store"
                        }]
                    });
                    pass.setPipeline(activeRenderer.pipeline);
                    pass.setBindGroup(0, bindGroup);
                    pass.draw(4);
                    pass.end();
                    activeRenderer.device.queue.submit([encoder.finish()]);
                    activeRenderer.renderedFrames += 1;
                    if (activeRenderer.renderedFrames === 1 || activeRenderer.renderedFrames % 30 === 0) {
                        publishRendererDiagnostics("rendering");
                    }
                    if (webCodecsFrame) {
                        const submittedFrame = webCodecsFrame;
                        activeRenderer.device.queue.onSubmittedWorkDone().then(function() {
                            activeRenderer.inFlightVideoFrames.delete(submittedFrame);
                            try { submittedFrame.close(); } catch (_) {}
                        }).catch(function(error) {
                            activeRenderer.inFlightVideoFrames.delete(submittedFrame);
                            try { submittedFrame.close(); } catch (_) {}
                            fail(error);
                        });
                        webCodecsFrame = null;
                    }
                    activeRenderer.canvas.style.display = "block";
                    activeVideo.style.opacity = "0";
                    if (activeVideo.parentElement) {
                        activeVideo.parentElement.style.setProperty("z-index", "-3", "important");
                    }

                    if (!activeRenderer.configuredReported && !activeRenderer.configuredReportPending) {
                        const submissionRevision = activeRenderer.configurationRevision;
                        activeRenderer.configuredReportPending = true;
                        activeRenderer.device.queue.onSubmittedWorkDone().then(function() {
                            if (submissionRevision !== activeRenderer.configurationRevision) return;
                            activeRenderer.configuredReportPending = false;
                            if (!isCurrent() || activeRenderer.failureReported || activeRenderer.configuredReported) return;
                            if (!configurationIsConfirmed(activeRenderer.context)) {
                                fail(new Error("The browser did not retain the requested color canvas configuration."));
                                return;
                            }
                            activeRenderer.configuredReported = true;
                            publishRendererDiagnostics("configured");
                            if (activeRenderer.confirmationTimeout) {
                                globalThis.clearTimeout(activeRenderer.confirmationTimeout);
                                activeRenderer.confirmationTimeout = 0;
                            }
                            activeRenderer.onConfigured();
                        }).catch(fail);
                    }
                } catch (error) {
                    if (webCodecsFrame) {
                        activeRenderer.inFlightVideoFrames.delete(webCodecsFrame);
                        try { webCodecsFrame.close(); } catch (_) {}
                    }
                    fail(error);
                    return;
                }
                activeRenderer.animationFrame = globalThis.requestAnimationFrame(function() { render(activeRenderer); });
            }

            renderer.confirmationTimeout = globalThis.setTimeout(function() {
                fail(new Error("No confirmed color-managed canvas frame was presented within 10 seconds."));
            }, 10000);

            (async function initialize() {
                let probeContext = null;
                try {
                    if (!globalThis.navigator || !globalThis.navigator.gpu || globalThis.isSecureContext === false) {
                        throw new Error("WebGPU is not exposed in this secure browsing context.");
                    }
                    if (renderer.sourceColorSpace !== "display-p3" && renderer.sourceColorSpace !== "srgb") {
                        throw new Error("No standard extended sRGB or Display-P3 working color space was selected.");
                    }
                    if (renderer.outputHdr && !globalThis.matchMedia("(dynamic-range: high)").matches) {
                        throw new Error("The active display does not report high dynamic range.");
                    }
                    const adapter = await globalThis.navigator.gpu.requestAdapter();
                    if (!adapter) throw new Error("No WebGPU adapter is available.");
                    const device = await adapter.requestDevice();
                    if (!isCurrent()) {
                        if (typeof device.destroy === "function") device.destroy();
                        return;
                    }
                    renderer.device = device;
                    device.lost.then(function(info) {
                        fail(new Error("The WebGPU device was lost: " + (info && info.message ? info.message : "unknown reason")));
                    });

                    const shaderModule = device.createShaderModule({ code: shaderSource });
                    if (typeof shaderModule.getCompilationInfo === "function") {
                        const compilation = await shaderModule.getCompilationInfo();
                        const errors = compilation.messages.filter(function(message) { return message.type === "error"; });
                        if (errors.length > 0) throw new Error(errors.map(function(message) { return message.message; }).join("; "));
                    }
                    const colorSpaceCandidates = renderer.outputHdr
                        ? Array.from(new Set([renderer.requestedCanvasColorSpace, "srgb"]))
                        : ["srgb"];
                    const preferredFormat = globalThis.navigator.gpu.getPreferredCanvasFormat();
                    const formatCandidates = renderer.outputHdr
                        ? ["rgba16float"]
                        : Array.from(new Set(["rgba16float", preferredFormat]));
                    let selectedConfiguration = null;
                    let lastConfigurationError = null;
                    for (const candidateColorSpace of colorSpaceCandidates) {
                        for (const candidateFormat of formatCandidates) {
                            const probeCanvas = globalThis.document.createElement("canvas");
                            probeCanvas.width = 2;
                            probeCanvas.height = 2;
                            probeContext = probeCanvas.getContext("webgpu");
                            if (!probeContext || typeof probeContext.getConfiguration !== "function") {
                                throw new Error("GPUCanvasContext configuration readback is unavailable.");
                            }
                            let errorScopeOpen = false;
                            try {
                                device.pushErrorScope("validation");
                                errorScopeOpen = true;
                                const probeConfiguration = {
                                    device: device,
                                    format: candidateFormat,
                                    colorSpace: candidateColorSpace,
                                    alphaMode: "opaque"
                                };
                                if (renderer.outputHdr) probeConfiguration.toneMapping = { mode: "extended" };
                                probeContext.configure(probeConfiguration);
                                probeContext.getCurrentTexture().createView();
                                const probeError = await device.popErrorScope();
                                errorScopeOpen = false;
                                if (probeError) throw probeError;
                                const retained = probeContext.getConfiguration();
                                const retainedBase = !!retained &&
                                    retained.format === candidateFormat &&
                                    retained.colorSpace === candidateColorSpace;
                                const retainedHdr = !renderer.outputHdr ||
                                    (!!retained.toneMapping && retained.toneMapping.mode === "extended");
                                if (!retainedBase || !retainedHdr) {
                                    throw new Error("The browser did not retain the requested WebGPU canvas configuration.");
                                }
                                selectedConfiguration = {
                                    colorSpace: candidateColorSpace,
                                    format: candidateFormat
                                };
                            } catch (configurationError) {
                                lastConfigurationError = configurationError;
                                if (errorScopeOpen) {
                                    try {
                                        const scopedError = await device.popErrorScope();
                                        if (scopedError) lastConfigurationError = scopedError;
                                    } catch (_) {}
                                }
                            } finally {
                                try { probeContext.unconfigure(); } catch (_) {}
                                probeContext = null;
                            }
                            if (selectedConfiguration) break;
                        }
                        if (selectedConfiguration) break;
                    }
                    if (!selectedConfiguration) {
                        throw lastConfigurationError || new Error("No color-managed WebGPU canvas configuration is available.");
                    }
                    renderer.canvasColorSpace = selectedConfiguration.colorSpace;
                    renderer.sourceColorSpace = selectedConfiguration.colorSpace;
                    renderer.canvasFormat = selectedConfiguration.format;

                    const descriptor = createPipelineDescriptor(shaderModule, renderer.canvasFormat);
                    const pipeline = typeof device.createRenderPipelineAsync === "function"
                        ? await device.createRenderPipelineAsync(descriptor)
                        : device.createRenderPipeline(descriptor);
                    if (!isCurrent()) return;

                    const context = canvas.getContext("webgpu");
                    if (!context || (renderer.outputHdr && typeof context.getConfiguration !== "function")) {
                        throw new Error("The projection canvas cannot create the required WebGPU context.");
                    }
                    device.pushErrorScope("validation");
                    const canvasConfiguration = {
                        device: device,
                        format: renderer.canvasFormat,
                        colorSpace: renderer.canvasColorSpace,
                        alphaMode: "opaque"
                    };
                    if (renderer.outputHdr) canvasConfiguration.toneMapping = { mode: "extended" };
                    context.configure(canvasConfiguration);
                    context.getCurrentTexture().createView();
                    const configurationError = await device.popErrorScope();
                    if (configurationError) throw configurationError;
                    if (!configurationIsConfirmed(context)) {
                        throw new Error("The projection canvas did not retain its requested color configuration.");
                    }

                    renderer.context = context;
                    renderer.pipeline = pipeline;
                    publishRendererDiagnostics("configured-pending-frame");
                    renderer.sampler = device.createSampler({
                        magFilter: "linear",
                        minFilter: "linear",
                        addressModeU: "clamp-to-edge",
                        addressModeV: "clamp-to-edge"
                    });
                    renderer.gamutLutTexture = device.createTexture({
                        size: {
                            width: renderer.gamutLutEdge,
                            height: renderer.gamutLutEdge,
                            depthOrArrayLayers: renderer.gamutLutEdge
                        },
                        dimension: "3d",
                        format: "rgba16float",
                        usage: globalThis.GPUTextureUsage.TEXTURE_BINDING | globalThis.GPUTextureUsage.COPY_DST
                    });
                    renderer.gamutLutView = renderer.gamutLutTexture.createView();
                    renderer.gamutLutSampler = device.createSampler({
                        magFilter: "linear",
                        minFilter: "linear",
                        addressModeU: "clamp-to-edge",
                        addressModeV: "clamp-to-edge",
                        addressModeW: "clamp-to-edge"
                    });
                    const sourceBytesPerRow = renderer.gamutLutEdge * 8;
                    const bytesPerRow = Math.ceil(sourceBytesPerRow / 256) * 256;
                    const paddedLut = new Uint8Array(bytesPerRow * renderer.gamutLutEdge * renderer.gamutLutEdge);
                    for (let blue = 0; blue < renderer.gamutLutEdge; blue += 1) {
                        for (let green = 0; green < renderer.gamutLutEdge; green += 1) {
                            const sourceOffset =
                                (blue * renderer.gamutLutEdge * renderer.gamutLutEdge +
                                    green * renderer.gamutLutEdge) * 8;
                            const destinationOffset =
                                blue * renderer.gamutLutEdge * bytesPerRow + green * bytesPerRow;
                            paddedLut.set(
                                gamutLutRgba16fBytes.subarray(sourceOffset, sourceOffset + sourceBytesPerRow),
                                destinationOffset
                            );
                        }
                    }
                    device.queue.writeTexture(
                        { texture: renderer.gamutLutTexture },
                        paddedLut,
                        { bytesPerRow: bytesPerRow, rowsPerImage: renderer.gamutLutEdge },
                        {
                            width: renderer.gamutLutEdge,
                            height: renderer.gamutLutEdge,
                            depthOrArrayLayers: renderer.gamutLutEdge
                        }
                    );
                    renderer.uniformBuffer = device.createBuffer({
                        size: 96,
                        usage: globalThis.GPUBufferUsage.UNIFORM | globalThis.GPUBufferUsage.COPY_DST
                    });
                    renderer.kind = "webgpu-color";
                    renderer.animationFrame = globalThis.requestAnimationFrame(function() { render(renderer); });
                } catch (error) {
                    if (probeContext && typeof probeContext.unconfigure === "function") {
                        try { probeContext.unconfigure(); } catch (_) {}
                    }
                    fail(error);
                }
            })();
        }
        """,
    )
