#!/usr/bin/env python3
"""Probe shipped spotlight transport on native EGL; never opens Minecraft.

Writes numerical evidence and a synthetic (not gameplay) surface/fog preview to
build/spotlight-transport-validation. Requires moderngl, numpy and Pillow.
"""
import argparse
import hashlib
import html
import json
import re
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'build/spotlight-transport-validation')
args = parser.parse_args()
OUT = args.output.expanduser().resolve()
OUT.mkdir(parents=True, exist_ok=True)
seen = set()
loaded_sources = {}


def source(name):
    path = ROOT / 'shader' / name
    if path in seen:
        return ''
    seen.add(path)
    loaded_sources[path.name] = path.read_text()
    return re.sub(r'^\s*#include\s+"([^"]+)"\s*$',
                  lambda m: source(Path(m[1]).name), loaded_sources[path.name], flags=re.M)


ctx = moderngl.create_standalone_context(require=330, backend='egl')
vertex = '''#version 330
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}
'''
header = '''#version 330
#define rec709_to_working_color mat3(1.)
#define BLOCKLIGHT_I 1.
#define clamp01(x) clamp(x,0.,1.)
uniform sampler3D colortex0;
uniform vec3 cameraPosition,ambient_color;
uniform float frameTimeCounter,blindness,darknessFactor;
'''
tail = '''
uniform sampler2D probe_data;
uniform int probe_mode;
uniform float sample_phase;
uniform float image_exposure;
uniform int image_pillar,image_front_wall;
uniform vec2 image_size;
uniform vec3 image_camera,image_forward,image_right,image_up;
out vec4 result;

bool probe_box(vec3 origin,vec3 direction,vec3 lo,vec3 hi,out float hit,out vec3 normal){
    vec3 safe=mix(vec3(1e-7),direction,greaterThan(abs(direction),vec3(1e-7)));
    vec3 a=(lo-origin)/safe,b=(hi-origin)/safe;
    vec3 near=min(a,b),far=max(a,b);
    hit=max(near.x,max(near.y,near.z));
    if(hit<0.||hit>min(far.x,min(far.y,far.z)))return false;
    normal=vec3(0.);
    if(near.x>=near.y&&near.x>=near.z)normal.x=-sign(direction.x);
    else if(near.y>=near.z)normal.y=-sign(direction.y);
    else normal.z=-sign(direction.z);
    return true;
}

void probe_image(){
    vec2 screen=(gl_FragCoord.xy/image_size*2.-1.)*vec2(image_size.x/image_size.y,1.);
    vec3 direction=normalize(image_forward+.52*(screen.x*image_right+screen.y*image_up));
    float distance=80.;vec3 normal=vec3(0.);vec3 albedo=vec3(.65);
    if(direction.z>1e-6){float t=(22.-image_camera.z)/direction.z;
        if(t>0.&&t<distance){distance=t;normal=vec3(0,0,-1);}}
    if(image_front_wall==1&&direction.z<-1e-6){float t=(-3.-image_camera.z)/direction.z;
        if(t>0.&&t<distance){distance=t;normal=vec3(0,0,1);}}
    if(direction.y<-1e-6){float t=(-3.-image_camera.y)/direction.y;
        if(t>0.&&t<distance){distance=t;normal=vec3(0,1,0);}}
    float box_hit;vec3 box_normal;
    if(image_pillar==1&&probe_box(image_camera,direction,vec3(0,-3,8),vec3(1,3,9),box_hit,box_normal)&&box_hit<distance){
        distance=box_hit;normal=box_normal;albedo=vec3(.55);}
    vec3 pos=image_camera+direction*distance;
    if(normal.y>.5)albedo=vec3(mix(.45,.65,mod(floor(pos.x/2.)+floor(pos.z/2.),2.)));
    // Match Photon's caller: geometric-normal receiver bias before the shared
    // evaluator, then its existing Lambert BRDF applies albedo/PI once.
    vec3 irradiance=afterlight_spot_irradiance(pos+normal*.03,normal);
    vec3 color=albedo*(vec3(.018)+irradiance/3.14159265359);
    if(probe_mode==5){mat2x3 fog=raymarch_afterlight_mist(image_camera,pos,sample_phase);
        color=color*fog[1]+fog[0];}
    if(probe_mode==4)result=vec4(color,1.);
    else {color=max(color*image_exposure,vec3(0.));result=vec4(pow(color/(1.+color),vec3(1./2.2)),1.);}
}

void main(){
    if(probe_mode>=4){probe_image();return;}
    ivec2 p=ivec2(gl_FragCoord.xy);
    vec4 a=texelFetch(probe_data,ivec2(p.x*3,p.y),0);
    vec4 b=texelFetch(probe_data,ivec2(p.x*3+1,p.y),0);
    vec4 c=texelFetch(probe_data,ivec2(p.x*3+2,p.y),0);
    if(probe_mode==0)result=vec4(afterlight_spot_irradiance(a.xyz+c.xyz*.03,b.xyz),1.);
    else if(probe_mode==1){mat2x3 fog=raymarch_afterlight_mist(a.xyz,b.xyz,sample_phase);result=vec4(fog[0],fog[1].x);}
    else if(probe_mode==2){vec3 to_light;result=vec4(afterlight_spot_incident(int(a.w),a.xyz,to_light),1.);}
    else result=vec4(vec3(afterlight_light_transmission(a.xyz,b.xyz)),1.);
}
'''
program = ctx.program(vertex_shader=vertex, fragment_shader=header
                      + source('afterlight_spotlight.glsl') + source('afterlight_fog.glsl') + tail)
