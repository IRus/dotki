#!/usr/bin/env python3
"""Download the locked Kinetica source snapshot; never use a sibling checkout."""
import fcntl
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def prepare():
    lock_bytes = (ROOT / 'dependencies/kinetica.lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    commit = lock['commit']
    if lock['repository'] != 'Heapy/kinetica' or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected an immutable Heapy/kinetica commit')
    patches = []
    for item in lock['patches']:
        path = (ROOT / item['path']).resolve()
        if not path.is_relative_to(ROOT) or sha256(path.read_bytes()) != item['sha256']:
            raise ValueError(f"Patch checksum mismatch: {item['path']}")
        patches.append(path)
    cache = ROOT / '.local/kinetica' / sha256(lock_bytes)[:20]
    cache.mkdir(parents=True, exist_ok=True)
    source = cache / 'source'
    # Serialize preparation so concurrent builds never see a partial source tree.
    with (cache / 'prepare.lock').open('w') as mutex:
        fcntl.flock(mutex, fcntl.LOCK_EX)
        manifest_file = cache / 'source-manifest.json'
        if source.exists():
            manifest = json.loads(manifest_file.read_text())
            for name, digest in manifest.items():
                path = source / name
                if not path.is_file() or sha256(path.read_bytes()) != digest:
                    raise ValueError(f'Cached source changed: {path}. Remove {cache} to restore it.')
            return source
        archive = cache / 'source.tar.gz'
        if not archive.exists():
            url = f'https://codeload.github.com/{lock["repository"]}/tar.gz/{commit}'
            print(f'Downloading Kinetica {commit}', file=sys.stderr)
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if sha256(data) != lock['archive_sha256']:
                raise ValueError('Kinetica archive checksum mismatch')
            archive.write_bytes(data)
        if sha256(archive.read_bytes()) != lock['archive_sha256']:
            raise ValueError(f'Kinetica archive checksum mismatch: {archive}')
        with tempfile.TemporaryDirectory(prefix='extract-', dir=cache) as temporary:
            with tarfile.open(archive, 'r:gz') as bundle:
                # Python's data filter rejects paths escaping the extraction directory.
                bundle.extractall(temporary, filter='data')
            unpacked = Path(temporary) / f'kinetica-{commit}'
            for patch in patches:
                subprocess.run(['git', 'apply', '--check', str(patch)], cwd=unpacked, check=True)
                subprocess.run(['git', 'apply', str(patch)], cwd=unpacked, check=True)
            # Separate coordinates avoid replacing the developer's normal 0.4.0 artifacts.
            version_files = [
                'publish.module-template.yaml', 'common.module-template.yaml',
                'plugins/kinetica-compiler-local/plugin.yaml',
                'kinetica-gradle-plugin/src/KineticaCoordinates.kt',
                'kinetica-compiler/src/CompilerContract.kt',
            ]
            for name in version_files:
                path = unpacked / name
                contents = path.read_text()
                if '0.4.0' not in contents:
                    raise ValueError(f'Upstream version changed in {name}; review the bootstrap')
                path.write_text(contents.replace('0.4.0', lock['artifact_version']))
            manifest = {
                str(path.relative_to(unpacked)): sha256(path.read_bytes())
                for path in sorted(unpacked.rglob('*')) if path.is_file()
            }
            shutil.move(str(unpacked), source)
            manifest_file.write_text(json.dumps(manifest, sort_keys=True) + '\n')
        return source


if __name__ == '__main__':
    try:
        print(prepare())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f'Kinetica preparation failed: {error}')
