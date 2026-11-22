# Cluster theme design direction

The whole 1920×720 panel is available. With Android Auto protocol 4.3/6.2 and the turn-card capability flag, the phone hides its stock directions card. Each layout owns the shape, position and material of Di-Connect's guidance. Do not keep a shared top-centre gap or reuse the same instrument sidebar across every theme.

The H7 paints its fixed symbols above our layer. Decorative surfaces and maps can continue behind them. Keep essential speed, turn arrow and turn distance clear of these approximate centres, allowing a few percent for variation:

| Symbol | x | y |
| --- | --- | --- |
| Gear | .50 | .06 |
| READY | .52 | .90 |
| Drive mode | .79 | .89 |
| Auto hold | .03 | .53 |
| EV car | .03 | .81 |
| Speed limit | .12 | .84 |
| Lane assist / departure | .18 | .83 |
| Vehicle status | .91 | .25 |
| Headlights | .26–.29 | .11 |
| Seatbelt | .69 | .11 |
| Parking brake | .81 | .15 |
| Passenger warning | .11 | .24 |

## Current collection

- Luxury Turbine: overlapping silver instruments on the left; open map and upper-right guidance.
- Performance Cockpit: central tachometer and road speed; separate energy rail; low right guidance.
- Music Lounge Panorama: scenic backdrop, independent album sleeve and speed arc; lower-centre guidance over the scenery, leaving the live map unobstructed.
- Daylight Future / Pearl Dusk: sculpted pearl instrument shell and low navigation ribbon; light and dark palettes.
- Smoked Glass: deep transparent instrument slab with independent energy wells and media shelf.
- Aurum Grand Tour: large gold instrument on the right, angled guidance on the left; map through the centre.
- Woodland Retreat / Woodland Afterglow: scenic forest, organic instrument shell and timber media shelf; pearl and walnut night palettes.

Use real readings only. Missing vehicle readings must not be replaced with made-up values. Guidance disappears when inactive; media uses actual artwork and metadata. Times use a 12-hour clock. Names are fictional. Bright palettes have separately selectable dark companions; these are not automatic day/night switching.

Phone map imagery stays live. Theme scenery is static ornament, never a substitute route map. Music and Woodland request a dedicated Android Auto map window and are unavailable on CarPlay until its resizing path is implemented. Other layouts support both projections. Render tests use a schematic map to expose overlap and contrast; it is not the map appearance in the car.

## Asset cost

The bundled scenic PNGs are approximately 1.8 MB (fjord) and 2.8 MB (forest), before APK compression. They are intentional local assets. Static Atelier material layers are cached per view size so navigation updates repaint only live content over the cached ornament.

Review full and compact renders on `di-connect-releases` / `theme-renders`, then test on the H7 with guidance active and inactive. Work on `codex/four-cluster-concepts`; fetch and rebase before pushing, never force-push and never replace a running build.

## Open-map collection and H7 feedback

The next collection adds Horizon Ribbon, Orbit Glass and Nightfall Audio, and updates the existing Aurora entry in place. These four use the full phone map, with no photographic replacement and no solid sidebar. Aurora keeps its teal/violet/pink outlines and familiar speed/flow instruments, gains a horizontal music card with larger text, and moves navigation to the lower right. Horizon uses a tapered titanium blade and lower-left guidance. Orbit uses transparent partial edge arcs and a vertical guidance lozenge. Nightfall floats media and speed over the map with upper-right guidance.

Performance's RPM/speed instrument is now on the left, not over the map car. Its music moves to the upper-right and battery flow is integrated below the instrument. Music Lounge's odometer uses a larger opaque capsule.

The render fixture now includes a car marker. A dedicated central corridor (43–65% across, 40–82% down) must remain untouched in the four new full-map layouts and Performance, with and without guidance, on both projection types. This represents the observed car-marker area and road ahead; it is a regression test, not a claim that every navigation app fixes its marker at one pixel.
