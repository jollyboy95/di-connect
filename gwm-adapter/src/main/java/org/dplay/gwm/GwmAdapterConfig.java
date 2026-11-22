/*
 * DiPlay GWM Adapter — GWM/Haval integration layer for DiPlay.
 * Copyright (C) 2026  jollyboy95
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See the LICENSE file.
 */
package org.dplay.gwm;

/**
 * Per-vehicle configuration. All display IDs here are UNVERIFIED hypotheses
 * (see docs/H7_DISPLAY_ARCHITECTURE.md). The on-car task is to replace them
 * with values logged from {@link GwmDisplayAdapter#dump()}.
 */
public final class GwmAdapterConfig {

    /** A candidate cluster display, described by role so it can be matched by size. */
    public static final class DisplayHint {
        public final int preferredLogicalId;  // best guess; -1 = match by size only
        public final int expectWidth;
        public final int expectHeight;

        public DisplayHint(int preferredLogicalId, int expectWidth, int expectHeight) {
            this.preferredLogicalId = preferredLogicalId;
            this.expectWidth = expectWidth;
            this.expectHeight = expectHeight;
        }
    }

    public final String vehicleName;
    /** Ordered cluster candidates, best first. */
    public final DisplayHint[] clusterCandidates;
    /** Steering adapter property name to poll (read-only). */
    public final String steeringNotifyProperty;
    /** Debounce window for duplicate raw steering codes. */
    public final long steeringDebounceMs;

    public GwmAdapterConfig(String vehicleName,
                            DisplayHint[] clusterCandidates,
                            String steeringNotifyProperty,
                            long steeringDebounceMs) {
        this.vehicleName = vehicleName;
        this.clusterCandidates = clusterCandidates;
        this.steeringNotifyProperty = steeringNotifyProperty;
        this.steeringDebounceMs = steeringDebounceMs;
    }

        /** The observed H7 cluster target; the adapter still checks that it is currently present. */
        public static final int H7_CLUSTER_DISPLAY_ID = 2;

    public static final GwmAdapterConfig H7_DEFAULTS = new GwmAdapterConfig(
            "Haval H7 Vanta (B11, AU)",
            new DisplayHint[] {
                new DisplayHint(H7_CLUSTER_DISPLAY_ID, 0, 0),
            },
            "sys.other.keyevent_notify",
            120L);

    private GwmAdapterConfig() {
        throw new AssertionError("no instances");
    }
}
