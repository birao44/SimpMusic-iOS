#!/usr/bin/env bash
# Build (and optionally install) the SimpMusic iOS app with xtool. Run in WSL.
#
#   scripts/ios/build-install.sh              # build only
#   scripts/ios/build-install.sh --install    # build, sign and install on the USB-connected iPhone
#   scripts/ios/build-install.sh --refresh    # re-download the CI framework first
#
# The xtool project (iosApp/) is copied from the Windows checkout to an ext4 build dir,
# because SwiftPM on /mnt/c is slow and trips over permissions/symlinks.
set -euo pipefail

SRC="${SRC:-/mnt/c/dev/SimpMusic/iosApp/}"
BUILD_DIR="${BUILD_DIR:-$HOME/build/simpmusic-ios}"
IOS_ENV="${IOS_ENV:-/mnt/c/dev/ios-scripts/ios-env.sh}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

install=0
refresh=0
for arg in "$@"; do
  case "$arg" in
    --install) install=1 ;;
    --refresh) refresh=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

command -v xtool >/dev/null || { echo "error: xtool not on PATH" >&2; exit 1; }
command -v rsync >/dev/null || { echo "error: rsync not found (sudo apt install rsync)" >&2; exit 1; }

mkdir -p "$BUILD_DIR"
rsync -a --delete \
  --exclude 'Frameworks/' --exclude '.build/' --exclude 'xtool/' --exclude '.swiftpm/' --exclude '.bsp/' \
  --exclude 'sim/' --exclude '*.xcodeproj/' \
  "$SRC" "$BUILD_DIR/"

if [ "$refresh" = 1 ] || [ ! -d "$BUILD_DIR/Frameworks/ComposeApp.xcframework" ]; then
  bash "$SCRIPT_DIR/fetch-framework.sh" "$BUILD_DIR"
fi

# shellcheck disable=SC1090
source "$IOS_ENV"

cd "$BUILD_DIR"
if [ "$install" = 1 ]; then
  # `xtool dev` = build + sign + install + launch. stdin from /dev/null so it does not
  # wait for a key press after launching.
  xtool dev </dev/null
else
  xtool dev build
  echo "Built: $BUILD_DIR/xtool/SimpMusic.app"
fi
