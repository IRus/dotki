#!/usr/bin/env python3
"""Fetch a checksum-verified Debian SQLite shared library for Native cross-linking.
The runtime uses Debian's libsqlite3-0 package from the Dockerfile, not this copy.
"""
import hashlib
import io
import lzma
import pathlib
import sys
import tarfile
import urllib.request

architectures = {'linuxArm64': 'arm64', 'linuxX64': 'amd64'}
target = sys.argv[1]
arch = architectures[target]
root = pathlib.Path(__file__).resolve().parent.parent
output = root / 'build' / f'sqlite-link-{target}'
lib = output / 'lib'
if (lib / 'libsqlite3.so').exists():
    print(f'SQLite linker library: {lib}')
    sys.exit(0)

def download(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()

base = 'https://deb.debian.org/debian/'
index = lzma.decompress(download(base + f'dists/bookworm/main/binary-{arch}/Packages.xz')).decode()
package = next(section for section in index.split('\n\n') if section.startswith('Package: libsqlite3-0\n'))
fields = dict(line.split(': ', 1) for line in package.splitlines() if ': ' in line and not line.startswith(' '))
archive = download(base + fields['Filename'])
assert hashlib.sha256(archive).hexdigest() == fields['SHA256'], 'Debian package checksum mismatch'
assert archive[:8] == b'!<arch>\n'
position = 8
payload = None
while position + 60 <= len(archive):
    header = archive[position:position + 60]
    name = header[:16].decode().strip().rstrip('/')
    size = int(header[48:58])
    start = position + 60
    if name in ('data.tar.xz', 'data.tar.gz'):
        payload = archive[start:start + size]
        break
    position = start + size + (size % 2)
assert payload is not None, 'Unsupported Debian data archive format'
lib.mkdir(parents=True, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(payload), mode='r:*') as contents:
    member = next(m for m in contents.getmembers() if m.isfile() and pathlib.PurePosixPath(m.name).name.startswith('libsqlite3.so.'))
    name = pathlib.PurePosixPath(member.name).name
    (lib / name).write_bytes(contents.extractfile(member).read())
    (lib / 'libsqlite3.so').symlink_to(name)
(output / 'source.txt').write_text(f"{base}{fields['Filename']}\nSHA256 {fields['SHA256']}\nVersion {fields['Version']}\n")
print(f'SQLite linker library: {lib} (Debian {fields["Version"]})')