vao = ctx.vertex_array(program, [])


def uniform(name, value):
    if name in program:
        program[name] = value


SIZE = 64
ORIGIN = np.array([-24., -16., -8.])
fog = np.zeros((SIZE, SIZE, SIZE, 4), dtype='f4')
previous = np.zeros_like(fog)
rgb = np.zeros_like(fog)
textures = [ctx.texture3d((SIZE, SIZE, SIZE), 4, dtype='f4') for _ in range(4)]
for unit, texture in enumerate(textures):
    texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
    texture.repeat_x = texture.repeat_y = texture.repeat_z = False
    texture.use(unit)
beam_texture = ctx.texture((4, 8), 4, dtype='f4')
beam_texture.use(4)
noise = ctx.texture3d((4, 4, 4), 1, np.full((4, 4, 4), .5, dtype='f4').tobytes(), dtype='f4')
noise.use(5)
beams = np.zeros((8, 4, 4), dtype='f4')
beams[0] = [[.5, .5, .5, 24], [0, 0, 1, .18], [1, .18, .03, .22], [0, 0, 0, 0]]
for name, value in {'afterlightFogVolume': 0, 'afterlightFogPreviousVolume': 1,
                    'afterlightLightVolume': 2, 'afterlightPreviousVolume': 3,
                    'afterlightFogBeams': 4, 'colortex0': 5, 'probe_data': 6,
                    'afterlightFogBlend': 1., 'afterlightVolumeBlend': 1.,
                    'afterlightFogDensity': 1.,
                    'image_exposure': 1., 'image_pillar': 1, 'image_front_wall': 0,
                    'afterlightFogBeamCount': 1, 'sample_phase': .5}.items():
    uniform(name, value)
for name in ('afterlightFogOrigin', 'afterlightFogPreviousOrigin', 'afterlightVolumeOrigin', 'afterlightPreviousOrigin'):
    uniform(name, tuple(ORIGIN))
for name in ('afterlightFogSize', 'afterlightFogPreviousSize', 'afterlightVolumeSize', 'afterlightPreviousSize'):
    uniform(name, (SIZE, SIZE, SIZE))
for name in ('cameraPosition', 'afterlightFogBeamOrigin', 'ambient_color'):
    uniform(name, (0., 0., 0.))
for texture in textures[2:]:
    texture.write(rgb.tobytes())


def upload():
    textures[0].write(fog.tobytes())
    textures[1].write(previous.tobytes())
    beam_texture.write(beams.tobytes())


def fill_box(field, lo, hi, solid=True):
    low = np.asarray(lo, dtype=int) - ORIGIN.astype(int)
    high = np.asarray(hi, dtype=int) - ORIGIN.astype(int)
    region = field[low[2]:high[2], low[1]:high[1], low[0]:high[0]]
    region[:, :, :, 2] = float(solid)
    if solid:
        region[:, :, :, 3] = 0


def probe(mode, a, b=(0, 0, -1), flat=None):
    a = np.atleast_2d(a)
    height = (len(a) + 255) // 256
    data = np.zeros((height * 256, 3, 4), dtype='f4')
    data[:len(a), 0, :a.shape[1]] = a
    data[:len(a), 1, :3] = b
    data[:len(a), 2, :3] = b if flat is None else flat
    inputs = ctx.texture((768, height), 4, data.tobytes(), dtype='f4')
    inputs.use(6)
    output = ctx.texture((256, height), 4, dtype='f4')
    frame = ctx.framebuffer([output])
    frame.use()
    ctx.viewport = (0, 0, 256, height)
    uniform('probe_mode', mode)
    vao.render(vertices=3)
    values = np.frombuffer(output.read(), dtype='f4').reshape(-1, 4)[:len(a)].copy()
    assert np.isfinite(values).all(), (mode, 'NaN or infinity', values)
    assert np.all(values >= -1e-6), (mode, 'Negative transport', values)
    frame.release()
    output.release()
    inputs.release()
    return values


def spot_fog(start, end):
    uniform('afterlightFogBeamCount', 1)
    lit = probe(1, start, end)
    uniform('afterlightFogBeamCount', 0)
    base = probe(1, start, end)
    uniform('afterlightFogBeamCount', 1)
    return lit[:, :3] - base[:, :3]


