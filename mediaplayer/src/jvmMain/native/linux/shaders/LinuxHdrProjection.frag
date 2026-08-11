#version 450

layout(set = 0, binding = 0, std140) uniform HdrConfiguration {
    ivec4 modes;       // transfer, projection, stereo, rotation
    ivec4 flags;       // eye order, output transfer, reserved, reserved
    ivec4 color;       // range, matrix, primaries, reserved
    vec4 projection;   // fov, yaw, pitch, roll
    vec4 view;         // zoom, source peak nits, target peak nits, reference white
    vec4 crop;         // left, top, right, bottom
    vec4 hdr10PlusHeader; // enabled, source peak nits, reserved, reserved
    vec4 hdr10PlusCurve[9]; // 33 samples packed four per vector
} configuration;

layout(set = 0, binding = 1) uniform sampler2D lumaTexture;
layout(set = 0, binding = 2) uniform sampler2D chromaTexture;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;
const float CAMERA_FOV_DEGREES = 95.0;

vec3 rotateDirection(vec3 direction) {
    float yaw = radians(configuration.projection.y);
    float pitch = radians(configuration.projection.z);
    float roll = radians(configuration.projection.w);
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
    return normalize(vec3(
        cr * direction.x - sr * direction.y,
        sr * direction.x + cr * direction.y,
        direction.z
    ));
}

vec3 rayForScreenUv(vec2 screenUv, float viewportAspect) {
    vec2 p = vec2(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0);
    float zoom = max(configuration.view.x, 0.01);
    float tanHalfFov = tan(radians(CAMERA_FOV_DEGREES) * 0.5 / zoom);
    return rotateDirection(normalize(vec3(
        p.x * viewportAspect * tanHalfFov,
        p.y * tanHalfFov,
        -1.0
    )));
}

vec2 eacFaceUv(float sc, float tc, float cellX, float cellY) {
    vec2 local = vec2(
        0.5 + atan(sc) / (0.5 * PI),
        0.5 - atan(tc) / (0.5 * PI)
    );
    return vec2((cellX + local.x) / 3.0, (cellY + local.y) / 2.0);
}

vec2 eacUv(vec3 direction) {
    vec3 absoluteDirection = abs(direction);
    if (absoluteDirection.z >= absoluteDirection.x && absoluteDirection.z >= absoluteDirection.y) {
        if (direction.z < 0.0) {
            return eacFaceUv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
        }
        return eacFaceUv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
    }
    if (absoluteDirection.x >= absoluteDirection.y) {
        if (direction.x > 0.0) {
            return eacFaceUv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
        }
        return eacFaceUv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
    }
    if (direction.y > 0.0) {
        return eacFaceUv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
    }
    return eacFaceUv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
}

vec2 rotateUv(vec2 uv) {
    if (configuration.modes.w == 1) return vec2(1.0 - uv.y, uv.x);
    if (configuration.modes.w == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (configuration.modes.w == 3) return vec2(uv.y, 1.0 - uv.x);
    return uv;
}

bool projectionUv(vec2 outputUv, out vec2 sourceUv) {
    vec2 eyeUv = outputUv;
    bool secondEye = false;
    float viewportAspect = 16.0 / 9.0;
    if (configuration.modes.z != 0) {
        secondEye = outputUv.x >= 0.5;
        eyeUv.x = fract(outputUv.x * 2.0);
        viewportAspect *= 0.5;
    }

    vec2 localUv = eyeUv;
    if (configuration.modes.y != 0) {
        vec3 direction = rayForScreenUv(eyeUv, viewportAspect);
        if (configuration.modes.y == 1 || configuration.modes.y == 2) {
            float horizontalFov = radians(max(configuration.projection.x, 1.0));
            float yaw = atan(direction.x, -direction.z);
            float pitch = asin(clamp(direction.y, -1.0, 1.0));
            if (abs(yaw) > horizontalFov * 0.5) return false;
            localUv = vec2(yaw / horizontalFov + 0.5, 0.5 - pitch / PI);
        } else if (configuration.modes.y >= 3 && configuration.modes.y <= 6) {
            float maxTheta = radians(max(configuration.projection.x, 1.0)) * 0.5;
            float theta = acos(clamp(-direction.z, -1.0, 1.0));
            if (theta > maxTheta) return false;
            float phi = atan(direction.y, direction.x);
            float radius = theta / maxTheta * 0.5;
            localUv = vec2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius);
        } else {
            localUv = eacUv(direction);
        }
    }

    localUv = rotateUv(localUv);
    if (any(lessThan(localUv, vec2(0.0))) || any(greaterThan(localUv, vec2(1.0)))) return false;

    vec4 eyeWindow = vec4(0.0, 0.0, 1.0, 1.0);
    if (configuration.modes.z == 1) {
        bool useSecond = configuration.flags.x != 0 ? !secondEye : secondEye;
        eyeWindow = useSecond ? vec4(0.5, 0.0, 1.0, 1.0) : vec4(0.0, 0.0, 0.5, 1.0);
    } else if (configuration.modes.z == 2) {
        bool useSecond = configuration.flags.x != 0 ? !secondEye : secondEye;
        eyeWindow = useSecond ? vec4(0.0, 0.5, 1.0, 1.0) : vec4(0.0, 0.0, 1.0, 0.5);
    }
    vec2 eyeSize = eyeWindow.zw - eyeWindow.xy;
    eyeWindow.xy += eyeSize * configuration.crop.xy;
    eyeWindow.zw -= eyeSize * configuration.crop.zw;
    sourceUv = mix(eyeWindow.xy, eyeWindow.zw, localUv);
    return true;
}

