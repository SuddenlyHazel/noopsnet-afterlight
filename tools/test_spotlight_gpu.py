#!/usr/bin/env python3
"""Native GLSL camera/cone regressions; does not open Minecraft or a launcher."""
import argparse
import json
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--shader', type=Path, default=ROOT / 'shader/afterlight_fog.glsl')
args = parser.parse_args()
source = args.shader.read_text().replace(
    '#include "/include/lighting/afterlight.glsl"',
    (ROOT / 'shader/afterlight.glsl').read_text()).replace('#include "/include/lighting/afterlight_spotlight.glsl"', (ROOT/'shader/afterlight_spotlight.glsl').read_text())
ctx = moderngl.create_standalone_context(require=330, backend='egl')
program = ctx.program(vertex_shader='''#version 330
void main() {
    vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
    gl_Position=vec4(p*2.-1.,0,1);
}
''', fragment_shader='''#version 330
#define rec709_to_working_color mat3(1.)
#define BLOCKLIGHT_I 1.
#define clamp01(x) clamp(x,0.,1.)
uniform sampler3D colortex0;
uniform vec3 cameraPosition,ambient_color;
uniform float frameTimeCounter,blindness,darknessFactor;
''' + source + '''
uniform sampler2D probes;
uniform int probe_mode;
out vec4 result;
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
    vec4 a=texelFetch(probes,ivec2(p.x*4,p.y),0);
    vec4 b=texelFetch(probes,ivec2(p.x*4+1,p.y),0);
    if(probe_mode==0) {
        vec4 c=texelFetch(probes,ivec2(p.x*4+2,p.y),0);
        vec4 d=texelFetch(probes,ivec2(p.x*4+3,p.y),0);
        float lo=a.w,hi=b.w;
        bool hit=afterlight_cone_interval(a.xyz,b.xyz,c,d.x,d.y,lo,hi);
        result=vec4(hit?1.:0.,lo,hi,0.);
    } else result=vec4(afterlight_integrate_spots(a.xyz,b.xyz,a.w,b.w),1.);
}
''')
vao = ctx.vertex_array(program, [])


def uniform(name, value):
    if name in program:
        program[name] = value


def run(probes, mode):
    height = (len(probes) + 511) // 512
    padded = np.zeros((height * 512, 4, 4), dtype='f4')
    padded[:len(probes)] = probes
    data = ctx.texture((2048, height), 4, padded.tobytes(), dtype='f4')
    output = ctx.texture((512, height), 4, dtype='f4')
    frame = ctx.framebuffer([output])
    data.use(5)
    uniform('probes', 5)
    uniform('probe_mode', mode)
    frame.use()
    ctx.viewport = (0, 0, 512, height)
    vao.render(vertices=3)
    result = np.frombuffer(output.read(), dtype='f4').reshape(-1, 4)[:len(probes)].copy()
    assert np.isfinite(result).all(), 'Spotlight produced NaN or infinity'
    frame.release()
    output.release()
    data.release()
    return result


# Independent double-precision convex-cone reference, including near-parallel
# rays. A positive finite cone intersects a line in at most one interval.
rng = np.random.default_rng(7331)
count = 262144
probes = np.zeros((count, 4, 4), dtype='f4')
probes[:, 0, :3] = rng.uniform(-40, 40, (count, 3))
probes[:, 1, :3] = rng.normal(size=(count, 3))
probes[:, 1, :3] /= np.linalg.norm(probes[:, 1, :3], axis=1)[:, None]
probes[:, 1, 3] = 60
probes[:, 2] = [0, 0, 1, .18]
probes[:, 3, :2] = [.22, 24]
half = count // 2
probes[half:, 1, 2] = rng.choice([-1, 1], half) / np.sqrt(1 + .22**2)
probes[half:, 1, 0] = .22 / np.sqrt(1 + .22**2) + rng.uniform(-1e-5, 1e-5, half)
probes[half:, 1, 1] = 0
probes[half:, 1, :3] /= np.linalg.norm(probes[half:, 1, :3], axis=1)[:, None]
intervals = run(probes, 0)
# A collision can clip a fixture to zero length. Its cap has no volume.
flat = np.zeros((1, 4, 4), dtype='f4')
flat[0, 1] = [1, 0, 0, 60]
flat[0, 2] = [0, 0, 1, .18]
flat[0, 3, 0] = .22
assert run(flat, 0)[0, 0] == 0
p = probes.astype('f8')
origin, direction = p[:, 0, :3], p[:, 1, :3]
axial, slope, radius, length = origin[:, 2], p[:, 3, 0], p[:, 2, 3], p[:, 3, 1]
dz = direction[:, 2]
near, far = -axial / dz, (length - axial) / dz
lo = np.maximum(0, np.minimum(near, far))
hi = np.minimum(60, np.maximum(near, far))
base_radius = radius + slope * axial
A = np.sum(direction[:, :2]**2, axis=1) - (slope * dz)**2
B = 2 * (np.sum(origin[:, :2] * direction[:, :2], axis=1) - base_radius * slope * dz)
C = np.sum(origin[:, :2]**2, axis=1) - base_radius**2
vertex = np.clip(-B / (2 * A), lo, hi)
f = lambda t: A * t * t + B * t + C
minimum = np.minimum(f(lo), f(hi))
minimum = np.where(A > 0, np.minimum(minimum, f(vertex)), minimum)
expected = (hi > lo) & (minimum < 0)
assert np.array_equal(intervals[:, 0] > .5, expected), 'Cone hit classification changed'

