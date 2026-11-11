#!/usr/bin/env python3
"""Render the Java lighting fixture through the shipped GLSL, without Minecraft."""
import argparse
import json
from pathlib import Path

import moderngl
import numpy as np

from build_shader import ROOT

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--shader', type=Path, default=ROOT / 'shader/afterlight.glsl')
args = parser.parse_args()
bridge = args.shader.read_text()
fixture = ROOT / 'build/lighting-fixture'
source = np.fromfile(fixture / 'corner-volume.rgba32f', dtype='<f4').reshape(24, 24, 24, 4)
ctx = moderngl.create_standalone_context(require=330, backend='egl')
vertex = '''#version 330
void main() {
    vec2 xy = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(xy * 2.0 - 1.0, 0, 1);
}'''
tail = '''
uniform vec3 point;
uniform vec3 surface_normal;
uniform vec3 original;
uniform float vanilla_level;
out vec4 result;
void main() {
    result = vec4(afterlight_blocklight(point, surface_normal, original, vanilla_level), 1);
}'''
target = ctx.texture((1, 1), 4, dtype='f4')
framebuffer = ctx.framebuffer([target])
framebuffer.use()
textures = [ctx.texture3d((24, 24, 24), 4, dtype='f2') for _ in range(2)]
for unit, texture in enumerate(textures):
    texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
    texture.repeat_x = texture.repeat_y = texture.repeat_z = False
    texture.use(unit)


def program(no_normal=False):
    defines = '#define NO_NORMAL\n' if no_normal else ''
    shader = ctx.program(vertex_shader=vertex, fragment_shader=(
        '#version 330\n#define rec709_to_working_color mat3(1.0)\n'
        '#define BLOCKLIGHT_I 1.0\n' + defines + bridge + tail))
    shader['afterlightLightVolume'] = 0
    shader['afterlightPreviousVolume'] = 1
    for prefix in ('afterlightVolume', 'afterlightPrevious'):
        shader[prefix + 'Origin'] = (0, 0, 0)
        shader[prefix + 'Size'] = (24, 24, 24)
    shader['afterlightVolumeBlend'] = 1
    shader['original'] = (.8, .4, .2)
    shader['vanilla_level'] = .64
    return shader, ctx.vertex_array(shader, [])


def upload(field, slot=0):
    textures[slot].write(field.astype('f2').tobytes())


def sample(shader, vao, point, normal=(0, 0, 0)):
    shader['point'] = point
    # The fixed NO_NORMAL variant intentionally eliminates this uniform.
    if 'surface_normal' in shader:
        shader['surface_normal'] = normal
    vao.render(vertices=3)
    return np.frombuffer(target.read(), dtype='f4')[:3].copy()


solid, solid_vao = program()
upload(source)
upload(source, 1)
positions = [(12.5, float(y), 16) for y in np.linspace(15.5, 15.999, 128)]
colored = np.array([sample(solid, solid_vao, p, (0, 0, -1)) for p in positions])
corner_span = float(np.ptp(colored, axis=0).max())
assert corner_span < 1e-5, ('Opaque ceiling must not dim a lit wall', corner_span)

black = source.copy()
black[:, :, :, :3] = 0
upload(black)
blackout = np.array([sample(solid, solid_vao, p, (0, 0, -1)) for p in positions])
blackout_leak = float(np.max(np.abs(blackout)))
assert blackout_leak < 1e-6, ('Blackout must not expose a warm corner band', blackout_leak)

# Current black and previous green fields keep identical coverage even where
# multiple opaque cells enter the interpolation footprint.
frames = []
for blend in np.linspace(0, 1, 61):
    solid['afterlightVolumeBlend'] = float(blend)
    frames.append(sample(solid, solid_vao, (12.5, 15.99, 15.99)))
frames = np.array(frames)
assert np.isfinite(frames).all()
assert np.max(np.abs(frames[:, (0, 2)])) < 1e-6, 'A green-to-black fade must never restore warm light'
assert np.all(np.diff(frames[:, 1]) <= 1e-6), 'Blackout fade must remain monotonic'
solid['afterlightVolumeBlend'] = 1
upload(source)

# The Java fixture has a complete opaque wall and ceiling. Outside air is dark
# and must remain part of the interpolation, even immediately beside the wall.
for point in ((12.5, 12.5, 17.01), (12.5, 17.01, 12.5), (12.5, 16.5, 16.5)):
    np.testing.assert_allclose(sample(solid, solid_vao, point), (.8, .4, .2), atol=1e-6)

particle, particle_vao = program(no_normal=True)
point = (12.5, 15.8, 15.8)
expected = sample(particle, particle_vao, point)
assert expected[1] > 1 and np.max(np.abs(expected[[0, 2]])) < 1e-6
for normal in ((1, 0, 0), (0, 1, 0), (float('nan'),) * 3):
    actual = sample(particle, particle_vao, point, normal)
    np.testing.assert_allclose(actual, expected, atol=1e-6,
                               err_msg='Normal-less particles must sample their own position')

report = {
    'renderer': ctx.info['GL_RENDERER'],
    'opaque_corner_radiance_span': corner_span,
    'blackout_warm_leak': blackout_leak,
    'corner_green_to_black_fade': 'passed',
    'closed_wall_and_dark_air': 'passed',
    'normal_less_particle_and_nan_normal': 'passed',
    'passed': True,
}
(fixture / 'surface-result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
