#!/usr/bin/env bash
# One-command weekly re-sign: fetch the latest CI framework, build, sign and install SimpMusic
# on the USB-connected iPhone. Run in WSL (Ubuntu), or double-click "Reinstall SimpMusic.cmd"
# on Windows, which runs this.
#
# Free Apple ID signing expires 7 days after install; running this again resets the 7 days.
# Library, downloads and settings are kept: reinstalling over the same app id keeps its data.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ENV="${IOS_ENV:-/mnt/c/dev/ios-scripts/ios-env.sh}"
LOG="${TMPDIR:-/tmp}/simpmusic-reinstall.log"

# shellcheck disable=SC1090
source "$IOS_ENV"

echo "Looking for the iPhone..."
for _ in 1 2 3 4 5 6; do
  device="$(timeout 10 idevice_id -l 2>/dev/null | head -1)"
  [ -n "$device" ] && break
  sleep 5
done
if [ -z "${device:-}" ]; then
  echo
  echo "No iPhone found. Plug it in with the cable, unlock it, tap Trust if asked, and run this again."
  echo "(If it still is not found: the Windows firewall rule for port 27015 may need re-adding; see docs/ios-upkeep.md.)"
  exit 3
fi
echo "Found iPhone $device"

attempt=1
while [ "$attempt" -le 2 ]; do
  refresh=""
  [ "$attempt" = 1 ] && refresh="--refresh"
  echo "Building and installing (attempt $attempt)... this takes about a minute."
  bash "$SCRIPT_DIR/build-install.sh" --install $refresh >"$LOG" 2>&1
  if grep -aq "\[Verifying\] 100%" "$LOG"; then
    echo
    echo "Installed. SimpMusic will keep opening until $(date -d '+7 days' '+%A %d %B')."
    echo "Run this again before then."
    exit 0
  fi
  # xtool sometimes stops mid-install with "Error: unknown"; a plain retry works.
  attempt=$((attempt + 1))
done

echo
echo "Install did not finish. Last lines of $LOG:"
tail -c 1500 "$LOG" | tr '\r' '\n' | grep -av "^\[Installing\]" | tail -15
exit 1
