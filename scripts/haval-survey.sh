#!/usr/bin/env bash
# Read-only survey of a GWM / Haval head unit over ADB.
#
#   scripts/haval-survey.sh <adb-serial> [output-dir]
#
# It only reads (getprop, pm list, dumpsys, settings list). It never installs, enables, writes
# a setting or calls a Vehicle HAL property. The raw output contains the VIN, serial number,
# Wi-Fi names and MAC addresses: keep it out of git (the default output dir is ignored).
set -euo pipefail

serial="${1:?usage: $0 <adb-serial> [output-dir]}"
out="${2:-build/haval-survey/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
a() { adb -s "$serial" "$@"; }

a get-state >/dev/null
a shell getprop                                  > "$out/getprop.txt"
a shell pm list packages -f -s                   > "$out/packages-system.txt"
a shell pm list packages -f -3                   > "$out/packages-user.txt"
a shell pm list packages -u                      > "$out/packages-including-uninstalled.txt"
a shell service list                             > "$out/services.txt"
a shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
                                                 > "$out/home-activities.txt"
a shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
                                                 > "$out/launcher-activities.txt"
for s in display window activity media_session usb wifigwm; do
  a shell dumpsys "$s"                           > "$out/dumpsys-$s.txt" 2>&1 || true
done
a shell dumpsys car_service                      > "$out/dumpsys-car_service.txt" 2>&1 || true
for ns in global system secure; do a shell settings list "$ns" > "$out/settings-$ns.txt"; done

# Vehicle HAL property names (no values), the same format as docs/haval-h7/vhal-properties.txt.
grep -E '^Property:0x' "$out/dumpsys-car_service.txt" \
  | sed -E 's/^Property:(0x[0-9a-f]+)\([0-9]+\),Property name:([^,]*),access:(0x[0-9]),changeMode:(0x[0-9]).*/\1 \2 \3 \4/' \
  | sort -u -k2 > "$out/vhal-properties.txt" || true

echo "Survey written to $out"
