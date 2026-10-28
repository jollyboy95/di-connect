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

## Guidance protocol experiments (added 28 Oct 2026)

Access: same Diagnostics section, below the map test. All default off/baseline; each applies at the next Android Auto connection (Restart).

| Experiment | What changes | Evidence level | Look for | Revert |
| --- | --- | --- | --- | --- |
| Guidance protocol 1.6 | Version request 1.6 instead of 1.2 | Gate confirmed in decompiled AA 16.1/16.2 (open-android-auto); not yet seen on the H7 | `NAV-EXPERIMENT version ... phone=` reply; `received type=32774/32775`; ETA/time left in Di-Connect directions and the HUD | Baseline 1.2 |
| Guidance protocol 4.3 | Version request 4.3 (phone answers 6.1 per 17.3 traces) | 4.3 gate for hidden UI elements confirmed static in AA 17.3; other gates between 1.7 and 4.3 not documented | Same as 1.6, plus whether anything else on the main screen changes | Baseline 1.2 |
| Turn-card flag | Cluster video config adds hidden UI element 5 (field 5, value 5); only sent when 4.3 is selected | Static trace: phone passes EXTRA_SHOW_TURN_CARD=false to the cluster nav service; Google Maps' reaction unverified | Whether Google's card disappears from the cluster video; `cluster hidden_ui_elements=[5]` | Switch off |
| Cluster-ready signal | Sends empty 0x8001 on the navigation channel after its open response | Direction HU→phone agreed by open-android-auto (16.2 handler, confidence "unverified"), AARE notes and AARE's head unit; effect unknown | `cluster-ready sent type=32769`; any change in received message types | Switch off |

Notes:
- Our 0x8007 decoder matches open-android-auto field-for-field (ETA string field 2, time-to-arrival field 3); those messages were never received because of the 1.2 request.
- 0x8006 step field 2 is the instruction text and field 4 the road names; the mapper now prefers field 4.
- ImageOptions field 1 is height and field 2 width (aa-proxy-rs, open-android-auto); our generated names are swapped. Both are 128, so the bytes are unchanged.
- Licences: open-android-auto GPL-3.0, aa-proxy-rs GPL-2.0, AARE GPL-3.0, open-headunit AGPL-3.0 (read for facts); mh2p-cluster is CC BY-NC-SA 4.0, read only, nothing copied. No code was copied from any of them.

### Findings, H7 report 11 (build r25941384)

- Confirmed: requesting 1.2 gives legacy guidance only (0x8004/0x8005). Requesting 1.6 (phone answered 1.7) or 4.3 (phone answered 6.2) gives rich guidance only: 0x8006 steps and 0x8007 position, with no legacy messages.
- Confirmed: 0x8007 carries the destination metres, the formatted ETA and the seconds to arrival. These match Google's card. Its step distance has only the shown figure and units, with no metres. The mapper now reads the shown figure, so Di-Connect's card and the HUD work at 1.6 and above.
- Not seen: lane guidance, or a second step.
- Inconclusive: phone disconnects (EOF) happened at both 1.6 and 4.3 for a few minutes and then stopped. They are not clearly tied to the version.
- Not yet tested: the turn-card flag and the cluster-ready signal.