# RESET stage in coordinates relative to (64,65.62,0), with conservative dense
# uniform haze. Test the original camera-inside failure independently of fog
# temporal dither, the global glare shoulder, or changing cue colours.
fog = np.zeros((48, 48, 48, 4), dtype='f4')
fog[:, :, :, 3] = .5
field = ctx.texture3d((48, 48, 48), 4, fog.tobytes(), dtype='f4')
field.filter = (moderngl.LINEAR, moderngl.LINEAR)
field.repeat_x = field.repeat_y = field.repeat_z = False
field.use(0)
noise = ctx.texture3d((4, 4, 4), 1, np.full((4, 4, 4), .5, dtype='f4').tobytes(), dtype='f4')
noise.use(1)
beams = np.zeros((8, 4, 4), dtype='f4')
for i, x in enumerate((52, 60, 68, 76)):
    beams[i] = [[x+.5-64, 3.03, -7.9, 24], [0, 0, 1, .18], [1, .02, .02, .22], [0, 0, 0, 0]]
beam_texture = ctx.texture((4, 8), 4, beams.tobytes(), dtype='f4')
beam_texture.use(2)
for name, value in {'afterlightFogVolume': 0, 'colortex0': 1, 'afterlightFogBeams': 2,
                    'afterlightFogBlend': 1., 'afterlightFogDensity': 1., 'afterlightFogBeamCount': 4,
                    'afterlightFogOrigin': (-24., -24., -24.), 'afterlightFogSize': (48., 48., 48.),
                    'afterlightFogBeamOrigin': (0., 0., 0.), 'cameraPosition': (64., 65.62, 0.)}.items():
    uniform(name, value)


def rays(origins, directions):
    a = np.zeros((len(origins), 4, 4), dtype='f4')
    a[:, 0, :3] = origins
    a[:, 1, :3] = directions
    a[:, 1, :3] /= np.linalg.norm(a[:, 1, :3], axis=1)[:, None]
    a[:, 1, 3] = 60
    return run(a, 1)[:, :3]


def camera_sweep(resolution):
    motion = []
    for heading in ((0, 0, 1), (0, 0, -1), (1, 0, 0)):
        origins = np.zeros((resolution, 3))
        origins[:, 0], origins[:, 1] = -3.5, 3.03
        origins[:, 2] = np.linspace(-10, 20, len(origins))
        motion.append(rays(origins, heading))
        origins[:, 0] = np.linspace(-10, 10, len(origins))
        origins[:, 2] = 13
        motion.append(rays(origins, heading))
    return motion


# Physical inverse-square radiance has steeper near-source gradients than the
# former compressed overlay. Smooth gradients converge with camera step size;
# discontinuities do not. Check that distinction, retaining the .003 bound at
# the finer resolution instead of accepting a larger arbitrary brightness jump.
coarse = camera_sweep(4097)
motion = camera_sweep(16385)
coarse_steps = np.array([np.abs(np.diff(a, axis=0)).max() for a in coarse])
fine_steps = np.array([np.abs(np.diff(a, axis=0)).max() for a in motion])
assert np.all(fine_steps <= coarse_steps*.30 + 1e-6), ('Nonconvergent camera crossing', coarse_steps, fine_steps)
max_step = max(float(np.abs(np.diff(a, axis=0)).max()) for a in motion)
assert max_step < .003, ('Discontinuous camera crossing', max_step)
assert all(np.all(a >= 0) for a in motion)
# A nonzero source aperture without a near-cap taper formerly switched all
# side-on shafts on at z=-7.9, producing a .096 red jump at any step resolution.
source_plane = rays([[-3.5, 3.03, -7.9002], [-3.5, 3.03, -7.8998]], (1, 0, 0))
source_plane_jump = float(np.abs(source_plane[1] - source_plane[0]).max())
assert source_plane_jump < 1e-5, ('Hard source aperture cap', source_plane_jump)

# At the cone's far end the eye used to collect a large omnidirectional red
# sheet from the first few metres, including rays aimed away from the fixtures.
xy = np.stack(np.meshgrid(np.linspace(-.8, .8, 33), np.linspace(-.5, .5, 21)), axis=-1).reshape(-1, 2)
away = rays(np.tile([-3.5, 3.03, 13.], (len(xy), 1)), np.column_stack((xy, np.ones(len(xy)))))
toward = rays(np.tile([-3.5, 3.03, 13.], (len(xy), 1)), np.column_stack((xy, -np.ones(len(xy)))))
away_peak = float(away.max())
assert away_peak < .025, ('Near-eye spotlight screenwash', away_peak)
assert float(toward.max()) > .10, 'Facing the fixture must retain a visible spotlight'
report = {'gpu': ctx.info['GL_RENDERER'], 'cone_reference_cases': count,
          'camera_motion_rays': sum(len(a) for a in motion), 'maximum_motion_step': max_step,
          'coarse_camera_motion_rays': sum(len(a) for a in coarse),
          'coarse_maximum_motion_step': float(coarse_steps.max()),
          'quarter_step_maximum_ratio': float((fine_steps / np.maximum(coarse_steps, 1e-10)).max()),
          'source_plane_jump': source_plane_jump,
          'inside_cone_away_peak': away_peak, 'inside_cone_toward_peak': float(toward.max()),
          'all_results_finite_nonnegative': True}
out = ROOT / 'build/mist-validation'
out.mkdir(parents=True, exist_ok=True)
(out / 'spotlight-result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
