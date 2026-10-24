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

import android.annotation.SuppressLint;

import java.lang.reflect.Method;

/**
 * Minimal read-only accessor for an Android system property.
 *
 * <p>Used only to READ the GWM steering notify property. It does not set, create, or
 * broadcast anything. If the property is unreadable (permission/variant dependent —
 * {@code gwm_adapter unavailable or permission denied}), this returns {@code null}
 * and the observer simply reports nothing.
 */
final class SystemPropertyReader {

    private static final Method GET;

    static {
        Method get = null;
        try {
            @SuppressLint("PrivateApi")
            Class<?> sp = Class.forName("android.os.SystemProperties");
            get = sp.getMethod("get", String.class);
        } catch (Throwable ignored) {
            // Not available; callers get null.
        }
        GET = get;
    }

    static String get(String key) {
        if (GET == null) return null;
        try {
            Object v = GET.invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SystemPropertyReader() {}
}
