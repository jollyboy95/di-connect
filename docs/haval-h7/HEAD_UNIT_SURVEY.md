# Haval H7 head unit survey (ADB, 21 Nov 2026)

A read-only survey of the real head unit over wireless ADB, to ground Di-Connect 2.0 in what the
car actually runs. Nothing on the car was installed, enabled, written or restarted. Re-run the
capture with `scripts/haval-survey.sh <serial>`; its raw output stays out of git because it
holds the VIN, serial number, Wi-Fi names and MAC addresses.

Sanitised Vehicle HAL property list: [vhal-properties.txt](vhal-properties.txt).

## 1. Identity

| Item | Value |
|---|---|
| Build | `GW35OVBPU1G091203B11`, Android 9 (API 28), `user/test-keys` |
| Board | Qualcomm SA8155 (`sa8155_v35_b16ovs`), kernel 4.14 aarch64 |
| Brand / model | `GWM` / `HAVAL` |
| Platform code | B16 overseas (`b16ovs`; theme path `/mnt/vendor/appres/theme/b16ovs`) |
| Supplier | **NOBO**, not Harman: `NoboAudioManager` in power control, `GWMElectronicManual_nuobo`, "NOBO IN9.0-OS" |
| Second OS | QNX alongside Android (`QnxDataTrackServer`, `size_system_data_qnx`), linked over internal Ethernet VLANs `eth0.2–eth0.12` |
| Skin family | Tank (`TANK_` Bluetooth name prefix, `tank-skin` keyboard) |
| Shell | `uid=2000(shell)`; no root; SELinux `u:r:shell:s0` |

> The existing docs call the unit "B11" and "Harman". The firmware says B16 overseas and NOBO.
> `GwmDisplayAdapter.isGwmTargetDevice()` still matches (`gwm`, `haval`), so detection is fine.

## 2. Factory apps you can open

All are system apps on `android.uid.system`. Labels are the English ones the car shows.

| Role | Package / component | Label | Entry points |
|---|---|---|---|
| Card launcher (factory Home) | `com.gwm.app.launcher/.view.HomeActivity` | GwmLauncher | HOME, `gwm.intent.action.HOME` broadcast |
| "Dynamic ball" launcher | `com.gwm.dynamiclauncher/.ball.ball.DynamicLauncherActivity` | Dynamic ball | HOME (priority −100), **currently the resolved default Home** |
| App menu (task manager) | `com.gwm.app.taskmanager/.MainActivity` | GWMTaskManager | — |
| Vehicle Settings | `com.gwm.app.vehicle.center/com.gwm.app.vehicle_center.VehicleDesignMainActivity` | **Car** | LAUNCHER, `VIEW gwm://vehicle` |
| System Settings (GWM) | `com.gwm.app.settings/com.gwm.settings.view.ui.MainActivity` | Settings | `android.settings.SETTINGS`, `WIRELESS_SETTINGS`, `com.gwm.settings.SETTINGS`, `VIEW open://settings.app.gwm.com`, `APPLICATION_DETAILS_SETTINGS` |
| Android Settings (AOSP) | `com.android.settings/.Settings` | Settings | LAUNCHER (also has the network / hotspot screens) |
| Climate | `com.gwm.app.hvac/.service.HvacService` (no activity) | GwmHvac | service actions `com.gwm.intent.action.TOGGLE_HVAC_CONTROLS`, `…_FRONT_CONTROLS`, `…_REAR_CONTROLS` |
| Power / energy | `com.gwm.app.energy.management/.MainActivity` | Power | LAUNCHER |
| Off-road | `com.gwm.app.offroadovs/.MainActivity` | OFF ROAD mode | LAUNCHER |
| I Space | `com.gwm.superspace/.MainActivity` | I Space | LAUNCHER, dock service `com.gwm.app.dock.remote` |
| Phone | `com.gwm.app.carphone/.ui.main.MainActivity` | Phone | LAUNCHER |
| Users / account | `com.gwm.app.personalcenter/.PersonCenterMainActivity` | Users | LAUNCHER |
| Weather | `com.gwm.eco.weatherservice/.weather.ui.weather.home.HomeActivity` | — | LAUNCHER |
| Navigation | `com.gwm.navi.app/com.gwm.navi.hmi.main.activity.NaviAutoActivity` | Navigation | LAUNCHER |
| Yandex Navigator | `ru.yandex.yandexnavi.auto/com.yandex.navigator.automotive.app.MainActivity` | — | LAUNCHER (Russia-market preload) |

