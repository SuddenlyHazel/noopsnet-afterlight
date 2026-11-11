#!/usr/bin/env python3
"""Standalone OpenGL verification and native-model room study. Never starts Minecraft.

Requires moderngl, numpy and Pillow. Reads the real Java engine's test export and
executes the shipped afterlight.glsl against its 3D texture on an EGL context.
The room is an offline study, not a Photon/Minecraft gameplay screenshot.
"""
import json
import math
from pathlib import Path
import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from build_shader import ROOT

RES=ROOT/'src/main/resources/assets/noopsnet_neon'
OUT=ROOT/'preview'


def main():
    ctx=moderngl.create_standalone_context(require=330,backend='egl')
    meta=json.loads((ROOT/'build/lighting-fixture/volume.json').read_text())
    raw=(ROOT/'build/lighting-fixture/volume.rgba32f').read_bytes()
    volume=ctx.texture3d((48,48,48),4,raw,dtype='f4')
    volume.filter=(moderngl.LINEAR,moderngl.LINEAR)
    volume.repeat_x=volume.repeat_y=volume.repeat_z=False
    volume.use(2)
    bridge=(ROOT/'shader/afterlight.glsl').read_text()
    # The harness works in linear Rec.709; Photon supplies its Rec.2020 matrix.
    common='#define rec709_to_working_color mat3(1.0)\n#define luminance_weights vec3(.2126,.7152,.0722)\n#define BLOCKLIGHT_I 1.0\n'+bridge
    probe=ctx.program(vertex_shader='''#version 330
    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}''',
        fragment_shader='#version 330\n'+common+'''
        uniform vec3 point;out vec4 result;
        void main(){result=vec4(afterlight_blocklight(point,vec3(0),vec3(.1),.9),1);}''')
    def uniforms(p):
        p['afterlightPreviousVolume']=2;p['afterlightPreviousOrigin']=(0,0,0);p['afterlightPreviousSize']=(0,0,0);p['afterlightVolumeBlend']=1.0
        p['afterlightLightVolume']=2;p['afterlightVolumeOrigin']=tuple(meta['origin']);p['afterlightVolumeSize']=(48,48,48)
    uniforms(probe)
    target=ctx.texture((1,1),4,dtype='f4');fb=ctx.framebuffer([target]);fb.use()
    vao=ctx.vertex_array(probe,[])
    samples={}
    for name,point in [('rose',(-3,3,2)),('sea_glass',(3,3,2)),('unlit',(19,3,2))]:
        probe['point']=point;vao.render(vertices=3)
        samples[name]=np.frombuffer(target.read(),dtype=np.float32)[:3].tolist()
    assert samples['rose'][0]>samples['rose'][2] and samples['rose'][2]>samples['rose'][1]*2,samples
    assert samples['sea_glass'][2]>samples['sea_glass'][0]*1.4,samples
    assert max(samples['unlit'])-min(samples['unlit'])<1e-5,samples
    # Render actual cuboid geometry and actual PNGs, with the same volume lookup.
    program=ctx.program(vertex_shader='''#version 330
    in vec3 position;in vec3 normal;in vec2 uv;uniform mat4 mvp;
    out vec3 world;out vec3 face_normal;out vec2 texcoord;
    void main(){world=position;face_normal=normal;texcoord=uv;gl_Position=mvp*vec4(position,1);}''',
        fragment_shader='#version 330\n'+common+'''
    uniform sampler2D albedo;uniform sampler2D specular;uniform vec3 tint;uniform float emission;
    in vec3 world;in vec3 face_normal;in vec2 texcoord;out vec4 result;
    void main(){vec4 tex=texture(albedo,texcoord);if(tex.a<.1)discard;
        vec3 base=pow(tex.rgb*tint,vec3(2.2));
        vec3 light=afterlight_blocklight(world,face_normal,vec3(.09),.8);
        float glow=texture(specular,texcoord).a*emission;
        result=vec4(base*(light+vec3(.035,.028,.021)+glow),1);}''')
    uniforms(program);program['albedo']=0;program['specular']=1
    width,height=1600,1040
    hdr=ctx.texture((width,height),4,dtype='f4')
    depth=ctx.depth_renderbuffer((width,height));fb=ctx.framebuffer([hdr],depth);fb.use();fb.clear(.012,.009,.02,1,depth=1)
    ctx.enable(moderngl.DEPTH_TEST)
    eye=np.array([8.,6.,-12.]);aim=np.array([0.,2.,1.]);f=(aim-eye);f/=np.linalg.norm(f)
    r=np.cross(f,[0,1,0]);r/=np.linalg.norm(r);u=np.cross(r,f)
    view=np.eye(4);view[:3,:3]=np.array([r,u,-f]);view[:3,3]=-view[:3,:3]@eye
    a=width/height;q=1/math.tan(math.radians(48)/2);near,far=.1,100
    proj=np.array([[q/a,0,0,0],[0,q,0,0],[0,0,(far+near)/(near-far),2*far*near/(near-far)],[0,0,-1,0]])
    program['mvp'].write(np.asarray((proj@view).T,dtype='f4').tobytes())
    textures={}
    def texture(name):
        if name not in textures:
            im=Image.open(RES/'textures/block'/f'{name}.png').convert('RGBA')
            t=ctx.texture(im.size,4,im.tobytes());t.filter=(moderngl.NEAREST,moderngl.NEAREST);textures[name]=t
        return textures[name]
    def cube(start,end,tex='carbon_panel',tint=(1,1,1),emit=0,faces=None,uvs=None):
        x,y,z=start;X,Y,Z=end
        sides={'north':([(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],(0,0,-1)),
               'south':([(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],(0,0,1)),
               'east':([(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],(1,0,0)),
               'west':([(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],(-1,0,0)),
               'up':([(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],(0,1,0)),
               'down':([(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)],(0,-1,0))}
        vertices=[]
        for face in faces or sides:
            ps,n=sides[face];uv=(uvs or {}).get(face,[0,0,16,16]);lo,hi,U,V=[v/16 for v in uv]
            coords=[(lo,hi),(U,hi),(U,V),(lo,V)]
            for index in [0,1,2,0,2,3]:vertices.extend([*ps[index],*n,*coords[index]])
        buf=ctx.buffer(np.asarray(vertices,dtype='f4').tobytes())
        vao=ctx.vertex_array(program,[(buf,'3f 3f 2f','position','normal','uv')])
        texture(tex).use(0);texture(tex+'_s').use(1);program['tint']=tint;program['emission']=emit
        vao.render();vao.release();buf.release()
    def model(name,pos):
        model=json.loads((RES/'models/block'/f'{name}_on.json').read_text())
        c=name.split('_')[0]
        palette=json.loads((ROOT/'tools/catalog.json').read_text())['colors'][c]['rgb']
        for e in model['elements']:
            for face,spec in e['faces'].items():
                tex=model['textures'][spec['texture'][1:]].split('/')[-1]
                tint=tuple(v/255 for v in palette) if spec.get('tintindex')==0 else (1,1,1)
                start=[v/16+p for v,p in zip(e['from'],pos)];end=[v/16+p for v,p in zip(e['to'],pos)]
                cube(start,end,tex,tint,7 if e.get('neoforge_data') else 0,[face],{face:spec.get('uv',[0,0,16,16])})
    # Warm material room, a floating walnut shelf, tiled floor and a small reading nook.
    for x in range(-7,8):
        for z in range(-6,4):cube((x,-.2,z),(x+1,0,z+1),'basalt_tiles')
    for x in range(-7,8):
        for y in range(6):cube((x,y,3),(x+1,y+1,3.2),'carbon_panel')
    cube((-7,0,-3),(-6.8,6,3),'walnut')
    cube((-5,1.75,1.9),(5,2,3),'walnut')
    cube((-5,1.6,2),(5,1.75,3),'brushed_alloy')
    for x in (-4.5,4.3):cube((x,0,2.1),(x+.2,1.75,2.7),'walnut')
    model('pink_flower',(-3,3,2));model('cyan_cloud',(3,3,2))
    model('violet_arch',(-.5,2,1.6));model('amber_bollard',(-4.5,2,1.5));model('ice_holo',(4,2,1.5))
    for x in range(-5,5):model('pink_strip' if x<0 else 'cyan_strip',(x,1,2))
    for x in (-3,-2,0,1,3):model('ice_fairy',(x,4.8,2))
    model('lime_blade',(-6,3,2));model('amber_sign',(5,3,2))
    # Ottoman, rug and low table, intentionally synthetic offline scenery.
    cube((-2,.01,-3),(2,.04,.2),'walnut',(.55,.42,.68))
    cube((-1.6,.05,-2.7),(-.1,.55,-1.5),'carbon_panel',(.6,.4,.55))
    cube((.6,.05,-1.8),(2,.7,-.4),'walnut')
    model('pink_bollard',(.8,.7,-1.6))
    pixels=np.frombuffer(hdr.read(),dtype=np.float32).reshape(height,width,4)[::-1,:,:3].copy()
    # Offline presentation bloom. The actual Photon pipeline is compiled separately.
    bright=np.maximum(pixels-.7,0)
    bloom=Image.fromarray(np.uint8(np.clip(bright/5,0,1)*255))
    bloom=(np.asarray(bloom.filter(ImageFilter.GaussianBlur(14)),dtype=np.float32)/255*2+
           np.asarray(bloom.filter(ImageFilter.GaussianBlur(4)),dtype=np.float32)/255*1.5)
    pixels=pixels+bloom
    pixels=pixels/(1+pixels)
    im=Image.fromarray(np.uint8(np.power(np.clip(pixels,0,1),1/2.2)*255))
    d=ImageDraw.Draw(im);font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
    d.rectangle((0,0,width,110),fill=(15,13,22));d.text((44,22),'AFTERLIGHT / MAKE ROOM FOR COLOUR',font=ImageFont.truetype(font,32),fill=(250,231,248))
    d.text((46,70),'Actual native models + Java RGB volume + shipped GLSL / standalone OpenGL room study',font=ImageFont.truetype(font,17),fill=(181,168,194))
    d.rectangle((0,height-48,width,height),fill=(15,13,22));d.text((44,height-35),'OFFLINE RENDER — not a Minecraft screenshot. Photon gameplay bloom and UI require player preview.',font=ImageFont.truetype(font,16),fill=(181,168,194))
    OUT.mkdir(exist_ok=True);im.save(OUT/'room-study.png')
    report={'renderer':ctx.info['GL_RENDERER'],'version':ctx.info['GL_VERSION'],'shader':'shader/afterlight.glsl','source':'Java RgbLightVolume fixture','rgb_probes':samples,'passed':True}
    (ROOT/'build/lighting-fixture/gpu-result.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2));print(OUT/'room-study.png')


if __name__=='__main__':main()
