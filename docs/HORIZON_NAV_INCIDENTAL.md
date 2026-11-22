# Horizon Nav — independent cluster navigation: investigation notes

Date: 2026-11-02 (follow-up to the UI modernisation branch work)

## Question

Can Di-Connect render its own navigation on the instrument cluster (GWM display routing:
head unit display 0, cluster display 2, HUD display 3 on the Australian H7 — configurable per
`GwmMode` / `GwmAdapterConfig`) while the Android Auto and CarPlay projection engines keep doing
the main head-unit work?

## Findings

- **Licence.** The reference implementation under investigation
  (<https://github.com/benbishop028-arch/Horizon-Maps>) contains no `LICENSE` file, so no code has
  been copied or incorporated. The renderer, if built, must be original work informed by the
  architecture only.
- **Mapbox.** The architecture assumes a Mapbox-style 3D map with token/licence-gated SDK. Di-Connect
  has no Mapbox token, so an independent renderer is required rather than a drop-in SDK use.
- **Hardware.** Display IDs 0/2/3 are H7-only confirmations for one vehicle class. Capability
  detection and configurable display mappings must stay in front of any cluster-side rendering, and
  factory cluster elements (READY, EV, ECO, speed sign, lane/car indicators, fixed gear) must not be
  obscured or repositioned without safe support.
- **Safety.** Navigation on the cluster is a driver-facing, safety-relevant surface. Nothing in the
  vehicle integration may write unverified commands; read-only discovery first.

## Decision

An independent cluster navigation renderer stays **experimental and off** behind
`ClusterNavigationExperiment` (default `false`). It must not replace the working Android Auto /
CarPlay guidance surfaces until validated on real hardware, and the factory HUD must keep working.

## Next validation steps (not implemented here)

1. Confirm cluster display capability on additional GWM models before any renderer ships.
2. Validate 3D rendering performance on the cluster GPU/CPU budget.
3. Only then consider enabling the flag through a settings-controlled, experimental-gated control.
