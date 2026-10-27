# Locked Kinetica bootstrap

Dotki currently needs Kinetica 0.4.0 built with Kotlin 2.4.10, plus the Canvas and
AppKit desktop APIs in `patches/kinetica-desktop.patch`. The patch preserves the
existing local implementation; it does not include unrelated repository changes.
Kinetica is Apache-2.0 licensed; the downloaded tree retains upstream LICENSE.

Run `./scripts/build-kinetica.sh` before Gradle. It downloads the exact public
Heapy/kinetica commit in `kinetica.lock.json`, verifies archive and patch SHA-256,
and caches the prepared sources under ignored `.local/kinetica/`. Subsequent runs
verify the prepared source files. It never reads or changes a sibling checkout.
Python 3.12+, Git, a JDK and the platform's native build tools are required.

The bootstrap changes only the artifact version constants to `0.4.0-dotki.1`.
It publishes compiler metadata first, then the Gradle plugin and the browser/AppKit
libraries with their dependencies. These separate Maven coordinates avoid
replacing a developer's ordinary Kinetica 0.4.0 installation. No remote artifacts
are published. The first build needs network access; later builds reuse caches.

To update: choose a full upstream commit, verify its archive SHA-256, rebase or
remove the desktop patch, update its checksum and bump `artifact_version` plus
the matching Dotki Gradle coordinates. Keep the compiler plugin and Dotki Kotlin
versions aligned. Run JVM/native tests and browser/desktop builds before publishing.
Remove the corresponding `.local/kinetica/<cache-key>` directory to restore a
manually changed cache. Do not modify or upload the downloaded source cache.

## Version audit — 2026-10-27

| Component | Current | Latest stable |
| --- | --- | --- |
| Gradle | 9.8.2 | 9.8.2 |
| Kotlin | 2.4.10 | 2.4.21 |
| Ktor | 3.5.1 | 3.6.0 |
| kotlinx.serialization | 1.11.0 | 1.12.0 |
| kotlinx.coroutines (test) | 1.10.1 | 1.11.0 |
| SQLiter | 1.4.0 | 1.4.0 |
| Playwright | 1.64.1 | 1.64.1 |
| Kotlin Toolchain (Kinetica) | 0.13.0 | 0.13.0 |
| Debian container base | 12 bookworm | 13 trixie |

Versions were checked against Maven Central metadata, the official Gradle releases,
and npm. This preparatory change keeps existing library versions; upgrading Kotlin
also requires rebuilding and checking Kinetica's compiler plugin.

Sources: [Gradle](https://gradle.org/releases/),
[Kotlin](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21),
[Ktor](https://github.com/ktorio/ktor/releases/tag/3.6.0),
[serialization](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.12.0),
[coroutines metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml),
[SQLiter metadata](https://repo.maven.apache.org/maven2/co/touchlab/sqliter-driver/maven-metadata.xml),
[Playwright](https://www.npmjs.com/package/@playwright/test),
[Debian](https://www.debian.org/releases/).

## Bootstrap validation

Validated on macOS ARM64 with a fresh downloaded source tree: Kinetica publication
succeeded, all 64 JVM tests in shared/server/desktop passed, and both the production
web bundle and debug native desktop executable built successfully. Corrupted archive,
patch, and cached-source checks were also exercised and rejected as expected.
