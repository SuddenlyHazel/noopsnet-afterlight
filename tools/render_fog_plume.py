#!/usr/bin/env python3
"""Render actual Java mist snapshots through the shipped fog shader on offscreen EGL.

Synthetic scene only; this never starts or controls Minecraft.
"""
import ast
import json
from pathlib import Path
import re

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/fog-plume-preview'
meta = json.loads((OUT / 'snapshots.json').read_text())
# Reuse the existing synthetic receiver/camera shader, not a second fog implementation.
probe = ast.parse((ROOT / 'tools/test_spotlight_transport_gpu.py').read_text())
strings = {node.targets[0].id: ast.literal_eval(node.value) for node in probe.body
           if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
           and node.targets[0].id in ('vertex', 'header', 'tail')}
seen = set()
def source(name):
    if name in seen:
        return ''
    seen.add(name)
    text = (ROOT / 'shader' / name).read_text()
    return re.sub(r'^\s*#include\s+"([^"]+)"\s*$',
                  lambda match: source(Path(match[1]).name), text, flags=re.M)
ctx = moderngl.create_standalone_context(require=330, backend='egl')
program = ctx.program(vertex_shader=strings['vertex'], fragment_shader=strings['header']
                      + source('afterlight_spotlight.glsl') + source('afterlight_fog.glsl') + strings['tail'])
vao = ctx.vertex_array(program, [])
n = meta['size']
zero = np.zeros((n,n,n,4), dtype='f4')
textures = [ctx.texture3d((n,n,n),4,zero.tobytes(),dtype='f4') for _ in range(4)]
for i, texture in enumerate(textures):
    texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
    texture.repeat_x = texture.repeat_y = texture.repeat_z = False
    texture.use(i)
beams = np.zeros((8,4,4), dtype='f4')
for i, (origin, target, color) in enumerate([
        ((-6,3,0),(4,-1,12),(1,.25,.04)), ((5,3,0),(-4,-1,12),(.05,.25,1))]):
    direction = np.array(target,dtype='f4')-origin
    direction /= np.linalg.norm(direction)
    beams[i] = [(*origin,24), (*direction,.18), (*color,.22), (0,0,0,0)]
beam_texture = ctx.texture((4,8),4,beams.tobytes(),dtype='f4');beam_texture.use(4)
noise_values = np.random.default_rng(177).uniform(0,1,(8,8,8)).astype('f4')
noise = ctx.texture3d((8,8,8),1,noise_values.tobytes(),dtype='f4')
noise.filter = (moderngl.LINEAR,moderngl.LINEAR);noise.use(5)
def uniform(name,value):
    if name in program:
        program[name] = value
for name,value in {'afterlightFogVolume':0,'afterlightFogPreviousVolume':1,
        'afterlightLightVolume':2,'afterlightPreviousVolume':3,'afterlightFogBeams':4,
        'colortex0':5,'afterlightFogBlend':1.,'afterlightVolumeBlend':1.,
        'afterlightFogDensity':1.,'afterlightFogBeamCount':2,'image_exposure':4.,
        'image_pillar':0,'image_front_wall':0,'probe_mode':5,'sample_phase':.5,
        'blindness':0.,'darknessFactor':0.}.items():
    uniform(name,value)
for name in ('afterlightFogOrigin','afterlightFogPreviousOrigin','afterlightVolumeOrigin','afterlightPreviousOrigin'):
    uniform(name,tuple(meta['origin']))
for name in ('afterlightFogSize','afterlightFogPreviousSize','afterlightVolumeSize','afterlightPreviousSize'):
    uniform(name,(n,n,n))
uniform('cameraPosition',(0.,0.,0.));uniform('afterlightFogBeamOrigin',(0.,0.,0.))
uniform('ambient_color',(.15,.18,.22))
camera = np.array((0.,5.,21.));forward = np.array((0.,-1.,6.))-camera;forward /= np.linalg.norm(forward)
right=np.cross(forward,(0.,1.,0.));right/=np.linalg.norm(right);up=np.cross(right,forward)
for name,value in [('image_camera',camera),('image_forward',forward),('image_right',right),('image_up',up)]:
    uniform(name,tuple(value))
w,h=640,360
rendered=ctx.texture((w,h),4,dtype='f4');frame=ctx.framebuffer([rendered]);frame.use()
uniform('image_size',(w,h))
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',18)
images=[]
for snapshot in meta['snapshots']:
    data=np.fromfile(OUT/snapshot['file'],dtype='<f4').reshape(n,n,n,4)
    textures[0].write(data.tobytes());textures[1].write(data.tobytes())
    uniform('frameTimeCounter',snapshot['seconds'])
    vao.render(vertices=3)
    pixels=np.frombuffer(frame.read(components=4,dtype='f4'),dtype='f4').reshape(h,w,4)
    assert np.isfinite(pixels).all() and (pixels>=0).all()
    image=Image.fromarray((np.flipud(pixels[:,:,:3]).clip(0,1)*255).astype('uint8'))
    caption=Image.new('RGB',(w,h+60),'#10141b');caption.paste(image,(0,60))
    draw=ImageDraw.Draw(caption);draw.text((16,8),f"{snapshot['seconds']:g} seconds - continuous output",font=font,fill='white')
    draw.text((16,33),'Synthetic GPU render / actual Java mist field / fixed exposure',font=font,fill='#afc0c7')
    caption.save(OUT/f"mist-{snapshot['seconds']:g}s.png");images.append(caption)
    # Top-down density is useful for reading the source-to-room front unambiguously.
    plane=data[:,14,:,3]
    plane=(1-np.exp(-plane*.45))
    rgb=np.stack((plane*.50,plane*.85,plane),axis=-1)
    Image.fromarray((rgb.clip(0,1)*255).astype('uint8')).resize((512,512),Image.Resampling.BILINEAR).save(OUT/f"density-{snapshot['seconds']:g}s.png")
contact=Image.new('RGB',(w*3,(h+60)*2),'#10141b')
for index,image in enumerate(images):contact.paste(image,((index%3)*w,(index//3)*(h+60)))
contact.save(OUT/'progression.png')
print(OUT/'progression.png')
