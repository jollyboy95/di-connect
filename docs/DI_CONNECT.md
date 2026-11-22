# Di-Connect integration preview

D-Play GWM is the CarPlay and UI foundation. Android Auto is imported as an Android library in the same APK, not an external app dependency.

- D-Play source: jollyboy95/d-play-gwm-h7 at 3df998919f075bc6e21efb5e8a314347813647a1.
- Android Auto source: jollyboy95/DiAuto-GWM at 4febfe1568faab5bf6899575b31e1c708298759f (gwm-h7-hud-fit, the r16 DiAuto GWM build), including its GWM/Haval H7 port. It uses D-Play's gwm-adapter module rather than its own copy.
- Separate application ID: com.jollyboy95.diconnect (debug suffix .debug).
- Open Android Auto from the existing D-Play home card. CarPlay is stopped before AA opens. Starting CarPlay waits for AA service teardown.
- AA retains its connection and settings screens in this first integration; these have not yet been redesigned to match every D-Play screen.
- Imported AA boot, Bluetooth, Wi-Fi, automation and USB-attach entry points are disabled initially. Connect Android Auto manually through its screen.
- D-Play settings/pairing are not migrated from the separately installed app.
- Resources are prefixed aa_ to prevent collision with CarPlay resources.
- Both source licenses and original notices are retained. Combined distribution must meet the applicable GPL/AGPL obligations.

## Validation

Physical tests are required for wired/wireless phones, calls, microphones, wheel controls, and cluster/HUD outputs. Source import is not evidence of vehicle compatibility.

## CarPlay runtime assets

The upstream source omits runtime identity files. The existing import-from-APK flow is retained. CI can provision the two files from the upstream published DiPlay release into an external build directory; they are not committed as source files.

## Installing updates

CI uses the committed GWM debug signing key, the stable com.jollyboy95.diconnect.debug package, and an increasing version code derived from the build timestamp. Install a newer CI APK over the existing Di-Connect installation to retain settings. The first install is separate from D-Play and DiAuto. Do not replace the debug key or change the package ID between updates. In-app update checking points to Di-Connect; private repository downloads still require GitHub access.

## Switching between CarPlay and Android Auto

- Starting CarPlay waits for the Android Auto service to finish stopping, for at most 10 seconds. After that CarPlay starts anyway and the log notes the timeout.
- While Android Auto stops for CarPlay, it leaves its auto-enabled hotspot on so wireless CarPlay can use it.
- Android Auto's "kill on disconnect" setting closes only the Android Auto screens and service. It no longer closes CarPlay, the home screen or the cluster window.
