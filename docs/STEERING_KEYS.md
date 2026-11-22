# GWM steering-wheel key observation (read-only)

## Approach

On GWM/Harman the steering-wheel buttons are **not** guaranteed to arrive as ordinary
Android `KEYCODE_*` events to a third-party app. The known interoperability lead is a
GWM adapter event stream exposed as a system property / notify string of the form:

```
sys.other.keyevent_notify=[<a>, <b>]
```

i.e. two numeric values follow the event name. **The meaning and ordering of the two
values are unverified.** Do not assume `[keycode, action]` vs `[action, keycode]`
without logging.

`GwmSteeringKeyObserver` therefore does two independent, read-only things and logs
both so they can be correlated on the car:

1. Polls the `sys.other.keyevent_notify` property and parses the two values.
2. Logs any ordinary `KeyEvent` that reaches the host activity (`dispatchKeyEvent`),
   in case some buttons do arrive as normal key events.

## Explicitly out of scope

- No synthesising, injecting, or re-broadcasting of steering-wheel events.
- No hooking of any protected Binder/system interface.
- No remapping of safety-critical steering-wheel functions.

This is **observation only**, so that real button → code correlations can be recorded
parked before any action mapping is designed (mapping, if added, belongs in the host
app's config, not here).

## Verification checklist (on the car, parked)

- [ ] Run observer, press each steering button in turn, record raw values.
- [ ] Note which buttons produce `keyevent_notify` vs ordinary `KeyEvent` vs nothing.
- [ ] Note whether `gwm_adapter unavailable or permission denied` appears (variant /
      permission dependent).
- [ ] Only then propose a button→action table — as data, not code here.
