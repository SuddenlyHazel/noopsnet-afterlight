#ifndef AFTERLIGHT_STAGE_LIGHTING
#define AFTERLIGHT_STAGE_LIGHTING

// diffuse_lighting.glsl includes this after Photon's material and specular
// helpers and after declaring metal_diffuse_amount. Both surface lobes share
// one beam sample and one voxel/haze traversal per source.
vec3 afterlight_stage_lighting(
    Material material,
    vec3 scene_pos,
    vec3 flat_normal,
    vec3 normal,
    vec3 view_dir
) {
    vec3 result = vec3(0.0);
    vec3 receiver = scene_pos + flat_normal * 0.03;
    float NoV = clamp01(dot(normal, view_dir));

    for (int i = 0; i < 8; ++i) {
        if (i >= afterlightFogBeamCount) break;
        // Laser-only rows are rendered by the separate beam path.
        if (texelFetch(afterlightFogBeams, ivec2(3, i), 0).x > 0.5) continue;

        vec4 start = texelFetch(afterlightFogBeams, ivec2(0, i), 0);
        vec4 axis = texelFetch(afterlightFogBeams, ivec2(1, i), 0);
        vec4 color = texelFetch(afterlightFogBeams, ivec2(2, i), 0);
        vec3 to_light;
        vec3 incident = afterlight_spot_radiance(receiver, start, axis, color, to_light);
        float NoL = dot(normal, to_light);
        if (NoL <= eps || max_of(incident) <= 0.0) continue;

        float transmission = afterlight_light_transmission(
            start.xyz + afterlightFogBeamOrigin,
            receiver
        );
        if (transmission <= 0.0) continue;

        // Photon applies its Lambertian material and metal-diffuse convention
        // after irradiance; retain that response for the stage diffuse lobe.
        vec3 response = NoL * material.albedo * rcp_pi
            * mix(1.0, metal_diffuse_amount, float(material.is_metal));

        // The GGX lobe uses the same mapped material, roughness, and Fresnel
        // as Photon's own direct highlight, without a celestial phase gate.
        vec3 halfway = view_dir + to_light;
        float halfway_length_sq = dot(halfway, halfway);
        if (halfway_length_sq > eps) {
            halfway *= inversesqrt(halfway_length_sq);
            float NoH = clamp01(dot(normal, halfway));
            float LoH = clamp01(dot(to_light, halfway));
            // LabPBR can encode exactly zero roughness. A point-source GGX
            // delta needs a finite width to avoid 0/0 at perfect alignment.
            Material stage_material = material;
            stage_material.roughness = max(stage_material.roughness, 0.002);
            response += get_specular_highlight_bsdf(
                stage_material, NoL, NoV, NoH * NoH, LoH
            );
        }
        result += incident * transmission * response;
    }
    return result;
}

#endif
