#!/usr/bin/env python3
"""Offscreen driver regression for stationary dim/white light and volume blending."""
import json
import moderngl
import numpy as np
from build_shader import ROOT

ctx=moderngl.create_standalone_context(require=330,backend='egl')
bridge=(ROOT/'shader/afterlight.glsl').read_text()
p=ctx.program(vertex_shader='''#version 330
void main(){vec2 xy=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(xy*2.-1.,0,1);}''',
fragment_shader='#version 330\n#define rec709_to_working_color mat3(1.0)\n#define BLOCKLIGHT_I 1.0\n'+bridge+'''
uniform vec3 original;uniform vec3 point;uniform float vanilla_level;out vec4 result;
void main(){result=vec4(afterlight_blocklight(point,vec3(0),original,vanilla_level),1);}''')
textures=[ctx.texture3d((16,16,16),4,dtype='f4') for _ in range(2)]
for unit,t in enumerate(textures):t.filter=(moderngl.LINEAR,moderngl.LINEAR);t.use(unit)
p['afterlightLightVolume']=0;p['afterlightPreviousVolume']=1
p['afterlightVolumeSize']=(16,16,16);p['afterlightPreviousSize']=(16,16,16)
p['afterlightVolumeOrigin']=(0,0,0);p['afterlightPreviousOrigin']=(0,0,0)
p['point']=(8,8,8);p['vanilla_level']=.8
target=ctx.texture((1,1),4,dtype='f4');fb=ctx.framebuffer([target]);fb.use();vao=ctx.vertex_array(p,[])
def volume(rgb):
    v=np.ones((16,16,16,4),dtype='f4');v[:,:,:,:3]=rgb;return v
def sample():
    vao.render(vertices=3);return np.frombuffer(target.read(),dtype='f4')[:3].copy()
spans={}
for name,rgb in [('dim_grey',[.012,.012,.012]),('white',[1.,1.,1.]),('dim_rose',[.08,.008,.03])]:
    data=volume(rgb).tobytes();textures[0].write(data);textures[1].write(data);p['afterlightVolumeBlend']=1
    frames=[]
    for frame in range(120):
        # Repeated identical uploads plus deliberately varying vanilla brightness.
        if frame%15==0:textures[0].write(data)
        p['original']=(.1+frame%17/8,)*3;frames.append(sample())
    span=float(np.ptp(np.asarray(frames),axis=0).max());assert span<1e-6,(name,span)
    spans[name]=span
textures[1].write(volume([1,1,1]).tobytes());textures[0].write(volume([.012,.012,.012]).tobytes())
p['original']=(.3,.3,.3)
frames=[]
for blend in np.linspace(0,1,61):p['afterlightVolumeBlend']=float(blend);frames.append(sample())
deltas=np.diff(np.asarray(frames),axis=0)
assert np.all(deltas<=1e-5) and np.max(np.abs(deltas))<.11,'Blend must be smooth and monotonic'
# A static green source must keep exactly the same contribution while a nearby
# equal-reach source cross-fades between red and blue.
textures[1].write(volume([.4,.2,0]).tobytes());textures[0].write(volume([0,.2,.4]).tobytes())
frames=[]
for blend in np.linspace(0,1,61):p['afterlightVolumeBlend']=float(blend);frames.append(sample())
static_span=float(np.ptp(np.asarray(frames)[:,1]))
assert static_span<1e-6,'An animated neighbour must not modulate the static source'
# Different texture origins must still address the same world-space illumination.
for index,origin in enumerate((4,0)):
    v=volume([0,0,0]);v[:,:,:,0]=(np.arange(16,dtype='f4')+.5+origin)[None,None,:]/24
    textures[index].write(v.tobytes())
p['afterlightVolumeOrigin']=(4,0,0);p['afterlightPreviousOrigin']=(0,0,0)
frames=[]
for blend in np.linspace(0,1,61):p['afterlightVolumeBlend']=float(blend);frames.append(sample())
assert float(np.ptp(np.asarray(frames),axis=0).max())<1e-5,'Rebasing must preserve world-space light'
# Exercise the actual production RGBA16F format at the old low-energy cutoff.
# The cutoff used to cause a 30% white-light jump even though alpha barely moved.
half=ctx.texture3d((16,16,16),4,dtype='f2');half.filter=(moderngl.LINEAR,moderngl.LINEAR)
half.repeat_x=half.repeat_y=half.repeat_z=False;half.use(0)
p['afterlightVolumeOrigin']=(0,0,0);p['afterlightVolumeBlend']=1
p['original']=(1,1,1);p['vanilla_level']=.05
levels=[0,float(np.nextafter(np.float16(0),np.float16(1))),.000099,.000101,1]
samples=[]
for energy in levels:
    v=np.zeros((16,16,16,4),dtype='f2');v[:,:,:,3]=energy;half.write(v.tobytes());samples.append(sample())
assert np.isfinite(samples).all(),'Zero and minimum half-float energy must stay finite'
assert np.max(np.abs(samples[0]-samples[1]))<.012,'Minimum half-float coverage remains bounded'
cutoff_step=float(np.max(np.abs(samples[2]-samples[3])))
assert cutoff_step<.004,('Low-energy coverage must be continuous',cutoff_step)
# A constant field crossing the volume's edge fade continuously covers zero and
# sub-half-float energy too: its alpha is attenuated in full-precision GLSL.
frames=[]
for x in np.linspace(.5,.54,129):
    p['point']=(float(x),8,8);frames.append(sample())
edge_step=float(np.max(np.abs(np.diff(np.asarray(frames),axis=0))))
assert edge_step<.012,('Volume-edge fade must not pop',edge_step)
assert np.all(np.diff(np.asarray(frames),axis=0)<=1e-5),'Edge coverage must stay monotonic'
report={'renderer':ctx.info['GL_RENDERER'],'stationary_frame_span':spans,'repeated_frames':120,'smooth_blend':'passed','world_space_rebase':'passed','static_source_span_during_live_fade':static_span,'half_float_cutoff_step':cutoff_step,'volume_edge_max_step':edge_step,'passed':True}
out=ROOT/'build/lighting-fixture/temporal-result.json';out.parent.mkdir(exist_ok=True,parents=True);out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
