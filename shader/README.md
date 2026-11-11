Afterlight / Photon 1.4

Photon is by sixthsurge and contributors: https://github.com/sixthsurge/photon
Its original LICENSE is included verbatim in this archive and PHOTON-LICENSE
in the Afterlight source. This is a modified modpack shader, not an official
Photon release. Redistribution must follow that licence.

NoopsNet additions: afterlight.glsl, RGB block-light injection in diffuse_lighting,
and brighter bloom/emission defaults. Reproduce with tools/build_shader.py;
upstream.lock.json pins the unmodified archive and its SHA-512.

Requires NoopsNet Afterlight 0.4.0 and Iris 1.8.14-beta.1 on NeoForge 1.21.1
for player-selected RGB spill. The shader continues to render without the mod
using ordinary block lighting. Distant Horizons is supported by Photon; Afterlight
lighting covers nearby full-detail blocks, not distant terrain meshes.

The 48-block volume updates at most four times per second, tracks the nearest
96 active placed Afterlight sources, and floods through loaded blocks with
Minecraft opacity attenuation. Closed opaque walls block it; light can travel
around corners and through doorways. Thin non-opaque shapes use Minecraft's
whole-block opacity, not per-triangle ray tracing. Redstone and palette changes
appear on the next update. Bloom is a separate screen-space glow effect.

Complementary is kept unchanged and remains selectable, but cannot read this
custom RGB volume. These modifications do not change shader packs globally.

Version 0.4.0 keeps unchanged snapshots untouched, cross-fades changed volumes
over 180 ms in world coordinates, and uses absolute LED irradiance so dim/white
lights do not inherit base-light brightness fluctuations.

Version 0.4.1 adds an experimental macOS OpenGL 4.1 path, selected automatically
by Iris's MC_OS_MAC macro. It backports the three-pass lighting split and
fragment-based SH skylight from Jacob Paterson's Photon PR #670, commit
73cdbebebf545716129a447386de9d1549634e8d:
https://github.com/sixthsurge/photon/pull/670

Afterlight adaptations preserve Photon 1.4's matrix-based SH interface and
Minecraft 1.21.1 overlays, and move the galaxy to a named custom texture on Mac
so it cannot override the intermediate shadow buffer. Both Afterlight RGB
volumes remain enabled, including temporal cross-fading. Photon compute-based
voxel lighting is disabled on Mac; Afterlight's CPU-built RGB lighting remains.
With Distant Horizons enabled on Mac, environment reflections and cloud shadows
are disabled to leave enough sampler slots. Sky reflections, bloom, RGB spill
and ordinary shadows remain. Desktop rendering retains the single lighting pass.

Build: python3 community/mods/noopsnet-neon/tools/build_shader.py
Validate: python3 community/mods/noopsnet-neon/tools/verify_shader_mac.py --profile high
Repeat with --dh and low/medium/ultra. Requires glslangValidator and cpp.
The checker compiles enabled vertex/fragment programs in all three dimensions,
counts reflected samplers, and reserves Iris texture units conservatively.
This is static validation, not an Apple-driver or in-game visual certification.

For the initial Mac gameplay test, disable Distant Horizons in Prism because the
reported native crash was in DH's instanced draw path. Select
Afterlight-Photon-1.4-0.4.1.zip in Iris; the old ZIP can remain for rollback.
Check RGB spill, smooth color changes, shadows, sky, glass/water, and all three
dimensions. The shader update does not repair WaterFrames' null-player packet
exception or establish that DH's native crash has been fixed.

Version 0.4.2 enables Iris separate translucent entity draws on Minecraft 1.21.1
for modded label/background transparency (Photon issue #569), and gives the
forward emissive-eyes pass premultiplied-alpha blending on colortex13.
Cobblemon's authored emissive textures remain intact. The player confirmed the
nameplate fix in game on macOS on 2026-11-11.
The compatibility patch also keeps enchantment glint on the matching forward
render target and guards the macOS compose pass's optional G-buffer clear.
Reference: https://github.com/sixthsurge/photon/issues/569

0.4.2 ships in both alpha.23 client variants.
Validation uses shader compilation and preprocessed pass/blend checks, not an
automated Minecraft screenshot.

Version 0.4.4 must be paired with Afterlight 0.5.3. Opaque voxels are explicitly
marked so interpolation cannot dilute valid lighting with wall interiors and
expose warm vanilla blocklight along corners. Dark air still participates in
interpolation. Normal-less particle passes sample without a surface-normal
offset. The mod also restores Iris NeoForge's omitted translucent-particle shader
selection hook. An offscreen GPU room fixture verifies corner blackout, color
continuity, closed walls and invalid particle normals. In-game acceptance remains
the player's check; see `tools/lighting_surface_test.py`.

Version 0.4.3 removes the low-energy cutoff that could abruptly restore white
blocklight at an RGB volume boundary. Zero energy still preserves ordinary
lighting; dim samples now fade continuously, without dividing RGB by tiny
energy values. Pair it with Afterlight 0.5.2, which samples its transition time
once per render frame so opaque and translucent passes agree. If asynchronous
results arrive during a fade, the latest stays pending until the existing
180 ms transition finishes, preventing a jump to an unseen endpoint.

Regression coverage includes a static source beside an animated source,
interrupted worker timing, camera-volume rebasing, and zero/minimum RGBA16F
energy. Run `tools/lighting_temporal_test.py` with moderngl/NumPy on an offline
EGL context, plus the Java tests and shader compilers above. These checks do not
replace the player's in-game visual acceptance.