The Vehicle Settings label is **"Car"** and both settings apps are labelled **"Settings"**. That
matters for Home (see section 9).

### Engineering and test menus (do not surface to drivers)

| Package | What it is |
|---|---|
| `com.gwm.engineermode/.GwmHomeActivity` | Engineering mode. Normally opened by the receiver `.GestureReceiver` on `gwm.intent.action.ENGINEERING_MODE_GESTURE` / `ENGINEERING_MODE_HARD_KEY`; it also has a LAUNCHER entry. It was open on the car during the survey. Sub-screens: `GwmScreenShowActivity`, `GwmScreenTouchActivity`, USB service `GwmUsbService`. |
| `com.gwm.traceur` | Labelled "GwmEngineerMode" too (trace capture) |
| `com.gwm.naviengineer/.NaviEngineerModeActivity` | Navigation engineer mode, also `com.gwm.naviengineer.naviengineermode` |
| `vendor.gwm.gwmpkiengmode` | PKI / certificate engineering mode |
| `com.gwmv3.radio.testmode/.TestActivity` | Tuner test |
| `com.gwm.app.personalization/.TestActivity` | Personalisation test |
| `com.gwm.car.eolaudiotest`, `eoltunertest`, `com.gwm.eol.*` | End-of-line factory tests |

These can change calibration, coding (`persist.vendor.gwm.cfg.*`) and certificates. Di-Connect
must not launch them or include them in its app grid.

### Hidden or removed for the driver

