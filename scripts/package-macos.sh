#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
configuration="${1:-release}"
case "$configuration" in
  release) task=linkReleaseExecutableNative ;;
  debug) task=linkDebugExecutableNative ;;
  *) echo 'Expected release or debug' >&2; exit 1 ;;
esac
if [[ "${2:-}" != --skip-build ]]; then
  ./gradlew --no-daemon ":desktop:$task"
fi
app="$PWD/build/macos/$configuration/Dotki.app"
mkdir -p "$app/Contents/MacOS"
cp "desktop/build/bin/native/${configuration}Executable/desktop.kexe" "$app/Contents/MacOS/dotki"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>dotki</string>
  <key>CFBundleIdentifier</key><string>com.heapyhop.dotki</string>
  <key>CFBundleName</key><string>Dotki</string>
  <key>CFBundleDisplayName</key><string>Dotki</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
codesign --verify --strict "$app"
archive="$PWD/build/macos/$configuration/Dotki-macos-arm64.zip"
# ditto replaces an existing zip; both paths are generated build artifacts.
ditto -c -k --sequesterRsrc --keepParent "$app" "$archive"
printf '%s\n%s\n' "$app" "$archive"
