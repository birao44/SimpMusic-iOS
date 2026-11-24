#!/usr/bin/env bash
# Download the latest CI-built ComposeApp.xcframework into an xtool project.
# Run in WSL.
#
#   scripts/ios/fetch-framework.sh [BUILD_DIR]
#
# BUILD_DIR defaults to ~/build/simpmusic-ios; the framework lands in BUILD_DIR/Frameworks/.
# Source: rolling prerelease `ios-framework-latest` of Siddh68/SimpMusic, published by
# .github/workflows/ios-framework.yml. Override with REPO=owner/name or TAG=... if needed.
set -euo pipefail

BUILD_DIR="${1:-$HOME/build/simpmusic-ios}"
REPO="${REPO:-Siddh68/SimpMusic}"
TAG="${TAG:-ios-framework-latest}"
ASSET="ComposeApp.xcframework.zip"
URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"

for tool in curl unzip; do
  command -v "$tool" >/dev/null || { echo "error: '$tool' not found (sudo apt install $tool)" >&2; exit 1; }
done

mkdir -p "$BUILD_DIR/Frameworks"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Downloading $URL"
curl -fL --retry 3 --progress-bar -o "$tmp/$ASSET" "$URL"

# The zip is made with `ditto -c -k --keepParent` on macOS; unzip restores the symlinks
# inside the framework (Versions/Current etc. on macOS slices; harmless on iOS slices).
unzip -q "$tmp/$ASSET" -d "$tmp/x"
test -d "$tmp/x/ComposeApp.xcframework" || { echo "error: zip does not contain ComposeApp.xcframework" >&2; exit 1; }

rm -rf "$BUILD_DIR/Frameworks/ComposeApp.xcframework"
mv "$tmp/x/ComposeApp.xcframework" "$BUILD_DIR/Frameworks/"

echo "Installed $BUILD_DIR/Frameworks/ComposeApp.xcframework:"
ls "$BUILD_DIR/Frameworks/ComposeApp.xcframework"
