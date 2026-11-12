#ifndef AFTERLIGHT_ROOM_MIST
#define AFTERLIGHT_ROOM_MIST
#include "/include/lighting/afterlight.glsl"
#include "/include/lighting/afterlight_spotlight.glsl"

vec4 afterlight_mist(vec3 pos) {
    vec4 mist=afterlight_medium(pos);
    // Fine wisps modulate local scattering; the shared transport grid carries
    // mean density so small animated noise does not shimmer surface shadows.
    vec3 world=pos+cameraPosition;
    float noise=texture(colortex0,world*0.045+frameTimeCounter*vec3(0.003,0.0008,0.001)).r;
    mist.a*=0.65+0.70*noise;
    return mist;
}
vec3 afterlight_spot_scattering(vec3 pos, vec3 view_dir) {
    vec3 light=vec3(0.0);
    for(int i=0;i<8;++i) {
        if(i>=afterlightFogBeamCount)break;
        if(texelFetch(afterlightFogBeams,ivec2(3,i),0).x>0.5)continue;
        vec3 to_light;
        light+=afterlight_spot_incident(i,pos,to_light)*afterlight_phase(dot(view_dir,to_light));
    }
    return light;
}

// Clip a visible camera ray to the positive, finite cone. Integrating this
// interval directly prevents a narrow shaft from appearing/disappearing when
// Photon's per-frame dither changes the coarser atmospheric samples.
bool afterlight_cone_interval(vec3 delta, vec3 direction, vec4 axis,
        float slope, float beam_length, inout float lo, inout float hi) {
    if (beam_length <= 0.0001) return false;
    float axial_origin = dot(delta, axis.xyz);
    float axial_direction = dot(direction, axis.xyz);
    if (abs(axial_direction) < 0.00001) {
        if (axial_origin < 0.0 || axial_origin > beam_length) return false;
    } else {
        float a = -axial_origin / axial_direction;
        float b = (beam_length-axial_origin) / axial_direction;
        lo=max(lo,min(a,b)); hi=min(hi,max(a,b));
    }
    if (hi <= lo) return false;
    float radius = axis.w + slope*axial_origin;
    float A = 1.0-(1.0+slope*slope)*axial_direction*axial_direction;
    float B = 2.0*(dot(delta,direction)-axial_origin*axial_direction-radius*slope*axial_direction);
    float C = dot(delta,delta)-axial_origin*axial_origin-radius*radius;
    if (abs(A) < 0.00001) {
        if (abs(B) < 0.00001) return C <= 0.0;
        if (B > 0.0) hi=min(hi,-C/B); else lo=max(lo,-C/B);
    } else {
        float discriminant = B*B-4.0*A*C;
        if (discriminant < 0.0) return A < 0.0;
        // Stable quadratic roots, including rays nearly parallel to the cone.
        float q = -0.5*(B+(B < 0.0 ? -1.0 : 1.0)*sqrt(discriminant));
        float a = abs(q) > 0.000001 ? q/A : -B/(2.0*A);
        float b = abs(q) > 0.000001 ? C/q : a;
        float near_root=min(a,b), far_root=max(a,b);
        if (A > 0.0) { lo=max(lo,near_root); hi=min(hi,far_root); }
        else if (lo < near_root) hi=min(hi,near_root);
        else lo=max(lo,far_root);
    }
    return hi > lo;
}

vec3 afterlight_integrate_spots(vec3 origin, vec3 direction, float begin, float finish) {
    vec3 light=vec3(0.0);
    for (int i=0; i<8; ++i) {
        if (i >= afterlightFogBeamCount) break;
        if (texelFetch(afterlightFogBeams,ivec2(3,i),0).x > 0.5) continue;
        vec4 start=texelFetch(afterlightFogBeams,ivec2(0,i),0);
        vec4 axis=texelFetch(afterlightFogBeams,ivec2(1,i),0);
        vec4 color=texelFetch(afterlightFogBeams,ivec2(2,i),0);
        vec3 beam_origin=start.xyz+afterlightFogBeamOrigin;
        float lo=begin, hi=finish;
        if (!afterlight_cone_interval(origin-beam_origin,direction,axis,color.w,start.w,lo,hi)) continue;
        // Both view attenuation and source attenuation use the same medium
        // and obstacle grid. The cone only bounds work; it is not an added fill.
        float transmission=afterlight_light_transmission(origin+direction*begin,origin+direction*lo);
        float stride=(hi-lo)/12.0;
        for(int j=0;j<12;++j) {
            vec3 pos=origin+direction*(lo+(float(j)+0.5)*stride);
            float step_transmission=exp(-afterlight_mist(pos).a*afterlight_extinction*stride);
            vec3 to_light;
            vec3 incident=afterlight_spot_radiance(pos,start,axis,color,to_light);
            if(max(incident.r,max(incident.g,incident.b))>0.0) {
                incident*=afterlight_light_transmission(beam_origin,pos);
                light+=transmission*(1.0-step_transmission)*afterlight_scattering_albedo
                    *incident*afterlight_phase(dot(direction,to_light));
            }
            transmission*=step_transmission;
        }
    }
    return light;
}