vec3 sampleP010(vec2 uv) {
    float y = texture(lumaTexture, uv).r;
    vec2 cbcr = texture(chromaTexture, uv).rg;
    // R16/R16G16 UNORM exposes the left-aligned P010 words divided by 65535.
    // Recover the original 10-bit codes before applying limited/full range.
    const float p010CodeScale = 65535.0 / 64.0;
    y *= p010CodeScale;
    cbcr *= p010CodeScale;
    if (configuration.color.x == 0) {
        y = clamp((y - 64.0) / 876.0, 0.0, 1.0);
        cbcr = (cbcr - 512.0) / 896.0;
    } else {
        y = clamp(y / 1023.0, 0.0, 1.0);
        cbcr = (cbcr - 512.0) / 1023.0;
    }
    if (configuration.color.y == 1) {
        return max(vec3(
            y + 1.5748 * cbcr.y,
            y - 0.187324 * cbcr.x - 0.468124 * cbcr.y,
            y + 1.8556 * cbcr.x
        ), vec3(0.0));
    }
    if (configuration.color.y == 2) {
        return max(vec3(
            y + 1.4020 * cbcr.y,
            y - 0.344136 * cbcr.x - 0.714136 * cbcr.y,
            y + 1.7720 * cbcr.x
        ), vec3(0.0));
    }
    return max(vec3(
        y + 1.4746 * cbcr.y,
        y - 0.164553 * cbcr.x - 0.571353 * cbcr.y,
        y + 1.8814 * cbcr.x
    ), vec3(0.0));
}

vec3 pqEotf(vec3 encoded) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    vec3 p = pow(clamp(encoded, 0.0, 1.0), vec3(1.0 / m2));
    return pow(max((p - c1) / max(c2 - c3 * p, vec3(1e-6)), vec3(0.0)), vec3(1.0 / m1)) * 10000.0;
}

vec3 srgbEotf(vec3 encoded) {
    bvec3 low = lessThanEqual(encoded, vec3(0.04045));
    vec3 linearLow = encoded / 12.92;
    vec3 linearHigh = pow((encoded + 0.055) / 1.055, vec3(2.4));
    return mix(linearHigh, linearLow, low);
}

vec3 srgbOetf(vec3 linearValue) {
    bvec3 low = lessThanEqual(linearValue, vec3(0.0031308));
    vec3 encodedLow = linearValue * 12.92;
    vec3 encodedHigh = 1.055 * pow(max(linearValue, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(encodedHigh, encodedLow, low);
}

vec3 pqOetf(vec3 nits) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    vec3 p = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(m1));
    return pow((c1 + c2 * p) / (1.0 + c3 * p), vec3(m2));
}

float pqOetfScalar(float nits) {
    return pqOetf(vec3(nits)).x;
}

