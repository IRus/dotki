#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :web:jsBrowserDistribution :server:linkDebugExecutableNative
exec server/build/bin/native/debugExecutable/server.kexe
