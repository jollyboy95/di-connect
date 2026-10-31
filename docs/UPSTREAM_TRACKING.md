# Upstream tracking

Di-Connect is its own app. DiPlay and DiAuto are sources of selected fixes, not replacements.
The machine-readable baseline is in `docs/upstream-baseline.json`. The **Watch upstream DiPlay and DiAuto** workflow
(`.github/workflows/upstream-watch.yml`) compares against it every week and opens an issue when there is something new.
It never changes code. Scheduled workflows run from the default branch only, so it starts once this file reaches `main`.

## How Di-Connect is built from its donors

| Part | Upstream | Our fork (pinned commit) | Assembled by |
| --- | --- | --- | --- |
| CarPlay, Settings, cluster and HUD base | [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) (MIT) | jollyboy95/d-play-gwm-h7 | `_integration/integrate.py` |
| Android Auto engine | [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto), from mikereidis/headunit (GPL-3.0) | jollyboy95/DiAuto-GWM | `_integration/integrate.py` |

`integrate.py` patches the donor source by exact text and stops when a patch no longer matches. Each sync therefore
shows every place where upstream touched code that Di-Connect changes.

## Current baseline (reviewed 31 Oct 2026)

| Upstream | Before | Now | Code changes taken |
| --- | --- | --- | --- |
| DiPlay | `4487c689`, 22 Oct 2026 (0.2.21 plus fixes) | `615b32ce`, 28 Oct 2026 (0.2.22) | 99 code commits (tests included) |
| DiAuto | `f304672`, 22 Oct 2026 (0.3.12) | `f304672` (0.3.12) | none. Upstream has not changed since our baseline. |

Donor fork: `jollyboy95/d-play-gwm-h7` branch `maintenance/upstream-sync` at `fcdbce6f`.

## DiPlay 4487c689..615b32ce: review

