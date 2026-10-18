#!/usr/bin/env bash
# Emulator smoke test: install the APK, open every tab with live data and keep a screenshot of each.
# The emulator has animations off, so the Rain tab shows its newest frame instead of playing.
set -euo pipefail

PKG=io.github.rayvader.hazemap
APK=$(ls HazeMapSG-*.apk | head -1)
OUT=smoke
mkdir -p "$OUT"

adb logcat -c || true
adb install -r "$APK"

# shot <file> <wait seconds> <am start extras...>
shot() {
  local name=$1 wait=$2
  shift 2
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@"
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
}

# data.gov.sg allows 5 quick calls per 10 s, so the Now tab needs a little over 10 s to fill in.
shot 1-now 30 --es tab now
shot 2-rain-radar 35 --es tab rain --es source radar
shot 3-rain-gauges 25 --es tab rain --es source gauges
shot 4-haze 25 --es tab haze
adb shell cmd uimode night yes
shot 5-now-dark 20 --es tab now
shot 6-rain-dark 25 --es tab rain --es source radar
adb shell cmd uimode night no

adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "The app crashed:"
  grep -A 14 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "No crash. Screenshots in $OUT/"
