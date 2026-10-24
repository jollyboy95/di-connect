# GWM Harman OS — D-Play integration handoff

**Purpose:** Independently authored integration notes and short Java examples for testing secondary-display rendering on GWM infotainment hardware, plus an investigation lead concerning steering-wheel controls in the separate SmartSplit app. Source references: `jollyboy95/Cluster-test-new`, default branch inspected 23 Oct 2026 (GitHub code-search snapshot commit `8a6045b4f838c406eebb9b28da590bf90f846131`).

## Verified from ClusterProbe source

- Uses Android `DisplayManager` and `getDisplay(displayId)`, followed by `createDisplayContext(target)` and `WindowManager` to show a non-interactive `TYPE_APPLICATION_OVERLAY` with `PixelFormat.TRANSLUCENT` on a chosen logical display. See `app/src/main/java/com/clusterprobe/OverlayManager.java`.
- Also supports ordinary Activity launch via `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK`. This can throw `SecurityException` depending on permissions. See `MainActivity.java`, method `launchOnDisplay`.
- A translucent `ClusterActivity` displays a custom `DialView` and tracks active windows for cleanup. See `ClusterActivity.java`.
- The on-car project uses Android **logical** display 0 = infotainment (1920×1080), 1 = Android Auto/CarPlay map (1920×720), 2 = gauge/cluster layer (1920×720), 3 = HUD (480×240). These IDs must be enumerated on the actual vehicle; SurfaceFlinger physical tokens differ.
- `TYPE_APPLICATION_OVERLAY` requires overlay authorization and is not a guarantee that one can reorder two separately composed physical video signals. Rendering a D-Play window on logical Display 2 does not inherently move the AA/CarPlay map from Display 1 into it.

## Steering-wheel input: **SmartSplit feature — not ClusterProbe code**

The owner has confirmed that steering-wheel button remapping is a feature of the **SmartSplit app**, not ClusterProbe. In SmartSplit, steering-wheel controls can be configured to trigger app actions. Static inspection of the supplied `base.apk` (which contains `com.smartlink.smartsplit` classes) confirms several steering-key handling entry points and an explicit GWM adapter key-event pattern. Exact button-code meanings and which path is active on this H7 remain **unverified**. No `KeyEvent`, `onKeyDown`, `MediaSession` or steering-wheel event implementation appeared in the currently searched ClusterProbe source. Therefore, **there is no verified GWM key-code mapping in this pack**. The enclosed `SteeringWheelInputProbe.java` is a standalone read-only diagnostic example for a test Activity; it does not claim global access to steering-wheel buttons. D-Play developers should confirm whether their app receives normal `KeyEvent`s, media session commands, or a GWM-specific Binder / broadcast path. Never infer the concrete codes or assume all button events reach third-party apps.

## Integration notes / safety

- Do not alter the factory cluster service, automotive permissions, system configuration, or required telltale/warning display.
- Driver-visible cluster content and input behavior should be validated while parked, and never used to suppress speed/gear/warnings.
- The examples are implementation references, not an installable APK and not a promise of D-Play functionality on all GWM models.
- This pack deliberately excludes: factory GWM APKs/ODEX/VDEX, raw HUT/CEM configuration, VIN/location/personal data, signing material, and third-party binary code.

## Contents

- `code/ClusterDisplayExample.java` — minimal directly adapted logic from ClusterProbe for selecting the display and adding/removing an overlay.
- `code/ActivityLaunchExample.java` — exact API approach used in ClusterProbe to launch a custom Activity onto a selected display.
- `code/SteeringWheelInputProbe.java` — **generic experimental diagnostic**, not from SmartSplit or ClusterProbe, for foreground key-event discovery; may not capture SmartSplit's actual mechanism.
- `SOURCE_REFERENCES.md` — links to actual project source files and distinctions between verified behavior and future work.
- `SMARTSPLIT_STEERING_FINDINGS.md` — verified string-level findings extracted from the supplied SmartSplit `base.apk`; no third-party binaries or proprietary code.

## Suggested next D-Play proof of concept

1. Enumerate available logical displays and verify real display sizes on each target GWM vehicle.
2. With the car parked and factory cluster intact, render a non-interactive test overlay on Display 2 and verify visual position and cleanup.
3. Treat SmartSplit steering-wheel customization as a separate reverse-engineering lead. Use the verified static SmartSplit findings in `SMARTSPLIT_STEERING_FINDINGS.md`, then use the optional generic foreground key test to see whether ordinary Android key events are involved. Do not present the probe as extracted SmartSplit code.
4. Decide whether D-Play requires a separate video renderer per display or can create a dedicated secondary presentation; **do not** assume existing Display 1 frames are automatically shared with Display 2.
