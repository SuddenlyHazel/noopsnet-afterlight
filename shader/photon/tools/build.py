#!/usr/bin/env python3
"""Build the editable Photon fork reproducibly, without launcher/server access."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# The source exporter places this directory under shader/photon in the
# standalone Afterlight repository; the workspace keeps it in community/shaders.
STANDALONE = (ROOT.parent / 'PHOTON-LICENSE').is_file()
REPO = ROOT.parents[1] if STANDALONE else ROOT.parents[2]
SOURCE = ROOT / 'source'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def source_files():
    files = {}
    for path in sorted(SOURCE.rglob('*')):
        if path.is_symlink():
            raise ValueError('Vendored source must not contain symlinks: ' + str(path))
        if not path.is_file():
            continue
        name = path.relative_to(SOURCE).as_posix()
        if name.startswith('.') or any(part.startswith('.') for part in Path(name).parts):
            continue
        files[name] = path.read_bytes()
    for required in ('LICENSE', 'AFTERLIGHT.txt', 'shaders/settings.glsl', 'shaders/shaders.properties'):
        if required not in files:
            raise ValueError('Missing source entry: ' + required)
    license_path = ('shader/PHOTON-LICENSE' if STANDALONE else
                    'community/mods/noopsnet-neon/shader/PHOTON-LICENSE')
    expected = (REPO / license_path).read_bytes()
    if files['LICENSE'].strip() != expected.strip():
        raise ValueError('Bundled Photon license differs from the pinned source')
    return files


def build(output=None):
    files = source_files()
    upstream = json.loads((ROOT / 'UPSTREAM.json').read_text())
    digest = hashlib.sha256()
    for name, data in sorted(files.items()):
        digest.update(name.encode() + b'\0' + data)
    source_hash = digest.hexdigest()
    ident = source_hash[:12]
    original = upstream['base_files_sha256']
    changed = sorted(name for name, data in files.items()
                     if sha256(data) != original.get(name))
    removed = sorted(set(original) - set(files))
    packaged = {
        'name': 'Afterlight–Photon', 'lane': 'devo', 'id': ident,
        'source_sha256': source_hash,
        'upstream': upstream['upstream'],
        'starting_derivative_sha256': upstream['base_derivative_sha256'],
        'changed_from_starting_derivative': changed,
        'removed_from_starting_derivative': removed,
    }
    files['AFTERLIGHT-SOURCE.json'] = (json.dumps(packaged, indent=2) + '\n').encode()
    directory = ROOT / 'build'
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / f'Afterlight-Photon-devo-{ident}.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            package.writestr(entry, data)
    prefix = '' if STANDALONE else 'community/mods/noopsnet-neon/'
    scope = [ROOT.relative_to(REPO).as_posix(), prefix + 'tools/build_shader.py',
             prefix + 'shader/PHOTON-LICENSE']
    try:
        checkout = subprocess.check_output(['git', 'rev-parse', '--show-toplevel'],
                                           cwd=REPO, text=True, stderr=subprocess.DEVNULL).strip()
        if Path(checkout).resolve() != REPO.resolve():
            raise ValueError('Source snapshot is inside a different checkout')
        status = subprocess.check_output(
            ['git', 'status', '--porcelain', '--untracked-files=all', '--', *scope],
            cwd=REPO, text=True, stderr=subprocess.DEVNULL).splitlines()
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO,
                                         text=True, stderr=subprocess.DEVNULL).strip()
    except (subprocess.CalledProcessError, ValueError):
        # A downloaded source snapshot can build before `git init`; it must
        # never advertise the clean commit provenance required for Devo install.
        status, commit = ['Source snapshot has no Git checkout'], None
    receipt = {
        **packaged, 'file': archive.name, 'sha256': sha256(archive.read_bytes()),
        'source_commit': commit,
        'source_scope_clean': not status, 'development_snapshot': bool(status),
        'source_changes': status,
    }
    (directory / 'candidate.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if output is not None:
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        if output.resolve() != archive.resolve():
            shutil.copy2(archive, output)
        (output.parent / 'afterlight-photon-candidate.json').write_text(json.dumps(receipt, indent=2) + '\n')
        return output
    return archive


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Also copy to a legacy build output path')
    args = parser.parse_args()
    print(build(args.output))
