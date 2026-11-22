# NOTICE — scope and deliberate exclusions

This project is an **integration/interoperability layer** for GWM / Haval vehicles,
built to plug into the GPL-3.0 DiPlay application and to be contributed back to it.

## What this repository is

Code that runs on hardware the author owns (a 2026 Haval H7) to:

- enumerate the vehicle's logical displays and route a rendered surface to the
  instrument cluster / HUD;
- render a simple, non-interactive driver display (speed, fuel/range, optional
  small turn indication);
- observe the GWM adapter's steering-wheel key stream read-only, for later action
  mapping.

## What this repository deliberately does NOT contain

To be explicit, because this module sits near a projection app:

- **No device-authentication material of any kind.** No MFi certificates, private
  keys, identity blobs, or `offline-mfi` assets.
- **No cert-chip / coprocessor code.** No I2C authentication transport, no
  `xcertplay`-style native libraries.
- **No projection-protocol handshake or DRM-circumvention logic.** This adapter
  does not implement, replace, or bypass any authentication that a host app or a
  phone-projection session performs.
- **No redistributed third-party binaries, factory GWM APKs/ODEX/VDEX, raw HUT/CEM
  configuration, VIN, or personal data.**

The host DiPlay build is responsible for its own projection and authentication. This
adapter only adds GWM display/input integration on top of it. If a feature cannot be
implemented without touching an authentication or protection mechanism, it is out of
scope for this repository.

## Verification status

All display IDs and steering-wheel key codes referenced here are **unverified
placeholders** until logged on the actual vehicle, parked. Nothing is to be described
as "confirmed" until tested on the H7.
