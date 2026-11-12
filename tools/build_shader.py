#!/usr/bin/env python3
"""Build the editable Afterlight–Photon source through the existing mod API.

The output alias remains compatible with the pack/devo builders. Private shader
candidates additionally have a content-addressed name and receipt under
community/shaders/afterlight-photon/build; public manifests are not changed.
"""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'shader/upstream.lock.json').read_text())
VENDORED = ROOT / 'shader/photon'
if not VENDORED.is_dir():
    VENDORED = ROOT.parents[1] / 'shaders/afterlight-photon'


def build():
    path = VENDORED / 'tools/build.py'
    spec = importlib.util.spec_from_file_location('noopsnet_photon_source_builder', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.build(ROOT / 'build/shaderpacks' / LOCK['output'])


if __name__ == '__main__':
    print(build())
