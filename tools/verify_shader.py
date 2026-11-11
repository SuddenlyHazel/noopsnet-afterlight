#!/usr/bin/env python3
"""Compile the modified shader stages with glslang, including DH and all dimensions."""
import functools
import json
from pathlib import Path
import posixpath
import re
import subprocess
from zipfile import ZipFile
from build_shader import build, ROOT


def main():
    archive=build(); out=ROOT/'build/shader-validation';out.mkdir(parents=True,exist_ok=True)
    results=[]
    with ZipFile(archive) as z:
        @functools.lru_cache(None)
        def expand(path):
            source=z.read(path).decode()
            return re.sub(r'^\s*#include "([^"]+)"',lambda m:expand('shaders'+m[1] if m[1].startswith('/') else posixpath.normpath(posixpath.join(posixpath.dirname(path),m[1]))),source,flags=re.M)
        # Opaque deferred lighting and forward translucent/DH lighting all use the bridge.
        for world in ('world0','world-1','world1'):
            for name in ('deferred4.fsh','gbuffers_water.fsh','dh_water.fsh','dh_terrain.fsh','gbuffers_spidereyes.fsh','gbuffers_block.fsh','gbuffers_particles_translucent.fsh','gbuffers_textured.fsh'):
                path=f'shaders/{world}/{name}';source=expand(path)
                assert name in ('dh_terrain.fsh','gbuffers_spidereyes.fsh','gbuffers_block.fsh') or source.count('lighting = afterlight_blocklight')==1
                # Iris supplies these macros and performs C-preprocessor line splicing.
                source=source.replace('#version 400 compatibility','#version 400 compatibility\n#define IS_IRIS\n#define MC_HAND_DEPTH 0.125\n#define MC_SHADOW_QUALITY 1.0\n#define MC_VERSION 12101\n#define DISTANT_HORIZONS').replace('\\\n','')
                target=out/f'{world}-{name}';target.write_text(source)
                r=subprocess.run(['glslangValidator','-S','frag',str(target)],capture_output=True,text=True)
                if r.returncode: raise RuntimeError(path+'\n'+r.stdout+r.stderr)
                results.append(path)
    (out/'result.json').write_text(json.dumps({'compiler':'glslangValidator','passed':results},indent=2)+'\n')
    print(f'Compiled {len(results)} shader stages, including RGB lighting, DH, and all dimensions')


if __name__=='__main__':main()
