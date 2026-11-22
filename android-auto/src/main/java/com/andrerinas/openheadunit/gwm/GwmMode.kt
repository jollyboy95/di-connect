package com.andrerinas.openheadunit.gwm

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.App
import org.dplay.gwm.GwmDisplayAdapter

/**
 * Whether the GWM/Haval path applies. Detection reads the build identity (ported from DiPlay's
 * GwmMode); the driver can turn it on by hand when a firmware build does not name GWM, Haval or B11.
 * Everything GWM-specific checks this first, so a BYD head unit never takes the GWM path.
 */
internal object GwmMode {
    fun detected(): Boolean = GwmDisplayAdapter.isGwmTargetDevice()

    fun active(context: Context): Boolean = detected() || GwmSettings(context).forced

    /** Overlay windows on another display need Android 8.0; the H7 runs Android 9. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** The Android Auto cluster map goes to the GWM cluster display this session. */
    fun clusterRoute(context: Context): Boolean =
        supported && active(context) && GwmSettings(context).clusterEnabled &&
            App.provide(context).settings.clusterMapEnabled

    /** Any GWM output that draws turn guidance from Android Auto's navigation metadata. */
    fun wantsGuidance(context: Context): Boolean {
        if (!supported || !active(context)) return false
        val gwm = GwmSettings(context)
        return (clusterRoute(context) && gwm.styledLayer && gwm.navCard) || gwm.hudEnabled
    }
}
