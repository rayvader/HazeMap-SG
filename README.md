# Haze Map SG

An Android app that shows Singapore's air quality on an interactive map, with
a per-region readings history. Data comes from Singapore NEA's free
[data.gov.sg real-time API](https://data.gov.sg/) — no API key needed.

## What it does

- **Map of the 5 NEA regions** (North, South, East, West, Central), colored by
  air-quality band.
- **Two map views, with a toggle:**
  - **Zones** — each region is a translucent colored square.
  - **Dots** — each region is a colored circle on the map.
- **Two readings, with a toggle:**
  - **PSI** (24-hour) — the default.
  - **PM2.5** (1-hour fine dust).
- **History on the phone.** Every time you open the app and it fetches, each
  region's reading is saved to an on-device database. Tap a region to see its
  line chart over time. Nothing is sent to any server; history lives only on
  your phone.
- **Fetch only when open.** The app pulls fresh data when you launch it, pull
  to refresh, or tap the refresh button. It does **no** background work, so it
  uses no battery while closed.

History fills over time: the more often you open the app, the more points the
chart has. Readings older than about a month are pruned to bound storage.

## Air-quality bands

| Band | PSI (24h) | PM2.5 (1h, µg/m³) |
|---|---|---|
| Good | 0–50 | 0–55 |
| Moderate | 51–100 | 56–150 |
| Unhealthy | 101–200 | 151–250 |
| Very unhealthy | 201–300 | 251–350 |
| Hazardous | 300+ | 350+ |

## Tech

- **Native Kotlin**, min SDK 26, target SDK 34.
- **[osmdroid](https://github.com/osmdroid/osmdroid)** for the map — free
  OpenStreetMap tiles, no API key or token.
- **Room** for the on-device history store.
- **[MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)** for the
  history line chart.
- No Google Play Services, no account, no login.

## Build

Builds run on **GitHub Actions** (`.github/workflows/android.yml`). Every push
to `main`, `feat/**` or `fix/**` runs the unit tests, builds a release APK, and
publishes it as a GitHub release. Pull requests build and keep the APK as a
workflow artifact without releasing.

To get the app:

1. Open the **Actions** tab (or **Releases**) in this repository.
2. Download the latest `HazeMapSG-1.0.N.apk`.
3. Copy it to your Android phone and open it to install (allow installing from
   this source if prompted).

Branch builds are published as **pre-releases**; builds from `main` are full
releases.

### Signing (optional)

Without a signing key the APK is signed with the debug key, which still
installs. To sign with your own key, add two repository secrets:

- `SIGNING_KEYSTORE_B64` — your PKCS#12 keystore, base64-encoded.
- `SIGNING_STORE_PASSWORD` — its password.

The key alias is `hazemap`.

### Build locally

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Data source and attribution

Air-quality data © Singapore National Environment Agency (NEA), served via
[data.gov.sg](https://data.gov.sg/). Map tiles ©
[OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