upload()
target = [.5, .5, 12.]
clear_surface = probe(0, target)[0, :3]
assert clear_surface[0] > .05, ('Spotlight needs no fog to light a surface', clear_surface)
assert np.allclose(probe(0, target, (0, 0, 1))[0, :3], 0, atol=1e-7), 'Back face lit'
assert np.allclose(probe(0, target, (1, 0, 0))[0, :3], 0, atol=1e-7), 'Tangent face lit'
assert np.allclose(probe(0, [5, .5, 12])[0, :3], 0, atol=1e-7), 'Outside cone lit'
assert np.allclose(spot_fog([.5, .5, 10], [.5, .5, 12]), 0, atol=1e-7), 'Visible beam without fog'

# Actual receiver is a solid voxel. Only the caller's geometric .03 bias
# prevents self-shadowing; the DDA must still block earlier opaque voxels.
fill_box(fog, (-8, -4, 12), (9, 8, 13))
fill_box(previous, (-8, -4, 12), (9, 8, 13))
upload()
wall_surface = probe(0, target)[0, :3]
assert np.allclose(wall_surface, clear_surface, rtol=.001, atol=1e-6), ('Receiver self-shadow', wall_surface)
flank = [2.8, .5, 12.]
clear_flank = probe(0, flank)[0, :3]
fill_box(fog, (0, -3, 6), (1, 4, 7))
fill_box(previous, (0, -3, 6), (1, 4, 7))
upload()
assert np.allclose(probe(0, target)[0, :3], 0, atol=1e-7), 'Pillar did not shadow surface'
assert clear_flank[0] > 1e-4 and np.allclose(probe(0, flank)[0, :3], clear_flank, rtol=.005), 'Centerline blocker cut off whole cone'

# A newly occupied cell must block throughout fog interpolation, even when
# the previous density snapshot did not yet contain that pillar.
previous[:] = 0
upload()
for blend in (0., .1, .5, .9, 1.):
    uniform('afterlightFogBlend', blend)
    assert np.allclose(probe(0, target)[0, :3], 0, atol=1e-7), ('Blended wall became transparent', blend)
uniform('afterlightFogBlend', 1.)

fog[:] = previous[:] = 0
attenuation = []
for density in (0., .2, .6, 1.):
    fog[:, :, :, 3] = previous[:, :, :, 3] = density
    upload()
    attenuation.append(float(probe(3, [.5, .5, .5], target)[0, 0]))
assert np.isclose(attenuation[0], 1.) and all(a > b for a, b in zip(attenuation, attenuation[1:])), attenuation
assert np.allclose(attenuation, np.exp(-.035 * 11.5 * np.array([0., .2, .6, 1.])), rtol=1e-5), 'Incorrect Beer extinction'
fog[:, :, :, 3] = .2
previous[:, :, :, 3] = .8
upload()
blend_transmission = []
for blend in (0., .25, .5, .75, 1.):
    uniform('afterlightFogBlend', blend)
    blend_transmission.append(float(probe(3, [.5, .5, .5], target)[0, 0]))
assert all(a < b for a, b in zip(blend_transmission, blend_transmission[1:])), blend_transmission
uniform('afterlightFogBlend', 1.)

fog[:, :, :, 3] = previous[:, :, :, 3] = .4
upload()
clear_fog = spot_fog([.5, .5, 10], [.5, .5, 12])
forward_fog = spot_fog([.5, .5, 12], [.5, .5, 10])
assert clear_fog[0, 0] > 1e-5 and forward_fog[0, 0] > clear_fog[0, 0] * 3, ('HG phase direction reversed', clear_fog, forward_fog)
# Independent dense integration of the single-scattering equation on the
# beam axis. Both source->sample and sample->eye extinction must be present.
travel = (np.arange(16384) + .5) / 16384 * 2
sigma, g = .035 * .4, .35
reference_errors = []
for observed, along, cosine in ((clear_fog[0, 0], 9.5 + travel, -1),
                                (forward_fog[0, 0], 11.5 - travel, 1)):
    phase = (1 - g*g) / (4 * np.pi * (1 + g*g - 2*g*cosine)**1.5)
    reference = np.mean(160 / (1 + along*along) * np.exp(-sigma*along)
                        * np.exp(-sigma*travel) * sigma * .85 * phase) * 2
    reference_errors.append(abs(float(observed) / reference - 1))
assert max(reference_errors) < .002, ('Incorrect light/view transport', reference_errors)
uniform('afterlightFogDensity', 0.)
assert np.allclose(spot_fog([.5, .5, 10], [.5, .5, 12]), 0, atol=1e-7), 'Fog toggle leaves a visible shaft'
assert np.allclose(probe(0, target)[0, :3], clear_surface, rtol=.001), 'Fog toggle extinguishes surface light'
uniform('afterlightFogDensity', 1.)
fill_box(fog, (0, -3, 6), (1, 4, 7))
fill_box(previous, (0, -3, 6), (1, 4, 7))
upload()
blocked_fog = spot_fog([.5, .5, 10], [.5, .5, 12])
assert np.allclose(blocked_fog, 0, atol=1e-6), ('Pillar did not shadow fog', blocked_fog)
uniform('afterlightFogDensity', 0.)
assert np.allclose(probe(0, target)[0, :3], 0, atol=1e-7), 'Fog toggle removes opaque shadows'
uniform('afterlightFogDensity', 1.)

