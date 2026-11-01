#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/.."
mkdir -p dist/mac-checks
xcodebuild -version > dist/mac-checks/xcode-version.txt
(cd watchos && swift test) > dist/mac-checks/crypto-tests.log 2>&1
xcodebuild -project watchos/WatchBridge.xcodeproj -scheme WatchBridge \
  -destination 'generic/platform=watchOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build > dist/mac-checks/watch-build.log 2>&1
printf '%s\n' 'Mac crypto tests and unsigned watch simulator build passed.'
printf '%s\n' 'Next: open the Xcode project, choose your team and physical watch, then Run.'
