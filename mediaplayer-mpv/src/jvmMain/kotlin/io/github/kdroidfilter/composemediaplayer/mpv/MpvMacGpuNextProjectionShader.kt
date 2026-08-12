package io.github.kdroidfilter.composemediaplayer.mpv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * libplacebo projection hook used by mpv's macvk `gpu-next` output.
 *
 * mpv currently accepts custom hooks as files, so the compiled adapter materializes its bundled
 * shader into a private temporary directory. Keeping every control as a dynamic parameter lets
 * device-motion updates change the view without rebuilding the Vulkan pipeline.
 */
internal object MpvMacGpuNextProjectionShader {
    private val shaderPath: Path by lazy(::materializeShader)

    fun pathForMpv(): String = shaderPath.toAbsolutePath().normalize().toString()

    fun options(
        configuration: MpvMacProjectionConfiguration,
        contentScaleMode: MpvMacContentScaleMode,
    ): String =
        listOf(
            "$PARAM_ENABLED=${configuration.enabled.asShaderInt()}",
            "$PARAM_PROJECTION_TYPE=${configuration.projectionType}",
            "$PARAM_FOV_DEGREES=${configuration.fovDegrees}",
            "$PARAM_STEREO=${configuration.stereo.asShaderInt()}",
            "$PARAM_LEFT_X0=${configuration.leftWindow[0]}",
            "$PARAM_LEFT_Y0=${configuration.leftWindow[1]}",
            "$PARAM_LEFT_X1=${configuration.leftWindow[2]}",
            "$PARAM_LEFT_Y1=${configuration.leftWindow[3]}",
            "$PARAM_LEFT_ROTATION=${configuration.leftRotation}",
            "$PARAM_RIGHT_X0=${configuration.rightWindow[0]}",
            "$PARAM_RIGHT_Y0=${configuration.rightWindow[1]}",
            "$PARAM_RIGHT_X1=${configuration.rightWindow[2]}",
            "$PARAM_RIGHT_Y1=${configuration.rightWindow[3]}",
            "$PARAM_RIGHT_ROTATION=${configuration.rightRotation}",
            "$PARAM_YAW_DEGREES=${configuration.yawDegrees}",
            "$PARAM_PITCH_DEGREES=${configuration.pitchDegrees}",
            "$PARAM_ROLL_DEGREES=${configuration.rollDegrees}",
            "$PARAM_ZOOM=${configuration.zoom}",
            "$PARAM_CONTENT_SCALE=${contentScaleMode.nativeValue}",
        ).joinToString(",")

