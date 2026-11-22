package com.andrerinas.openheadunit.connection.carkey

import android.content.Context
import com.andrerinas.openheadunit.gwm.GwmMode
import com.andrerinas.openheadunit.gwm.GwmSteeringKeys

/**
 * GWM/Haval steering-wheel keys from the car's gwm_adapter (see [GwmSteeringKeys]).
 *
 * Each key arrives with the firmware's own code (1000–1007, 2000–2007, 4000–4004). Like other
 * proprietary head-unit codes, DiAuto sends one to Android Auto only after it is assigned to an
 * action in Settings → Keymap; the learning screen sees these keys through the usual
 * broadcast. Does nothing on head units that are not GWM.
 */
class GwmAdapterKeyReceiver : CarKeyReceiver {
    private var context: Context? = null

    private val listener: (GwmSteeringKeys.Event) -> Unit = { event ->
        context?.let { ctx ->
            when {
                event.pressed -> handleKey(ctx, event.code, true)
                event.released -> handleKey(ctx, event.code, false)
                // A long press keeps the key held; the release follows as long-press-up.
            }
        }
    }

    override val isSupported = true
    override val isSUNeeded = false

    override fun register(context: Context) {
        if (!GwmMode.active(context)) return
        this.context = context.applicationContext
        GwmSteeringKeys.addListener(context, listener)
    }

    override fun unregister() {
        if (context == null) return
        GwmSteeringKeys.removeListener(listener)
        context = null
    }
}
