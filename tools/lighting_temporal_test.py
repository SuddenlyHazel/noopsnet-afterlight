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
uniform vec3 original;out vec4 result;
void main(){result=vec4(afterlight_blocklight(vec3(8),vec3(0),original,.8),1);}''')
textures=[ctx.texture3d((16,16,16),4,dtype='f4') for _ in range(2)]
for unit,t in enumerate(textures):t.filter=(moderngl.LINEAR,moderngl.LINEAR);t.use(unit)
p['afterlightLightVolume']=0;p['afterlightPreviousVolume']=1
p['afterlightVolumeSize']=(16,16,16);p['afterlightPreviousSize']=(16,16,16)
p['afterlightVolumeOrigin']=(0,0,0);p['afterlightPreviousOrigin']=(0,0,0)
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
# Different texture origins must still address the same world-space illumination.
for index,origin in enumerate((4,0)):
    v=volume([0,0,0]);v[:,:,:,0]=(np.arange(16,dtype='f4')+.5+origin)[None,None,:]/24
    textures[index].write(v.tobytes())
p['afterlightVolumeOrigin']=(4,0,0);p['afterlightPreviousOrigin']=(0,0,0)
frames=[]
for blend in np.linspace(0,1,61):p['afterlightVolumeBlend']=float(blend);frames.append(sample())
assert float(np.ptp(np.asarray(frames),axis=0).max())<1e-5,'Rebasing must preserve world-space light'
report={'renderer':ctx.info['GL_RENDERER'],'stationary_frame_span':spans,'repeated_frames':120,'smooth_blend':'passed','world_space_rebase':'passed','passed':True}
out=ROOT/'build/lighting-fixture/temporal-result.json';out.parent.mkdir(exist_ok=True,parents=True);out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
