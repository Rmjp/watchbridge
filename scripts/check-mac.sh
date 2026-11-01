#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/.."
mkdir -p dist/mac-checks
CHECK_DIR="$PWD/dist/mac-checks"
xcodebuild -version > "$CHECK_DIR/xcode-version.txt"
if ! (cd watchos && swift test --scratch-path "$CHECK_DIR/SwiftBuild") > "$CHECK_DIR/crypto-tests.log" 2>&1; then
    cat "$CHECK_DIR/crypto-tests.log"
    exit 1
fi
if ! xcodebuild -project watchos/WatchBridge.xcodeproj -scheme WatchBridge \
  -destination 'generic/platform=watchOS Simulator' \
  -derivedDataPath "$CHECK_DIR/DerivedData" \
  CODE_SIGNING_ALLOWED=NO build > "$CHECK_DIR/watch-build.log" 2>&1; then
    tail -n 30 "$CHECK_DIR/watch-build.log"
    printf '%s\n' "Full build log: $CHECK_DIR/watch-build.log" >&2
    printf '%s\n' 'If the watchOS simulator runtime is missing, install it in Xcode Settings > Components and retry.' >&2
    exit 1
fi
printf '%s\n' 'Mac crypto tests and unsigned watch simulator build passed.'
printf '%s\n' 'Next: open the Xcode project, choose your team and physical watch, then Run.'
