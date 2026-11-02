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

import android.os.Looper;
import android.os.Handler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * READ-ONLY observer for GWM steering-wheel key activity.
 *
 * <p>GWM/Harman does not reliably deliver steering buttons as ordinary Android
 * {@code KEYCODE_*} events to third-party apps. The known interoperability lead is a
 * GWM adapter notify string of the form {@code sys.other.keyevent_notify=[a, b]}
 * carrying two numeric values whose meaning/ordering are UNVERIFIED.
 *
 * <p>This class polls that property and reports what it parses, so real button→code
 * correlations can be logged on the car (parked) before any action mapping is
 * designed. It:
 * <ul>
 *   <li>does NOT synthesise, inject, or re-broadcast key events;</li>
 *   <li>does NOT hook any protected system/Binder interface;</li>
 *   <li>does NOT map or trigger vehicle actions.</li>
 * </ul>
 * Action mapping, if ever added, belongs in the host app's configuration, not here.
 */
public final class GwmSteeringKeyObserver {

    /** One observed, unverified raw steering event. */
    public static final class Observation {
        public final int rawA;
        public final int rawB;
        public final long timestampMs;

        Observation(int rawA, int rawB, long timestampMs) {
            this.rawA = rawA;
            this.rawB = rawB;
            this.timestampMs = timestampMs;
        }

        @Override public String toString() {
            // Ordering of the two values is UNVERIFIED — do not label them yet.
            return "SteeringObservation{raw=[" + rawA + ", " + rawB + "] t=" + timestampMs + "}";
        }
    }

    public interface Listener {
        void onObservation(Observation observation);
    }

    private static final Pattern NOTIFY =
            Pattern.compile("\\[\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*]");
    private static final long POLL_INTERVAL_MS = 50L;
        private static final ScheduledExecutorService POLL_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gwm-steering-observer");
            thread.setDaemon(true);
            return thread;
            });

    private final Listener listener;
    private final String property;
    private final long debounceMs;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String lastRaw = null;
    private long lastAtMs = 0L;
        private volatile boolean running = false;
        private ScheduledFuture<?> polling;

    public GwmSteeringKeyObserver(Listener listener) {
        this(listener, GwmAdapterConfig.H7_DEFAULTS.steeringNotifyProperty,
                GwmAdapterConfig.H7_DEFAULTS.steeringDebounceMs);
    }

    public GwmSteeringKeyObserver(Listener listener, String property, long debounceMs) {
        this.listener = listener;
        this.property = property;
        this.debounceMs = debounceMs;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        polling = POLL_EXECUTOR.scheduleWithFixedDelay(
                this::poll, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (polling != null) {
            polling.cancel(false);
            polling = null;
        }
    }

    private void poll() {
        if (!running) return;
        try {
            String value = SystemPropertyReader.get(property);
            if (value != null && !value.isEmpty()) {
                handleValue(value);
            }
        } catch (RuntimeException ignored) {
            // A vendor property implementation can fail independently of the app.
        }
    }

    private void handleValue(String value) {
        Matcher m = NOTIFY.matcher(value);
        if (!m.find()) return;

        String raw = m.group(1) + "," + m.group(2);
        long now = System.currentTimeMillis();

        // Drop repeats within the debounce window (the adapter may re-emit).
        if (raw.equals(lastRaw) && (now - lastAtMs) < debounceMs) {
            return;
        }
        lastRaw = raw;
        lastAtMs = now;

        try {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (listener != null) {
                Observation observation = new Observation(a, b, now);
                mainHandler.post(() -> {
                    if (running) listener.onObservation(observation);
                });
            }
        } catch (NumberFormatException ignored) {
            // Non-numeric payload; ignore.
        }
    }
}