# Static cues, density and camera must not acquire a pulse from Photon's
# temporal phase. Random camera rays also include eye-inside/away views.
fog[:, :, :, 2] = previous[:, :, :, 2] = 0
fog[:, :, :, 3] = previous[:, :, :, 3] = .4
upload()
held = []
for phase in np.linspace(.01, .99, 17):
    uniform('sample_phase', float(phase))
    held.append(probe(1, [-6, 1, 8], [6, 1, 12])[0])
uniform('sample_phase', .5)
temporal_range = np.ptp(held, axis=0)
assert temporal_range.max() < 1e-5, ('Static scene pulses', temporal_range)
rng = np.random.default_rng(9681)
starts = rng.uniform([-12, -6, -5], [12, 8, 28], (4096, 3))
directions = rng.normal(size=(len(starts), 3))
directions /= np.linalg.norm(directions, axis=1)[:, None]
ends = starts + directions * rng.uniform(.001, 50, (len(starts), 1))
camera_rays = probe(1, starts, ends)
assert np.all(camera_rays[:, 3] <= 1.00001), 'Fog amplifies transmittance'
assert np.allclose(probe(1, target, target)[0], [0, 0, 0, 1]), 'Zero camera ray'
motion_steps = []
for heading in ((0, 0, 1), (0, 0, -1), (1, 0, 0)):
    origins = np.full((8193, 3), .5)
    origins[:, 2] = np.linspace(-1, 26, len(origins))
    moving = probe(1, origins, origins + np.array(heading) * 12)
    motion_steps.append(float(np.abs(np.diff(moving[:, :3], axis=0)).max()))
assert max(motion_steps) < .01, ('Camera crossing generates a flash', motion_steps)

# Intersect a voxel for only about 0.00014 blocks. Fixed half-block stepping
# misses this shadow; the shared traversal must visit that crossed cell.
fill_box(fog, (1, 0, 0), (2, 1, 1))
upload()
assert np.allclose(probe(3, [.5, .5, .5], [5.5, .5, 5.499])[0, :3], 0, atol=1e-7), 'Grazing opaque voxel leaked'
assert probe(3, [.5, .5, .5], [5.499, .5, 5.5])[0, 0] > .8, 'Adjacent clear ray falsely shadowed'
fog[:, :, :, 2] = 0
fog[:, :, :, 3] = .4
previous[:] = fog
upload()

# Independent light sources add linearly before the material and display
# transforms. This catches an arbitrary cap reintroduced into shared transport.
single_surface = probe(0, target)[0, :3]
single_fog = spot_fog([.5, .5, 12], [.5, .5, 10])[0]
beams[1:] = beams[0]
upload()
uniform('afterlightFogBeamCount', 8)
eight_surface = probe(0, target)[0, :3]
eight_fog_lit = probe(1, [.5, .5, 12], [.5, .5, 10])[0, :3]
uniform('afterlightFogBeamCount', 0)
eight_fog_base = probe(1, [.5, .5, 12], [.5, .5, 10])[0, :3]
assert np.allclose(eight_surface, single_surface * 8, rtol=1e-5, atol=1e-6), 'Surface sources do not add linearly'
assert np.allclose(eight_fog_lit - eight_fog_base, single_fog * 8, rtol=1e-5, atol=1e-6), 'Fog sources do not add linearly'

# Full-resolution synthetic receiver + fog scene. Four separately coloured
# fixtures illuminate a floor, wall, and one-block pillar using the same grid.
fog[:] = 0
fog[:, :, :, 3] = .35
fill_box(fog, (-24, -4, -8), (40, -3, 56))
fill_box(fog, (-24, -3, 22), (40, 48, 23))
fill_box(fog, (0, -3, 8), (1, 3, 9))
previous[:] = fog
beams[:] = 0
axis = np.array([0., -.12, 1.])
axis /= np.linalg.norm(axis)
for i, (x, color) in enumerate(zip((-7, .5, 4.5, 9), ((1, .04, .02), (.04, .12, 1), (.03, 1, .20), (1, .38, .03)))):
    beams[i] = [[x, 3, 0, 27], [*axis, .18], [*color, .22], [0, 0, 0, 0]]
uniform('afterlightFogBeamCount', 4)
upload()
camera = np.array([13., 7., -5.])
forward = np.array([.5, 0., 12.]) - camera
forward /= np.linalg.norm(forward)
right = np.cross(forward, [0., 1., 0.])
right /= np.linalg.norm(right)
up = np.cross(right, forward)
for name, value in (('image_camera', camera), ('image_forward', forward), ('image_right', right), ('image_up', up)):
    uniform(name, tuple(value))
