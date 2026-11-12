#!/usr/bin/env python3
"""Execute Photon's actual LabPBR decoder on exported Java sign material bytes."""
import json
import re
from zipfile import ZipFile
import moderngl
import numpy as np
from build_shader import ROOT, LOCK

ctx=moderngl.create_standalone_context(require=330,backend='egl')
with ZipFile(ROOT/'build/shaderpacks'/LOCK['output']) as z:
    source=z.read('shaders/include/surface/material.glsl').decode()
    settings=z.read('shaders/settings.glsl').decode()
struct=source[source.index('struct Material {'):source.index('const Material water_material')]
decode=source[source.index('void decode_specular_map(vec4 specular_map, inout Material material)'):source.index('#elif TEXTURE_FORMAT == TEXTURE_FORMAT_OLD')]
afterlight=source[source.index('const uint afterlight_material_mask'):source.index('#if TEXTURE_FORMAT == TEXTURE_FORMAT_LAB')]
neon_strength=float(re.search(r'^\s*#define AFTERLIGHT_NEON_STRENGTH ([\d.]+)',settings,re.M)[1])
emission_strength=float(re.search(r'^\s*#define EMISSION_STRENGTH ([\d.]+)',settings,re.M)[1])
helpers='''
#define SSR_ROUGHNESS_THRESHOLD 1.0
float sqr(float x){return x*x;}
float max0(float x){return max(x,0.0);}
float linear_step(float a,float b,float x){return clamp((x-a)/(b-a),0.,1.);}
'''+f'\n#define AFTERLIGHT_NEON_STRENGTH {neon_strength}\n#define EMISSION_STRENGTH {emission_strength}\n'
p=ctx.program(vertex_shader='''#version 330
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}''',
fragment_shader='#version 330\n'+helpers+struct+afterlight+decode+'''
uniform sampler2D albedo;uniform sampler2D specular;out vec4 result;
void main(){ivec2 pos=ivec2(gl_FragCoord.xy);vec4 pixel=texelFetch(albedo,pos,0);
 Material m;m.albedo=pow(pixel.rgb,vec3(2.2));m.emission=vec3(0);m.afterlight_emission=vec3(0);
 m.is_afterlight=true;m.is_metal=false;m.is_hardcoded_metal=false;m.f0=vec3(.02);m.sss_amount=0.;
 decode_specular_map(texelFetch(specular,pos,0),m);
 result=vec4(pixel.a>.5?m.emission+m.afterlight_emission:vec3(0),1);}
''')
fixture=ROOT/'build/art-material-fixture'
albedo=ctx.texture((32,32),4,(fixture/'albedo.rgba8').read_bytes());albedo.use(0);p['albedo']=0;p['specular']=1
target=ctx.texture((32,32),4,dtype='f4');fb=ctx.framebuffer([target]);fb.use();vao=ctx.vertex_array(p,[])
samples={}
for state in ('lit','off'):
    spec=ctx.texture((32,32),4,(fixture/(state+'-specular.rgba8')).read_bytes());spec.use(1)
    vao.render(vertices=3);pixels=np.frombuffer(target.read(),dtype='f4').reshape(32,32,4)
    samples[state]=pixels[0,:6,:3].tolist()
    if state=='lit':
        for i in range(3):assert pixels[0,i,i]>30 and sum(pixels[0,i,:3])-pixels[0,i,i]<1e-5
        assert np.min(pixels[0,3,:3])>30,'White art must emit HDR radiance'
        assert np.min(pixels[0,4,:3])>0,'Dim colours must retain emission'
        assert np.max(pixels[0,5,:3])==0,'Transparent pixels must remain dark'
    else:assert np.max(pixels[:,:,:3])==0,'Redstone blackout must remove emission'
profile_radiance=[]
for glow in range(5):
    spec=ctx.texture((32,32),4,(fixture/f'profile-{glow}-specular.rgba8').read_bytes());spec.use(1)
    vao.render(vertices=3);pixels=np.frombuffer(target.read(),dtype='f4').reshape(32,32,4)
    profile_radiance.append(float(pixels[0,3,0]))
assert profile_radiance[0]==0 and profile_radiance[4]>30
for glow in range(5):
    alpha=(fixture/f'profile-{glow}-specular.rgba8').read_bytes()[3*4+3]/255
    expected=alpha*(1+2*alpha)*neon_strength*emission_strength
    assert abs(profile_radiance[glow]-expected)<1e-4
report={'renderer':ctx.info['GL_RENDERER'],'material_source':'Java SignArtMaterial exported RGBA bytes','shader_source':'Vendored Photon/Afterlight decode_specular_map, exact source from built preset','rgb_hdr_probes':samples,'glow_profile_white_hdr':profile_radiance,'passed':True}
(fixture/'gpu-result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
