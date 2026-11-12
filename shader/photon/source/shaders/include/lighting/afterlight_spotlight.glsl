#ifndef AFTERLIGHT_SPOTLIGHT_TRANSPORT
#define AFTERLIGHT_SPOTLIGHT_TRANSPORT

uniform sampler3D afterlightFogVolume;
uniform sampler3D afterlightFogPreviousVolume;
uniform sampler2D afterlightFogBeams;
uniform vec3 afterlightFogOrigin, afterlightFogPreviousOrigin;
uniform vec3 afterlightFogSize, afterlightFogPreviousSize;
uniform vec3 afterlightFogBeamOrigin;
uniform float afterlightFogBlend;
uniform float afterlightFogDensity;
uniform int afterlightFogBeamCount;

// Density is an artist-controlled room field. These coefficients describe
// ordinary stage haze; extinction applies on BOTH legs of the light path.
const float afterlight_extinction = 0.035;
const float afterlight_scattering_albedo = 0.85;
// The accepted stage source was calibrated in diffuse-response units. Convert
// those units to incident radiance before either surface BRDF or fog uses it.
// Color is linear Rec.709 in the beam texture and is converted exactly once,
// below, to Photon's linear working space.
const float afterlight_spot_power = 160.0 * pi * AFTERLIGHT_STAGE_STRENGTH;

bool afterlight_inside(vec3 cell, vec3 size) {
    return size.x > 0.0 && all(greaterThanEqual(cell, vec3(0.0))) && all(lessThan(cell, size));
}
float afterlight_previous_density(vec3 position) {
    vec3 cell = position-afterlightFogPreviousOrigin;
    return afterlight_inside(cell,afterlightFogPreviousSize)
        ? max(0.0,texture(afterlightFogPreviousVolume,cell/afterlightFogPreviousSize).a) : 0.0;
}
vec4 afterlight_medium(vec3 position) {
    vec3 cell=position-afterlightFogOrigin;
    if(!afterlight_inside(cell,afterlightFogSize))return vec4(0.0);
    // Current geometry is authoritative. Never blend a closed wall into air.
    if(texelFetch(afterlightFogVolume,ivec3(cell),0).b>0.5)return vec4(0.0);
    vec4 medium=texture(afterlightFogVolume,cell/afterlightFogSize);
#ifdef AFTERLIGHT_FORWARD_CURRENT_ONLY
    medium.a=max(medium.a,0.0);
#else
    medium.a=mix(afterlight_previous_density(position),max(medium.a,0.0),afterlightFogBlend);
#endif
    vec3 edge=min(cell,afterlightFogSize-cell);
    medium.a*=smoothstep(0.0,2.0,min(edge.x,min(edge.y,edge.z)))*afterlightFogDensity;
    return medium;
}

// Amanatides-Woo voxel traversal: every crossed cell contributes its actual
// path length. Half-block stepping could skip grazing walls or thin shadows.
// Callers bias surface receivers into air along the GEOMETRIC normal.
float afterlight_light_transmission(vec3 start, vec3 end) {
    vec3 delta=end-start;
    float distance=length(delta);
    if(distance<0.0001)return 1.0;
    vec3 direction=delta/distance;
    vec3 cell_position=start-afterlightFogOrigin;
    if(!afterlight_inside(cell_position,afterlightFogSize))return 0.0;
    ivec3 cell=ivec3(floor(cell_position));
    ivec3 step_dir=ivec3(sign(direction));
    vec3 step_length=1.0/max(abs(direction),vec3(1e-8));
    vec3 boundary=vec3(cell)+vec3(greaterThan(direction,vec3(0.0)));
    vec3 next_distance=abs(boundary-cell_position)*step_length;
    next_distance=mix(vec3(1e20),next_distance,greaterThan(abs(direction),vec3(1e-8)));
    float traveled=0.0,optical_depth=0.0;
    for(int step=0;step<128;++step) {
        if(any(lessThan(cell,ivec3(0)))||any(greaterThanEqual(cell,ivec3(afterlightFogSize))))return 0.0;
        float next=min(distance,min(next_distance.x,min(next_distance.y,next_distance.z)));
        float segment=max(0.0,next-traveled);
        if(segment>0.00001) {
            vec4 voxel=texelFetch(afterlightFogVolume,cell,0);
            if(voxel.b>0.5)return 0.0;
#ifdef AFTERLIGHT_FORWARD_CURRENT_ONLY
            float density=max(voxel.a,0.0);
#else
            vec3 midpoint=start+direction*(traveled+segment*0.5);
            float density=mix(afterlight_previous_density(midpoint),max(voxel.a,0.0),afterlightFogBlend);
#endif
            optical_depth+=density*segment*afterlightFogDensity;
        }
        if(next>=distance-0.00001)return exp(-afterlight_extinction*optical_depth);
        bvec3 crossed=lessThanEqual(next_distance,vec3(next+0.00001));
        cell+=ivec3(crossed)*step_dir;
        next_distance+=vec3(crossed)*step_length;
        traveled=next;
    }
    return 0.0; // Conservative bounded fallback, never unshadowed leakage.
}

