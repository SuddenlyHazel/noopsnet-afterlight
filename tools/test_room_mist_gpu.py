#!/usr/bin/env python3
"""Exercise shipped mist GLSL on a real offscreen GPU, without controlling Minecraft."""
import json
import math
from pathlib import Path
import moderngl
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/mist-validation';out.mkdir(parents=True,exist_ok=True)
ctx=moderngl.create_standalone_context(require=330,backend='egl')
vertex='''#version 330
out vec2 uv;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.-1.,0,1);}
'''
source=(ROOT/'shader/afterlight_fog.glsl').read_text().replace('#include "/include/lighting/afterlight.glsl"',(ROOT/'shader/afterlight.glsl').read_text()).replace('#include "/include/lighting/afterlight_spotlight.glsl"', (ROOT/'shader/afterlight_spotlight.glsl').read_text())
header='''#version 330
#define rec709_to_working_color mat3(1.)
#define BLOCKLIGHT_I 1.
#define clamp01(x) clamp(x,0.,1.)
uniform sampler3D colortex0;
uniform vec3 cameraPosition;
uniform vec3 ambient_color;
uniform float frameTimeCounter,blindness,darknessFactor;
'''
tail='''
in vec2 uv;out vec4 result;
uniform vec3 ray_end;
uniform float sample_phase;
uniform int mode;
void main(){
 if(mode==1){result=vec4(afterlight_spot_scattering(ray_end,normalize(ray_end)),1);return;}
 vec3 end=mode==2?vec3((uv.x*2.-1.)*15.,(uv.y*2.-1.)*8.,24.):ray_end;
 mat2x3 fog=raymarch_afterlight_mist(vec3(0),end,sample_phase);
 if(mode==2){vec3 background=vec3(.03,.035,.05);vec3 color=background*fog[1]+fog[0];result=vec4(pow(color/(1.+color),vec3(1./2.2)),1);}
 else result=vec4(fog[0],fog[1].x);
}
'''
p=ctx.program(vertex_shader=vertex,fragment_shader=header+source+tail);vao=ctx.vertex_array(p,[])
def uniform(name,value):
 if name in p:p[name]=value
n=32
fog=np.zeros((n,n,n,4),dtype='f4');fog[:,:,:,0]=.35
rgb=np.zeros_like(fog)
noise=ctx.texture3d((4,4,4),1,np.full((4,4,4),.5,dtype='f4').tobytes(),dtype='f4')
textures=[ctx.texture3d((n,n,n),4,dtype='f4') for _ in range(4)]
beam=ctx.texture((4,8),4,dtype='f4')
for i,t in enumerate(textures):
 t.filter=(moderngl.LINEAR,moderngl.LINEAR);t.repeat_x=t.repeat_y=t.repeat_z=False;t.use(i)
beam.use(4);noise.use(5)
for name,value in {'afterlightFogVolume':0,'afterlightFogPreviousVolume':1,'afterlightLightVolume':2,'afterlightPreviousVolume':3,'afterlightFogBeams':4,'colortex0':5}.items():uniform(name,value)
for name in ('afterlightFogOrigin','afterlightFogPreviousOrigin','afterlightVolumeOrigin','afterlightPreviousOrigin'):uniform(name,(-16.,-16.,-4.))
for name in ('afterlightFogSize','afterlightFogPreviousSize','afterlightVolumeSize','afterlightPreviousSize'):uniform(name,(n,n,n))
for name in ('afterlightFogBlend','afterlightVolumeBlend'):uniform(name,1.)
for name in ('cameraPosition','afterlightFogBeamOrigin','ambient_color'):uniform(name,(0.,0.,0.))
for name in ('frameTimeCounter','blindness','darknessFactor'):uniform(name,0.)
uniform('sample_phase',.5)
uniform('afterlightFogDensity',1.)
textures[2].write(rgb.tobytes());textures[3].write(rgb.tobytes())
beams=np.zeros((8,4,4),dtype='f4')
small=ctx.texture((1,1),4,dtype='f4');fb=ctx.framebuffer([small])
def upload():textures[0].write(fog.tobytes());textures[1].write(fog.tobytes());beam.write(beams.tobytes())
def sample(end=(0.,0.,20.),mode=0):
 uniform('ray_end',end);uniform('mode',mode);fb.use();ctx.viewport=(0,0,1,1);vao.render(vertices=3)
 a=np.frombuffer(small.read(),dtype='f4').copy();assert np.isfinite(a).all(),a;return a