// Thin laser shafts must not depend on a raymarch randomly hitting a tiny
// cylinder. Find the closest points of two finite segments, then integrate a
// soft Gaussian cross-section. It remains stable as the viewpoint moves.
vec3 afterlight_laser_scattering(vec3 origin, vec3 direction, float ray_length) {
    vec3 light = vec3(0.0);
    for (int i=0; i<8; ++i) {
        if (i >= afterlightFogBeamCount) break;
        if (texelFetch(afterlightFogBeams, ivec2(3,i), 0).x < 0.5) continue;
        vec4 start = texelFetch(afterlightFogBeams, ivec2(0,i), 0);
        vec4 axis = texelFetch(afterlightFogBeams, ivec2(1,i), 0);
        vec4 color = texelFetch(afterlightFogBeams, ivec2(2,i), 0);
        vec3 delta = origin - (start.xyz + afterlightFogBeamOrigin);
        float b = dot(direction, axis.xyz), d = dot(direction, delta), e = dot(axis.xyz, delta);
        float denom = max(0.0001, 1.0 - b*b);
        float s = clamp((b*e-d) / denom, 0.0, ray_length);
        float t = clamp(b*s+e, 0.0, start.w);
        s = clamp(b*t-d, 0.0, ray_length);
        t = clamp(b*s+e, 0.0, start.w);
        vec3 point = origin + direction * s;
        vec3 separation = delta + direction*s - axis.xyz*t;
        float radius = max(0.025, axis.w + t*color.w);
        float distance_sq = dot(separation, separation);
        if (distance_sq > radius*radius*9.0) continue;
        float density = afterlight_mist(point).a;
        // The camera endpoint belongs to every screen ray. A shaft crossing
        // that endpoint must fade locally, not paint the entire viewport.
        float camera_fade = smoothstep(0.15, 0.75, s);
        float amount = exp(-distance_sq / (radius*radius)) * density * camera_fade;
        // Finite cap avoids a bright singularity while looking down the beam.
        float path = min(2.0, radius * 1.77245 / sqrt(denom));
        light += (color.rgb * rec709_to_working_color) * amount * path * 22.0;
    }
    return light;
}

mat2x3 raymarch_afterlight_mist(vec3 origin, vec3 end, float dither) {
    if (afterlightFogSize.x < 1.0) return mat2x3(vec3(0.0), vec3(1.0));
    vec3 delta = end - origin;
    float ray_length = length(delta);
    if (ray_length < 0.0001) return mat2x3(vec3(0.0), vec3(1.0));
    vec3 direction = delta / ray_length;
    vec3 safe_dir = mix(vec3(0.00001), direction, greaterThan(abs(direction), vec3(0.00001)));
    vec3 near_plane = (afterlightFogOrigin-origin) / safe_dir;
    vec3 far_plane = (afterlightFogOrigin+afterlightFogSize-origin) / safe_dir;
    vec3 lower = min(near_plane,far_plane), upper = max(near_plane,far_plane);
    float begin = max(0.0,max(lower.x,max(lower.y,lower.z)));
    float finish = min(ray_length,min(upper.x,min(upper.y,upper.z)));
    if (finish <= begin) return mat2x3(vec3(0.0), vec3(1.0));
    // Deterministic midpoints: Photon's varying temporal dither modulated the
    // whole local fog layer (including black extinction) across thin fields.
    // At most one voxel per step, bounded independently of view distance.
    int steps = min(64,max(8,int(ceil(finish-begin))));
    float stride = (finish-begin)/float(steps);
    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    for (int i=0; i<64; ++i) {
        if (i >= steps) break;
        vec3 pos = origin + direction * (begin+(float(i)+0.5)*stride);
        vec4 mist = afterlight_mist(pos);
        if (mist.a < 0.0001) continue;
        float step_transmittance = exp(-mist.a*afterlight_extinction*stride);
        vec4 rgb = afterlight_sample(afterlightLightVolume, afterlightVolumeOrigin, afterlightVolumeSize, pos);
        if (afterlightVolumeBlend < 1.0) rgb = mix(afterlight_sample(afterlightPreviousVolume,
            afterlightPreviousOrigin,afterlightPreviousSize,pos),rgb,afterlightVolumeBlend);
        float vanilla = pow(clamp(mist.r,0.0,1.0),3.0);
        float coverage = clamp(pow(max(rgb.a,0.0),1.0/2.2)/max(mist.r,0.05),0.0,1.0);
        vec3 lighting = vec3(0.025,0.03,0.04) + ambient_color * (0.10+0.45*mist.g*mist.g);
        lighting += (vec3(1.0,0.55,0.25) * rec709_to_working_color) * vanilla * (1.0-coverage) * 3.0;
        lighting += (rgb.rgb * rec709_to_working_color) * (2.0/max(rgb.a,2.0)) * 4.0;
        scattered += transmittance*(1.0-step_transmittance)*lighting;
        transmittance *= step_transmittance;
    }
    scattered+=afterlight_integrate_spots(origin,direction,begin,finish);
    // Lasers retain their narrow analytic profile and separate glare shoulder;
    // spotlight radiance is now transported linearly, without a cone-fill cap.
    vec3 lasers=afterlight_laser_scattering(origin,direction,min(ray_length,finish));
    float peak=max(lasers.r,max(lasers.g,lasers.b));
    scattered+=lasers/(1.0+peak/0.65);
    scattered *= clamp01(1.0-blindness-darknessFactor);
    return mat2x3(scattered,vec3(transmittance));
}
#endif
