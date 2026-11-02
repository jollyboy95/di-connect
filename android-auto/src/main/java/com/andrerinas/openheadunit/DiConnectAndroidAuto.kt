package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.WirelessScopePolicy
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.Settings

/**
 * Connects Android Auto from Di-Connect's home screen, as DiAuto's own home buttons do: an Android
 * phone on USB connects by cable (DiAuto's USB check, which also asks for USB access and switches
 * the phone to accessory mode); otherwise native wireless Android Auto to the chosen paired phone.
 * Android Auto opens its projection screen by itself once the phone has connected.
 */
object DiConnectAndroidAuto {
    enum class Route { OPENED, USB, WIRELESS, NEEDS_PHONE }

    private const val PHONE_ADDRESS = "di_connect_aa_phone_address"
    private const val PHONE_NAME = "di_connect_aa_phone_name"
    private const val OPENS_WITH_CARPLAY = "di_connect_opens_with_carplay"

    fun phoneAddress(context: Context): String? = prefs(context).getString(PHONE_ADDRESS, null)
    fun phoneName(context: Context): String? = prefs(context).getString(PHONE_NAME, null)
    fun savePhone(context: Context, address: String, name: String) =
        prefs(context).edit().putString(PHONE_ADDRESS, address).putString(PHONE_NAME, name).apply()

    /** "Connect when Di-Connect opens" starts Android Auto unless CarPlay is chosen. */
    fun opensWithCarPlay(context: Context): Boolean = prefs(context).getBoolean(OPENS_WITH_CARPLAY, false)
    fun saveOpensWithCarPlay(context: Context, carPlay: Boolean) =
        prefs(context).edit().putBoolean(OPENS_WITH_CARPLAY, carPlay).apply()

    /** Without starting Android Auto: only a running Android Auto service can be connected. */
    fun isConnected(context: Context): Boolean =
        DiConnectEngine.service != null && App.provide(context).commManager.isConnected

    fun connect(activity: Activity): Route {
        App.get(activity).initializeAndroidAuto()
        val app = App.provide(activity)
        if (app.commManager.isConnected) {
            activity.startActivity(AapProjectionActivity.intent(activity).putExtra(AapProjectionActivity.EXTRA_FOCUS, true))
            return Route.OPENED
        }
        val usb = activity.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb?.deviceList?.values?.any { UsbDeviceCompat.isAndroidDevice(it) } == true) {
            start(activity, AapService.ACTION_CHECK_USB)
            return Route.USB
        }
        val address = phoneAddress(activity) ?: return Route.NEEDS_PHONE
        // As DiAuto's Wireless button: native wireless Android Auto, with Wi-Fi among the connection
        // types so the USB-only gate does not keep the route down.
        app.settings.wifiConnectionMode = 3
        if (!WirelessScopePolicy.allowsWireless(app.settings.connectionModes)) {
            app.settings.connectionModes = app.settings.connectionModes + Settings.ConnectionMode.WIFI
        }
        start(activity, AapService.ACTION_NATIVE_AA_POKE) { putExtra(AapService.EXTRA_MAC, address) }
        return Route.WIRELESS
    }

    /** DiAuto's full settings, for anything Di-Connect's own settings do not cover. */
    fun openAdvancedSettings(activity: Activity) {
        App.get(activity).initializeAndroidAuto()
        activity.startActivity(Intent(activity, com.andrerinas.openheadunit.main.SettingsActivity::class.java))
    }

    private fun start(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply {
            this.action = action
            extras()
        })
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
}
