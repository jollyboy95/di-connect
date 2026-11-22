package com.shilapi.xcertplay

import android.content.Context
import android.view.Display
import org.dplay.gwm.GwmAdapterConfig
import org.dplay.gwm.GwmDisplayAdapter

/**
 * Whether the GWM/Haval cluster route applies. Detection reads the build identity; the driver can
 * turn it on by hand when a firmware build does not name GWM, Haval or B11.
 */
internal object GwmMode {
    fun detected(): Boolean = GwmDisplayAdapter.isGwmTargetDevice()

    fun active(context: Context): Boolean = detected() || AirPlayPersistence.loadGwmForced(context)

    fun defaultClusterDisplayId(context: Context): Int =
        if (active(context)) GwmAdapterConfig.H7_CLUSTER_DISPLAY_ID else Display.INVALID_DISPLAY

    fun selectedClusterDisplayId(context: Context): Int =
        AirPlayPersistence.loadGwmClusterDisplayId(context) ?: defaultClusterDisplayId(context)
}