vec3 afterlight_spot_radiance(vec3 position,vec4 start,vec4 axis,vec4 color,out vec3 to_light) {
    vec3 delta=position-(start.xyz+afterlightFogBeamOrigin);
    float distance_sq=dot(delta,delta);
    to_light=-delta*inversesqrt(max(distance_sq,0.0001));
    float along=dot(delta,axis.xyz);
    if(start.w<=0.0001||along<=0.0||along>=start.w)return vec3(0.0);
    float radius=max(0.05,axis.w+along*color.w);
    float radial=length(delta-axis.xyz*along)/radius;
    if(radial>=1.0)return vec3(0.0);
    // Continuous beam profile, including the center, instead of a flat fill.
    float angular=exp(-3.5*radial*radial)*(1.0-smoothstep(0.8,1.0,radial));
    // The finite aperture has a nonzero radius. Without a near-cap taper,
    // crossing its plane switches a whole side-on shaft on in one frame.
    float range_fade=smoothstep(0.0,min(0.5,start.w*0.25),along)
        *smoothstep(0.0,min(2.0,start.w*0.25),start.w-along);
    return (color.rgb*rec709_to_working_color)*afterlight_spot_power*angular*range_fade/(1.0+distance_sq);
}
vec3 afterlight_spot_incident(int beam,vec3 position,out vec3 to_light) {
    vec4 start=texelFetch(afterlightFogBeams,ivec2(0,beam),0);
    vec4 axis=texelFetch(afterlightFogBeams,ivec2(1,beam),0);
    vec4 color=texelFetch(afterlightFogBeams,ivec2(2,beam),0);
    vec3 incident=afterlight_spot_radiance(position,start,axis,color,to_light);
    if(max(incident.r,max(incident.g,incident.b))<=0.0)return vec3(0.0);
    return incident*afterlight_light_transmission(start.xyz+afterlightFogBeamOrigin,position);
}

// Incident irradiance: Photon's material path applies albedo / pi afterwards.
vec3 afterlight_spot_irradiance(vec3 position,vec3 normal) {
    vec3 light=vec3(0.0);
    for(int i=0;i<8;++i) {
        if(i>=afterlightFogBeamCount)break;
        if(texelFetch(afterlightFogBeams,ivec2(3,i),0).x>0.5)continue;
        vec4 start=texelFetch(afterlightFogBeams,ivec2(0,i),0);
        vec4 axis=texelFetch(afterlightFogBeams,ivec2(1,i),0);
        vec4 color=texelFetch(afterlightFogBeams,ivec2(2,i),0);
        vec3 to_light;
        vec3 incident=afterlight_spot_radiance(position,start,axis,color,to_light);
        float cosine=max(dot(normal,to_light),0.0);
        if(cosine<=0.0||max(incident.r,max(incident.g,incident.b))<=0.0)continue;
        light+=incident*cosine*afterlight_light_transmission(start.xyz+afterlightFogBeamOrigin,position);
    }
    return light;
}
float afterlight_phase(float cosine) {
    // Normalized Henyey-Greenstein phase, shared convention: camera ray points
    // toward the sample, to_light points from the sample toward the fixture.
    const float g=0.35;
    return (1.0-g*g)/(12.5663706144*pow(max(0.001,1.0+g*g-2.0*g*cosine),1.5));
}
#endif
