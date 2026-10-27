#!/usr/bin/env bash
set -euo pipefail
dotki_root="$(cd "$(dirname "$0")/.." && pwd)"
kinetica_root="$(python3 "$dotki_root/scripts/fetch-kinetica.py")"
cd "$kinetica_root"
# Bootstrap metadata before Kinetica's local build plugin installs the compiler JAR.
./kotlin publish mavenLocal -m kinetica-compiler --non-transitive
./kotlin publish mavenLocal -m kinetica-browser -m kinetica-appkit -m kinetica-gradle-plugin --transitive