uniform('afterlightFogBeamCount',0);upload();clear=sample();assert np.allclose(clear,[0,0,0,1]),clear
fog[:,:,:,3]=1;upload();mist=sample();assert .45<mist[3]<.55,mist
beams[0]=[[0,0,1,20],[0,0,1,.18],[1,0,0,.22],[0,0,0,0]]
uniform('afterlightFogBeamCount',1);upload();red=sample()
assert red[0]>mist[0]+.10 and np.allclose(red[1:3],mist[1:3]),red
lit=sample((0.,0.,12.),1)
# Independent point-sample reference: inverse-square source, Beer attenuation,
# normalized HG backscatter. This is radiance, not the old arbitrary gain.
expected_lit=(160/(1+11**2))*math.exp(-.035*11)*(.8775/(4*math.pi*(1.1225+.7)**1.5))
assert math.isclose(float(lit[0]),expected_lit,rel_tol=.001),(lit,expected_lit)
# Place an opaque partition across the stage beam. Surface depth is independent.
fog[12,:,:,2]=1;fog[12,:,:,3]=0;upload();shadow=sample((0.,0.,12.),1);assert np.allclose(shadow[:3],0),shadow
fog[12,:,:,2]=0;fog[12,:,:,3]=1
# A thin side-on cone used to flicker when Photon's temporal dither moved
# the bulk fog samples in/out of it. Hold camera, density and light constant.
beams[0]=[[-1,0,10,2],[1,0,0,.18],[1,0,0,.22],[0,0,0,0]];upload()
held=[]
for phase in np.linspace(.01,.99,33):
 uniform('sample_phase',float(phase));held.append(float(sample()[0]))
spot_dither_range=max(held)-min(held)
print('Held spotlight temporal range:',spot_dither_range,flush=True)
assert spot_dither_range<.0001,held
uniform('sample_phase',.5)
motion=[float(sample((x,0.,20.))[0]) for x in np.linspace(-2,2,201)]
assert max(abs(a-b) for a,b in zip(motion,motion[1:]))<.03,motion
short=sample((0.,0.,8.));uniform('afterlightFogBeamCount',0);short_base=sample((0.,0.,8.));uniform('afterlightFogBeamCount',1)
assert np.allclose(short,short_base,atol=1e-5),(short,short_base)
beams[0]=[[-3,0,10,6],[1,0,0,.055],[0,1,0,.0012],[1,0,0,0]];upload()
laser=sample();uniform('afterlightFogBeamCount',0);base=sample();uniform('afterlightFogBeamCount',1)
assert laser[1]>base[1]+.25,(laser,base)
blocked=sample((0.,0.,7.));uniform('afterlightFogBeamCount',0);blocked_base=sample((0.,0.,7.));uniform('afterlightFogBeamCount',1)
assert np.allclose(blocked,blocked_base,atol=1e-5),(blocked,blocked_base)
profile=[float(sample((0.,y*2,20.))[1]) for y in (0,.01,.02,.03,.04,.05)]
assert all(a>b for a,b in zip(profile,profile[1:])),profile
zero=sample((0.,0.,0.));assert np.allclose(zero,[0,0,0,1]),zero
parallel=sample((20.,0.,0.))
# Full-power overlapping beams looking directly down the camera must stay
# bounded instead of flashing white. Scalar compression also preserves hue.
beams[:]=[[0,0,1,20],[0,0,1,.055],[1,1,1,.0012],[1,0,0,0]]
uniform('afterlightFogBeamCount',8);upload();glare=sample()
assert np.all(glare[:3]-base[:3]<=.65001),glare
beams[:,2,:3]=[.9,.3,.1];upload();tinted=sample()[:3]-base[:3]
assert np.allclose(tinted/tinted[2],[9,3,1],rtol=.001),tinted
# A laser crossing the camera used to add the same colored overlay to every
# direction, even looking away. Its near-eye contribution must be zero.
beams[:]=0;beams[0]=[[-10,0,0,20],[1,0,0,.055],[1,0,0,.0012],[1,0,0,0]]
upload();uniform('afterlightFogBeamCount',1)
camera_laser=[]
for end in ((0,0,20),(0,0,-3),(0,15,0),(20,0,0)):
 uniform('afterlightFogBeamCount',1);on=sample(end)
 uniform('afterlightFogBeamCount',0);off=sample(end)
 camera_laser.append(float(np.max(np.abs(on-off))))