width, height = 1920, 1080
output = ctx.texture((width, height), 4, dtype='f4')
frame = ctx.framebuffer([output])
frame.use()
ctx.viewport = (0, 0, width, height)
uniform('image_size', (width, height))
timings = {}
for mode, label in ((4, 'surface_only'), (5, 'surface_and_full_resolution_fog')):
    uniform('probe_mode', mode)
    vao.render(vertices=3)
    ctx.finish()
    times = []
    for _ in range(3):
        with ctx.query(time=True) as query:
            vao.render(vertices=3)
        ctx.finish()
        times.append(query.elapsed / 1e6)
    timings[label] = times
    pixels = np.frombuffer(output.read(), dtype='f4').reshape(height, width, 4)
    assert np.isfinite(pixels).all() and np.all(pixels >= -1e-6), label
    preview = pixels[:, :, :3]
    if mode == 4:
        preview = np.power(preview / (1 + preview), 1 / 2.2)
    Image.fromarray((np.flipud(preview).clip(0, 1) * 255).astype('uint8')).save(OUT / f'{label}.png')

# The review gallery uses one documented display transform and material set.
# Fog-off really disables density on both transport legs; it does not merely
# hide the volumetric overlay. All images still run the same combined shader.
gallery_width, gallery_height, exposure = 1280, 720, 4.
gallery_output = ctx.texture((gallery_width, gallery_height), 4, dtype='f4')
gallery_frame = ctx.framebuffer([gallery_output])
gallery_frame.use()
ctx.viewport = (0, 0, gallery_width, gallery_height)
uniform('image_size', (gallery_width, gallery_height))
uniform('image_exposure', exposure)
uniform('image_front_wall', 1)
uniform('probe_mode', 5)
gallery = [
    dict(id='01-no-fog', title='No fog · pillar present', density=0., pillar=True,
         camera=[13., 7., -2.], target=[.5, 0., 12.],
         detail='Direct illumination remains visible without haze. The blue fixture casts a pillar shadow.'),
    dict(id='02-haze', title='Haze · pillar present', density=.35, pillar=True,
         camera=[13., 7., -2.], target=[.5, 0., 12.],
         detail='The same sources light surfaces and scatter through haze; the pillar obstructs both.'),
    dict(id='03-dense-fog', title='Dense fog · pillar present', density=1., pillar=True,
         camera=[13., 7., -2.], target=[.5, 0., 12.],
         detail='Increasing density scatters more light and dims surfaces through source and camera attenuation.'),
    dict(id='04-no-pillar', title='Haze · pillar removed', density=.35, pillar=False,
         camera=[13., 7., -2.], target=[.5, 0., 12.],
         detail='Compare with Haze: only the pillar is removed from geometry and the obstruction grid.'),
    dict(id='05-inside-toward', title='Inside cone · looking toward fixture', density=.35, pillar=True,
         camera=[4.5, 1.08, 16.], target=[4.5, 3., 0.],
         detail='Camera inside the green cone, looking toward its source; forward scattering is stronger.'),
    dict(id='06-inside-away', title='Inside cone · looking away from fixture', density=.35, pillar=True,
         camera=[4.5, 1.08, 16.], target=[4.5, -.12, 26.],
         detail='Same camera position and density, turned away. The illuminated receiver remains visible.'),
]
try:
    title_font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 24)
    caption_font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 15)
except OSError:
    title_font = caption_font = ImageFont.load_default()

def render_scene(scene, labeled=True):
    fog[:] = 0
    fog[:, :, :, 3] = 1.
    fill_box(fog, (-24, -4, -8), (40, -3, 56))
    fill_box(fog, (-24, -3, 22), (40, 48, 23))
    fill_box(fog, (-24, -3, -4), (40, 48, -3))
    if scene['pillar']:
        fill_box(fog, (0, -3, 8), (1, 3, 9))
    previous[:] = fog
    upload()
    uniform('afterlightFogDensity', scene['density'])
    uniform('afterlightFogBeamCount', 4 if scene.get('lights', True) else 0)
    uniform('image_pillar', int(scene['pillar']))
    camera = np.array(scene['camera'])
    forward = np.array(scene['target']) - camera
    forward /= np.linalg.norm(forward)
    right = np.cross(forward, [0., 1., 0.])
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    for name, value in (('image_camera', camera), ('image_forward', forward), ('image_right', right), ('image_up', up)):
        uniform(name, tuple(value))
    vao.render(vertices=3)
    pixels = np.frombuffer(gallery_output.read(), dtype='f4').reshape(gallery_height, gallery_width, 4)
    assert np.isfinite(pixels).all() and np.all(pixels >= 0), scene['id']
    rendered = Image.fromarray((np.flipud(pixels[:, :, :3]).clip(0, 1) * 255).astype('uint8'))
    if labeled:
        captioned = Image.new('RGB', (gallery_width, gallery_height + 80), '#10141b')
        captioned.paste(rendered, (0, 80))
        draw = ImageDraw.Draw(captioned)
        draw.text((20, 12), scene['title'], fill='#f1f5f9', font=title_font)
        draw.text((20, 48), f"Density {scene['density']:g}  |  Fixed exposure +2 EV (4x)  |  Synthetic native GLSL · 1280 × 720",
                  fill='#b6c2d3', font=caption_font)
        rendered = captioned
    scene.setdefault('image', scene['id'] + '.png')
    scene['display_rgb_mean'] = pixels[:, :, :3].mean(axis=(0, 1)).astype(float).tolist()
    rendered.save(OUT / scene['image'])


for scene in gallery:
    render_scene(scene)