| Package | Path | State for user 0 |
|---|---|---|
| `com.gdc.carplay` (Apple CarPlay) | `/system/app/CarPlay` | **uninstalled** (`installed=false`); still on the system partition |
| `com.gwm.app.aap` (Android Auto v2.0-20221010) | `/system/app/AndroidAuto` | **uninstalled** |
| `com.gwm.app.manual` (owner's manual) | `/system/app/GWMElectronicManual_nuobo` | hidden, disabled |
| `com.gwm.app.armrestscreen` | `/system/app/GWMRearArmrestOVS` | hidden, disabled |
| `com.gwm.app.wadeovs`, online music/radio, TuneIn | — | uninstalled |

Removing the factory projection apps for user 0 is what leaves the USB port and the iAP2 / AOA
handshake free for Di-Connect. `cmd package install-existing com.gdc.carplay` would bring the
factory app back. It is reversible, but it changes how the car answers a plugged-in phone, so
it was **not** done and needs the owner's go-ahead. The car's coding still says both are fitted
(`persist.vendor.gwm.cfg.carplay.function=1`, `cfg.android.auto=1`,
`cfg.carplay.authenticated=true`); global settings `android_auto_start_connect=0` and
`key_Android_Auto_state=2` are the factory AA app's own state.

## 3. Media

| Layer | Packages |
|---|---|
| Player UI | `com.gwm.app.car.media` "Media Center" (AOSP car media: `MediaDispatcherActivity`, `android.car.intent.action.MEDIA_TEMPLATE`) |
| Sources | Radio `com.gwm.app.mediatunerovs/.ui.HomeActivity` ("Radio"), USB music `com.gwm.overseas.localmusic` ("Music"), USB video `com.gwm.overseas.localvideo` ("USB Video"), Yandex Music, VK Video |
| Source services (media sessions) | `com.gwm.app.media.server.radio`, `.usbmusic`, `.usbvideo`, `.bluetooth`, `com.gwm.app.media.union`, `com.gwm.app.media.server.mix` |
| Home cards | `com.gwm.app.minibar` (`MusicPlayerView`, `RadioPlayerView` in the card launcher) |
| Entry intents | `com.gwm.app.media.intent.action.PLAY_MUSIC` / `PLAY_VIDEO` (category `com.gwm.app.media.intent.category.PLAY`) on `com.gwm.media.entry` |

At survey time Di-Connect's Android Auto session (`HeadunitRevived`) held the media-button
session. Every factory source publishes a standard `MediaSession`, so Home can show factory
radio or USB as Now Playing through `MediaSessionManager`. That needs Di-Connect to be an
enabled notification listener, which it is not yet (the listeners today are GWM's, Kafo Themes'
and OpenLauncher's).

## 4. Vehicle interfaces

| Binder service | Interface | Notes |
|---|---|---|
| `car_service` | `android.car.ICar` | AAOS car service. `dumpsys car_service` is readable from shell and lists **2,656 VHAL properties** |
| `gwm_adapter` | `com.gwm.android.adapter.IGwmAdapterService` | GWM key/value bridge (`car.hvac.*`, `car.comfort_setting.*`). Di-Connect's `GwmClimateBar` already uses it |
| `cluster_manager` | `com.gwm.android.multidisplay.IClusterViewManager` | cluster view manager in `GWMMultiDisplay` |
| `hardkey_service` | `com.gwm.app.hardkey.IHardKeyService` | steering keys; Di-Connect already listens |
| `wifigwm` | `com.gwm.wifi.IWifiGwmManager` | hotspot and STA control (section 7) |
| `gwm_feature`, `gwm_skin`, `gwm_gesture` | `android.app.IGwmFeatureService`, `IGwmSkinManager`, `IGwmGesture` | feature flags, themes, gestures (framework additions) |
| `com.gwm.fota.service`, voice, account | — | not relevant to Home |

### VHAL properties worth using (read-only)

Standard AAOS: `PERF_VEHICLE_SPEED`, `CURRENT_GEAR`, `GEAR_SELECTION`, `IGNITION_STATE`,
`PARKING_BRAKE_ON`, `NIGHT_MODE`, `FUEL_LEVEL`, `FUEL_LEVEL_LOW`, `EV_BATTERY_LEVEL`,
`EV_CHARGE_PORT_CONNECTED`, `ENV_OUTSIDE_TEMPERATURE`, `PERF_ODOMETER`, `DOOR_LOCK`,
`DISPLAY_BRIGHTNESS`, `ENGINE_RPM`.

GWM (`GWM_<ECU>_<signal>`; 2,604 of them): `GWM_DISPLAY_DAY_NIGHT_STATE`,
`GWM_DISPLAY_BRIGHTSTATE_{IVI,CLUSTER,HUD}`, `GWM_HUD_SwtSts`, `GWM_HUD_BrightnessLvl`,
`GWM_HUD_HeightLvl`, `GWM_HUD_UIMod`, `GWM_IP_*` (cluster fuel / range), `GWM_VCU_AvrgEgyCns`,
`GWM_L_PM25InAirQltyLvl` (cabin air quality), `GWM_T_Box_PM25AirQLvl`.

### Climate

Standard HVAC properties are present and read/write: `HVAC_POWER_ON`, `HVAC_AC_ON`,
`HVAC_AUTO_ON`, `HVAC_MAX_AC_ON`, `HVAC_MAX_DEFROST_ON`, `HVAC_DEFROSTER`, `HVAC_RECIRC_ON`,
`HVAC_AUTO_RECIRC_ON`, `HVAC_DUAL_ON`, `HVAC_FAN_SPEED`, `HVAC_FAN_DIRECTION`,
`HVAC_TEMPERATURE_SET`, `HVAC_STEERING_WHEEL_HEAT`, `HVAC_TEMPERATURE_DISPLAY_UNITS`. Coding:
dual-zone A/C (`cfg.a.c.system=2`), comfort mode, max fast cooling, front seat heating and
ventilation, driver headrest. The factory climate UI is SystemUI's **"HVAC Container"**
window, which `com.gwm.app.hvac` opens through its `TOGGLE_HVAC_*` service actions.
`dev.cybercat.acbridge` (a third-party climate bridge) is also installed.

### Vehicle-critical: never write

Write-only `GWM_HUT_*` properties are head-unit-to-CAN commands. Examples:
`GWM_HUT_EPB_SwtPosn` (parking brake), `GWM_HUT_AVHSwitchSts` (auto hold),
`GWM_HUT_HDCSwitchCtrl` (hill descent), `GWM_HUT_HAVP_*` (automated parking),
`GWM_HUT_EgyRecvrySet`, `GWM_HUT_HybModReq`, `GWM_HUT_ChrgStopReq`, `GWM_HUT_CreepSwt`,
`GWM_HUT_AccelPedalSnvtyAdj`, plus every `GWM_ADAS_*`, `GWM_ACC_*`, `GWM_AEB_*`, `GWM_EPS_*`
and `OBD2_FREEZE_FRAME_CLEAR`. Di-Connect must keep an explicit **allow-list** of the climate
and comfort keys it writes, and never write anything else, as `GwmClimateBar` does today.

## 5. Displays (verified)

| Logical ID | Unique ID | Size | Density | Role (verified by windows on it) |
|---:|---|---|---:|---|
| 0 | `local:0` | 1920 × 1080 | 160 | Centre screen, built-in, touch |
| 1 | `local:1` | 1920 × 720 | 213 | HDMI presentation, **no windows at survey time**; front-passenger screen is not fitted (`cfg.front.passenger.screen=0`) |
| 2 | `local:3` | 1920 × 720 | 213 | **Instrument cluster**: `GWMMultiDisplay` overlay plus Di-Connect's cluster overlay |
| 3 | `local:4` | 480 × 240 | 71 | **HUD**: `GWMMultiDisplay` presentation plus Di-Connect's HUD overlay |

Displays 1–3 have `FLAG_PRESENTATION` and `FLAG_SECURE`, and their devices are
`FLAG_OWN_CONTENT_ONLY`, so they never mirror the centre screen. This confirms
`GwmAdapterConfig.H7_CLUSTER_DISPLAY_ID = 2` and `GWM_HUD_DEFAULT_DISPLAY_ID = 3`. The table in
`docs/H7_DISPLAY_ARCHITECTURE.md` was right, except that display 1 is not shown as a projection
layer: nothing draws on it.

### Instrument cluster

- `com.gwm.android.multidisplay/.InstrumentClusterRenderingServiceImpl` is the AAOS
  instrument-cluster renderer, bound by the car service and guarded by
  `android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE`. Only one renderer can be
  bound, so Di-Connect should keep drawing its own overlay rather than replace it.
- The cluster's dials are drawn on the QNX side. Android draws layers over them; coding
  `cfg.cluster=9`, with time display and units from `cfg.cluster.*`.
- `com.gwm.app.scenemode` listens for `com.gwm.intent.action.CLUSTER_WIDGET_UPDATE`,
  `CLUSTER_WIDGET_UP_DOWN` and `CLUSTER_VIEW_SERVICE_START`. That is the factory route for
  cards on the cluster, and is worth studying for a native-looking music or turn card.
- Global setting `cluster_brightness_level=3`, `cluster_brightness_compensation=0`.

### HUD

- Fitted (`cfg.head.up.display=1`), 480 × 240 at 71 dpi on display 3.
- VHAL: `GWM_HUD_SwtSts` (on/off), `GWM_HUD_BrightnessLvl`, `GWM_HUD_HeightLvl`,
  `GWM_HUD_UIMod`, `GWM_HUD_ReslType`, `GWM_DISPLAY_BRIGHTSTATE_HUD`; global
  `hud_brightness_compensation=0`. Read these to dim or hide Di-Connect's HUD layer when the
  driver turns the HUD off. Do not write the `*Swt` requests.

## 6. Android Auto and CarPlay

- At survey time Di-Connect's AA (`com.andrerinas.openheadunit.aap.AapProjectionActivity`) was
  running over the hotspot, with cluster and HUD overlays on displays 2 and 3.
- USB was idle (`kernel_state=DISCONNECTED`, functions `adb`). Port role sysfs
  (`/sys/class/typec`) is absent, so host/device switching is not visible to apps.
- The factory CarPlay and AA apps are removed for user 0 (section 2). If a firmware update
  restores them, they compete for the phone. Di-Connect should detect
  `com.gdc.carplay` / `com.gwm.app.aap` being installed and tell the driver.
- `com.qualcomm.wfd.service` (Miracast) and `wifip2p` are present. P2P device name `HAVAL_xxxx`.

## 7. Wi-Fi and hotspot

- `wifigwm` (`IWifiGwmManager`, v20241008) runs station and hotspot **at the same time**:
  `wlan0` is a client of the home or phone network, and `wlan1` is the access point
  `192.168.43.1/24`, **5 GHz, channel 149**, with up to 8 clients. 2.4 GHz AP is off.
- Allowed AP channels: 2.4 GHz 1–13, 5 GHz 36–48 and 149–165 (no DFS).
- Wi-Fi country code is **RU** (`persist.vendor.gwm.wificountrycode`; the boot value is `cn`).
  Channel 149 suits Australia, so this is not urgent. Changing the country code is firmware
  configuration and needs the owner's approval.
- Soft AP uses a block-list (empty). `isSoftapRouterAndDnsInfoEnabled=true`.
- SystemUI quick settings include a `hotspot` tile; the AOSP settings path is `Settings >
  Network & internet`. GWM's own `WifiSettingService` lives in `com.gwm.app.settings`.

## 8. What else is installed (driver-installed apps)

OpenLauncher, Kafo Themes (with accessibility and dashboard services), Shizuku, App Manager,
Permission Manager X, Termux, F-Droid, Aurora Store, EasyTouch, Lower Brightness, SmartSplit,
GWM Voice Bridge, Cluster Store, `dev.cybercat.acbridge`, and audio boosters.

- GWM keeps an **app allow-list** in global settings (`apps_white_list`,
  `installed_apps_white_list`); Di-Connect (`com.jollyboy95.diconnect.debug`) is on it. A
  release package ID would need adding too, or the app menu may hide it.
- Enabled accessibility services still name `com.shihab.diplay` and `com.shihab.diplay.hudtest`,
  which are no longer installed. They are harmless, but worth tidying.
- The unit trusts **many ADB keys**, including keys created by on-car apps (Shizuku, App
  Manager, Permission Manager). Anyone on the car's hotspot who has one can reach the shell.
  Consider turning wireless ADB off when not developing.
- Shizuku is running. It gives Di-Connect shell-level access at runtime without a computer.

## 9. Compared with Di-Connect today

| Area | Di-Connect today | On the car | Action |
|---|---|---|---|
| Cluster / HUD display IDs | 2 / 3, marked "hypothesis" | 2 / 3, verified | Mark as verified in `H7_DISPLAY_ARCHITECTURE.md` (done) |
| Climate | `gwm_adapter` keys via `GwmClimateBar` | Also standard `HVAC_*` VHAL props and the factory panel | Keep `gwm_adapter`; add "Open full climate" through `TOGGLE_HVAC_CONTROLS` |
| Home › Vehicle settings | matches launcher label "vehicle" / "car setting" | label is "Car" | **Bug**: falls back to Di-Connect. Use the component |
| Home › System settings | matches label "setting" | two apps are labelled "Settings" | Ambiguous. Use `com.gwm.app.settings` explicitly |
| Home › Factory Home | first HOME activity that isn't Di-Connect | four candidates: GwmLauncher, OpenLauncher, Dynamic ball, FallbackHome | Target `com.gwm.app.launcher/.view.HomeActivity` |
| Home › Sources | label match "radio", "usb", "music", "bluetooth" | Radio, Music, USB Video, Media Center | Map to components; Bluetooth goes to Media Center |
| Home › Apps grid | every LAUNCHER activity | includes engineering and EOL test apps | Filter them out |
| Now playing | Di-Connect's own projection music only | factory sources publish media sessions | Add `MediaSessionManager` (needs notification listener) |
| Day/night | Di-Connect setting | `GWM_DISPLAY_DAY_NIGHT_STATE`, `NIGHT_MODE`, `theme_mode` | Follow the car |
| Factory projection apps | not checked | removed for user 0 | Warn if they come back |
| `GwmLemonPlatform` markers | `com.gwm.life.launcher` and similar | not present (that is the older x86 platform) | No change; the H7 uses `GwmMode` |

## 10. Liquid Glass Home: recommendations

In priority order. Each is small and keeps today's behaviour as the fallback.

1. **Open factory screens by component, not by label.** Add a table of known components
   (`com.gwm.app.vehicle.center/…VehicleDesignMainActivity`,
   `com.gwm.app.settings/…MainActivity`, `com.gwm.app.launcher/.view.HomeActivity`,
   `com.gwm.app.mediatunerovs/.ui.HomeActivity`, `com.gwm.overseas.localmusic`,
   `com.gwm.app.car.media`), try each with `resolveActivity`, and only then fall back to
   label matching. This fixes "Vehicle settings" opening Di-Connect.
2. **"Full climate" button** in the temperature and fan popovers, calling
   `startService(Intent("com.gwm.intent.action.TOGGLE_HVAC_CONTROLS").setPackage("com.gwm.app.hvac"))`.
   It opens the factory panel for anything the glass bar does not cover (rear, recirculation,
   max defrost). It needs an on-car test, because the service's exported state was not
   confirmed.
3. **Hide engineering and test apps** in the Apps popover: `com.gwm.engineermode`,
   `com.gwm.traceur`, `com.gwm.naviengineer`, `vendor.gwm.gwmpkiengmode`,
   `com.gwmv3.radio.testmode`, `com.gwm.app.personalization` (TestActivity), `com.gwm.eol.*`,
   `com.gwm.car.eol*`. Put factory apps first (Car, Climate, Radio, Music, Phone, Power,
   Off-road, Navigation), then the driver's apps.
4. **Follow the car's day/night.** Read `GWM_DISPLAY_DAY_NIGHT_STATE` (or `NIGHT_MODE`) and
   drive `HomeDashboardView.night`, so the glass dims with the cluster.
5. **Status chips from real signals.** Show hotspot "5 GHz · 1 phone" from `wifigwm` or
   `WifiManager`, outside temperature from `ENV_OUTSIDE_TEMPERATURE`, cabin air quality from
   `GWM_L_PM25InAirQltyLvl`, and range from `GWM_IP_AuxRemainDistance` or `FUEL_LEVEL`. Hide a
   chip whose source does not answer, as the climate bar already does.
6. **Factory media as Now Playing.** Once Di-Connect is a notification listener, take the
   active session from `com.gwm.app.media.server.*` when no phone is projecting. The Bluetooth
   and USB cards then work without a phone.
7. **Driving-aware layout.** Use `PERF_VEHICLE_SPEED` / `GEAR_SELECTION` to lock the Apps grid
   and long text while moving, as the factory UX restrictions do (`CarDrivingState`).
8. **HUD awareness.** If `GWM_HUD_SwtSts` says off, stop drawing the HUD layer. Match its
   brightness to `GWM_HUD_BrightnessLvl`.
9. **Stay a normal activity for now.** Do not declare `CATEGORY_HOME` yet. The resolved Home
   is already `com.gwm.dynamiclauncher` (the driver's choice). If Home becomes the default
   later, ship it as a disabled-by-default `activity-alias` the driver turns on, so the factory
   Home is always one setting away.
10. **Shizuku as an optional helper.** Where Di-Connect needs shell rights today (auto hotspot,
    `settings put`, granting its own app-ops), use Shizuku when it is running, so the driver
    does not need a computer.

## 11. Needs the owner's approval (not done)

- Reinstalling the factory CarPlay or Android Auto app for user 0.
- Changing the Wi-Fi country code, HUD or cluster coding, or any `persist.vendor.gwm.cfg.*` value.
- Opening engineering mode functions or writing any `GWM_HUT_*` / ADAS / chassis property.
- Setting Di-Connect as the default Home.