assert max(camera_laser)<1e-5,camera_laser
# Static colored fog and a thin density boundary must not pulse with the
# atmospheric sampling phase, including the transmittance (black flashes).
fog[:]=0;rgb[:]=0;fog[4:8,:,:,3]=1.2;rgb[4:7,:,:,0]=1;rgb[4:7,:,:,3]=1
textures[2].write(rgb.tobytes());textures[3].write(rgb.tobytes());upload()
held=[]
for phase in np.linspace(.01,.99,33):
 uniform('sample_phase',float(phase));held.append(sample((0,0,23.3)))
fog_dither_range=np.ptp(held,axis=0)
assert np.max(fog_dither_range)<1e-5,fog_dither_range
uniform('sample_phase',.5);fog[:]=0;fog[:,:,:,0]=.35;fog[:,:,:,3]=1;rgb[:]=0
textures[2].write(rgb.tobytes());textures[3].write(rgb.tobytes())
# Visual/performance fixture with three crossing spotlights and two lasers.
beams[:]=0
beams[0]=[[-5,-2,4,23],[.35,.12,.929,.18],[1,.025,.08,.22],[0,0,0,0]]
beams[1]=[[5,-2,4,23],[-.35,.12,.929,.18],[.03,.12,1,.22],[0,0,0,0]]
beams[2]=[[0,3,4,20],[0,-.2,.9798,.18],[.04,1,.14,.22],[0,0,0,0]]
beams[3]=[[-7,0,8,14],[1,0,0,.055],[1,.025,.025,.0012],[1,0,0,0]]
beams[4]=[[7,2,8,14],[-.98,-.1,.1732,.055],[.02,.3,1,.0012],[1,0,0,0]]
uniform('afterlightFogBeamCount',5);upload();uniform('mode',2)
# Exercise both transition textures: active shows usually interpolate snapshots.
uniform('afterlightFogBlend',.5);uniform('afterlightVolumeBlend',.5)
image=ctx.texture((640,360),4,dtype='f4');large=ctx.framebuffer([image]);large.use();ctx.viewport=(0,0,640,360)
vao.render(vertices=3);ctx.finish()
times=[]
for _ in range(3):
 with ctx.query(time=True) as query:vao.render(vertices=3)
 ctx.finish();times.append(query.elapsed/1e6)
a=np.frombuffer(image.read(),dtype='f4').reshape(360,640,4);assert np.isfinite(a).all()
Image.fromarray((np.flipud(a[:,:,:3]).clip(0,1)*255).astype('uint8')).save(out/'mist-beams.png')
report={'gpu':ctx.info['GL_RENDERER'],'clear':clear.tolist(),'mist':mist.tolist(),'red_spotlight':red.tolist(),
 'held_spotlight_dither_range':spot_dither_range,'spotlight_foreground_occlusion':bool(np.allclose(short,short_base)),
 'held_colored_fog_dither_range':fog_dither_range.tolist(),'camera_crossing_laser_overlay':camera_laser,
 'eight_head_on_beams':glare.tolist(),'beam_hue_preserved':True,'max_beam_radiance':.65,
 'partition_shadow':shadow.tolist(),'laser':laser.tolist(),'laser_profile':profile,
 'foreground_occlusion':bool(np.allclose(blocked,blocked_base)),'zero_ray':zero.tolist(),
 'parallel_ray_finite':bool(np.isfinite(parallel).all()),'640x360_five_beams_gpu_ms':times,'preview':'mist-beams.png'}
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
