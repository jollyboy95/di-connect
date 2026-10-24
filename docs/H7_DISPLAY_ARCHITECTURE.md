# H7 display architecture (working notes)

Target: 2026 Haval H7 Vanta (B11, AU), NOBO IN9.0-OS head unit, Android 9,
Qualcomm SA8155.

## Logical displays (UNVERIFIED — enumerate on the car)

Android exposes displays through `DisplayManager.getDisplays()`. Each has a logical
`displayId` and a reported size. The **physical** SurfaceFlinger tokens are separate
and differ per boot — do not hard-code them.

Working hypothesis for the H7 (to be confirmed by `GwmDisplayAdapter.dump()`):

| Logical ID | Role (hypothesis) | Size (hypothesis) |
|-----------:|-------------------|-------------------|
| 0 | Centre infotainment | 1920 × 1080 |
| 1 | Projection / AA map layer | 1920 × 720 |
| 2 | Gauge / cluster layer | 1920 × 720 |
| 3 | HUD | 480 × 240 |

**These IDs are a starting guess, not fact.** The first on-car task is to run the
adapter's `dump()` and record the real IDs and sizes. The mapping above only exists
so the code has named constants to replace.

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