cameras = [
    dict(id='overview', label='Overview', camera=[13., 7., -2.], target=[.5, 0., 12.]),
    dict(id='side', label='From the side', camera=[15., 4., 10.], target=[.5, 1.5, 10.]),
    dict(id='stage', label='From the stage', camera=[.5, 4., 1.5], target=[.5, 0., 21.]),
    dict(id='inside-toward', label='Inside cone · toward fixture', camera=[4.5, 1.08, 16.], target=[4.5, 3., 0.]),
    dict(id='inside-away', label='Inside cone · away from fixture', camera=[4.5, 1.08, 16.], target=[4.5, -.12, 26.]),
]
fog_presets = [dict(id='off', label='Off', density=0.), dict(id='haze', label='Haze', density=.35),
               dict(id='dense', label='Dense fog', density=1.)]
interactive = []
(OUT / 'views').mkdir(exist_ok=True)
for camera_choice in cameras:
    for fog_choice in fog_presets:
        for lights in (True, False):
            for pillar in (True, False):
                key = f'{camera_choice["id"]}_{fog_choice["id"]}_{int(lights)}_{int(pillar)}'
                scene = dict(id=key, title=f'{camera_choice["label"]} · {fog_choice["label"]}',
                             camera_id=camera_choice['id'], fog_id=fog_choice['id'],
                             camera=camera_choice['camera'], target=camera_choice['target'],
                             density=fog_choice['density'], lights=lights, pillar=pillar,
                             image=f'views/{key}.png')
                render_scene(scene, labeled=False)
                interactive.append(scene)

parameters = {
    'gpu': ctx.info['GL_RENDERER'], 'render_size': [gallery_width, gallery_height],
    'fixed_exposure_multiplier': exposure, 'fixed_exposure_ev': 2,
    'display_transform': 'Reinhard color/(1+color), then gamma 1/2.2; no auto exposure or bloom',
    'actual_shader_sources_sha256': {name: hashlib.sha256(text.encode()).hexdigest() for name, text in loaded_sources.items()},
    'scope': 'Actual checked-out spotlight and fog GLSL; synthetic ray-traced planes/pillar; not gameplay screenshots',
    'material_albedo': {'wall': .65, 'floor_checker': [.45, .65], 'pillar': .55},
    'ambient_irradiance': .018, 'density_field_value_in_air': 1.,
    'volume_origin': ORIGIN.tolist(), 'volume_size': SIZE, 'noise_sample': .5,
    'frame_time': 0., 'frame_sample_phase': .5, 'current_previous_blend': 1.,
    'receiver_normal_bias': .03, 'vertical_fov_degrees': float(np.degrees(2 * np.arctan(.52))),
    'geometry': {'floor_y': -3, 'rear_wall_z': 22, 'front_wall_z': -3,
                 'pillar_min': [0, -3, 8], 'pillar_max': [1, 3, 9]},
    'beam_texture_rows': beams[:4].astype(float).tolist(), 'scenes': gallery,
    'interactive_cameras': cameras, 'interactive_fog_presets': fog_presets,
    'interactive_scenes': interactive,
}
(OUT / 'scene-parameters.json').write_text(json.dumps(parameters, indent=2) + '\n')
cards = '\n'.join(
    f'<article><a href="{scene["image"]}"><img src="{scene["image"]}" alt="{html.escape(scene["title"])}"></a>'
    f'<h2>{html.escape(scene["title"])}</h2><p>{html.escape(scene["detail"])}</p></article>' for scene in gallery)
