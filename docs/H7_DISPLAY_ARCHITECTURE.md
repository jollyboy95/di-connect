# H7 display architecture (working notes)

Target: 2026 Haval H7 Vanta (B11, AU), NOBO IN9.0-OS head unit, Android 9,
Qualcomm SA8155.

## Logical displays (verified on the car, 21 Nov 2026)

Android exposes displays through `DisplayManager.getDisplays()`. Each has a logical
`displayId` and a reported size. The **physical** SurfaceFlinger tokens are separate
and differ per boot — do not hard-code them.

Verified with `dumpsys display` and `dumpsys window` on build `GW35OVBPU1G091203B11`
(full survey: [haval-h7/HEAD_UNIT_SURVEY.md](haval-h7/HEAD_UNIT_SURVEY.md)):

| Logical ID | Role | Size | Density |
|-----------:|------|------|--------:|
| 0 | Centre infotainment (built-in, touch) | 1920 × 1080 | 160 |
| 1 | HDMI presentation; nothing draws on it on this car | 1920 × 720 | 213 |
| 2 | Instrument cluster (GWMMultiDisplay and Di-Connect overlays) | 1920 × 720 | 213 |
| 3 | HUD (GWMMultiDisplay presentation and Di-Connect overlay) | 480 × 240 | 71 |

Displays 1–3 are `FLAG_PRESENTATION`, `FLAG_SECURE` and own-content-only. Logical IDs
were stable across this boot. Keep the IDs as settings, in case a firmware update
reorders them.

## Why a window on display 2 is not automatically "the map on the cluster"

Placing a `TYPE_APPLICATION_OVERLAY` window on logical display 2 renders *this app's*
surface there. It does **not** move frames that another composed video signal (e.g.
the projection map on display 1) is drawing. Two separately composed physical signals
are not reorderable just because an overlay is added. So:

- The adapter can draw its **own** content (dials, speed, HUD) on the cluster.
- Moving a projection map surface onto the cluster is a separate question for the
  host app and is **not assumed** to work here.

## Permissions / caveats

- Overlay requires authorised overlay privilege (`SYSTEM_ALERT_WINDOW` or an
  equivalent OEM grant). `ActivityOptions.setLaunchDisplayId` may throw
  `SecurityException` depending on launch policy — callers must catch it.
- Overlay windows are non-interactive here by design.
- Verify positioning and cleanup parked before any road use.
