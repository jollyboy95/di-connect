# SmartSplit steering-wheel integration findings (static APK inspection)

## Source and verification level

The user supplied a 55 MB `base.apk` on 23 October 2026. Its primary `classes.dex` contains `com/smartlink/smartsplit/` class descriptors. The observations below are **literal symbols and strings found inside that DEX**. This is static string inspection, **not** control-flow decompilation, an active-car test or proof of what is running on this exact vehicle. These strings are supplied as interoperability clues only; no extracted code or third-party APK is redistributed.

## Confirmed symbols and patterns in DEX

- `com.smartlink.smartsplit.ACTION_STEERING_KEY` — an app-namespaced steering-key action string. Its broadcast or intent semantics are **not verified** merely by its name.
- `handleSteeringKey`, `onSteeringKey`, `handleRawKey`, `changeWallpaperFromSteering`, `handleTransparencySteeringPress` — steering-related method names.
- `getKeyCode`, `dispatchKeyEvent`, `onSteeringKey$lambda$121` — additional symbols, not proof of a particular delivery path.
- `sys.other.keyevent_notify`, `sys.other.keyevent_notify=`, `gwm_adapter:sys.other.keyevent_notify=[` — GWM adapter/event source strings.
- `sys\.other\.keyevent_notify.*\[([0-9]+),\s*([0-9]+)\]` — literal regular expression indicating parsing of **two numeric values** following the event name. The meaning and ordering of the two values remain unverified.
- `GWM adapter steering key polling started`, `GWM adapter steering key polling stopped` — indicates steering-specific polling code exists.
- `Logcat filter started with car data and steering key tags`, `Steering rawCode=`, `Steering action=`, `handleRawKey ignored duplicate rawCode=`, `handleRawKey ignored because mappedAction=0` — logging strings indicating raw code processing, mapping and duplicate filtering.
- `steeringKeyDebounceMs`, `lastAdapterKeyCode`, `isLicenseActiveForSteering` — configuration/state symbols relevant when inspecting behavior.
- `gwm_adapter unavailable or permission denied` — an explicit diagnostic suggesting this access path may fail depending on permissions or vehicle variant.

## Developer guidance for D-Play

The strongest interoperability lead is the GWM adapter's `sys.other.keyevent_notify` stream, not assuming generic Android `KEYCODE_*` events. Inspect the relevant entry points and parser on a legally obtained copy, verify actual on-device events **while safely parked**, then associate observed codes with button actions. Check applicable app permissions and licensing. Implement read-only observation first and avoid invoking control interfaces or hijacking safety-critical steering-wheel functions.

No verified steering-wheel key numeric mapping, protected Binder signature, or confirmed GWM broadcast interface is supplied. The generic `SteeringWheelInputProbe.java` example is not copied from SmartSplit and should not be misrepresented as SmartSplit's implementation.