page = '''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Afterlight shader bench</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#10141b;color:#eef3fb;font:16px/1.5 system-ui,sans-serif}
main{max-width:1400px;margin:auto;padding:28px}h1{font-size:30px;margin:0 0 8px}a{color:#8dc7ff}
.note{max-width:1000px;color:#bac8da}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,480px),1fr));gap:24px;margin-top:28px}
article{background:#19212d;border:1px solid #344257;border-radius:10px;overflow:hidden}img{width:100%;display:block}
h2{font-size:22px;margin:30px 0 12px}article h2{font-size:19px;margin:16px 18px 6px}article p{margin:0 18px 20px;color:#bac8da}
code,pre{font-size:13px}pre{white-space:pre-wrap;overflow-wrap:anywhere;color:#bac8da}
.controls{display:flex;flex-wrap:wrap;gap:16px 24px;align-items:end;padding:20px;background:#19212d;border:1px solid #344257;border-radius:10px;margin:24px 0 16px}
label{display:flex;flex-direction:column;gap:6px;font-size:14px;font-weight:600}label.toggle{flex-direction:row;align-items:center;padding-bottom:9px}
select{font:inherit;max-width:100%;padding:9px 12px;background:#10141b;color:#eef3fb;border:1px solid #53657c;border-radius:5px}
input[type=checkbox]{width:18px;height:18px;accent-color:#79caff}.controls label:first-child{min-width:250px}
.main-panel{overflow:hidden;border:1px solid #344257;border-radius:10px;background:#080b10}#main-image{aspect-ratio:16/9;object-fit:contain}
.caption{padding:14px 18px;background:#19212d}.caption p{margin:2px 0;color:#bac8da}.caption strong{color:#eef3fb}
.cameras{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:16px 0 24px}
.cameras button{padding:0;overflow:hidden;background:#19212d;color:#bac8da;border:2px solid #344257;border-radius:7px;cursor:pointer;font:inherit;text-align:left}
.cameras button[aria-pressed=true]{border-color:#8dc7ff;color:white}.cameras span{display:block;padding:8px;font-size:13px}
button:focus-visible,select:focus-visible,a:focus-visible{outline:3px solid #f7c965;outline-offset:3px}details{border-top:1px solid #344257;padding:14px 0}
summary{cursor:pointer;color:#bac8da}#status{font-size:13px;min-height:20px;color:#9eb3ca}
@media(max-width:600px){main{padding:16px}.controls{padding:14px}.controls label:first-child{min-width:0;width:100%}}
</style><main><h1>Afterlight shader bench</h1>
<p class="note">Inspect the same scene from five positions. Change fog, stage lights, and the pillar
to compare surface illumination, visible beams, and shadows. Every selection uses the actual GLSL
renderer at <strong>fixed exposure +2 EV (4×)</strong>.</p>
<p class="note">These are 60 repeatable, pre-rendered synthetic views. They are offline test scenes, not gameplay screenshots.</p>
<div class="controls" aria-label="Scene controls">
<label>Camera<select id="camera">__CAMERA_OPTIONS__</select></label>
<label>Fog<select id="fog">__FOG_OPTIONS__</select></label>
<label class="toggle"><input id="lights" type="checkbox" checked>Stage lights</label>
<label class="toggle"><input id="pillar" type="checkbox" checked>Pillar present</label>
</div>
<div class="main-panel"><img id="main-image" src="views/overview_haze_1_1.png" alt="Overview with haze, stage lights, and pillar">
<div class="caption"><strong id="scene-title">Overview · Haze</strong><p id="scene-caption">Stage lights on · Pillar present · Fixed exposure +2 EV</p>
<a id="full-image" href="views/overview_haze_1_1.png">Open full-size image</a></div></div>
<div id="status" role="status" aria-live="polite"></div>
<div class="cameras" id="camera-thumbnails" aria-label="Other camera positions with the same settings"></div>
<details><summary>Exact settings for this view</summary><pre id="current-parameters"></pre></details>
<p><a href="scene-parameters.json">Scene parameters and shader hashes</a> ·
<a href="result.json">GPU regressions and timing</a> · <a href="README.md">Rerun instructions</a></p>
<h2>Reference comparisons</h2><p class="note">Stable labeled images: density (1–3), pillar (2–4), and viewing direction inside a cone (5–6).</p>
<noscript><p>The controls require JavaScript. All reference images below remain available.</p></noscript>
<div class="grid">__CARDS__</div></main>
<script id="bench-data" type="application/json">__BENCH_DATA__</script>
<script>
'use strict';
const bench=JSON.parse(document.getElementById('bench-data').textContent);
const scenes=new Map(bench.scenes.map(scene=>[scene.id,scene]));
const camera=document.getElementById('camera'),fog=document.getElementById('fog');
const lights=document.getElementById('lights'),pillar=document.getElementById('pillar');
const mainImage=document.getElementById('main-image'),status=document.getElementById('status');
const thumbnails=document.getElementById('camera-thumbnails');
let request=0;
function key(id=camera.value){return `${id}_${fog.value}_${Number(lights.checked)}_${Number(pillar.checked)}`;}
const buttons=bench.cameras.map(view=>{
 const button=document.createElement('button');button.type='button';button.setAttribute('aria-label',view.label);
 const image=document.createElement('img');image.alt=view.label;image.loading='lazy';
 const label=document.createElement('span');label.textContent=view.label;
 button.append(image,label);button.addEventListener('click',()=>{camera.value=view.id;update();});
 thumbnails.append(button);return {view,button,image};
});
function restoreHash(){
 const p=new URLSearchParams(location.hash.slice(1));
 if(bench.cameras.some(view=>view.id===p.get('camera')))camera.value=p.get('camera');
 if(bench.fog.some(value=>value.id===p.get('fog')))fog.value=p.get('fog');
 if(p.has('lights'))lights.checked=p.get('lights')!=='0';
 if(p.has('pillar'))pillar.checked=p.get('pillar')!=='0';
}
function update(saveHash=true){
 const scene=scenes.get(key());if(!scene){status.textContent='This scene is unavailable.';return;}
 for(const {view,button,image} of buttons){image.src=scenes.get(key(view.id)).image;button.setAttribute('aria-pressed',String(view.id===camera.value));}
 if(saveHash){const p=new URLSearchParams({camera:camera.value,fog:fog.value,lights:Number(lights.checked),pillar:Number(pillar.checked)});
  try{history.replaceState(null,'','#'+p);}catch(error){/* Image controls also work without history permission. */}}
 const version=++request;status.textContent='Loading selected view…';
 const next=new Image();next.onload=()=>{
  if(version!==request)return;
  mainImage.src=scene.image;mainImage.alt=scene.title;
  document.getElementById('scene-title').textContent=scene.title;
  document.getElementById('scene-caption').textContent=`Stage lights ${scene.lights?'on':'off'} · Pillar ${scene.pillar?'present':'removed'} · Density ${scene.density} · Fixed exposure +2 EV (4×)`;
  document.getElementById('full-image').href=scene.image;
  document.getElementById('current-parameters').textContent=JSON.stringify({camera:scene.camera,look_at:scene.target,
   fog_density:scene.density,stage_lights:scene.lights,pillar:scene.pillar,exposure_multiplier:4,
   render_size:[1280,720],image:scene.image},null,2);
  status.textContent='1280 × 720 · Native GLSL render · Exposure is identical in every view';
 };
 next.onerror=()=>{if(version===request)status.textContent='Image missing. Keep the views folder beside this page.';};
 next.src=scene.image;
}
for(const control of [camera,fog,lights,pillar])control.addEventListener('change',()=>update());
window.addEventListener('hashchange',()=>{restoreHash();update(false);});
restoreHash();update(false);
</script></html>
'''
camera_options = ''.join(f'<option value="{c["id"]}">{html.escape(c["label"])}</option>' for c in cameras)
fog_options = ''.join(f'<option value="{f["id"]}"{" selected" if f["id"] == "haze" else ""}>{f["label"]}</option>' for f in fog_presets)
bench_data = json.dumps({'cameras': cameras, 'fog': fog_presets, 'scenes': interactive}, separators=(',', ':')).replace('<', '\\u003c')
(OUT / 'index.html').write_text(page.replace('__CAMERA_OPTIONS__', camera_options).replace('__FOG_OPTIONS__', fog_options)
                              .replace('__CARDS__', cards).replace('__BENCH_DATA__', bench_data))
