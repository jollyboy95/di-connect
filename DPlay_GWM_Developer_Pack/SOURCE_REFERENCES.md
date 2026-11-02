# Source code references

Inspected GitHub repository: https://github.com/jollyboy95/Cluster-test-new

- Secondary-display overlay code: https://github.com/jollyboy95/Cluster-test-new/blob/8a6045b4f838c406eebb9b28da590bf90f846131/app/src/main/java/com/clusterprobe/OverlayManager.java
- Activity launch via `ActivityOptions.setLaunchDisplayId`: https://github.com/jollyboy95/Cluster-test-new/blob/8a6045b4f838c406eebb9b28da590bf90f846131/app/src/main/java/com/clusterprobe/MainActivity.java
- Fullscreen cluster host with transparent background and cleanup: https://github.com/jollyboy95/Cluster-test-new/blob/8a6045b4f838c406eebb9b28da590bf90f846131/app/src/main/java/com/clusterprobe/ClusterActivity.java
- Multi-display notes: https://github.com/jollyboy95/Cluster-test-new/blob/8a6045b4f838c406eebb9b28da590bf90f846131/docs/H7_DISPLAY_ARCHITECTURE.md

**Provenance:** Code examples are trimmed/adapted from the above source except the steering-wheel probe, which is a newly written diagnostic. The repository's source revision may have changed since this inspection. Verify integration on each head unit.

## SmartSplit provenance

The steering-wheel button customization is reported by the vehicle owner as an existing **SmartSplit** feature. This pack has **not** inspected or extracted SmartSplit steering-wheel handling code. The included `SteeringWheelInputProbe.java` is independent example code and should not be cited as proof of SmartSplit implementation or GWM key codes.