float pqEotfScalar(float encoded) {
    return pqEotf(vec3(encoded)).x;
}

float toneMapBt2390(float luminanceNits, float sourcePeak, float targetPeak) {
    sourcePeak = max(sourcePeak, 1.0);
    targetPeak = max(targetPeak, 1.0);
    if (targetPeak >= sourcePeak) return min(luminanceNits, sourcePeak);
    float sourcePeakCode = pqOetfScalar(sourcePeak);
    float target = clamp(pqOetfScalar(targetPeak) / sourcePeakCode, 0.0, 1.0);
    float knee = clamp(1.5 * target - 0.5, 0.0, 1.0);
    float inputCode = clamp(pqOetfScalar(luminanceNits) / sourcePeakCode, 0.0, 1.0);
    float outputCode = inputCode;
    if (inputCode > knee && knee < 1.0) {
        float t = clamp((inputCode - knee) / (1.0 - knee), 0.0, 1.0);
        float t2 = t * t;
        float t3 = t2 * t;
        outputCode = (2.0 * t3 - 3.0 * t2 + 1.0) * knee
            + (t3 - 2.0 * t2 + t) * (1.0 - knee)
            + (-2.0 * t3 + 3.0 * t2) * target;
    }
    return min(pqEotfScalar(clamp(outputCode * sourcePeakCode, 0.0, 1.0)), targetPeak);
}

vec3 toneMapNits(vec3 nits) {
    float sourcePeak = max(configuration.view.y, 1.0);
    float targetPeak = max(configuration.view.z, 1.0);
    float luminance = max(dot(nits, vec3(0.2627, 0.6780, 0.0593)), 1e-6);
    float mapped = configuration.hdr10PlusHeader.x > 0.5
        ? min(luminance, targetPeak)
        : toneMapBt2390(luminance, sourcePeak, targetPeak);
    return max(nits * (mapped / luminance), vec3(0.0));
}

float hdr10PlusCurveSample(int index) {
    int bounded = clamp(index, 0, 32);
    return configuration.hdr10PlusCurve[bounded / 4][bounded % 4] * 10000.0;
}

vec3 applyHdr10Plus(vec3 nits) {
    if (configuration.hdr10PlusHeader.x < 0.5) return nits;
    float luminance = max(dot(nits, vec3(0.2627, 0.6780, 0.0593)), 0.0);
    float normalized = clamp(luminance / max(configuration.hdr10PlusHeader.y, 1.0), 0.0, 1.0);
    float curvePosition = normalized * 32.0;
    int lower = int(floor(curvePosition));
    int upper = min(lower + 1, 32);
    float mapped = mix(
        hdr10PlusCurveSample(lower),
        hdr10PlusCurveSample(upper),
        fract(curvePosition)
    );
    float scale = luminance > 0.000001 ? mapped / luminance : 0.0;
    return max(nits * scale, vec3(0.0));
}

float hlgInverse(float signalValue) {
    const float a = 0.17883277;
    const float b = 1.0 - 4.0 * a;
    const float c = 0.55991073;
    return signalValue <= 0.5
        ? signalValue * signalValue / 3.0
        : (exp((signalValue - c) / a) + b) / 12.0;
}

float hlgForward(float sceneValue) {
    const float a = 0.17883277;
    const float b = 1.0 - 4.0 * a;
    const float c = 0.55991073;
    return sceneValue <= 1.0 / 12.0
        ? sqrt(max(3.0 * sceneValue, 0.0))
        : a * log(max(12.0 * sceneValue - b, 1e-6)) + c;
}

float sourceLuma(vec3 value) {
    if (configuration.color.z == 1) return dot(value, vec3(0.2126, 0.7152, 0.0722));
    if (configuration.color.z == 2) return dot(value, vec3(0.2290, 0.6917, 0.0793));
    return dot(value, vec3(0.2627, 0.6780, 0.0593));
}

vec3 hlgToNits(vec3 signalValue) {
    vec3 scene = vec3(hlgInverse(signalValue.r), hlgInverse(signalValue.g), hlgInverse(signalValue.b));
    float sceneLuma = max(sourceLuma(scene), 1e-6);
    float gamma = 1.2 + 0.42 * log(max(configuration.view.y, 1.0) / 1000.0) / log(10.0);
    return scene * pow(sceneLuma, max(gamma, 0.0) - 1.0) * max(configuration.view.y, 1.0);
}

