# Repeatable spotlight shader bench

Run `test_spotlight_transport_gpu.py` with Python, ModernGL, NumPy and Pillow,
using a native EGL OpenGL 3.3 context. It renders the checked-out Afterlight GLSL;
it does not launch Minecraft or operate the launcher.

From the repository root:

```sh
python community/mods/noopsnet-neon/tools/test_spotlight_transport_gpu.py
```

The default output is the ignored directory
`community/mods/noopsnet-neon/build/spotlight-transport-validation/`.
Open its `index.html` for the interactive inspection page. Select an overview,
side view, view from the stage, or either direction inside a cone; then choose
fog off/haze/dense, stage lights on/off, and the pillar present/removed.
All 60 combinations are pre-rendered with the native GLSL renderer. Camera
thumbnails retain the current lighting settings, and the URL hash remembers
the selection. The original six labeled reference images remain below the
viewer with stable filenames.

To place a self-contained copy elsewhere, add `--output /path/to/shader-bench`.
Keep the generated `views/` folder beside `index.html`. The page works directly
from disk with plain JavaScript/CSS and needs no server, packages, or network.

The comparisons use fixed exposure +2 EV (4×), neutral wall/pillar materials, a
checker floor, constant time/noise, Reinhard mapping, and gamma 1/2.2. Exposure
does not adapt between images. The no-fog case sets the shared density control
to zero, preserving surface light and opaque shadows. The pillar comparison
removes both its rendered geometry and occupied voxels. The final pair looks
toward and away from a fixture from one position inside its cone.

These are synthetic test scenes, not gameplay screenshots. They use the actual
spotlight/fog GLSL with simple ray-traced geometry and a documented display
transform; they do not reproduce Photon's complete exposure, bloom or material
pipeline. `scene-parameters.json` records the scene and compiled shader hashes.

The same run checks surface illumination without fog, receiver bias, facing
normals, cone limits, shared pillar shadows, grazing voxel intersections, density
blending, Beer extinction, an independent single-scattering reference, source
linearity, temporal stability and camera crossings. `result.json` records the
results and native 1920×1080 timing. The full-resolution fog timing is a deliberate
stress test; Photon's volumetric pass normally runs at reduced resolution.
