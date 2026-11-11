# Photon 1.4 macOS compatibility backport

Adapted from Jacob Paterson's Photon PR [#670](https://github.com/sixthsurge/photon/pull/670),
commit `73cdbebebf545716129a447386de9d1549634e8d`. Covered by the bundled
[Photon license](../PHOTON-LICENSE). These are complete replacement source files,
not a new upstream release. `upstream.json` pins the SHA-256 of each original
file (null means new); the builder refuses changed inputs.

The shader README describes behavior and validation. NoopsNet adaptations:

- Preserve the pinned version's matrix SH representation and overlay behavior.
- Use `afterlightGalaxy` on Mac instead of aliasing the intermediate shadow buffer.
- Budget both custom RGB textures, including the DH compatibility fallback.
- Compile out unreachable reflection sampling when environment reflections are off.
- Fix quality-profile AO names and missing rain uniforms.

When upgrading Photon, review each replacement against the new source and
rerun the entire profile/dimension/DH matrix. Do not merely update the hashes.
