#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
target="${1:-linuxArm64}"
case "$target" in linuxArm64|linuxX64) ;; *) echo 'Expected linuxArm64 or linuxX64' >&2; exit 1 ;; esac
python3 scripts/prepare-linux-sqlite.py "$target"
./gradlew --no-daemon -PnativeTarget="$target" -PsqliteLibraryDir="$PWD/build/sqlite-link-$target/lib" :web:jsBrowserDistribution :server:linkReleaseExecutableNative
context="build/docker-context-$target"
mkdir -p "$context/web"
cp Dockerfile "$context/Dockerfile"
cp server/build/bin/native/releaseExecutable/server.kexe "$context/dotki"
for asset in index.html client.js style.css favicon.svg; do
  cp "web/build/dist/js/productionExecutable/$asset" "$context/web/$asset"
done
echo "Docker build context: $context"
