#!/usr/bin/env bash
# Emulator smoke test: install the APK, open every tab with live data and keep a screenshot of each,
# then tap through the real UI (tabs, area picker, toggle, play) the way a person would.
# The emulator has animations off, so the Rain tab shows its newest frame instead of autoplaying;
# pressing Play still runs the playback loop, which is what the interaction part checks.
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
shot 3-rain-radar-wide 35 --es tab rain --es source radar --ef zoom 8.5
shot 4-rain-gauges 25 --es tab rain --es source gauges
shot 5-haze 25 --es tab haze
adb shell cmd uimode night yes
shot 6-now-dark 20 --es tab now
shot 7-rain-dark 25 --es tab rain --es source radar
shot 8-haze-dark 20 --es tab haze
adb shell cmd uimode night no

# ---- Interaction: real taps, found by their visible text. A failure here is reported, not fatal. ----
set +e
LOG="$OUT/interaction.txt"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

dump() {
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# tap <text>: tap the first element whose text, description or id matches
tap() {
  dump || { say "FAIL dump before tapping '$1'"; return 1; }
  local xy
  xy=$(python3 .github/scripts/ui_probe.py bounds "$OUT/ui.xml" "$1") || { say "FAIL no element '$1'"; return 1; }
  adb shell input tap $xy
  say "tapped '$1' at $xy"
  sleep 2
}

# attr <id> <attribute>: read an attribute of an element from a fresh dump
attr() {
  dump && python3 .github/scripts/ui_probe.py attr "$OUT/ui.xml" "$1" "$2"
}

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 25
say "area before: $(attr areaName text)"
tap areaName
sleep 1
adb exec-out screencap -p > "$OUT/9-area-picker.png"
tap "Bedok"
sleep 3
say "area after picking Bedok: $(attr areaName text)"
adb exec-out screencap -p > "$OUT/10-now-bedok.png"

tap "Rain"
sleep 30
adb exec-out screencap -p > "$OUT/11-rain-by-tap.png"
tap "Gauges"
sleep 15
say "after tapping Gauges, source label: $(attr rainSource text)"
adb exec-out screencap -p > "$OUT/12-gauges-by-tap.png"

# Play runs the whole loop: Pause shows while it plays, the clock moves, then it returns to Play.
say "before play: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"
tap btnPlay
sleep 1
say "during play: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"
sleep 2
say "during play +2s: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"
adb exec-out screencap -p > "$OUT/13-playing.png"
sleep 10
say "after play: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"

tap "Radar"
sleep 5
say "after tapping Radar, source label: $(attr rainSource text)"
tap "Haze"
sleep 20
adb exec-out screencap -p > "$OUT/14-haze-by-tap.png"
tap "PM2.5"
sleep 2
adb exec-out screencap -p > "$OUT/15-haze-pm25.png"
tap "Dots"
sleep 2
adb exec-out screencap -p > "$OUT/16-haze-dots.png"
set -e

adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "The app crashed:"
  grep -A 14 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "No crash. Screenshots and interaction log in $OUT/"
