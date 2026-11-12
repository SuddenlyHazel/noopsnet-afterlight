#!/usr/bin/env python3
"""Original Afterlight pixel materials, baked models, recipes and catalog.

No third-party art; deterministic generation. Pillow is a development dependency
only. Build the checked-in resources with Gradle without running this script.
"""
import argparse
import json
import math
from pathlib import Path
import random
import tempfile

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / 'tools/catalog.json').read_text())
ID = 'noopsnet_neon'
SIZE = 64
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
GLYPHS = {
    'N': ['1001','1101','1011','1001','1001'], 'O': ['0110','1001','1001','1001','0110'],
    'P': ['1110','1001','1110','1000','1000'], 'S': ['0111','1000','0110','0001','1110'],
    'M': ['10001','11011','10101','10001','10001'], 'E': ['1111','1000','1110','1000','1111'],
    'T': ['11111','00100','00100','00100','00100'], 'R': ['1110','1001','1110','1010','1001'],
    '0': ['0110','1001','1001','1001','0110'], '7': ['1111','0001','0010','0100','0100'],
    'C': ['0111','1000','1000','1000','0111'], 'A': ['0110','1001','1111','1001','1001'],
    'F': ['1111','1000','1110','1000','1000'],
}


def write_json(root, path, data):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, indent=2) + '\n')


def text(draw, word, xy, fill, scale=2):
    x, y = xy
    for char in word:
        bitmap = GLYPHS[char]
        for row, bits in enumerate(bitmap):
            for col, bit in enumerate(bits):
                if bit == '1':
                    draw.rectangle((x + col*scale, y + row*scale,
                                    x + (col+1)*scale-1, y + (row+1)*scale-1), fill=fill)
        x += (len(bitmap[0]) + 1)*scale


