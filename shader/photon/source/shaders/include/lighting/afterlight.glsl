#ifndef AFTERLIGHT_RGB_VOLUME
#define AFTERLIGHT_RGB_VOLUME
uniform sampler3D afterlightLightVolume;
uniform vec3 afterlightVolumeOrigin;
uniform vec3 afterlightVolumeSize;
uniform sampler3D afterlightPreviousVolume;
uniform vec3 afterlightPreviousOrigin;
uniform vec3 afterlightPreviousSize;
uniform float afterlightVolumeBlend;

vec4 afterlight_sample(sampler3D volume, vec3 origin, vec3 size, vec3 position) {
    if (size.x < 1.0) return vec4(0.0);
    vec3 cell = position - origin;
    vec3 edge = min(cell, size - cell);
    float fade = smoothstep(0.5, 2.5, min(edge.x, min(edge.y, edge.z)));
    if (fade <= 0.0) return vec4(0.0);
    // Opaque cells carry alpha -1. Ordinary texture filtering would mix their
    // zero radiance into the room and expose warm vanilla light at corners.
    // Interpolate only the traversable neighbours, retaining dark air cells so
    // light still fades at its reach limit and cannot cross a closed wall.
    vec3 grid = cell - 0.5;
    ivec3 base = ivec3(floor(grid));
    vec3 fraction = fract(grid);
    vec4 light = vec4(0.0);
    float total = 0.0;
    for (int z = 0; z < 2; ++z) {
        for (int y = 0; y < 2; ++y) {
            for (int x = 0; x < 2; ++x) {
                ivec3 offset = ivec3(x, y, z);
                vec3 weights = mix(1.0 - fraction, fraction, vec3(offset));
                float weight = weights.x * weights.y * weights.z;
                ivec3 texel = clamp(base + offset, ivec3(0), ivec3(size) - 1);
                vec4 sample_light = texelFetch(volume, texel, 0);
                if (sample_light.a >= 0.0) {
                    light += sample_light * weight;
                    total += weight;
                }
            }
        }
    }
    return total > 0.0 ? light * (fade / total) : vec4(0.0);
}

// scene_pos and the volume origin are both relative to the interpolated camera.
// Sampling the air in front of a surface prevents opaque walls sampling darkness.
vec3 afterlight_blocklight(vec3 scene_pos, vec3 normal, vec3 original, float vanilla_level) {
#ifdef NO_NORMAL
    // Particle/textured vertices have no normal attribute. Photon uses this
    // same guard for its LPV: their unused TBN can contain normalize(0) NaNs.
    vec3 position = scene_pos;
#else
    vec3 position = scene_pos + normal * 0.55;
#endif
    vec4 light = afterlight_sample(afterlightLightVolume, afterlightVolumeOrigin, afterlightVolumeSize, position);
#ifndef AFTERLIGHT_FORWARD_CURRENT_ONLY
    if (afterlightVolumeBlend < 1.0) {
        vec4 previous = afterlight_sample(afterlightPreviousVolume, afterlightPreviousOrigin, afterlightPreviousSize, position);
        light = mix(previous, light, afterlightVolumeBlend);
    }
#endif
    // Coverage has a fractional-power falloff: even tiny energy can replace a
    // visible share of vanilla light. A nonzero cutoff makes that share pop at
    // volume edges, so only skip an exactly empty sample.
    if (light.a <= 0.0) return original;
    // Replace the matching share of white blocklight; retain sunlight, skylight,
    // and unrelated torches. Add radiance so low-lightmap surfaces still receive spill.
    float coverage = clamp(pow(light.a, 1.0 / 2.2) / max(vanilla_level, 0.05), 0.0, 1.0);
    // RGB already contains energy. Avoid dividing dim samples by tiny alpha;
    // the only division is the bounded rescale for overlapping bright sources.
    vec3 colored = light.rgb * rec709_to_working_color;
    float energy_scale = 2.0 / max(light.a, 2.0);
    // Absolute irradiance: dim/white LEDs must not inherit vanilla/AO brightness
    // fluctuations through the old dot(original, luminance_weights) multiplier.
    // The authored RGB field was calibrated in diffuse-response units. Convert
    // only that field to irradiance before Photon's later Lambert / pi; the
    // retained vanilla contribution already uses Photon's own units.
    return original * (1.0 - coverage)
        + colored * energy_scale * pi * AFTERLIGHT_LED_STRENGTH * BLOCKLIGHT_I;
}
#endif
