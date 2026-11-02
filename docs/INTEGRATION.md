# Integrating the GWM adapter into a DiPlay build

This module is an Android library (`org.dplay.gwm`). It does not start itself; the
host app constructs and drives it.

## Minimal host usage

```java
// 1. Discover real displays (do this once, log it, record the IDs).
GwmDisplayAdapter displays = new GwmDisplayAdapter(appContext);
android.util.Log.i("GWM", displays.dump());

// 2. Pick the cluster display. Replace the guessed ID with the logged one.
int clusterDisplayId = displays.pickClusterDisplayId(GwmAdapterConfig.H7_DEFAULTS);

// 3. Render a non-interactive driver surface on it (parked test first).
ClusterOverlayRenderer cluster = new ClusterOverlayRenderer(appContext);
cluster.show(clusterDisplayId);          // throws if overlay privilege missing
cluster.update(72 /*km/h*/, 58 /*% fuel*/, 430 /*km range*/, ClusterDialView.Turn.NONE);

// 4. (Optional) observe steering keys, read-only, parked.
GwmSteeringKeyObserver keys = new GwmSteeringKeyObserver(ev ->
        android.util.Log.i("GWM", "observed " + ev));
keys.start();

// ... later
keys.stop();
cluster.hide();
```

## Where this sits relative to DiPlay's projection

- DiPlay handles phone projection and whatever authentication it performs. **This
  module never touches that.**
- The adapter draws its *own* content (speed/fuel/HUD dials) on the cluster. It does
  **not** assume it can relocate a projected map surface between physical displays —
  see `H7_DISPLAY_ARCHITECTURE.md`.

## Upstreaming

Licensed GPL-3.0-or-later to match DiPlay. Intended path: verify on the H7, then
offer the GWM display/input adapter to the DiPlay maintainers as a module they can
fold into a GWM build. Keep auth/projection concerns on their side.

## Build

Standard Android library module. Wire `gwm-adapter` into a host Gradle project as an
`:gwm-adapter` module dependency, or copy the `org.dplay.gwm` sources in. `minSdk 26`
to match the GWM port; `compileSdk` per host.
