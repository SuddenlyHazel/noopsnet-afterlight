#!/usr/bin/env python3
"""Reproducibly build the licensed Photon derivative; never modify Complementary."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT/'shader/upstream.lock.json').read_text())


def build():
    cache = ROOT/'.cache/shaders'
    cache.mkdir(parents=True, exist_ok=True)
    upstream = cache/LOCK['file']['filename']
    f = LOCK['file']
    if not upstream.exists():
        request = urllib.request.Request(f['url'], headers={'User-Agent':'NoopsNet-Afterlight/0.3'})
        with urllib.request.urlopen(request, timeout=90) as response: data = response.read()
        assert len(data) == f['size'] and hashlib.sha512(data).hexdigest() == f['hashes']['sha512']
        upstream.write_bytes(data)
    data = upstream.read_bytes()
    assert len(data) == f['size'] and hashlib.sha512(data).hexdigest() == f['hashes']['sha512'], 'Photon checksum mismatch'
    with ZipFile(io.BytesIO(data)) as z:
        files = {n:z.read(n) for n in z.namelist() if not n.endswith('/')}
    license_text = (ROOT/'shader/PHOTON-LICENSE').read_bytes()
    assert files['LICENSE'].strip() == license_text.strip(), 'Review changed upstream licence'
    # Reviewed backport of Photon PR #670. Verify every replaced upstream file
    # so a future Photon update cannot silently invalidate the compatibility port.
    compat = ROOT / 'shader/mac-compat'
    manifest = json.loads((compat / 'upstream.json').read_text())
    for path, expected in manifest['files'].items():
        actual = hashlib.sha256(files[path]).hexdigest() if path in files else None
        assert actual == expected, f'Review macOS backport for changed upstream file: {path}'
        files[path] = (compat / path).read_bytes()
    path = 'shaders/include/lighting/diffuse_lighting.glsl'
    source = files[path].decode()
    marker = '#include "/include/lighting/bsdf.glsl"'
    assert source.count(marker) == 1
    source = source.replace(marker, marker+'\n#include "/include/lighting/afterlight.glsl"')
    marker = '#ifdef HANDHELD_LIGHTING\n    lighting += get_handheld_lighting(scene_pos, ao);'
    assert source.count(marker) == 1
    source = source.replace(marker, 'lighting = afterlight_blocklight(scene_pos, flat_normal, lighting, light_levels.x);\n\n'+marker)
    files[path] = source.encode()
    files['shaders/include/lighting/afterlight.glsl'] = (ROOT/'shader/afterlight.glsl').read_bytes()
    # Defaults can still be adjusted in the standard shader options screen.
    path = 'shaders/settings.glsl'
    source = files[path].decode()
    for setting, old, new in [('BLOOM_INTENSITY','1.00','1.35'),('BLOOM_SPREAD','1.00','1.15'),('EMISSION_STRENGTH','1.00','1.50')]:
        marker = f'#define {setting} {old} '
        assert source.count(marker) == 1
        source = source.replace(marker, f'#define {setting} {new} ')
    # LabPBR normals/specular/emission; Photon defaults to vanilla resources otherwise.
    source = source.replace('//#define NORMAL_MAPPING', '#define NORMAL_MAPPING')
    source = source.replace('//#define SPECULAR_MAPPING', '#define SPECULAR_MAPPING')
    # Iris 1.8 supports separate translucent entity draws on MC 1.21.1.
    # Photon gates this on newer Minecraft, routing Cobblemon labels through
    # the opaque G-buffer and losing their background alpha (Photon #569).
    marker = '#if MC_VERSION > 12111\n  #define USE_SEPARATE_ENTITY_DRAWS\n#endif'
    assert source.count(marker) == 1
    source = source.replace(marker, '#if defined IS_IRIS || MC_VERSION > 12111\n  #define USE_SEPARATE_ENTITY_DRAWS\n#endif')
    files[path] = source.encode()
    path = 'shaders/shaders.properties'
    source = files[path].decode()
    marker = '# Separate Entity Draws\n#if MC_VERSION <= 12111'
    assert source.count(marker) == 1
    source = source.replace(marker, '# Separate Entity Draws\n#ifdef IS_IRIS\nseparateEntityDraws = true\n#endif\n#if !defined IS_IRIS && MC_VERSION <= 12111')
    # The emissive-eyes pass now also writes premultiplied colour into the
    # translucent target. Match that target's other forward-rendered passes.
    marker = 'blend.gbuffers_textured.colortex13              = ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA'
    assert source.count(marker) == 1
    source = source.replace(marker, marker + '\n#if defined IS_IRIS || MC_VERSION > 12111\nblend.gbuffers_spidereyes.colortex13 = ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA\n#endif')
    marker = '#if MC_VERSION > 12111\nblend.gbuffers_armor_glint.colortex13'
    assert source.count(marker) == 1
    source = source.replace(marker, '#if defined IS_IRIS || MC_VERSION > 12111\nblend.gbuffers_armor_glint.colortex13')
    files[path] = source.encode()
    path = 'shaders/program/gbuffers_armor_glint.fsh'
    source = files[path].decode()
    assert source.count('#if MC_VERSION > 12111') == 1
    files[path] = source.replace('#if MC_VERSION > 12111', '#ifdef USE_SEPARATE_ENTITY_DRAWS').encode()
    files['AFTERLIGHT.txt'] = (ROOT/'shader/README.md').read_bytes()
    output = ROOT/'build/shaderpacks'/LOCK['output']; output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output,'w',ZIP_DEFLATED) as z:
        for path,data in sorted(files.items()):
            info = ZipInfo(path, (1980,1,1,0,0,0)); info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info,data)
    return output


if __name__ == '__main__': print(build())
