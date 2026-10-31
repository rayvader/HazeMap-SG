# Haze Map SG

A Singapore weather app for Android: today's forecast, a rain map you can play back
over the past two hours, and haze (PSI and PM2.5) with a history chart.
It uses free public data. No account or API key. Location is optional and asked only when you tap “Use my location”.

| Now | Rain (playing back) | Haze |
|---|---|---|
| <img src="docs/screenshots/now.jpg" width="240"> | <img src="docs/screenshots/rain-playback.jpg" width="240"> | <img src="docs/screenshots/haze.jpg" width="240"> |

*Screenshots are from the CI emulator with live data. Dark mode: [Now](docs/screenshots/now-dark.jpg).*

## What it does

**Now** — for the area you pick (47 NEA forecast areas, default Ang Mo Kio; tap the name at the top):
temperature, condition and when that forecast ends, high and low, humidity, wind, UV,
the next 24 hours by period, a haze summary for your region, and the next four days.
Tap the area name, then “Use my location”, to choose the nearest forecast area.

**Rain** — a map of where it is raining, with a play button and a slider for the past two hours.
A switch at the top right picks the picture:

| | Radar | Gauges |
|---|---|---|
| Shows | rain clouds, as a radar picture | one dot per NEA rain gauge (90), coloured by how hard it rained |
| Steps | 10 minutes (13 frames) | 5 minutes (24 frames) |
| Source | [RainViewer](https://www.rainviewer.com/) | NEA via data.gov.sg |
| Detail | coarse (about 1.2 km per pixel) | exact readings; tap a dot for its value |

It plays by itself and starts over when it reaches the newest picture. Press Pause or drag the slider to stop it;
it stays still until you press Play. It does not start by itself when the phone has animations turned off.
Zoom out to watch rain coming across the Strait.
Radar pictures are saved on the phone (about 1 MB). The app downloads only the pictures it does not have yet,
and with no network it plays the last two hours it saved, with a note saying how old they are.
Pictures older than two hours are deleted when new ones arrive.

**Haze** — NEA's five regions as dots coloured by PSI,
both PSI and PM2.5 shown on each dot, a ranked list, and a line chart of each region's readings.
The chart is built from readings the app saves on your phone each time it fetches, so it grows
the more you open the app. Nothing is sent anywhere.

**My places** — Singapore is home and has every feature. Tap the globe at the top to open a list of
other cities you saved. Each shows its temperature and weather; tap one for its page with the next four
days, the humidity and the wind. The star on a page saves or removes it. Use "Add a place" to search any city in the world.
City weather is from [Open-Meteo](https://open-meteo.com/) (free for apps without ads or subscriptions).

**Updates** — the app updates itself. When it opens it asks GitHub (at most every 6 hours) for the newest
release. If that is newer, a banner at the top says so, lists what's new in it (scroll it if it is long),
and the app downloads it: by itself on Wi-Fi, or when
you tap Update on mobile data. It checks the file is this app, newer, and signed with the same key, then
installs it when you leave the app, so it never closes in front of you. On Android 12 and newer that needs no
tap. On older Android, or the first time, Android asks you once (allow "Install unknown apps", then Update).
Install in the banner does it right away. The X skips that version. The line at the end of the Now tab shows
the version you have, and "Check for updates" there asks GitHub at once, whatever the 6 hours: the line says
that you are up to date, or that a newer version is out, and a banner you closed comes back. If something fails, the banner says why
and offers Try again, or Download to get the file in your browser. The request sends nothing about you
except what any web request does.

**Widgets** — three home screen widgets with a sky that matches the weather. Each one shows Singapore
or one of your saved cities. It has a clock and the date, the temperature, the weather now, the place
name, and a pill with the PSI (the humidity for a city). The Square and the Strip also show the time of
the reading beside the pill; the Wide leaves it out, to give its clock the room. Every time, on the
widgets and in the app, is 24-hour (15:00), whatever the phone is set to:

| Widget | Size | Layout |
|---|---|---|
| Square | 2x2 | clock and picture on top; temperature, weather, place, pill and its time below |
| Wide | 4x2 | big clock and big picture; "27° Light Rain · Bedok" on one line, and the pill beside it |
| Strip | 4x1 | clock, picture, weather and pill in one row |

| Shows | Place | Temperature | Weather | Pill | Clock |
|---|---|---|---|---|---|
| Singapore | your forecast area, such as Bedok | average of all NEA stations | NEA forecast for your area | PSI, in its band colour | Singapore time |
| A saved city | the city and its country, such as Kyoto, Japan | the city's temperature | the city's weather | humidity | the city's own time |

The place is one line, so it never changes a widget's height. A long one ends in "…" instead of wrapping.

When you add a widget, a screen asks what it should show: Singapore or a city from My places, and the
calendars for the next-event line. Tap a place, or Done to keep the one marked. To change it later,
long-press the widget and open its settings (Android 12 and newer); on older Android, remove the widget
and add it again. Each widget keeps its own place, so a Square can show Singapore while a Wide shows
Tokyo. Tapping a city widget opens that city's page. A widget keeps its city even if you take the city
off My places.

**Next event** — the Wide and Square widgets can show your next calendar event, such as "15:00 Lunch".
On the widget's screen, open Calendars, allow calendar access, and tick the calendars to use. The list
starts with the ones your Calendar app shows; a calendar switched off in its account has no events on
the phone and cannot be ticked. One list serves every widget. With no event in the next week there is no
line at all.

The line shows the first day that still has an event. On that day a timed event beats an all-day one,
and one about to start beats one that has been running a while. Cancelled and declined events are
skipped. Times follow the phone's clock, like your Calendar app. Tap the line to open the event in your
Calendar app. It changes as an event starts and ends, and when a calendar changes.

It is only the event: no weather, and no network. The app reads each event's title and time on the
phone, and never sends them anywhere.

**Short widgets** — a home screen app decides how tall a 4x2 widget is: about 220 dp on a Pixel, about
140 dp on some other phones. The widget asks for its height and packs its text to fit. The date always
sits under the clock. The Wide has its temperature, weather and place on one line, so its clock gets the
rest: as big as the height allows, from 60 sp on a tall one down to 22 sp on the shortest, and the same
size with or without an event line. It keeps the event line down to about 108 dp. A Square shows the
event only from about 170 dp up: its line sits under the sky picture and would cover it on a shorter
widget. The date goes only where even the smallest clock leaves no room for it: on a Square below about
122 dp, and on a Wide only at its shortest height with much larger text. A larger text size in Settings
counts as less height, by as much as the text grows; the space around the text stays the same. A big
clock also shrinks for very large text, so that it stays on one line. A 139 dp Wide has a 42 sp clock,
the date and the event line, and keeps all three at 115% text with a 32 sp clock. If the home screen does
not say how tall a widget is, it is drawn as the shortest one. The widgets cannot be resized, so they
settle on a size when you add them.

The sky colour and its picture follow the weather: sunny, partly cloudy, cloudy, rain, thunderstorm,
haze and windy. After dark each of them has a night version, lit by a soft glow of the moon: a clear sky
shows the moon and the stars, partly cloudy a cloud across the moon, and rain, thunder, haze and wind
their own dark skies. In Singapore night runs from 7 pm to 7 am. A city is dark when the sun is down
there. The night skies were chosen from three mockups; the one built is option C, the moon glow
([docs/design/night-skies.html](docs/design/night-skies.html)). The picture moves for about
10 seconds each time the home screen shows it, then rests, to save battery. With animations turned off,
and on some home screen apps, it stays still. The clock is the phone's own and keeps time by itself.

Long-press the home screen, pick Widgets, then Haze Map SG. The weather refreshes about every 30 minutes
(the fastest Android allows), even when the app is closed, and when you change area in the app. A city
widget asks Open-Meteo for its city only, and a Singapore widget asks NEA only.
The sky looks the same in light and dark mode.

## Data, and what to expect

| Data | Source | Terms |
|---|---|---|
| Forecasts, temperature, humidity, wind, UV, rain gauges, PSI, PM2.5 | [NEA via data.gov.sg](https://data.gov.sg/) | Singapore Open Data Licence 1.0 |
| Rain radar pictures | [RainViewer](https://www.rainviewer.com/api.html) free API | personal use, credit required |
| Base map | OpenStreetMap, through osmdroid | © OpenStreetMap contributors |

- **NEA allows 6 calls per 10 seconds without a key.** The app keeps to 5. A cold start of the Now tab
  needs 9 calls, so it takes 10 to 15 seconds to fill in completely. The last saved copy of everything
  is shown at once, and each card updates as its data arrives.
- Offline, the app shows the last saved forecast, haze and rain-gauge readings, and map tiles you
  have already viewed. Radar pictures are not saved: the radar shows "Couldn't load the radar. Tap to retry."
- The app fetches only while it is open. The one exception is the widgets, which Android refreshes about
  every 30 minutes (see Widgets above).
- Times are always Singapore time.
- The area picker sets the forecast region by the nearest NEA region label, which is an approximation
  of NEA's actual region borders.

## Tech stack

- Kotlin 1.9 on Android, with XML layouts and view binding (no Compose). Min SDK 26 (Android 8.0),
  target and compile SDK 34.
- Android Gradle Plugin 8.5, Gradle 8.7 (wrapper), Java 17.
- AndroidX (AppCompat, Material Components, ConstraintLayout, SwipeRefreshLayout, Lifecycle),
  Kotlin coroutines, Room for the haze history.
- [osmdroid](https://github.com/osmdroid/osmdroid) for the OpenStreetMap maps and
  [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) for the history chart (from JitPack).
- Unit tests: JUnit 4 and `org.json`, on the JVM.
- CI: GitHub Actions, with an Android 14 emulator for the smoke test.

Permissions the app declares: internet and network state, coarse location (asked only for
"Use my location"), calendar read (asked only when you open Calendars for the widget line), and
installing its own updates.

## Install

1. Open the latest entry under **Releases**, on your phone.
2. Download `HazeMapSG-1.0.N.apk` and open it. Allow installing from your browser if asked.

Builds from `main` are releases; builds from `feat/**` and `fix/**` branches are pre-releases.
The app's own update check only offers full releases.

**If Android says "App not installed":** the APKs are signed with a temporary key that changes
with every build (no signing key is configured), and Android will not update an app with a
different key. Uninstall the old Haze Map SG first. Its saved haze history is lost.
To make updates install over the top, add the two secrets below.

### Fixed signing key (optional)

Add two repository secrets and every build is signed with your key:

- `SIGNING_KEYSTORE_B64` — your PKCS#12 keystore, base64-encoded (key alias `hazemap`).
- `SIGNING_STORE_PASSWORD` — its password.

Keep a backup of the keystore. If you lose it, installed copies cannot be updated.

## Build and test

### Prerequisites

- JDK 17.
- The Android SDK with platform 34 and its build tools. Point Gradle at it with `ANDROID_HOME`, or
  `sdk.dir` in a `local.properties` file (git-ignored). Android Studio sets both up.
- No API keys or accounts: every data source is public.

### Local build

```sh
./gradlew testDebugUnitTest      # unit tests, reports in app/build/reports/tests/
./gradlew assembleDebug          # debug APK in app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK in app/build/outputs/apk/release/
```

Or open the folder in Android Studio and run the `app` configuration on a phone or emulator.

### Configuration

The build reads only environment variables, all optional:

| Name | Used for | Without it |
|---|---|---|
| `GITHUB_RUN_NUMBER` | `versionCode` N and `versionName` `1.0.N` | version 1.0.1 |
| `SIGNING_STORE_FILE` | path to the PKCS#12 release keystore | release APK signed with the debug key |
| `SIGNING_STORE_PASSWORD` | keystore and key password | |
| `SIGNING_KEY_ALIAS` | key alias | `hazemap` |

In CI these come from the repository secrets `SIGNING_KEYSTORE_B64` and `SIGNING_STORE_PASSWORD`
(see [Fixed signing key](#fixed-signing-key-optional)). Release builds are not minified.

### CI and releases

Builds run on GitHub Actions (`.github/workflows/android.yml`) on pushes to `main`, `feat/**` and
`fix/**`, on pull requests, and by hand. Changes to only `*.md` files or `docs/` do not start a build.

1. **build** runs the unit tests and builds the release APK `HazeMapSG-1.0.N.apk` (N is the run number).
   On a push it publishes it as the GitHub release `v1.0.N`; a pull request keeps it only as the
   `hazemap-apk` artifact. It also builds the same code as version 1.0.1, for the self-update test.
   Each release's notes start with a **What's new** list from `.github/scripts/release_notes.py`: the
   titles of the pull requests merged since the last release (commit subjects for a branch pre-release),
   and a full-changelog compare link. Its tests run first in the same job.
2. **smoke** (pushes only) installs the APK on an Android 14 emulator (Pixel 5), opens every tab with
   live data, taps through the area picker, the Radar/Gauges switch and Play, switches dark mode and
   rotation while open, and goes offline. It also checks the widgets, the calendar line, the night skies
   and the app updating itself from 1.0.1. Screenshots and a log are kept as the `smoke-screenshots`
   artifact. It never blocks a release. The script is `.github/scripts/smoke.sh`.

The unit tests (about 200, in `app/src/test`) include the parsers, run against real NEA, RainViewer,
Open-Meteo and GitHub responses saved in `app/src/test/resources/fixtures`.

The app reads launch extras so the smoke test can reach a screen directly: `tab` (`now`, `rain`, `haze`),
`source` (`radar`, `gauges`), `zoom`, `pin_widget`, `widget_scene`, `widget_preview` and
`installed_version`.

The emulator is a Pixel, whose home screen gives widgets plenty of height, so the smoke test also draws the
widgets at exact short heights inside the app (the `widget_preview` extra) and keeps those pictures as
`fit-*.png`. To run only one part, start the workflow by hand with the input `only` set to `widget-fit`
(the widget pictures) or `update-line` (the version line and Check for updates).

## Code map

```
.github/workflows/android.yml   build, release and emulator smoke test
.github/scripts/                smoke.sh and its UI helper ui_probe.py
app/build.gradle.kts            version, signing and dependencies
app/src/main/java/.../hazemap/  all app code (one package)
app/src/main/res/               layouts, widget layouts and info, sky and icon drawables
app/src/test/                   unit tests and saved API responses (fixtures)
docs/                           screenshots and design mockups
```

All code is in `app/src/main/java/io/github/rayvader/hazemap/`:

| File | Job |
|---|---|
| `HazeApp.kt` | start-up: saved theme, update install hook, osmdroid tile cache |
| `MainActivity.kt` | tabs, area picker, update banner; builds the map tabs on first visit |
| `NowScreen.kt`, `RainScreen.kt`, `HazeScreen.kt` | one per tab |
| `RadarOverlay.kt`, `GaugeOverlay.kt`, `RadarCache.kt` | draw radar tiles and gauge dots on the osmdroid map; radar pictures saved on the phone |
| `Http.kt` | HTTP, and the rate limiter for the data.gov.sg limit |
| `WeatherRepository.kt`, `JsonCache.kt` | fetch with a disk cache; saved copies for instant paint |
| `NeaParsers.kt`, `NeaApi.kt`, `Models.kt` | parse the responses (pure, no Android) and the data they give |
| `WeatherLogic.kt`, `TimeFmt.kt` | condition icons, forecast sentences, wind, rain levels, tile maths; Singapore-time formatting |
| `HistoryDb.kt`, `HazeRepository.kt` | on-device haze history (Room) |
| `Areas.kt`, `Region.kt`, `Band.kt` | the 47 forecast areas, the five PSI regions, and the air-quality bands |
| `PlaceData.kt`, `PlaceStore.kt`, `PlacesSheet.kt`, `PlaceActivity.kt` | My places: Open-Meteo search and weather, the saved list, the city page |
| `UpdateChecker.kt`, `UpdateLogic.kt`, `Updater.kt`, `UpdateStatusReceiver.kt` | self-update from GitHub releases: check, verify, download, install |
| `WidgetUpdater.kt`, `WidgetLogic.kt`, `WidgetFit.kt`, `WidgetTarget.kt`, `WidgetPlaceActivity.kt`, `WidgetPreview.kt` | the three widgets, what they show, how they fit their height, and which place each one shows |
| `CalendarLogic.kt`, `PhoneCalendars.kt`, `CalendarsActivity.kt` | the next-event line and the calendar picker |
| `ThemeChoice.kt`, `UiHelpers.kt` | saved settings, light/dark theme, shared view helpers |

The layouts were chosen from three mockups; the one that was built is option A (tabs).
See [docs/design](docs/design/weather-mockups.html).

## Credits

Contains information from NEA's real-time weather datasets, accessed from
[data.gov.sg](https://data.gov.sg/), made available under the
[Singapore Open Data Licence version 1.0](https://data.gov.sg/open-data-licence).
Radar images by [RainViewer](https://www.rainviewer.com/). Map data © OpenStreetMap contributors.
Built with [osmdroid](https://github.com/osmdroid/osmdroid) and
[MPAndroidChart](https://github.com/PhilJay/MPAndroidChart).
