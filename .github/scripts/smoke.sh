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
# screenshot straight away (no dump in between). The app also logs "play start" and one "play loop N" line each time the loop starts over.
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
say "still looping: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"
PLAY_XY=$(python3 .github/scripts/ui_probe.py bounds "$OUT/ui.xml" btnPlay)
adb shell input tap $PLAY_XY
sleep 2
say "after pausing: button='$(attr btnPlay content-desc)' clock='$(attr rainClock text)'"

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
anim_scale() {
  for s in animator_duration_scale transition_animation_scale window_animation_scale; do
    adb shell settings put global "$s" "$1"
  done
}
# Leaving the home screen and coming back is what starts the widget pictures. Extras go to the app.
home_again() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" "$@"
  sleep 4
  adb shell input keyevent KEYCODE_HOME
  sleep 1
}
RAW=$(mktemp -d)
# raw_shots <name> <count>: quick screenshots without PNG encoding, kept out of the uploaded folder
raw_shots() {
  for i in $(seq 1 "$2"); do
    adb exec-out screencap > "$RAW/$1-$i.raw"
    sleep 0.6
  done
}
picture_frames() { python3 .github/scripts/ui_probe.py frames "$OUT/ui-widgets.xml" "$@"; }
# The widget clocks redraw the whole widget when the minute changes, so steady pictures are shot clear of it.
clear_of_minute() {
  local s
  s=$(adb shell date +%S | tr -d '\r')
  s=${s#0}
  if [ "${s:-0}" -ge 52 ]; then sleep $((63 - s)); fi
}

adb shell input keyevent KEYCODE_HOME
sleep 2
# A widget picture keeps the speed it first played at, so animations are on before the widgets arrive.
anim_scale 1
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
dump && cp "$OUT/ui.xml" "$OUT/ui-widgets.xml"
say "--- widgets on the home screen, real weather ---"
python3 .github/scripts/ui_probe.py widgets "$OUT/ui-widgets.xml" | tee -a "$LOG"
REAL=$(python3 .github/scripts/ui_probe.py widgets "$OUT/ui-widgets.xml" | grep -o -m1 'picture=[a-z]*' | cut -d= -f2)

# Every sky in turn: its picture should move just after the home screen shows, and rest 10 s later.
for scene in sun partly cloud rain storm haze wind night; do
  home_again --es widget_scene "$scene"
  raw_shots "$scene-moving" 2
  sleep 9
  clear_of_minute
  raw_shots "$scene-resting" 2
  adb exec-out screencap -p > "$OUT/45-widget-$scene.png"
  say "sky $scene moving: $(picture_frames "$RAW/$scene"-moving-*.raw)"
  say "sky $scene resting: $(picture_frames "$RAW/$scene"-resting-*.raw)"
  rm -f "$RAW/$scene"-moving-*.raw "$RAW/$scene-resting-2.raw"
  # The real weather's resting frame is kept, to compare with the still picture below.
  [ "$scene" = "$REAL" ] || rm -f "$RAW/$scene-resting-1.raw"
done
anim_scale 0

# Back to the real weather. Dark mode, and back, make the home screen build its widgets again with animations off.
home_again --es widget_scene auto
sleep 4
dump && say "--- widgets back on the real weather ---" && python3 .github/scripts/ui_probe.py widgets "$OUT/ui.xml" | tee -a "$LOG"
adb shell cmd uimode night yes
sleep 10
adb exec-out screencap -p > "$OUT/44-widgets-home-dark.png"
adb shell cmd uimode night no
sleep 5
home_again
clear_of_minute
raw_shots still 2
say "pictures with animations off: $(picture_frames "$RAW"/still-*.raw)"
say "resting $REAL picture vs animations off: $(picture_frames "$RAW/$REAL-resting-1.raw" "$RAW/still-1.raw")"
rm -rf "$RAW"

# ---- Saved places: open the list, search a city, save it, open its page, remove it ----
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab now
sleep 20
adb exec-out screencap -p > "$OUT/49-places-light-home.png"
tap "My places"
sleep 3
adb exec-out screencap -p > "$OUT/50-places-empty.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
tap btnTheme
sleep 8
say "places run theme button: $(attr btnTheme content-desc)"
tap "My places"
sleep 3
adb exec-out screencap -p > "$OUT/50b-places-empty-dark.png"
tap "＋ Add a place"
sleep 3
adb shell input text "tokyo"
adb shell input keyevent KEYCODE_ENTER
sleep 6
adb exec-out screencap -p > "$OUT/51-places-search.png"
tap "Tokyo"
sleep 8
adb exec-out screencap -p > "$OUT/52-places-list.png"
tap "Tokyo"
sleep 10
say "place page title: $(attr placeTitle text)"
say "place page star: $(attr btnStar content-desc)"
say "place page humidity: '$(attr placeChipHumidity text)'"
say "place page wind: '$(attr placeChipWind text)'"
adb exec-out screencap -p > "$OUT/53-place-page.png"
tap btnStar
say "place page star after tap: $(attr btnStar content-desc)"
tap btnBack
sleep 2
tap btnTheme
sleep 6
say "places run theme button after: $(attr btnTheme content-desc)"

# ---- Rain replay: with animations on, the map plays by itself and starts over ----
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
STARTS_BEFORE=$(adb logcat -d | grep -c 'play start')
LOOPS_BEFORE=$(adb logcat -d | grep -c 'play loop')
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab rain --es source radar
sleep 45
adb exec-out screencap -p > "$OUT/60-rain-auto-a.png"
sleep 3
adb exec-out screencap -p > "$OUT/61-rain-auto-b.png"
say "rain started by itself: $(( $(adb logcat -d | grep -c 'play start') - STARTS_BEFORE ))"
say "rain loops by itself: $(( $(adb logcat -d | grep -c 'play loop') - LOOPS_BEFORE ))"
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0

# ---- Update banner: pretend to be an old version so the real GitHub check finds a newer release ----
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab now --es installed_version 0.0.1
sleep 25
say "update banner: '$(attr updateTitle text)' / '$(attr updateSub text)'"
adb exec-out screencap -p > "$OUT/70-update-banner.png"
tap btnTheme
sleep 8
say "update banner in dark: '$(attr updateTitle text)'"
adb exec-out screencap -p > "$OUT/71-update-banner-dark.png"
tap btnTheme
sleep 6
tap "Dismiss"
sleep 2
say "update banner after dismiss: '$(attr updateTitle text)'"
adb exec-out screencap -p > "$OUT/72-update-banner-dismissed.png"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" --es tab now --es installed_version 0.0.1
sleep 20
say "update banner after reopening: '$(attr updateTitle text)'"

# ---- Offline: with no network the app should fall back to what it saved ----
adb shell svc wifi disable
adb shell svc data disable
sleep 5
shot 20-offline-now 25 --es tab now
dump && say "offline footer: $(python3 .github/scripts/ui_probe.py attr "$OUT/ui.xml" nowFooter text | tr '\n' ' ')"
shot 21-offline-gauges 25 --es tab rain --es source gauges
shot 22-offline-radar 25 --es tab rain --es source radar
dump && say "offline radar: status='$(python3 .github/scripts/ui_probe.py attr "$OUT/ui.xml" rainStatus text)' clock='$(python3 .github/scripts/ui_probe.py attr "$OUT/ui.xml" rainClock text)'"
adb shell svc wifi enable
adb shell svc data enable

# ---- Self-update: an old copy (version 1.0.1) finds the newest release on GitHub, downloads it, checks
# it and installs it when the app is left. Runs last: afterwards the phone has the newest release. ----
version_code() { adb shell dumpsys package "$PKG" | grep -m1 -o 'versionCode=[0-9]*'; }
logged() { adb logcat -d | grep -c "$1"; }
# self_update_open <name>: open the app and wait until the update is downloaded and checked
self_update_open() {
  local ready_before start_before
  ready_before=$(logged 'update ready')
  start_before=$(logged 'update download start')
  adb shell am start -W -n "$PKG/.MainActivity" --es tab now > /dev/null
  for i in $(seq 1 45); do
    [ "$(logged 'update ready')" -gt "$ready_before" ] && break
    if [ "$i" = 10 ] && [ "$(logged 'update download start')" -le "$start_before" ]; then
      say "self update ($1): no download by itself, tapping Update"
      tap updateDownload
    fi
    sleep 2
  done
  say "self update banner ($1): '$(attr updateTitle text)' / '$(attr updateSub text)'"
  adb exec-out screencap -p > "$OUT/80-self-update-$1.png"
}
if [ -f update-test-old.apk ]; then
  sleep 5
  adb uninstall "$PKG" > /dev/null
  adb install update-test-old.apk > /dev/null
  say "self update before: $(version_code)"
  # First time: Android has not been allowed to take installs from the app, so the banner asks for a
  # tap, and the tap opens Android's own prompt. The test says no to it.
  self_update_open first
  tap updateDownload
  sleep 6
  adb exec-out screencap -p > "$OUT/81-self-update-android-asks.png"
  say "self update prompt: $(adb shell dumpsys activity activities | grep -m1 -o 'topResumedActivity=.*' | cut -c1-150)"
  adb shell input keyevent KEYCODE_BACK
  sleep 3
  adb shell input keyevent KEYCODE_HOME
  sleep 10
  say "self update after saying no: $(version_code)"
  # Stands in for the person allowing "Install unknown apps" for the app, the one time Android asks.
  # From then on the update installs by itself when the app is left.
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
  adb shell am force-stop "$PKG"
  self_update_open allowed
  adb shell input keyevent KEYCODE_HOME
  sleep 25
  say "self update after leaving the app: $(version_code)"
  adb logcat -d | grep ' Updater' | sed -E 's/^[0-9-]+ //' | tee -a "$LOG"
fi
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
