# H7 Android Auto map negotiation experiments

Access: Android Auto settings > GWM/Haval > Diagnostics > Map layout tests (experimental).
Changes take effect on the next Android Auto connection. Select the mode, return home and use Restart. Do not change the theme, detail setting, route or map zoom between comparisons. Setup and photographs should be done while parked.

| Run | Map test | Arrow images | Evidence |
| --- | --- | --- | --- |
| A | Baseline | Off | On |
| B | Stock reserved areas | Off | On |
| C | Stock areas, 160 DPI, current stream | Off | On |
| D | Stock areas, 1280 stream, 160 DPI | Off | On |
| E | Minimal reserved areas | Off | On |
| F | Phone default (insets omitted) | Off | On |
| G | Best map result from A-F | On | On |

For each run: reconnect, start the same route, photograph the map and directions/ETA placement, then save a diagnostic report. Test once in a full-map theme, then repeat the best result in Centre and Split. Look for complete directions, visible route/car marker, legible labels, and top/bottom versus combined side-card placement. Record app version, selected test and theme with each result.

Restore Baseline and turn Arrow image capability off, then reconnect, to restore current negotiation. Evidence logging can be switched off immediately. Existing native warning indicators are unaffected. The existing local navigation-card switch may deliberately produce a duplicate card; leave it off during the map-placement comparisons.

The stock constructor has insets top=140, bottom=100, left=240, right=240 at 1280x720. We have not verified that this was the selected stock H7 profile. B scales those ratios to the actual map content. C fixes DPI at 160 without changing the codec. D changes codec size but preserves the physical map window aspect; it is NOT a claim of bit-for-bit stock negotiation. F omits content/stable insets but keeps codec letterboxing, so the map is not stretched or cropped. No mode promises to remove Google's card.

NAV-EXPERIMENT negotiation lines record the actual profile, stream content, DPI and insets. Opt-in received lines record message IDs, field presence, byte counts, timing values and unknown field numbers; new evidence does not export road/destination text, coordinates or image contents. Unknown fields are evidence only, never guessed into timing values. The arrow-image capability test asks for 128x128/32-bit images and does not itself add a new renderer or guarantee richer ETA data.

CarPlay map-window negotiation is a separate pending implementation; these tests affect Android Auto only.