    private fun materializeShader(): Path {
        val directory = Files.createTempDirectory("kmediaplayer-mpv-projection-")
        runCatching {
            Files.setPosixFilePermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        val shader = directory.resolve(SHADER_FILE_NAME)
        Files.writeString(shader, source, StandardOpenOption.CREATE_NEW)
        runCatching {
            Files.setPosixFilePermissions(
                shader,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        directory.toFile().deleteOnExit()
        shader.toFile().deleteOnExit()
        return shader
    }

    private fun Boolean.asShaderInt(): Int = if (this) 1 else 0

    internal val source: String =
        """
        //!PARAM $PARAM_ENABLED
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 1
        0

        //!PARAM $PARAM_PROJECTION_TYPE
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 7
        0

        //!PARAM $PARAM_FOV_DEGREES
        //!TYPE DYNAMIC float
        //!MINIMUM 1.0
        //!MAXIMUM 360.0
        60.0

        //!PARAM $PARAM_STEREO
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 1
        0

        //!PARAM $PARAM_LEFT_X0
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        0.0

        //!PARAM $PARAM_LEFT_Y0
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        0.0

        //!PARAM $PARAM_LEFT_X1
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        1.0

        //!PARAM $PARAM_LEFT_Y1
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        1.0

        //!PARAM $PARAM_LEFT_ROTATION
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 3
        0

        //!PARAM $PARAM_RIGHT_X0
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        0.0

        //!PARAM $PARAM_RIGHT_Y0
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        0.0

        //!PARAM $PARAM_RIGHT_X1
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        1.0

        //!PARAM $PARAM_RIGHT_Y1
        //!TYPE DYNAMIC float
        //!MINIMUM 0.0
        //!MAXIMUM 1.0
        1.0

        //!PARAM $PARAM_RIGHT_ROTATION
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 3
        0

        //!PARAM $PARAM_YAW_DEGREES
        //!TYPE DYNAMIC float
        //!MINIMUM -180.0
        //!MAXIMUM 180.0
        0.0

        //!PARAM $PARAM_PITCH_DEGREES
        //!TYPE DYNAMIC float
        //!MINIMUM -89.0
        //!MAXIMUM 89.0
        0.0

        //!PARAM $PARAM_ROLL_DEGREES
        //!TYPE DYNAMIC float
        //!MINIMUM -180.0
        //!MAXIMUM 180.0
        0.0

        //!PARAM $PARAM_ZOOM
        //!TYPE DYNAMIC float
        //!MINIMUM 0.5
        //!MAXIMUM 4.0
        1.0

        //!PARAM $PARAM_CONTENT_SCALE
        //!TYPE DYNAMIC int
        //!MINIMUM 0
        //!MAXIMUM 2
        0

        //!HOOK OUTPUT
        //!BIND HOOKED
        //!WHEN $PARAM_ENABLED
        //!DESC KMediaPlayer projection

        const float KMP_PI = 3.14159265358979323846264;
        const float KMP_CAMERA_FOV_DEGREES = 95.0;

        vec2 kmpRotateUv(vec2 uv, int rotation) {
            if (rotation == 1) return vec2(1.0 - uv.y, uv.x);
            if (rotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
            if (rotation == 3) return vec2(uv.y, 1.0 - uv.x);
            return uv;
        }

        vec4 kmpSampleLocal(vec2 localUv, bool rightEye) {
            if (localUv.x < 0.0 || localUv.x > 1.0 ||
                localUv.y < 0.0 || localUv.y > 1.0) {
                return vec4(0.0, 0.0, 0.0, 1.0);
            }
            vec4 window = rightEye
                ? vec4($PARAM_RIGHT_X0, $PARAM_RIGHT_Y0, $PARAM_RIGHT_X1, $PARAM_RIGHT_Y1)
                : vec4($PARAM_LEFT_X0, $PARAM_LEFT_Y0, $PARAM_LEFT_X1, $PARAM_LEFT_Y1);
            int rotation = rightEye ? $PARAM_RIGHT_ROTATION : $PARAM_LEFT_ROTATION;
            vec2 sourceUv = mix(window.xy, window.zw, kmpRotateUv(localUv, rotation));
            return HOOKED_tex(sourceUv);
        }

        vec3 kmpRayForScreenUv(vec2 screenUv, float viewportAspect) {
            vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
            float tanHalfFov = tan(
                (KMP_CAMERA_FOV_DEGREES * KMP_PI / 180.0) *
                    0.5 / max($PARAM_ZOOM, 0.01)
            );
            vec3 direction = normalize(
                vec3(p.x * viewportAspect * tanHalfFov, p.y * tanHalfFov, -1.0)
            );
            float yaw = $PARAM_YAW_DEGREES * KMP_PI / 180.0;
            float pitch = $PARAM_PITCH_DEGREES * KMP_PI / 180.0;
            float roll = $PARAM_ROLL_DEGREES * KMP_PI / 180.0;
            float cy = cos(yaw);
            float sy = sin(yaw);
            direction = vec3(
                cy * direction.x + sy * direction.z,
                direction.y,
                -sy * direction.x + cy * direction.z
            );
            float cp = cos(pitch);
            float sp = sin(pitch);
            direction = vec3(
                direction.x,
                cp * direction.y - sp * direction.z,
                sp * direction.y + cp * direction.z
            );
            float cr = cos(roll);
            float sr = sin(roll);
            return normalize(
                vec3(
                    cr * direction.x - sr * direction.y,
                    sr * direction.x + cr * direction.y,
                    direction.z
                )
            );
        }

        vec2 kmpEacFaceUv(float sc, float tc, float cellX, float cellY) {
            vec2 local = vec2(
                0.5 + atan(sc) / (0.5 * KMP_PI),
                0.5 - atan(tc) / (0.5 * KMP_PI)
            );
            return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
        }

        vec2 kmpEacUv(vec3 direction) {
            vec3 ad = abs(direction);
            if (ad.z >= ad.x && ad.z >= ad.y) {
                if (direction.z < 0.0) {
                    return kmpEacFaceUv(
                        direction.x / -direction.z,
                        direction.y / -direction.z,
                        0.0,
                        0.0
                    );
                }
                return kmpEacFaceUv(
                    -direction.x / direction.z,
                    direction.y / direction.z,
                    2.0,
                    0.0
                );
            }
            if (ad.x >= ad.y) {
                if (direction.x > 0.0) {
                    return kmpEacFaceUv(
                        direction.z / direction.x,
                        direction.y / direction.x,
                        1.0,
                        0.0
                    );
                }
                return kmpEacFaceUv(
                    -direction.z / -direction.x,
                    direction.y / -direction.x,
                    0.0,
                    1.0
                );
            }
            if (direction.y > 0.0) {
                return kmpEacFaceUv(
                    direction.x / direction.y,
                    direction.z / direction.y,
                    1.0,
                    1.0
                );
            }
            return kmpEacFaceUv(
                direction.x / -direction.y,
                -direction.z / -direction.y,
                2.0,
                1.0
            );
        }

        vec2 kmpContentUv(vec2 outputUv, float destinationAspect, float contentAspect) {
            destinationAspect = max(destinationAspect, 0.001);
            contentAspect = max(contentAspect, 0.001);
            if ($PARAM_CONTENT_SCALE == 2) return outputUv;
            if ($PARAM_CONTENT_SCALE == 1) {
                if (destinationAspect > contentAspect) {
                    outputUv.y =
                        (outputUv.y - 0.5) * (contentAspect / destinationAspect) + 0.5;
                } else {
                    outputUv.x =
                        (outputUv.x - 0.5) * (destinationAspect / contentAspect) + 0.5;
                }
                return outputUv;
            }
            if (destinationAspect > contentAspect) {
                float occupiedWidth = contentAspect / destinationAspect;
                outputUv.x = (outputUv.x - 0.5) / occupiedWidth + 0.5;
            } else {
                float occupiedHeight = destinationAspect / contentAspect;
                outputUv.y = (outputUv.y - 0.5) / occupiedHeight + 0.5;
            }
            return outputUv;
        }

        vec4 hook() {
            float destinationAspect = HOOKED_size.x / max(HOOKED_size.y, 1.0);
            float contentAspect = input_size.x / max(input_size.y, 1.0);
            float viewportAspect = $PARAM_STEREO != 0 ? contentAspect * 0.5 : contentAspect;
            vec2 screenUv = kmpContentUv(HOOKED_pos, destinationAspect, contentAspect);
            if (screenUv.x < 0.0 || screenUv.x > 1.0 ||
                screenUv.y < 0.0 || screenUv.y > 1.0) {
                return vec4(0.0, 0.0, 0.0, 1.0);
            }

            bool rightEye = false;
            if ($PARAM_STEREO != 0) {
                if (screenUv.x < 0.5) {
                    screenUv.x *= 2.0;
                } else {
                    screenUv.x = (screenUv.x - 0.5) * 2.0;
                    rightEye = true;
                }
            }

            if ($PARAM_PROJECTION_TYPE == 0) {
                return kmpSampleLocal(screenUv, rightEye);
            }

            vec3 direction = kmpRayForScreenUv(screenUv, viewportAspect);
            if ($PARAM_PROJECTION_TYPE == 1 || $PARAM_PROJECTION_TYPE == 2) {
                float horizontalFov = max($PARAM_FOV_DEGREES, 1.0) * KMP_PI / 180.0;
                float yaw = atan(direction.x, -direction.z);
                float pitch = asin(clamp(direction.y, -1.0, 1.0));
                if (abs(yaw) > horizontalFov * 0.5) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                return kmpSampleLocal(
                    vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / KMP_PI),
                    rightEye
                );
            }

            if ($PARAM_PROJECTION_TYPE >= 3 && $PARAM_PROJECTION_TYPE <= 6) {
                float maxTheta = max($PARAM_FOV_DEGREES, 1.0) * KMP_PI / 180.0 * 0.5;
                float theta = acos(clamp(-direction.z, -1.0, 1.0));
                if (theta > maxTheta) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                float phi = atan(direction.y, direction.x);
                float radius = theta / maxTheta * 0.5;
                return kmpSampleLocal(
                    vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius),
                    rightEye
                );
            }

            return kmpSampleLocal(kmpEacUv(direction), rightEye);
        }
        """.trimIndent() + "\n"

    private const val SHADER_FILE_NAME = "kmediaplayer-projection.hook"
    private const val PARAM_ENABLED = "kmp_projection_enabled"
    private const val PARAM_PROJECTION_TYPE = "kmp_projection_type"
    private const val PARAM_FOV_DEGREES = "kmp_fov_degrees"
    private const val PARAM_STEREO = "kmp_stereo"
    private const val PARAM_LEFT_X0 = "kmp_left_x0"
    private const val PARAM_LEFT_Y0 = "kmp_left_y0"
    private const val PARAM_LEFT_X1 = "kmp_left_x1"
    private const val PARAM_LEFT_Y1 = "kmp_left_y1"
    private const val PARAM_LEFT_ROTATION = "kmp_left_rotation"
    private const val PARAM_RIGHT_X0 = "kmp_right_x0"
    private const val PARAM_RIGHT_Y0 = "kmp_right_y0"
    private const val PARAM_RIGHT_X1 = "kmp_right_x1"
    private const val PARAM_RIGHT_Y1 = "kmp_right_y1"
    private const val PARAM_RIGHT_ROTATION = "kmp_right_rotation"
    private const val PARAM_YAW_DEGREES = "kmp_yaw_degrees"
    private const val PARAM_PITCH_DEGREES = "kmp_pitch_degrees"
    private const val PARAM_ROLL_DEGREES = "kmp_roll_degrees"
    private const val PARAM_ZOOM = "kmp_zoom"
    private const val PARAM_CONTENT_SCALE = "kmp_content_scale"
}
