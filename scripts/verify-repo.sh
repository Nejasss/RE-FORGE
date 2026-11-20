#!/usr/bin/env bash
set -euo pipefail

echo "Checking repository structure..."
for mod in app core engine-registry storage filemanager apk-inspector dex-analysis resource-inspector binary-analysis hex-viewer patch-workspace rebuild-engine signing-engine terminal-interface; do
  test -f "$mod/build.gradle.kts"
done
test -f "native/CMakeLists.txt"
test -f ".github/workflows/android-build.yml"
echo "All module build files verified."
