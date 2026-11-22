# GWM / Haval H7 port

Android Auto (DiAuto) for the 2026 Australian Haval H7 Vanta Hybrid (B11, Harman OS / NOBO
IN9.0-OS, Android 9). The main Android Auto screen stays on the centre display; the instrument
cluster and head-up display get optional outputs. This is a separate Android Auto app, not a
combined CarPlay/Android Auto app.

**Nothing here is validated on the car yet.** It compiles and its unit tests pass in CI; every
display ID, data key and layout position must be checked parked before road use.

## Source

Ported from [jollyboy95/d-play-gwm-h7](https://github.com/jollyboy95/d-play-gwm-h7) at
`1460810` ("Lift the GWM cluster bottom row above the car's lamp row", built there as r15). Only
GWM integration code was taken. No CarPlay protocol classes, authentication resources,
credentials, signing keys or APKs were copied. See `docs/THIRD_PARTY.md` for licensing.

| In DiPlay | Status there | Here |
| --- | --- | --- |
| Live cluster stream in an overlay window on display 2 (`GwmLiveProjectionOverlay`) | Working on the H7 (Apple Maps cluster stream shown) | Same window route, fed by DiAuto's Android Auto cluster stream: `gwm/GwmOutputs.kt` |
| Aston-style layer (`GwmClusterHudView`) | Built, iterated on the car (r9–r15) | `gwm/GwmClusterLayerView.kt`, adapted to Android Auto data, with explicit unavailable/stale states |
| `gwm_adapter` vehicle reads (`GwmVehicleData`) | Built; on the H7 every value came back empty | `gwm/GwmVehicleData.kt`, plus the `com.gwm.android.adapter.permission.GET_DATA` permission (protection level normal) that the adapter checks before answering — found in `GwmAdapterServer.apk` on the car |
| Display enumeration and dump (`GwmDisplayAdapter`) | Diagnostic | `org/dplay/gwm/GwmDisplayAdapter.java` |
| Platform detection and settings (`GwmMode`, persistence) | Working | `gwm/GwmMode.kt`, `gwm/GwmSettings.kt` (own preferences file) |
| Head-up arrow strip (`GwmHeadUpView`) | Partly built | `gwm/GwmHeadUpView.kt`: arrow, distance, arrival only |
| Steering observer (`GwmSteeringKeyObserver`) | Polled `sys.other.keyevent_notify` as a system property; never saw a key | Replaced: that id is a gwm_adapter data id (GwmAdapterServer.apk). `gwm/GwmSteeringKeys.kt` registers a data-change listener; `connection/carkey/GwmAdapterKeyReceiver.kt` feeds the firmware codes (1000–1007, 2000–2007, 4000–4004) into DiAuto's Keymap, where each button is assigned to an Android Auto action |
| Parked test pattern (`GwmDisplayTestView`) | Diagnostic | `org/dplay/gwm/GwmDisplayTestView.java`, shown as an overlay |
| Old circular dial renderer (`ClusterDialView`) | Superseded | Not ported (the approved design has no large gauges) |
| CarPlay-specific `MAP_WITH_CUSTOM_CARD` request | CarPlay only | No Android Auto equivalent; see "Navigation cards" |

## How it fits DiAuto

DiAuto already declares an Android Auto **CLUSTER** video sink
(`aap/protocol/messages/ClusterDisplayServices.kt`), decodes it with its own
`clusterVideoDecoder`, and crops the 16:9 codec frame to the panel (`hud/ClusterMapConfig.kt`).
The GWM path reuses all of that:

- `ClusterDisplayServices.profile()` returns `GwmClusterProfile.forSession()` on the GWM route:
  the same sink, sized to the selected display and with a safe area that fits the GWM layout.
- `ClusterMapScreen.show()` hands off to `GwmOutputs.start()` when GWM mode is active, so none of
  the BYD display lookup, cluster-theme monitor or layer logic runs on a GWM head unit. BYD
  behaviour is otherwise unchanged.
- `GwmOutputs` adds a non-focusable `TYPE_APPLICATION_OVERLAY` window on the selected display
  (DiPlay's working route), puts a `TextureView` for the cluster decoder in it, and the custom
  layer above it. No second receiver, decoder or protocol stack exists.
- Session end (`AapTransport`) and `ClusterMapScreen.dismiss()` call `GwmOutputs.stop()`, which
  removes the windows, stops vehicle polling, frees the decoder surface and clears music/guidance.
  A reconnect starts everything again from `AapProjectionActivity.onStart()`.

### Navigation cards

Android Auto gives two separate things:

1. The **cluster video**, drawn by the navigation app. Google Maps draws its own small cards into
   that video. They are pixels in the stream: they cannot be moved, restyled or removed. DiAuto
   can only influence where the app places them through the safe area (content insets).
2. **Navigation metadata** on the navigation status channel (`aap/AapNavigation.kt`: next turn,
   distance, road, time and distance to destination). The custom navigation card and the HUD are
   drawn from this, through DiAuto's existing `BydNavigationMapper` → `DashboardGuidance`, so they
   can sit anywhere.

The GWM safe area (`GwmClusterProfile`: top 31 %, bottom 25 %, sides 6 %) keeps the car marker and
the app's own cards in the band between the custom navigation card and the instrument strip. Because
the app's card cannot be turned off, DiAuto's own card is off by default; it can be turned on in settings.

### Music

From Android Auto's media playback channel (`MediaMetaData` title, artist, album, album art,
duration; `MediaPlaybackStatus` state and position) in `aap/AapService.kt` →
`gwm/GwmMusicState.kt`. It does not depend on DiAuto's "sync media session" setting.

## Cluster layout

Map-first, after the approved Aston Martin / CarPlay Ultra-style design, built around the stock
**OFF-ROAD** cluster theme. Panel percentages (verify on the car):

| Element | Position | Source |
| --- | --- | --- |
| Live map | full panel | Android Auto cluster video |
| Stock gear indicator | top centre, 0–10 %, left clear | car |
| Navigation card | top centre, 10.5–30.5 % (shorter when the phone sends no trip data) | Android Auto navigation metadata |
| Music card | top right, 10–28.5 % | Android Auto media channel |
| Instrument strip | 76 % to bottom: fuel bar + range from 20.5 % (right of the stock lamps), speed centre, odometer right | `gwm_adapter` |
| READY, speed-limit/assist, ECO | lamp row under the strip | car, draws above DiAuto |

No circular gauges and no copies of stock indicators. A value the car does not report shows `—`.
Before any data arrives the speed unit reads "no data"; if the feed stops for more than 2 s it
reads "data paused" and the numbers are dropped, never frozen.

## Build

```sh
./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug \
  :app:assembleGithubDebug -PgwmAbiFilters=arm64-v8a -PgwmRevision=<n>
```

CI: `.github/workflows/build-gwm-h7.yml` runs this on every push to `main` or a `gwm-h7-*`
branch and publishes `DiAuto-GWM-H7-arm64-debug.apk`, its `.sha256`, and test/lint reports to the
`gwm-h7-builds` branch (and as a workflow artifact).

- **Package**: the debug build is `com.andrerinas.headunitrevived.gwmh7`, version `0.3.12-gwm.r<n>`.
  It installs next to the official DiAuto and Head Unit Revived (which share
  `com.andrerinas.headunitrevived`) without replacing them or touching their data. Only run one
  Android Auto receiver at a time. The BYD-only debug test outputs keyed to `.bydhudtest` are off
  in this build.
- **Signing**: set the repository secret `GWM_DEV_KEYSTORE_B64` (base64 PKCS12, alias
  `diauto-gwm-dev`, password `diautogwmdev` unless `GWM_DEV_KEYSTORE_PASSWORD` is set) and every
  build is signed with it, so each new APK updates the last one in place. Without it CI falls
  back to a throwaway key and warns. The key is never committed.

## On-car test steps (parked, ignition on, handbrake on)

1. Install the APK (`adb install -r DiAuto-GWM-H7-arm64-debug.apk`). Leave GWM's factory Android
   Auto and any other receiver (Head Unit Revived, official DiAuto) closed.
2. Open DiAuto GWM → Settings → **GWM / Haval cluster, HUD and vehicle data**. It should say the
   head unit was detected; if not, switch on "Use the GWM route on this head unit".
3. Allow **Display over other apps** if asked.
4. Tap **Scan displays and vehicle data**. Note every display ID, name and size, and whether
   `gwm_adapter` is reachable with real speed/fuel/range/odometer values. Send this text back.
5. Tap **Show test pattern on the cluster display**: "DISPLAY TEST" should appear on the cluster.
   Hide it again. If it appears elsewhere, pick the right display under "Cluster display".
6. Switch on **Android Auto map on the cluster** and **Traffic keeps left**. Connect the phone.
7. Check the centre screen shows Android Auto normally, and the cluster shows the map with the
   custom layer. Start a Google Maps route: the navigation card should show turn, distance,
   road, arrival time. Play music: the music card should show title, artist, artwork, progress.
8. Check the stock gear indicator, READY, speed-limit/assist and ECO are all still visible and
   not covered. Photograph the cluster for spacing adjustments.
9. Optional: switch on the HUD arrow and pick display 3. The windscreen shows only a small arrow
   window of that display: tap **Show HUD grid**, note which lettered cells you can read, and set
   the arrow area (left, top, width, height) to cover them. Then check the arrow during a route.
10. **Show steering-wheel key codes** and press each button once to see its code. With Android Auto
    connected, open Settings → Keymap, pick an action and press the button to assign it.
    Unassigned buttons are ignored by DiAuto and keep their factory behaviour.
11. Disconnect the phone: the cluster and HUD windows must disappear. Reconnect: they return.

Report anything that differs. Don't treat any of this as confirmed until those steps pass.