def material(root, name, color, kind='metal', enabled=True, glow=4):
    """Encode LabPBR 1.3 (R=smoothness, G=F0, B=porosity, A=emission).

    Normal RGB is tangent XY plus AO, NOT a conventional blue normal Z.
    Nonzero height avoids POM's zero-height sentinel; no parallax is required.
    """
    rng = random.Random(name)
    albedo = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    specular = Image.new('RGBA', albedo.size, (0, 10, 0, 0))
    height = Image.new('L', albedo.size, 240)
    for y in range(SIZE):
        for x in range(SIZE):
            grain = rng.randrange(-3, 4)
            if kind == 'alloy':
                v = 153 + (y % 3)*2 + grain
                c = (v+36, v-10, v-43, 255); s = (125, 234, 0, 0)
            elif kind == 'wood':
                stripe = (x//5 + int(2*math.sin(y/9))) % 5
                v = 79 + stripe*3 + grain
                c = (v+29,v+1,v-22,255); s=(62,10,12,0)
            elif kind == 'tiles':
                seam = x % 32 < 2 or y % 32 < 2
                v = 88 if seam else 140 + grain
                c = (v+10, v, v-17, 255); s = (65, 12, 24, 0)
                height.putpixel((x,y), 210 if seam else 243)
            elif kind == 'tube':
                # Coloured shoulders around a small pale core keep bloom legible.
                core = abs(x - 31.5) / 31.5
                v = round(208+42*max(0,1-core*2))
                c = (v,v,v,255) if enabled else (40,40,40,255)
                s = (185, 10, 0, 248 if enabled else 0)
            else:
                seam = x in (0, 1, 62, 63) or y in (0, 1, 62, 63)
                v = 178 if seam else 213 + grain
                c = (v+12, v+2, v-15, 255); s = (90, 10, 0, 0)
                height.putpixel((x,y), 216 if seam else 244)
            if kind not in ('market', 'metro', 'holo'):
                albedo.putpixel((x,y), c)
                specular.putpixel((x,y), s)
    if kind in ('market', 'metro', 'holo'):
        mask = Image.new('L', albedo.size, 0); d = ImageDraw.Draw(mask)
        if kind == 'market':
            text(d,'CAFE',(13,10),255,2)
            d.rounded_rectangle((15,32,41,47),radius=4,outline=255,width=2)
            d.arc((37,32,50,44),260,110,fill=255,width=2)
            d.line([(12,51),(47,51)],fill=230,width=2)
            for x in (23,33): d.line([(x,28),(x-2,25),(x,22)],fill=230,width=2)
            d.line([(6,7),(6,11)],fill=210,width=1);d.line([(4,9),(8,9)],fill=210,width=1)
        elif kind == 'metro':
            d.rounded_rectangle((5,4,58,59),radius=10,outline=210,width=1)
            text(d,'TEA',(17,12),255,2)
            d.line([(31,49),(31,31)],fill=255,width=2)
            d.ellipse((18,30,30,40),outline=255,width=2)
            d.ellipse((32,25,44,35),outline=255,width=2)
            d.line([(24,49),(39,49)],fill=210,width=2)
        else:
            d.ellipse((9,9,49,53),fill=230)
            d.ellipse((22,3,55,45),fill=0)
            for x,y in ((48,16),(52,39),(17,57)):
                d.line([(x-3,y),(x+3,y)],fill=255,width=1)
                d.line([(x,y-3),(x,y+3)],fill=255,width=1)
        for y in range(SIZE):
            for x in range(SIZE):
                coverage = mask.getpixel((x,y))
                if coverage:
                    c = (245,245,245) if enabled else (35,35,35)
                    albedo.putpixel((x,y), c + (255,))
                    specular.putpixel((x,y), (160,10,0, min(250,coverage+25) if enabled else 0))
    normal = Image.new('RGBA', albedo.size)
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (height.getpixel(((x+1)%SIZE,y))-height.getpixel(((x-1)%SIZE,y))) / 255
            dy = (height.getpixel((x,(y+1)%SIZE))-height.getpixel((x,(y-1)%SIZE))) / 255
            length = math.sqrt(dx*dx+dy*dy+1)
            normal.putpixel((x,y),(round(127.5-127.5*dx/length),round(127.5-127.5*dy/length),255,height.getpixel((x,y))))
    target = root / f'assets/{ID}/textures/block'
    target.mkdir(parents=True, exist_ok=True)
    if glow!=4:specular.putalpha(specular.getchannel('A').point(lambda a:a*glow//4))
    for suffix, image in (('',albedo),('_s',specular),('_n',normal)):
        image.save(target / f'{name}{suffix}.png', optimize=False)


def box(start, end, texture='body', glow=False, faces=FACES, uv=None):
    element = {'from':list(start), 'to':list(end), 'faces':{}}
    for face in faces:
        spec = {'texture':'#'+texture}
        if uv is not None: spec['uv'] = uv
        if texture in ('tube','glyph'): spec['tintindex'] = 0
        element['faces'][face] = spec
    if glow:
        element['shade'] = False
        element['neoforge_data'] = {'block_light':15, 'sky_light':15, 'ambient_occlusion':False}
    return element


def neon_stroke(points, width=3, z=1.0):
    """Extrude a rasterized vector stroke into non-overlapping raised tube runs.

    The recessed copper shoulder and proud luminous face give silhouettes depth.
    Horizontal runs keep geometry bounded and avoid overlapping coplanar quads.
    """
    mask=Image.new('L',(32,32));d=ImageDraw.Draw(mask)
    pts=[(round(x*2),round((16-y)*2)) for x,y in points]
    d.line(pts,fill=255,width=width,joint='curve')
    radius=width/2
    for x,y in pts:
        d.ellipse((x-radius,y-radius,x+radius-1,y+radius-1),fill=255)
    elements=[]
    for y in range(32):
        x=0
        while x<32:
            if not mask.getpixel((x,y)):x+=1;continue
            start=x
            while x<32 and mask.getpixel((x,y)):x+=1
            elements.append(box((start/2,15.5-y/2,z),(x/2,16-y/2,z+.55),'tube',True))
    return elements


def geometry(form):
    b = box
    if form == 'channel':
        return [b((0,0,2),(16,16,16)), b((0,0,0),(16,5,2)), b((0,11,0),(16,16,2)),
                b((0,5,1.25),(16,11,2),'alloy'), b((0,7,0.75),(16,9,1.25),'tube',True)]
    if form == 'strip':
        return [b((0,5,13),(16,11,16)), b((0,6,12),(16,10,13),'alloy'),
                b((0,7,11.5),(16,9,12),'tube',True)]
    if form == 'elbow':
        return [b((0,5,13),(5,11,16)), b((5,5,13),(11,16,16)),
                b((0,6,12),(6,10,13),'alloy'), b((6,6,12),(10,16,13),'alloy'),
                b((0,7,11.5),(9,9,12),'tube',True), b((7,9,11.5),(9,16,12),'tube',True)]
    if form == 'louver':
        return [b((0,0,2),(16,16,16)), b((2,1,1.75),(14,15,2),'tube',True,('north',)),
                b((0,0,0),(2,16,2),'alloy'), b((14,0,0),(16,16,2),'alloy')] + [
                    b((2,y,0),(14,y+1.5,1.5)) for y in (0,3,6,9,12,14.5)]
    if form == 'sign':
        return [b((0,0,13),(16,16,16)), b((.5,.5,12.5),(15.5,15.5,13),'alloy'),
                b((1,1,12),(15,15,12.5)), b((1,1,11.98),(15,15,12),'glyph',True,('north',),[0,0,16,16]),
                b((0,0,11.5),(1,5,12),'tube',True), b((15,11,11.5),(16,16,12),'tube',True)]
    if form == 'blade':
        return [b((6,13,12),(10,15,16),'alloy'), b((6,2,12),(10,4,16),'alloy'),
                b((6.5,1,.5),(9.5,15,12)), b((6,0,0),(10,1,12),'alloy'), b((6,15,0),(10,16,12),'alloy'),
                b((6.45,1,1),(6.5,15,11),'glyph',True,('west',),[0,0,16,16]),
                b((9.5,1,1),(9.55,15,11),'glyph',True,('east',),[0,0,16,16]),
                b((6.5,1,0),(9.5,15,.5),'tube',True)]
    if form == 'holo':
        return [b((1,0,1),(15,2,15)), b((3,2,3),(13,3,13),'alloy'),
                b((5,3,5),(11,3.5,11),'tube',True), b((7.5,3.5,7.5),(8.5,5,8.5),'alloy')] + neon_stroke(
                [(10.5,14.5),(8,15),(5.5,13.5),(4.5,11),(5,8),(7,6),(10,6),(12,7.5),(9,7.5),(7.5,9),(7.5,12),(8.5,13.5),(10.5,14.5)],2,7.5)
    if form == 'bollard':
        return [b((3,0,3),(13,1,13),'alloy'), b((7,1,7),(9,7,9)),
                b((4,7,4),(12,8,12),'ceramic'), b((5,8,5),(11,11,11),'tube',True),
                b((4,11,4),(12,12,12),'ceramic'), b((5,12,5),(11,13,11),'ceramic'),
                b((6,13,6),(10,13.5,10),'ceramic')]
    if form == 'arch':
        arc=[(8+5.5*math.cos(a),5+9*math.sin(a)) for a in [i*math.pi/32 for i in range(33)]]
        return [b((1,0,4),(15,1.5,12),'body'),b((2,1.5,5),(14,2,11),'alloy'),
                b((2,2,8.1),(3.5,5,9),'alloy'),b((12.5,2,8.1),(14,5,9),'alloy')] + neon_stroke(arc,4,7.5)
    if form == 'cloud':
        cloud=[(3,5),(1.5,6),(1,8),(2,10),(4,10.5),(4.5,13),(6.5,14),(9,13.5),(10.5,11.5),
               (13,11.5),(14.5,10),(15,7),(13,5),(3,5)]
        return [b((3,3,14),(13,13,16),'ceramic'),b((5,5,12.5),(11,11,14),'alloy')] + neon_stroke(cloud,3,11.5)
    if form == 'flower':
        points=[]
        for i in range(81):
            a=i*math.tau/80;r=4.4+1.8*math.cos(5*a)
            points.append((8+r*math.cos(a),8+r*math.sin(a)))
        return [b((5,5,14),(11,11,16),'body'),b((6.5,6.5,11.5),(9.5,9.5,14),'alloy'),
                b((7,7,10.75),(9,9,11.5),'ceramic')] + neon_stroke(points,2,11.5)
    if form == 'fairy':
        result=[b((0,12,14),(1,15,16),'alloy'),b((15,12,14),(16,15,16),'alloy')]
        # Curved dark cable, independent jewel-like bulbs and small brass sockets.
        for i in range(1,15):
            y=10+3*((i-7.5)/7.5)**2
            result.append(b((i,y,14),(i+1,y+.4,14.4),'body'))
        for x,y in [(2,10.6),(5,8.9),(8,8.5),(11,8.9),(14,10.6)]:
            result.extend([b((x-.4,y+1,13.7),(x+.4,y+2.2,14.6),'alloy'),
                b((x-.8,y-.5,13.2),(x+.8,y+1,14.8),'tube',True),
                b((x-.45,y-1,13.55),(x+.45,y-.5,14.45),'tube',True)])
        return result
    raise ValueError(form)


def connected_geometry(form, mask):
    """Disjoint planar runs make seamless ports without coplanar overlapping faces."""
    effective=mask or 3
    def region(width):
        low=(16-width)//2;high=low+width
        cells={(x,y) for x in range(low,high) for y in range(low,high)}
        for bit,bounds in [(1,(0,low,8,high)),(2,(8,low,16,high)),(4,(low,8,high,16)),(8,(low,0,high,8))]:
            if effective&bit:
                x,y,X,Y=bounds;cells|={(a,b) for a in range(x,X) for b in range(y,Y)}
        return cells
    def extrude(cells,z,Z,texture,glow=False):
        result=[];active={}
        for y in range(16):
            next_active={}
            x=0
            while x<16:
                if (x,y) not in cells:x+=1;continue
                start=x
                while x<16 and (x,y) in cells:x+=1
                run=(start,x)
                element=active.get(run)
                if element is None:
                    element=box((start,y,z),(x,y+1,Z),texture,glow);result.append(element)
                else:element['to'][1]=y+1
                next_active[run]=element
            active=next_active
        return result
    if form=='channel':
        base=[box((0,0,2),(16,16,16))]
        base+=extrude({(x,y) for x in range(16) for y in range(16)}-region(6),0,2,'body')
        return base+extrude(region(6),1.25,2,'alloy')+extrude(region(2),.75,1.25,'tube',True)
    return extrude(region(6),13,16,'body')+extrude(region(4),12,13,'alloy')+extrude(region(2),11.5,12,'tube',True)


def generate(root, java):
    colors, forms, materials = (CATALOG[k] for k in ('colors','forms','materials'))
    assets = f'assets/{ID}'
    data = f'data/{ID}'
    material(root,'carbon_panel',None)
    material(root,'brushed_alloy',None,'alloy')
    material(root,'basalt_tiles',None,'tiles')
    material(root,'walnut',None,'wood')
    for glow in range(4):
        for kind,source in [('tube','tube'),('market','market'),('metro','metro'),('holo','holo')]:
            material(root,f'{kind}_glow{glow}',None,source,True,glow)
            write_json(root,f'{assets}/models/block/glow_profile_{kind}_{glow}.json',{'parent':'minecraft:block/cube_all','textures':{'all':f'{ID}:block/{kind}_glow{glow}'}})
    lang = {'itemGroup.noopsnet_neon':'NoopsNet Afterlight',
        'item.noopsnet_neon.palette':'Colour Palette', 'noopsnet_neon.palette.title':'Choose your glow',
        'noopsnet_neon.palette.hex':'Hex', 'noopsnet_neon.palette.drag':'Drag to choose any colour',
        'noopsnet_neon.palette.invalid':'6 hex digits', 'noopsnet_neon.palette.apply':'Apply palette',
        'noopsnet_neon.palette.help':'Air: pick · Light: paint · Sneak: sample',
        'noopsnet_neon.palette.sampled':'Sampled %s',
        'noopsnet_neon.art.title':'Make your sign', 'noopsnet_neon.art.denied':'You can no longer edit this sign.',
        'noopsnet_neon.art.stale':'This sign changed. Reopen to get the latest design.'}
    names = []
    for name, label in materials.items():
        names.append(name); lang[f'block.{ID}.{name}'] = label
        write_json(root,f'{assets}/blockstates/{name}.json',{'variants':{'':{'model':f'{ID}:block/{name}'}}})
        write_json(root,f'{assets}/models/block/{name}.json',{'parent':'minecraft:block/cube_all','textures':{'all':f'{ID}:block/{name}'}})
        write_json(root,f'{data}/recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'building',
            'pattern':['SS','SS'],'key':{'S':{'item':'minecraft:'+{'carbon_panel':'terracotta','brushed_alloy':'copper_ingot','basalt_tiles':'stone_bricks'}[name]}},
            'result':{'id':f'{ID}:{name}','count':4}})
    for color, info in colors.items():
        for suffix, enabled in (('',True),('_off',False)):
            for texture, kind in (('tube','tube'),('market','market'),('metro','metro'),('holo','holo')):
                material(root, f'{color}_{texture}{suffix}',info['rgb'],kind,enabled)
        for form, spec in forms.items():
            name = color+'_'+form; names.append(name)
            lang[f'block.{ID}.{name}'] = info['name']+' '+spec['name']
            variants = {}
            for lit in (True,False):
                mode = 'on' if lit else 'off'
                elements = geometry(form)
                if not lit:
                    for e in elements:
                        e.pop('neoforge_data',None); e.pop('shade',None)
                textures = {'body':f'{ID}:block/walnut', 'alloy':f'{ID}:block/brushed_alloy',
                            'ceramic':f'{ID}:block/carbon_panel',
                            'particle':f'{ID}:block/carbon_panel', 'tube':f'{ID}:block/{color}_tube'+('' if lit else '_off')}
                glyph = {'sign':'market','blade':'metro','holo':'holo'}.get(form,'market')
                textures['glyph'] = f'{ID}:block/{color}_{glyph}'+('' if lit else '_off')
                write_json(root,f'{assets}/models/block/{name}_{mode}.json',{
                    'parent':'minecraft:block/block','render_type':'minecraft:cutout' if form in ('sign','blade','holo') else 'minecraft:solid','textures':textures,
                    'elements':elements})
                for facing, y in (('north',0),('east',90),('south',180),('west',270)):
                    for wet in (True,False):
                        variants[f'facing={facing},lit={str(lit).lower()},waterlogged={str(wet).lower()}'] = {
                            'model':f'{ID}:block/{name}_{mode}','y':y}
            if form in ('channel','strip','elbow'):
                variants={f'joining={joining},{key}':value for key,value in variants.items() for joining in ('false','true')}
                for mask in range(16):
                    for lit in (True,False):
                        mode='on' if lit else 'off';elements=connected_geometry(form,mask)
                        if not lit:
                            for e in elements:e.pop('neoforge_data',None);e.pop('shade',None)
                        textures={'body':f'{ID}:block/walnut','alloy':f'{ID}:block/brushed_alloy','particle':f'{ID}:block/walnut','tube':f'{ID}:block/{color}_tube'+('' if lit else '_off')}
                        model=f'{name}_connect{mask}_{mode}'
                        write_json(root,f'{assets}/models/block/{model}.json',{'parent':'minecraft:block/block','render_type':'minecraft:solid','textures':textures,'elements':elements})
            write_json(root,f'{assets}/blockstates/{name}.json',{'variants':variants})
            write_json(root,f'{data}/recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'building',
                'pattern':[' I ','GDG',' C '], 'key':{
                    'I':{'item':'minecraft:'+spec['ingredient']}, 'G':{'item':'minecraft:glowstone_dust'},
                    'D':{'item':'minecraft:'+info['dye']+'_dye'}, 'C':{'item':f'{ID}:carbon_panel'}},
                'result':{'id':f'{ID}:{name}','count':spec['count']}})
            write_json(root,f'{data}/recipe/{name}_recolor.json',{'type':'minecraft:crafting_shapeless','category':'building',
                'ingredients':[{'tag':f'{ID}:{form}'},{'item':'minecraft:'+info['dye']+'_dye'}],
                'result':{'id':f'{ID}:{name}','count':1}})
    for name in names:
        parent = name if name in materials else name+'_on'
        write_json(root,f'{assets}/models/item/{name}.json',{'parent':f'{ID}:block/{parent}'})
        entry = {'type':'minecraft:item','name':f'{ID}:{name}'}
        if name not in materials:
            entry['functions']=[{'function':'minecraft:copy_components','source':'block_entity','include':['minecraft:dyed_color','minecraft:custom_data']}]
        write_json(root,f'{data}/loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{
            'rolls':1,'entries':[entry],
            'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    for form in forms:
        values = [f'{ID}:{c}_{form}' for c in colors]
        for registry in ('block','item'):
            write_json(root,f'{data}/tags/{registry}/{form}.json',{'replace':False,'values':values})
    write_json(root,'data/minecraft/tags/block/mineable/pickaxe.json',{'replace':False,'values':[f'{ID}:{n}' for n in names]})
    write_json(root,f'{assets}/lang/en_us.json',lang)
    # A tintable paint drop on a wooden handle; no external item artwork.
    item = Image.new('RGBA',(16,16)); d=ImageDraw.Draw(item)
    d.line([(3,13),(10,6)],fill=(133,91,61),width=3)
    d.ellipse((7,1,14,8),fill=(240,240,240)); target=root/f'{assets}/textures/item/palette.png'
    target.parent.mkdir(parents=True,exist_ok=True); item.save(target)
    write_json(root,f'{assets}/models/item/palette.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{ID}:item/palette'}})
    write_json(root,f'{data}/recipe/palette.json',{'type':'minecraft:crafting_shapeless','category':'misc',
        'ingredients':[{'item':'minecraft:stick'},{'item':'minecraft:glow_ink_sac'},{'tag':'c:dyes'}],
        'result':{'id':f'{ID}:palette','count':1}})
    write_json(root,'pack.mcmeta',{'pack':{'pack_format':34,'description':'NoopsNet Afterlight — original neon and LabPBR materials'}})
    (root/'texture.properties').write_text('format=lab-pbr/1.3\n')
    # One catalog owns both assets and Java registrations/shapes.
    def java_array(name, values):
        """Emit the same four-space layout used by the repository Java check."""
        values = [json.dumps(value) for value in values]
        prefix = f'    public static final String[] {name} = '
        inline = prefix + '{' + ', '.join(values) + '};'
        if len(inline) <= 100:
            return [inline]
        lines = [prefix + '{']
        line = '        '
        for index, value in enumerate(values):
            token = value + (',' if index < len(values)-1 else '')
            if len(line.rstrip()) > 0 and len(line) + 1 + len(token) > 100:
                lines.append(line.rstrip())
                line = '        '
            if len(line.rstrip()) > 0:
                line += ' '
            line += token
        lines.extend([line, '    };'])
        return lines
    source = ['package net.noopsnet.neon;','', '// Generated by tools/generate.py; edit tools/catalog.json and geometry().',
        'import net.minecraft.world.level.block.Block;', 'import net.minecraft.world.phys.shapes.Shapes;',
        'import net.minecraft.world.phys.shapes.VoxelShape;', '', 'public final class NeonCatalog {']
    source += java_array('COLORS', colors) + java_array('FORMS', forms) + java_array('MATERIALS', materials)
    source += ['', '    public static int light(String form) {', '        return switch (form) {']
    for form, spec in forms.items(): source.append(f'            case "{form}" -> {spec["light"]};')
    source += ['            default -> throw new IllegalArgumentException(form);', '        };', '    }', '',
        '    public static int color(String color) {', '        return switch (color) {']
    for color, info in colors.items():
        r,g,b=info['rgb'];source.append(f'            case "{color}" -> 0x{r:02X}{g:02X}{b:02X};')
    source += ['            default -> throw new IllegalArgumentException(color);', '        };', '    }', '',
        '    public static VoxelShape shape(String form) {', '        return switch (form) {']
    for form in forms:
        # Holographic glyphs are intangible; only their projector collides.
        elements = [e for e in geometry(form) if form != 'holo' or e['to'][1] <= 3.5]
        boxes = ['Block.box('+', '.join(map(str,e['from']+e['to']))+')' for e in elements]
        source += [f'            case "{form}" ->', '                    Shapes.or(', '                            Shapes.empty(),']
        source += ['                            ' + box + (',' if index < len(boxes)-1 else ');')
                   for index, box in enumerate(boxes)]
    source += ['            default -> throw new IllegalArgumentException(form);', '        };', '    }', '}']
    java.parent.mkdir(parents=True,exist_ok=True); java.write_text('\n'.join(source)+'\n')


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    root = ROOT/'src/main/resources'; java = ROOT/'src/main/java/net/noopsnet/neon/NeonCatalog.java'
    if not args.check:
        generate(root,java); print('Generated 75 blocks, original 64px materials and LabPBR companions'); return
    with tempfile.TemporaryDirectory(prefix='afterlight-assets-') as directory:
        temp = Path(directory); generate(temp/'resources',temp/'NeonCatalog.java')
        expected = {p.relative_to(temp/'resources'):p for p in (temp/'resources').rglob('*') if p.is_file()}
        stale = [str(p) for p,t in expected.items() if not (root/p).exists() or (root/p).read_bytes()!=t.read_bytes()]
        managed = ('assets/noopsnet_neon','data/noopsnet_neon','data/minecraft/tags/block/mineable')
        actual = {p.relative_to(root) for folder in managed for p in (root/folder).rglob('*') if p.is_file()}
        stale += [str(p)+' (unexpected)' for p in actual-set(expected)]
        if not java.exists() or java.read_bytes()!=(temp/'NeonCatalog.java').read_bytes(): stale.append(str(java))
        if stale: raise SystemExit('Generated files differ: '+', '.join(stale))
    print('All generated assets and Java catalog are reproducible')


if __name__ == '__main__': main()
