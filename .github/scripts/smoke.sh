#!/usr/bin/env bash
# Emulator smoke test: install the APK, open every tab with live data and keep a screenshot of each,
# then tap through the real UI (tabs, area picker, theme, play) the way a person would.
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
dump
say "location row: $(python3 .github/scripts/ui_probe.py bounds "$OUT/ui.xml" "Use my location")"
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

# Play: a dump takes seconds and the whole playback is about 6 s, so find the button first, then tap and
# screenshot straight away (no dump in between). The app also logs "play start" and "play end".
say "before play: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"
PLAY_XY=$(python3 .github/scripts/ui_probe.py bounds "$OUT/ui.xml" btnPlay)
adb shell log -t SMOKE "tapping play at $PLAY_XY"
adb shell input tap $PLAY_XY
sleep 1.5
adb exec-out screencap -p > "$OUT/13a-playing.png"
sleep 1.5
adb exec-out screencap -p > "$OUT/13b-playing-later.png"
sleep 9
adb exec-out screencap -p > "$OUT/13c-after-play.png"
say "after play: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"

tap "Radar"
sleep 5
say "after tapping Radar, source label: $(attr rainSource text)"
tap "Haze"
sleep 20
adb exec-out screencap -p > "$OUT/14-haze-by-tap.png"
tap "West"
sleep 3
adb exec-out screencap -p > "$OUT/16b-haze-history.png"
tap "OK"

# ---- Robustness: switch dark mode while the app is open, then rotate it ----
adb shell cmd uimode night yes
sleep 10
adb exec-out screencap -p > "$OUT/17-live-dark.png"
adb shell cmd uimode night no
sleep 8
tap "Rain"
sleep 8
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 5
adb exec-out screencap -p > "$OUT/18-landscape-rain.png"
tap "Now"
sleep 3
adb exec-out screencap -p > "$OUT/19-landscape-now.png"
adb shell settings put system user_rotation 0
sleep 3

# ---- Theme toggle: the phone stays in light mode, so only the button and the saved choice can make it dark ----
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab now
sleep 25
say "theme before: button='$(attr btnTheme content-desc)'"
adb exec-out screencap -p > "$OUT/30-theme-light-start.png"
tap btnTheme
sleep 8
say "theme after 1st tap: button='$(attr btnTheme content-desc)'"
adb exec-out screencap -p > "$OUT/31-theme-now-dark.png"
tap "Rain"
sleep 30
adb exec-out screencap -p > "$OUT/32-theme-rain-dark.png"
tap "Gauges"
sleep 12
adb exec-out screencap -p > "$OUT/33-theme-gauges-dark.png"
tap "Haze"
sleep 20
adb exec-out screencap -p > "$OUT/34-theme-haze-dark.png"
say "theme on Haze tab: button='$(attr btnTheme content-desc)'"
tap "West"
sleep 3
adb exec-out screencap -p > "$OUT/35-theme-history-dark.png"
tap "OK"
tap areaName
sleep 1
adb exec-out screencap -p > "$OUT/36-theme-area-picker-dark.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
# Close the app fully and open it again: the dark theme should come back by itself.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab haze
sleep 25
say "theme after restart: button='$(attr btnTheme content-desc)'"
adb exec-out screencap -p > "$OUT/37-theme-restart-dark.png"
# Tapping once more on the Haze tab, then the other tabs, should show light mode as before.
tap btnTheme
sleep 8
say "theme after 2nd tap: button='$(attr btnTheme content-desc)'"
adb exec-out screencap -p > "$OUT/38-theme-haze-light-again.png"
tap "Rain"
sleep 25
adb exec-out screencap -p > "$OUT/39-theme-rain-light-again.png"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab now
sleep 20
say "theme after 2nd restart: button='$(attr btnTheme content-desc)'"

# ---- Home screen widgets: ask the launcher to add each shape, confirm, then look at the home screen ----
adb shell input keyevent KEYCODE_HOME
sleep 2
add_widget() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" --es pin_widget "$1"
  sleep 5
  adb exec-out screencap -p > "$OUT/40-widget-pin-$1.png"
  tap "Add to home screen" || tap "Add automatically" || tap "Add"
  sleep 4
}
add_widget small
add_widget wide
add_widget strip
adb shell input keyevent KEYCODE_HOME
sleep 25
adb exec-out screencap -p > "$OUT/43-widgets-home-light.png"
say "widgets bound: $(adb shell dumpsys appwidget | grep -c 'io.github.rayvader.hazemap')"
adb shell cmd uimode night yes
sleep 10
adb exec-out screencap -p > "$OUT/44-widgets-home-dark.png"
adb shell cmd uimode night no
sleep 5

# ---- Offline: with no network the app should fall back to what it saved ----
adb shell svc wifi disable
adb shell svc data disable
sleep 5
shot 20-offline-now 25 --es tab now
dump && say "offline footer: $(python3 .github/scripts/ui_probe.py attr "$OUT/ui.xml" nowFooter text | tr '\n' ' ')"
shot 21-offline-gauges 25 --es tab rain --es source gauges
shot 22-offline-radar 25 --es tab rain --es source radar
adb shell svc wifi enable
adb shell svc data enable
set -e

adb logcat -d > "$OUT/logcat.txt" || true
say "--- app log lines for playback ---"
grep -E "SMOKE|RainScreen" "$OUT/logcat.txt" | sed -E 's/^[0-9-]+ //' | tee -a "$LOG" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "The app crashed:"
  grep -A 14 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "No crash. Screenshots and interaction log in $OUT/"
