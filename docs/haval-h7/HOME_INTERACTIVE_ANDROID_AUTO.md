# Can Home show the real, touchable Android Auto? (investigation, 21 Nov 2026)

Short answer: **yes, but not by copying the cluster map.** It needs Home and the projection to
share one video surface. The cleanest route is for Home to become a layer *over* the full Android
Auto picture, with Android Auto told to keep its own buttons inside the map zone.

## What exists today

| Stream | AA channel | Who decodes it | Input |
|---|---|---|---|
| Main projection (the full AA screen) | `ID_VID` | `App.provide().videoDecoder`, drawn into `AapProjectionActivity`'s Surface/Texture/GL view | touch on `ID_INP`, mapped in `AapProjectionActivity.sendTouchEvent` from view pixels to the negotiated video size minus margins |
| Cluster map | `ID_CLUSTER_VID` | the cluster decoder, drawn on display 2 | `ID_CLUSTER_INP` (rotary/keys only, no touch) |

The cluster stream is a separate, small map with no touch input, made for the dashboard.
Showing it in Home would give a map you can look at but not use. That is what the Home preview
names as "the live map … comes in the next build".

## Why the main stream can't just be "moved" into Home

- `VideoDecoder.setSurface()` stops the codec. The next picture needs a fresh keyframe from the
  phone. Measured on this project's phones, that takes **6–116 s** (see the comment above the
  stall watchdog in `AapProjectionActivity`). So swapping the surface between Home and the full
  screen would black out AA every time the driver switches.
- The video size is fixed for the session (`HeadUnitScreenConfig.negotiatedResolutionType`, for
  example 1920×1080). Only margins can change during a session (`UpdateUiConfig`, already used
  when the system bars change).

## Workable designs

1. **Home as an overlay on Android Auto (recommended).** Keep `AapProjectionActivity` and its
   surface exactly as they are. Send AA margins (the existing `UpdateUiConfig` path) for the
   strips Home uses (top bar, dock, right column, climate bar), so AA lays its map, buttons and
   keyboard inside the map zone. Draw Home's glass chrome in those margins, in the same activity
   or as an overlay window. Touches in the map zone go to AA through the existing mapping; touches
   on the chrome go to Home. "Full screen AA" then just sends zero margins: there is no surface
   change, and no keyframe wait.
   - Cost: a Home layout mode inside the projection activity, plus margin plumbing. Most of the
     margin and touch maths already exists.
   - Risk: some phones re-layout slowly after a margin change. Test switching on the H7 first.
2. **Home owns the surface.** Move the projection view into Home's map zone, and make the full
   screen a resize of that same view (resizing a SurfaceView keeps its Surface, so the decoder
   carries on). This is a bigger refactor of `AapProjectionActivity` (watchdog, renderer
   fallback, focus cycle), so it is a later step.
3. **Cluster map in Home (not recommended).** Easy, but it can't be touched and it costs the
   dashboard its map.

## Suggested next step

Prototype design 1 behind a Di-Connect setting marked (experimental). Add a Home layout mode to
`AapProjectionActivity` that sends margins matching `HomeDashboardView`'s zones, and draws the
existing Home view with the map zone left transparent. Validate on the H7 that:
- AA re-lays out inside the zone within about 1 s,
- touch lands where it is drawn,
- the cluster and HUD overlays on displays 2 and 3 are unaffected.
