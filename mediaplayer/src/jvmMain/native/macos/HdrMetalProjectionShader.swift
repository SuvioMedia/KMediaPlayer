let macMetalProjectionShader = """
#include <metal_stdlib>
using namespace metal;

struct VertexOutput {
    float4 position [[position]];
    float2 uv;
};

vertex VertexOutput projection_vertex(uint vertex_id [[vertex_id]]) {
    const float2 positions[4] = {
        float2(-1.0, -1.0), float2(1.0, -1.0),
        float2(-1.0, 1.0), float2(1.0, 1.0)
    };
    VertexOutput output;
    const float2 position = positions[vertex_id];
    output.position = float4(position, 0.0, 1.0);
    output.uv = float2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
    return output;
}

constant float PI = 3.14159265358979323846;
constant float CAMERA_FOV_DEGREES = 95.0;
constexpr sampler video_sampler(coord::normalized, address::clamp_to_edge, filter::linear);

float2 rotate_uv(float2 uv, float rotation) {
    if (rotation > 0.5 && rotation < 1.5) return float2(1.0 - uv.y, uv.x);
    if (rotation >= 1.5 && rotation < 2.5) return float2(1.0 - uv.x, 1.0 - uv.y);
    if (rotation >= 2.5) return float2(uv.y, 1.0 - uv.x);
    return uv;
}

float3 yuv_to_rgb(
    float2 source_uv,
    texture2d<float, access::sample> luma,
    texture2d<float, access::sample> chroma,
    constant float *p
) {
    const bool ten_bit = p[22] > 0.5;
    const bool full_range = p[23] > 0.5;
    const float maximum_code = ten_bit ? 1023.0 : 255.0;
    const float code_scale = ten_bit ? (65535.0 / 64.0) : 255.0;
    const float y_code = luma.sample(video_sampler, source_uv).r * code_scale;
    const float2 c_code = chroma.sample(video_sampler, source_uv).rg * code_scale;
    float y;
    float2 cbcr;
    if (full_range) {
        y = y_code / maximum_code;
        cbcr = c_code / maximum_code - 0.5;
    } else if (ten_bit) {
        y = (y_code - 64.0) / 876.0;
        cbcr = (c_code - 512.0) / 896.0;
    } else {
        y = (y_code - 16.0) / 219.0;
        cbcr = (c_code - 128.0) / 224.0;
    }
    y = max(y, 0.0);
    const float cb = cbcr.x;
    const float cr = cbcr.y;
    if (p[19] > 0.5 && p[19] < 1.5) {
        return float3(y + 1.4746 * cr, y - 0.164553 * cb - 0.571353 * cr, y + 1.8814 * cb);
    }
    if (p[19] >= 1.5) {
        return float3(y + 1.4020 * cr, y - 0.344136 * cb - 0.714136 * cr, y + 1.7720 * cb);
    }
    return float3(y + 1.5748 * cr, y - 0.187324 * cb - 0.468124 * cr, y + 1.8556 * cb);
}

float pq_eotf(float signal) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    const float value = pow(clamp(signal, 0.0, 1.0), 1.0 / m2);
    return pow(max(value - c1, 0.0) / max(c2 - c3 * value, 0.000001), 1.0 / m1) * 10000.0;
}

float pq_oetf(float nits) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    const float value = pow(clamp(nits, 0.0, 10000.0) / 10000.0, m1);
    return pow((c1 + c2 * value) / (1.0 + c3 * value), m2);
}

float hlg_inverse_oetf(float signal) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    return signal <= 0.5 ? signal * signal / 3.0 : (exp((signal - c) / a) + b) / 12.0;
}

float sdr_inverse_oetf(float signal) {
    signal = max(signal, 0.0);
    return signal <= 0.081 ? signal / 4.5 : pow((signal + 0.099) / 1.099, 1.0 / 0.45);
}

float srgb_inverse_eotf(float signal) {
    signal = max(signal, 0.0);
    return signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
}

float3 source_primaries_to_bt2020(float3 color, float primaries) {
    if (primaries < 0.5) return color;
    if (primaries < 1.5) {
        return float3(
            0.627403896 * color.r + 0.329283038 * color.g + 0.043313066 * color.b,
            0.069097289 * color.r + 0.919540395 * color.g + 0.011362316 * color.b,
            0.016391439 * color.r + 0.088013308 * color.g + 0.895595253 * color.b
        );
    }
    if (primaries < 2.5) {
        return float3(
            0.753833034 * color.r + 0.198597369 * color.g + 0.047569597 * color.b,
            0.045743849 * color.r + 0.941777220 * color.g + 0.012478931 * color.b,
            -0.001210340 * color.r + 0.017601717 * color.g + 0.983608623 * color.b
        );
    }
    if (primaries < 3.5) {
        return float3(
            0.595254206 * color.r + 0.349313920 * color.g + 0.055431874 * color.b,
            0.081243662 * color.r + 0.891503296 * color.g + 0.027253043 * color.b,
            0.015512341 * color.r + 0.081911642 * color.g + 0.902576017 * color.b
        );
    }
    return float3(
        0.655036777 * color.r + 0.302160965 * color.g + 0.042802258 * color.b,
        0.072140556 * color.r + 0.916631129 * color.g + 0.011228315 * color.b,
        0.017113370 * color.r + 0.097853470 * color.g + 0.885033160 * color.b
    );
}

float3 gamut_map_to_bt709(
    float3 normalized_bt2020,
    texture3d<float, access::sample> gamut_lut
) {
    const float lut_edge = float(gamut_lut.get_width());
    const float3 coordinates = (clamp(normalized_bt2020, 0.0, 1.0) * (lut_edge - 1.0) + 0.5) / lut_edge;
    return gamut_lut.sample(video_sampler, coordinates).rgb;
}

float bt2390(float nits, float source_peak, float target_peak) {
    const float source_code = pq_oetf(source_peak);
    const float normalized_target = clamp(pq_oetf(target_peak) / max(source_code, 0.000001), 0.0, 1.0);
    const float knee = clamp(1.5 * normalized_target - 0.5, 0.0, 1.0);
    const float input_code = clamp(pq_oetf(nits) / max(source_code, 0.000001), 0.0, 1.0);
    if (input_code <= knee || knee >= 1.0) return min(nits, target_peak);
    const float t = clamp((input_code - knee) / max(1.0 - knee, 0.000001), 0.0, 1.0);
    const float t2 = t * t;
    const float t3 = t2 * t;
    const float output_code =
        (2.0 * t3 - 3.0 * t2 + 1.0) * knee +
        (t3 - 2.0 * t2 + t) * (1.0 - knee) +
        (-2.0 * t3 + 3.0 * t2) * normalized_target;
    return min(pq_eotf(clamp(output_code * source_code, 0.0, 1.0)), target_peak);
}

float hdr10_plus_curve_sample(int index, constant float *p) {
    return p[26 + clamp(index, 0, 32)] * 10000.0;
}

float3 apply_hdr10_plus(float3 linear_nits, constant float *p) {
    if (p[24] < 0.5) return linear_nits;
    const float luminance = max(dot(linear_nits, float3(0.2627, 0.6780, 0.0593)), 0.0);
    const float normalized = clamp(luminance / max(p[25], 1.0), 0.0, 1.0);
    const float curve_position = normalized * 32.0;
    const int lower = int(floor(curve_position));
    const int upper = min(lower + 1, 32);
    const float mapped = mix(
        hdr10_plus_curve_sample(lower, p),
        hdr10_plus_curve_sample(upper, p),
        fract(curve_position)
    );
    const float scale = luminance > 0.000001 ? mapped / luminance : 0.0;
    return max(linear_nits * scale, float3(0.0));
}

float3 color_manage(
    float3 encoded,
    texture3d<float, access::sample> gamut_lut,
    constant float *p
) {
    if (p[18] < 0.5 || p[18] >= 2.5) {
        float3 linear_source;
        if (p[18] < 0.5) {
            linear_source = float3(
                sdr_inverse_oetf(encoded.r),
                sdr_inverse_oetf(encoded.g),
                sdr_inverse_oetf(encoded.b)
            );
        } else if (p[18] < 3.5) {
            linear_source = float3(
                srgb_inverse_eotf(encoded.r),
                srgb_inverse_eotf(encoded.g),
                srgb_inverse_eotf(encoded.b)
            );
        } else {
            linear_source = max(encoded, float3(0.0));
        }
        const float3 linear_bt2020 = source_primaries_to_bt2020(linear_source, p[59]);
        if (p[20] > 0.5) return max(linear_bt2020, float3(0.0));
        return gamut_map_to_bt709(linear_bt2020, gamut_lut);
    }
    float3 linear_nits;
    if (p[18] < 1.5) {
        linear_nits = float3(pq_eotf(encoded.r), pq_eotf(encoded.g), pq_eotf(encoded.b));
    } else {
        linear_nits = float3(
            hlg_inverse_oetf(encoded.r),
            hlg_inverse_oetf(encoded.g),
            hlg_inverse_oetf(encoded.b)
        );
        linear_nits = p[21] * pow(max(linear_nits, float3(0.0)), float3(1.2));
    }
    linear_nits = source_primaries_to_bt2020(linear_nits, p[59]);
    linear_nits = apply_hdr10_plus(linear_nits, p);
    if (p[20] > 0.5) return max(linear_nits / 100.0, 0.0);

    const float luminance = max(dot(linear_nits, float3(0.2627, 0.6780, 0.0593)), 0.000001);
    const float mapped = p[24] > 0.5
        ? min(luminance, 100.0)
        : bt2390(luminance, max(p[21], 100.0), 100.0);
    const float3 normalized_bt2020 = linear_nits * (mapped / luminance) / 100.0;
    return gamut_map_to_bt709(normalized_bt2020, gamut_lut);
}

float3 ray_for_screen_uv(float2 screen_uv, constant float *p) {
    const float2 position = float2(screen_uv.x * 2.0 - 1.0, 1.0 - screen_uv.y * 2.0);
    const float tan_half_fov = tan(CAMERA_FOV_DEGREES * PI / 360.0 / max(p[17], 0.01));
    float3 direction = normalize(float3(position.x * p[3] * tan_half_fov, position.y * tan_half_fov, -1.0));
    const float yaw = p[14] * PI / 180.0;
    const float pitch = p[15] * PI / 180.0;
    const float roll = p[16] * PI / 180.0;
    direction = float3(cos(yaw) * direction.x + sin(yaw) * direction.z, direction.y,
        -sin(yaw) * direction.x + cos(yaw) * direction.z);
    direction = float3(direction.x, cos(pitch) * direction.y - sin(pitch) * direction.z,
        sin(pitch) * direction.y + cos(pitch) * direction.z);
    return normalize(float3(cos(roll) * direction.x - sin(roll) * direction.y,
        sin(roll) * direction.x + cos(roll) * direction.y, direction.z));
}

float2 eac_face_uv(float sc, float tc, float cell_x, float cell_y) {
    const float2 local = float2(0.5 + atan(sc) / (0.5 * PI), 0.5 - atan(tc) / (0.5 * PI));
    return float2((cell_x + local.x) / 3.0, (cell_y + local.y) / 2.0);
}

float2 eac_uv(float3 direction) {
    const float3 a = abs(direction);
    if (a.z >= a.x && a.z >= a.y) {
        if (direction.z < 0.0) return eac_face_uv(direction.x / -direction.z, direction.y / -direction.z, 0.0, 0.0);
        return eac_face_uv(-direction.x / direction.z, direction.y / direction.z, 2.0, 0.0);
    }
    if (a.x >= a.y) {
        if (direction.x > 0.0) return eac_face_uv(direction.z / direction.x, direction.y / direction.x, 1.0, 0.0);
        return eac_face_uv(-direction.z / -direction.x, direction.y / -direction.x, 0.0, 1.0);
    }
    if (direction.y > 0.0) return eac_face_uv(direction.x / direction.y, direction.z / direction.y, 1.0, 1.0);
    return eac_face_uv(direction.x / -direction.y, -direction.z / -direction.y, 2.0, 1.0);
}

float4 sample_local(
    float2 local_uv,
    float eye,
    texture2d<float, access::sample> luma,
    texture2d<float, access::sample> chroma,
    texture3d<float, access::sample> gamut_lut,
    constant float *p
) {
    if (any(local_uv < 0.0) || any(local_uv > 1.0)) return float4(0.0, 0.0, 0.0, 1.0);
    float4 window = float4(p[4], p[5], p[6], p[7]);
    float rotation = p[8];
    if (eye > 0.5) {
        window = float4(p[9], p[10], p[11], p[12]);
        rotation = p[13];
    }
    const float2 rotated = rotate_uv(local_uv, rotation);
    const float2 source_uv = mix(window.xy, window.zw, rotated);
    return float4(color_manage(yuv_to_rgb(source_uv, luma, chroma, p), gamut_lut, p), 1.0);
}

fragment float4 projection_fragment(
    VertexOutput input [[stage_in]],
    texture2d<float, access::sample> luma [[texture(0)]],
    texture2d<float, access::sample> chroma [[texture(1)]],
    texture3d<float, access::sample> gamut_lut [[texture(2)]],
    constant float *p [[buffer(0)]]
) {
    float2 screen_uv = input.uv;
    float eye = 0.0;
    if (p[2] > 0.5) {
        if (screen_uv.x < 0.5) screen_uv.x *= 2.0;
        else { screen_uv.x = (screen_uv.x - 0.5) * 2.0; eye = 1.0; }
    }
    if (p[0] < 0.5) return sample_local(screen_uv, eye, luma, chroma, gamut_lut, p);
    const float3 direction = ray_for_screen_uv(screen_uv, p);
    if (p[0] < 2.5) {
        const float horizontal_fov = max(p[1], 1.0) * PI / 180.0;
        const float yaw = atan2(direction.x, -direction.z);
        const float pitch = asin(clamp(direction.y, -1.0, 1.0));
        if (abs(yaw) > horizontal_fov * 0.5) return float4(0.0, 0.0, 0.0, 1.0);
        return sample_local(float2(yaw / horizontal_fov + 0.5, 0.5 - pitch / PI), eye, luma, chroma, gamut_lut, p);
    }
    if (p[0] < 6.5) {
        const float max_theta = max(p[1], 1.0) * PI / 360.0;
        const float theta = acos(clamp(-direction.z, -1.0, 1.0));
        if (theta > max_theta) return float4(0.0, 0.0, 0.0, 1.0);
        const float phi = atan2(direction.y, direction.x);
        const float radius = theta / max_theta * 0.5;
        return sample_local(float2(0.5 + cos(phi) * radius, 0.5 - sin(phi) * radius), eye, luma, chroma, gamut_lut, p);
    }
    return sample_local(eac_uv(direction), eye, luma, chroma, gamut_lut, p);
}
"""
