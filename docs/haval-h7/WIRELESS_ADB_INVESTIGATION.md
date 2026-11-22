# Wireless ADB auto-enable on the Haval H7 — decompiled (21 Nov 2026)

Owner's own vehicle and firmware backup. Goal: make the car answer ADB over Wi-Fi after a cold
start without opening the full Engineering Menu and flipping the Wi-Fi ADB switch each drive.

**This is now fully traced** from the decompiled engineering app
(`~/Documents/haval-h7-firmware/system/app/GWMEngineerMode`, vdex → smali via the dexlib2 bundled
in apktool 2.6.0). One empirical test remains, and Di-Connect's own diagnostic report already
answers it.

## Why it matters to Di-Connect

`shared/.../adb/LocalAdb.kt` connects to **`127.0.0.1:5555`** as the `shell` user with Di-Connect's
own key and runs privileged shell commands that way (no root). Every ADB-dependent feature is
bootstrapped by **adbd listening on TCP 5555**. On this unit that port only opens after the
Engineering Menu's Wi-Fi ADB switch is flipped, and it closes again at the next cold boot.

## Exactly what the Wi-Fi ADB switch does (decompiled)

In `com.gwm.engineermode`, `GwmHutInfoFragment.writeNetAdbPort(isOpen)` (obfuscated `b.b.c.g.b.i.Y0`):

```
SystemProperties.set("service.adb.tcp.port", isOpen ? "5555" : "-1")
SystemProperties.set("ctl.restart", "adbd")
```

That is the entire switch — two `setprop`s, run as **system uid** (`android.uid.system`). The code
even catches a failure with the log "Missing relinux permission, error ->", i.e. even system uid is
sometimes blocked by SELinux.

Confirmed from the smali:
- The switch's displayed state is read **live** with `getprop service.adb.tcp.port` and
  `Settings.Global adb_enabled` — it stores no persistent "port on" flag.
- **`service.adb.tcp.port` is volatile** (a `service.*` prop; gone every boot).
- **`persist.adb` appears nowhere in the engineering app.** GWM never uses the persistent property.
- `GwmBootCompleteReceiver.onReceive` on `BOOT_COMPLETED` only: starts `GwmUsbService`, and sets
  `vendor.gwm.adb_install_disable=true` (this is the `adb install` block). **It does not touch the
  tcp port.** So nothing re-opens 5555 at boot. This matches the owner's experience exactly.

Live snapshot while the switch was on (`adb-state-snapshot.txt`): `service.adb.tcp.port=5555`,
`persist.adb.tcp.port` **empty**, `adb_enabled=1`, `ro.secure=1`, `ro.debuggable=0`,
`vendor.gwm.adb_install_disable=true`.

## The one remaining unknown (and how it's already answered)

Stock Android keeps an init rule `on property:persist.adb.tcp.port=*` that copies it into
`service.adb.tcp.port` at boot, so **`setprop persist.adb.tcp.port 5555` would make 5555 open at
every cold boot** — *if* two things hold on this unit:
1. the `shell` user is allowed to set `persist.adb.tcp.port` (SELinux), and
2. this firmware kept that init rule.

Di-Connect's `AdbSwitch.persistNetwork()` **already** runs `setprop persist.adb.tcp.port 5555` over
the loopback connection and reads it back, reporting one of:
- "set to stay on across restarts" (readback = 5555) → persist is writable; only (2) left to prove
  by a reboot test.
- "the car refused to keep it on (persist.adb.tcp.port='…')" → shell is blocked from setting it.

So the next step is just to **read Di-Connect's diagnostic report** after it has had one ADB
session, or run the probe below. No more decompiling is needed.

### On-car probe (next brief connection)

```
adb -s <serial> shell 'setprop persist.adb.tcp.port 5555; getprop persist.adb.tcp.port'
# if it reads back 5555:  reboot the unit, wait, then:  adb connect <ip>:5555  (does it open itself?)
# also capture the init rule, read-only:
adb -s <serial> shell 'cat /init*.rc /vendor/etc/init/*.rc 2>/dev/null | grep -i adb.tcp.port'
```

## The fix for Di-Connect (by outcome of the test)

**If shell can persist the port (likely clean win):** `AdbSwitch` already sets it; make sure
`persistNetwork()` runs on the very first successful session and that the result is surfaced on the
Home ADB status. Then wireless ADB opens itself at every cold boot and all ADB features "just work"
after the one-time bootstrap — no engineering menu, no root.

**If shell is blocked from `persist.adb.*`:** persistence needs system/root, which we are avoiding.
Realistic fallback without root: keep a **single** manual enable per cold boot, but have Di-Connect,
as soon as it gets that session, reproduce the switch itself
(`setprop service.adb.tcp.port 5555; setprop ctl.restart adbd`) and hold the loopback connection for
the whole drive, so every ADB feature works for the rest of that session. A root path
(`persist.adb.tcp.port` as root, or a tiny init trigger) is the only way to remove the per-cold-boot
step entirely, and is a separate decision.

## Full Engineering Menu (the "tap dance")

`GestureReceiver` (exported, `gwm.intent.action.ENGINEERING_MODE_GESTURE` /
`…_HARD_KEY`) gates the full menu on the **current foreground app** (it checks for
`com.gwm.app.settings/.MainActivity`, the map, or media center) plus the gesture count. So a bare
`am broadcast` from the shell will not reliably unlock it — it must be fired while the right screen
is on top. This is why only the on-screen tap sequence works. Not needed for the ADB goal (the Wi-Fi
ADB switch itself is just the two setprops above); documented so we don't chase the broadcast.

## Status

Mechanism fully reverse-engineered offline. Nothing changed on the car or in Di-Connect's code.
The remaining step is empirical (persist-port reboot test), and Di-Connect's existing report already
distinguishes the two cases. Decompiled smali kept at the analysis scratchpath, not committed.
