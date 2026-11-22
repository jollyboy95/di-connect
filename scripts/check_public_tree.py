#!/usr/bin/env python3
"""Fail CI if credential containers or private-key blocks enter the source tree."""
from pathlib import Path
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
failures = []
# The GWM H7 workflow commits one source-only debug APK at the root. It is allowed only while it
# holds no accessory identity and no credential container.
published_apk = 'DiPlay-GWM-H7-arm64-debug.apk'
# The fixed debug signing key that lets each GWM revision install over the last. Debug keys are not
# secret (standard password "android"); allowed by exact path only, never as a release key.
debug_keystore = 'mobile/gwm-debug.keystore'


def credential_free_apk(path):
    try:
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                lower = entry.lower()
                if 'offline-mfi/' in lower or Path(lower).suffix in blocked_suffixes - {'.apk'}:
                    return False
                if private_block.search(archive.read(entry)):
                    return False
        return True
    except zipfile.BadZipFile:
        return False


for name in filter(None, names):
    path = root / name
    if not path.is_file():
        continue
    if name == debug_keystore:
        continue
    if name == published_apk:
        if not credential_free_apk(path):
            failures.append(name)
    elif '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name)
    elif private_block.search(path.read_bytes()):
        failures.append(name)
if failures:
    raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: no credential containers or private-key blocks.')