| Item | Upstream commits | Already present? | Benefit | Conflict risk | Decision |
| --- | --- | --- | --- | --- | --- |
| Ignore unsafe extras on intents from other apps | b85e257c | No | Other apps cannot drive Di-Connect's screens | Medium: our auto-connect patch read the same extra | Adapted: our patch now uses upstream's `requestedPage()` |
| USB permission results need an app-only permission | 4c9af446 | No | Forged USB results are refused | Low | Imported |
| AirPlay limits: control header, body, iAP package, bplist depth; data sockets only for the control peer | a83284c5, 411f0b60, aeb1d733 | No | Crash and memory safety on hostile or broken input | Low | Imported |
| NCM NTB16 parsing bounds | 1759a981 | No | Wired CarPlay cannot loop on a bad frame | Low | Imported |
| MFi: https for public hosts, no numeric-host tricks | 59622e1f, d898ad48 | No | Safer remote MFi | Low (offline MFi unchanged) | Imported |
| Update download limits: github.com https only, plain APK names, size cap | 65ab1f36 | No | Safer in-app updates | Medium: the 64 MB cap is sized for DiPlay | Adapted: cap raised to 128 MB (Di-Connect is about 40 MB); its tests follow the cap |
| Backup import key allow-lists; map-embed caps; Bonjour status-line cap | 3e7d94cc, 63aca3bc, 91f6d36d | No | Hardening | Low | Imported |
| Hotspot name kept out of logcat; UDID redaction in reports | 43d75593, 4fca1756 | No | Privacy in shared reports | Low | Imported |
| Report: header kept above the cut, tail kept, viewer shows newest 200k characters | 2be80cec, e13f9f52, b090d460, 718a9681 | No | Faster report screen; exported report still complete | Low | Imported |
| Session log written on a background thread; overflow reported | e0ccc4de, c69f0165 | No | Less main-thread work while CarPlay runs | Low | Imported |
| Touch: merge queued moves, queue a move only once accepted | ad03bf44, 47b39a5c | No | Smoother CarPlay touch | Low | Imported |
| Trace text built only when wanted; USB log caps | 50957d41, 2cce3825, 8e739c52 | No | Less CPU on busy links | Low | Imported |
| Music rate steering (buffered and realtime) to the level held | 0d73aed3, 7f40cfab, 193e3fdd | No | Steadier CarPlay music, fewer drop-outs | Low | Imported |
| Microphone PCM packets within one link MTU; call mic at the call's own rate | 8d5fd4b2, 03b588de | No | Clearer CarPlay calls | Low | Imported |
| Cluster stream starts after the main CarPlay frame, without reconnecting; native cluster recovery for late displays | 56cc0b67, a1dfc298 | No | Faster, steadier CarPlay map on the cluster (display 2) | Medium: same lines as our GWM cluster presentation record | Adapted: kept our record, added upstream's main-frame gate. **Test in the car.** |
| Wired start: wait for a late USB network, no cable prompt during re-enumeration, USB device check after 20 s | b014031a, a60c6a3e, e80374aa | No | Wired CarPlay reliability | Low | Imported |
| Boot start returns to a running CarPlay session | f6786b21 | No | No second session after the car wakes | Low | Imported |
| Ask to restart the iPhone when the screen stream has no picture; name known failure causes; remember the last problem | ce4c41c8, 9f6f3275, fa5f9bfe, 4dc1085d | No | Clearer CarPlay errors | Low | Imported |
| iAP2 lane guidance declared, so the iPhone sends lane data (0x5204) | 057ca284 | No | Lane guidance for CarPlay cluster and HUD | Low | Imported |
| Opt-ins: audio focus for navigation prompts; keep CarPlay in the launcher card's task; calls at the call's own rate | ef485eb4, 785d0264, 9d09a1ef | No | New settings, off by default | Low | Imported |
| Manual external network interface name | 48997a54 | No | Wired setups with odd interface names | Low | Imported |
| BYD, Denza N9 and ECARX vehicle items | 459e049b, dced75da, d971ca4f | No | None on the H7 (they only switch on for those cars) | Low | Imported unchanged (detected per car) |
| Speedometer text contrast on album backgrounds | f20c2498 | No | Readability in D-Play's own cluster | Low | Imported |
| Korean and Turkish | fc7cc5b9, 2227b9b4, b7b7b29f, 4f8c8a8f, aa0f1a9f | No | Two more languages | Medium: Di-Connect's own text has no ko/tr yet | Adapted: Di-Connect's strings show in English there (111 ko, 120 tr) |
| Version 0.2.22 | 28d6002b | — | — | Low | Imported (versionName; the GWM revision version code is unchanged) |
| Website, docs and READMEs | many | — | None in the app | — | Skipped |

## Conflicts found and how they were resolved

1. `CarPlayHostActivity.setClusterSurface`: upstream added `clusterOutputReady()` and our fork records the cluster
   presentation. Both are kept.
2. `mobile/build.gradle.kts`: the GWM revision version code is kept and the name follows upstream 0.2.22.
3. `CarPlayHostActivity.onDestroy`: upstream adds forwarded duplicate screens that return early. The climate strip's
   show, pause and hide now skip forwarded screens, and hiding runs after upstream's early return.
4. `DiPlayActivity`: the auto-connect patch follows upstream's `requestedPage(intent)`.
5. New `values-ko` and `values-tr` locales: Di-Connect's string tables stay at nine locales, and an English fallback
   file fills the gaps.

## Not changed by this sync

These keep their current code and behaviour:

- Android Auto engine (DiAuto), cluster themes, HUD
- Climate strip and the GWM vehicle data writes
- Hotspot 5 GHz guard, ADB keep-on, primary-phone wake
- Settings, app ID and signing key

## Still to test in the car

The automated build compiles the app and runs the unit and render tests only. None of these can be checked off the car:

- CarPlay wireless and USB connect, music, calls and the microphone
- CarPlay map on the cluster (display 2), most of all after the upstream main-frame gate
- Android Auto connect, cluster themes, HUD (display 3) and the climate strip, as a regression check
- In-app update from this build to the next one

## Sync history

| Date | Branch | DiPlay | DiAuto | Notes |
| --- | --- | --- | --- | --- |
| 31 Oct 2026 | `maintenance/upstream-sync` | 615b32ce (0.2.22) | f304672 (0.3.12, unchanged) | First tracked sync |
