#!/usr/bin/env python3
"""Offline orthographic studies of the actual generated JSON meshes and textures.

Bloom is an illustrative postprocess, not a claim of in-game shader validation.
No Minecraft client is launched. No external JS/CDNs or game assets are used.
"""
import functools
import html
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont
from generate import CATALOG, ID, ROOT

RES = ROOT/'src/main/resources'
OUT = ROOT/'preview'
SCALE = 11
CANVAS = (360,360)


def project(p):
    x,y,z = p
    return (180 - SCALE*(.8*(x-8)+.6*(z-8)), 195+SCALE*(.30*(x-8)-.866*(y-8)-.40*(z-8)))


@functools.lru_cache(maxsize=256)
def texture(name):
    path = RES/'assets'/name.replace(':','/textures/')
    return Image.open(path.with_suffix('.png')).convert('RGBA')


def face_points(e, face):
    x,y,z = e['from']; X,Y,Z = e['to']
    return {'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],
            'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],
            'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)]}[face]


def uv_for(e,face):
    if 'uv' in e['faces'][face]: return e['faces'][face]['uv']
    x,y,z = e['from']; X,Y,Z = e['to']
    return {'north':[16-X,16-Y,16-x,16-y], 'east':[16-Z,16-Y,16-z,16-y], 'up':[x,z,X,Z]}[face]


def transform_quad(image, points, uv):
    # Invert screen-space parallelogram to native 64px texture coordinates.
    p,q,_,r = points
    ax,ay = q[0]-p[0],q[1]-p[1]; bx,by = r[0]-p[0],r[1]-p[1]
    det = ax*by-ay*bx
    if abs(det)<1e-8: return Image.new('RGBA',CANVAS)
    u,v,U,V = (n*4 for n in uv)
    a,b = (U-u)*by/det,-(U-u)*bx/det
    d,e = -(V-v)*ay/det,(V-v)*ax/det
    warped = image.transform(CANVAS,Image.Transform.AFFINE,(a,b,u-a*p[0]-b*p[1],d,e,v-d*p[0]-e*p[1]),Image.Resampling.NEAREST)
    mask = Image.new('L',CANVAS); ImageDraw.Draw(mask).polygon(points,fill=255)
    warped.putalpha(ImageChops.multiply(warped.getchannel('A'),mask))
    return warped


def render(name,mode='on',split=False):
    suffix = '' if name in CATALOG['materials'] else '_'+mode
    model=json.loads((RES/f'assets/{ID}/models/block/{name}{suffix}.json').read_text())
    if 'elements' not in model:
        model['elements']=[{'from':[0,0,0],'to':[16,16,16],'faces':{f:{'texture':'#all'} for f in ('north','east','up')}}]
    faces=[]
    for e in model['elements']:
        for f in ('north','east','up'):
            if f not in e['faces']: continue
            points=face_points(e,f)
            depth=sum(.51*x+.46*y-.69*z for x,y,z in points)/4
            faces.append((depth,e,f,points))
    scene=np.zeros((CANVAS[1],CANVAS[0],4),dtype=np.uint8)
    emission=np.zeros_like(scene)
    tinted=np.zeros(CANVAS[::-1],dtype=bool)
    zbuffer=np.full(CANVAS[::-1],-np.inf)
    yy,xx=np.mgrid[:CANVAS[1],:CANVAS[0]]
    for _,e,f,points in sorted(faces,key=lambda q:q[0]):
        texname=model['textures'][e['faces'][f]['texture'][1:]]
        tex=texture(texname).copy(); spec=texture(texname+'_s')
        is_tinted=e['faces'][f].get('tintindex')==0
        tint=(255,255,255) if split or not is_tinted else CATALOG['colors'][name.split('_')[0]]['rgb']
        # Front-on broad studio illumination on housings, full brightness on emitters.
        gain=1 if e.get('neoforge_data') else {'north':.83,'east':.60,'up':1.15}[f]
        tex=Image.merge('RGBA',tuple(tex.getchannel(channel).point(lambda a:min(255,int(a*gain*tint[channel]/255)))
                                   for channel in range(3))+(tex.getchannel('A'),))
        pts=[project(p) for p in points]
        patch=transform_quad(tex,pts,uv_for(e,f))
        light=tex.copy();light.putalpha(ImageChops.multiply(tex.getchannel('A'),spec.getchannel('A')))
        litpatch=transform_quad(light,pts,uv_for(e,f))
        # Per-pixel depth makes louvers and recessed channels occlude correctly.
        p,q,_,r=pts
        ax,ay=q[0]-p[0],q[1]-p[1];bx,by=r[0]-p[0],r[1]-p[1]
        det=ax*by-ay*bx
        if abs(det)<1e-8:continue
        u=((xx-p[0])*by-(yy-p[1])*bx)/det
        v=(-(xx-p[0])*ay+(yy-p[1])*ax)/det
        depths=[.52*x+.5*y-.69*z for x,y,z in points]
        depth=depths[0]+u*(depths[1]-depths[0])+v*(depths[3]-depths[0])
        rgba=np.asarray(patch);visible=(rgba[:,:,3]>0)&(depth>zbuffer)
        scene[visible]=rgba[visible];emission[visible]=np.asarray(litpatch)[visible]
        tinted[visible]=is_tinted
        zbuffer[visible]=depth[visible]
    if split:
        body=scene.copy();body[tinted,3]=0
        glow_part=scene.copy();glow_part[~tinted,3]=0
        emission_img=Image.fromarray(emission)
        bloom=Image.alpha_composite(emission_img.filter(ImageFilter.GaussianBlur(9)),emission_img.filter(ImageFilter.GaussianBlur(3)))
        return Image.fromarray(body),Image.alpha_composite(bloom,Image.fromarray(glow_part))
    scene=Image.fromarray(scene);emission=Image.fromarray(emission)
    glow=emission.filter(ImageFilter.GaussianBlur(9))
    glow=Image.alpha_composite(glow,emission.filter(ImageFilter.GaussianBlur(3)))
    result=Image.alpha_composite(glow,scene)
    return result


def font(size):
    path=Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    return ImageFont.truetype(str(path),size) if path.exists() else ImageFont.load_default(size=size)


def main():
    (OUT/'blocks').mkdir(parents=True,exist_ok=True)
    for form in CATALOG['forms']:
        for mode in ('on','off'):
            body,glow=render('amber_'+form,mode,split=True)
            body.save(OUT/f'blocks/custom_{form}_{mode}_body.png')
            glow.save(OUT/f'blocks/custom_{form}_{mode}_glow.png')
    for c in CATALOG['colors']:
        for f in CATALOG['forms']:
            for mode in ('on','off'):
                render(c+'_'+f,mode).save(OUT/f'blocks/{c}_{f}_{mode}.png')
    for material in CATALOG['materials']:
        render(material).save(OUT/f'blocks/{material}.png')
    sheet=Image.new('RGB',(1480,1460),(25,23,30)); d=ImageDraw.Draw(sheet)
    d.text((40,22),'AFTERLIGHT',font=font(44),fill=(225,242,250))
    d.text((42,83),'NOOPSNET  /  MAKE ROOM FOR COLOUR',font=font(16),fill=(242,198,155))
    for i,(f,spec) in enumerate(CATALOG['forms'].items()):
        x=20+(i%4)*365;y=130+(i//4)*400
        d.rounded_rectangle((x,y,x+350,y+385),radius=12,fill=(51,43,38),outline=(96,78,63))
        color=list(CATALOG['colors'])[i%6]
        image=Image.open(OUT/f'blocks/{color}_{f}_on.png');sheet.paste(image,(x-4,y-12),image)
        d.text((x+20,y+337),spec['name'],font=font(18),fill=(218,233,245))
    d.text((40,1370),'ANY RGB COLOUR   /   12 COZY FORMS   /   3 WARM MATERIALS',font=font(17),fill=(207,181,157))
    d.text((40,1411),'OFFLINE NATIVE-MODEL STUDIES. Illustrative bloom; not gameplay or shader captures.',font=font(15),fill=(105,127,149))
    sheet.save(OUT/'afterlight.png')
    cards=''.join(f'<article><canvas data-form="{f}" data-label="{html.escape(spec["name"])}" width="360" height="360" role="img" aria-label="{html.escape(spec["name"])}"></canvas><h2>{html.escape(spec["name"])}</h2><code>noopsnet_neon:amber_{f}</code></article>' for f,spec in CATALOG['forms'].items())
    swatches=''.join(f'<button type="button" data-preset="{c}" style="--swatch:rgb{tuple(spec["rgb"])}" aria-pressed="false">{html.escape(spec["name"])}</button>' for c,spec in CATALOG['colors'].items())
    presets={c:'#'+''.join(f'{n:02X}' for n in spec['rgb']) for c,spec in CATALOG['colors'].items()}
    page=(ROOT/'tools/catalog.html').read_text()
    (OUT/'index.html').write_text(page.replace('PRESETS',swatches).replace('CARDS',cards).replace('PRESET_DATA',json.dumps(presets)))
    print(OUT/'index.html')


if __name__=='__main__':main()