(OUT / 'README.md').write_text(f'''# Afterlight shader bench

Open `index.html` in your browser. Choose one of five cameras, fog off/haze/dense,
stage lights on/off, and the pillar present/removed. The 60 combinations are
native GLSL renders at the same fixed exposure. Camera thumbnails preserve your
lighting settings, and the URL hash remembers the current selection. Six labeled
reference images retain their original filenames below the interactive viewer.
This folder is self-contained; keep its `views/` folder when copying it elsewhere.

These are synthetic scenes using the checked-out spotlight and fog shader source,
not Minecraft screenshots. The geometry renderer and fixed display transform are
part of the probe. Photon's in-game exposure, bloom and material pipeline are not
reproduced. All gallery images use +2 EV (4×), Reinhard mapping, gamma 1/2.2,
neutral materials, and fixed time/noise. Nothing adjusts exposure per image.

The no-fog image sets `afterlightFogDensity=0`, disabling density on both light
paths while keeping surface illumination and opaque shadows. The pillar
comparison removes the pillar from both visible geometry and the occupancy grid.
The last two images share a camera position inside the green cone.

Run with a Python environment containing `moderngl`, `numpy`, and `Pillow`:

```sh
python {ROOT / 'tools/test_spotlight_transport_gpu.py'} --output /path/to/shader-bench
```

Omit `--output` to write to the repository's ignored build directory. A native
EGL OpenGL 3.3 context is required. This command does not open or control the game.
`scene-parameters.json` records all scene settings and the exact compiled shader
source hashes. `result.json` records numeric regressions and 1920×1080 GPU timings;
the fog benchmark deliberately runs at full resolution, unlike Photon's reduced
resolution volumetric pass. Interactive views are 1280×720. The six labeled
reference images add an 80-pixel caption strip. No dependencies or network
connection are needed to use the generated inspection page.
''')

report = {'gpu': ctx.info['GL_RENDERER'], 'surface_without_fog': clear_surface.tolist(),
          'receiver_self_shadow': False, 'back_faces_and_outside_cone_dark': True,
          'pillar_shadows_surface_and_fog': True, 'off_axis_pillar_bypass_lit': True,
          'fog_toggle_preserves_surface_light_and_occlusion': True,
          'light_transmission_at_density_0_02_06_1': attenuation,
          'current_previous_blend_transmission': blend_transmission,
          'fog_looking_away': clear_fog.tolist(), 'fog_looking_toward_light': forward_fog.tolist(),
          'single_scattering_reference_max_relative_error': max(reference_errors),
          'blocked_fog': blocked_fog.tolist(), 'temporal_phase_range': temporal_range.tolist(),
          'finite_camera_rays': len(camera_rays), 'full_resolution': [width, height],
          'continuous_camera_rays': 3 * 8193, 'maximum_camera_step_radiance': max(motion_steps),
          'grazing_voxel_shadow': True, 'eight_sources_add_linearly': True,
          'gpu_milliseconds': timings, 'previews_are_synthetic_not_gameplay': True}
report['gallery'] = {'index': 'index.html', 'scene_count': len(gallery),
                     'interactive_scene_count': len(interactive),
                     'render_size': [gallery_width, gallery_height], 'fixed_exposure_multiplier': exposure}
(OUT / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