vec3 nitsToHlg(vec3 nits) {
    float targetPeak = max(configuration.view.z, 1.0);
    vec3 displayLinear = max(nits / targetPeak, vec3(0.0));
    float displayLuma = max(dot(displayLinear, vec3(0.2627, 0.6780, 0.0593)), 1e-6);
    float gamma = max(1.2 + 0.42 * log(targetPeak / 1000.0) / log(10.0), 0.01);
    vec3 scene = displayLinear * pow(displayLuma, 1.0 / gamma - 1.0);
    return vec3(hlgForward(scene.r), hlgForward(scene.g), hlgForward(scene.b));
}

vec3 sourcePrimariesToBt2020(vec3 linearRgb) {
    if (configuration.color.z == 1) {
        return vec3(
            0.627404 * linearRgb.r + 0.329283 * linearRgb.g + 0.043313 * linearRgb.b,
            0.069097 * linearRgb.r + 0.919540 * linearRgb.g + 0.011362 * linearRgb.b,
            0.016391 * linearRgb.r + 0.088013 * linearRgb.g + 0.895595 * linearRgb.b
        );
    }
    if (configuration.color.z == 2) {
        return vec3(
            0.753833 * linearRgb.r + 0.198597 * linearRgb.g + 0.047570 * linearRgb.b,
            0.045744 * linearRgb.r + 0.941777 * linearRgb.g + 0.012479 * linearRgb.b,
            -0.001210 * linearRgb.r + 0.017602 * linearRgb.g + 0.983609 * linearRgb.b
        );
    }
    return linearRgb;
}

vec3 bt2020ToLinearSrgb(vec3 linearRgb) {
    return vec3(
        1.660491 * linearRgb.r - 0.587641 * linearRgb.g - 0.072850 * linearRgb.b,
        -0.124550 * linearRgb.r + 1.132900 * linearRgb.g - 0.008350 * linearRgb.b,
        -0.018151 * linearRgb.r - 0.100579 * linearRgb.g + 1.118730 * linearRgb.b
    );
}

float hashNoise(vec2 position) {
    return fract(sin(dot(position, vec2(12.9898, 78.233))) * 43758.5453);
}

float triangularDither(vec2 position) {
    float first = hashNoise(position);
    float second = hashNoise(position + vec2(37.0, 17.0));
    return (first - second) / 1023.0;
}

void main() {
    vec2 sourceUv;
    if (!projectionUv(inUv, sourceUv)) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 encoded = sampleP010(sourceUv);
    vec3 nits;
    if (configuration.modes.x == 1) {
        nits = hlgToNits(encoded);
    } else if (configuration.modes.x == 2) {
        nits = srgbEotf(encoded) * max(configuration.view.w, 1.0);
    } else {
        nits = pqEotf(encoded);
    }
    nits = sourcePrimariesToBt2020(nits);
    if (configuration.modes.x != 2) nits = applyHdr10Plus(nits);
    nits = toneMapNits(nits);

    if (configuration.flags.y == 2) {
        // Windows-scRGB semantics in the shared FP16 scene: 1.0 is the
        // producer's declared SDR reference white and values stay unclamped.
        outColor = vec4(
            bt2020ToLinearSrgb(nits) / max(configuration.view.w, 1.0),
            1.0
        );
        return;
    }
    if (configuration.flags.y == 3) {
        vec3 linearSrgb = bt2020ToLinearSrgb(nits) / max(configuration.view.w, 1.0);
        vec3 outputSignal = srgbOetf(clamp(linearSrgb, 0.0, 1.0));
        outputSignal += vec3(triangularDither(gl_FragCoord.xy) * (1023.0 / 255.0));
        outColor = vec4(clamp(outputSignal, 0.0, 1.0), 1.0);
        return;
    }

    vec3 outputSignal = configuration.flags.y == 1 ? nitsToHlg(nits) : pqOetf(nits);
    outputSignal += vec3(triangularDither(gl_FragCoord.xy));
    outColor = vec4(clamp(outputSignal, 0.0, 1.0), 1.0);
}
