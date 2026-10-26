package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.view.View
import com.andrerinas.openheadunit.DiConnectViews
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Di-Connect: Android Auto's GWM cluster layer and HUD are D-Play's own views, fed by D-Play's car
 * values and Android Auto's turn guidance and now playing, so they look and behave as in CarPlay.
 */
internal object DiConnectPlayViews {
    fun install(app: Context) {
        DiConnectViews.cluster = { displayContext -> ClusterLayer(displayContext, app) }
        DiConnectViews.hud = { displayContext, anchor -> HudLayer(displayContext, anchor) }
        DiConnectViews.hudTestLabel = { it.getString(com.shilapi.xcertplay.host.R.string.settings_gwm_hud_test) }
    }

    fun guidance(next: DiConnectViews.Guidance?): ClusterTurnGuidance? = next?.takeIf { it.icon != 0 }?.let {
        ClusterTurnGuidance(
            icon = it.icon,
            roundaboutExit = it.roundaboutExit,
            distanceMeters = it.distanceMeters,
            road = it.road,
            arrivalEpochSeconds = it.remainingSeconds.takeIf { s -> s >= 0 }
                ?.let { s -> System.currentTimeMillis() / 1000 + s },
            remainingSeconds = it.remainingSeconds.takeIf { s -> s >= 0 }?.toLong(),
            remainingMeters = it.remainingMeters.takeIf { m -> m >= 0 }?.toLong(),
        )
    }

    private class ClusterLayer(displayContext: Context, private val app: Context) : DiConnectViews.Layer {
        private val hud = ClusterThemeFrame(displayContext, ClusterConnection.ANDROID_AUTO)
        private val vehicle: (GwmVehicleData.Snapshot?) -> Unit = { snapshot -> hud.post { hud.setSnapshot(snapshot) } }
        private val stopMusic = DiConnectViews.observeMusic { music ->
            ClusterMusicState.publish(CarPlayNowPlaying(
                title = music.title, album = music.album, artist = music.artist,
                durationMillis = music.durationMillis, elapsedMillis = music.positionMillis, playing = music.playing,
            ), music.artwork)
        }
        override val view: View get() = hud

        init { GwmVehicleData.addListener(app, vehicle) }

        override fun setGuidance(guidance: DiConnectViews.Guidance?) = hud.setGuidance(guidance(guidance))

        override fun release() {
            GwmVehicleData.removeListener(vehicle)
            stopMusic()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
        }
    }

    private class HudLayer(displayContext: Context, anchor: Int) : DiConnectViews.Layer {
        private val hud = GwmAndroidAutoHeadUpView(displayContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setAnchor(GwmAndroidAutoHeadUpView.Anchor.entries.getOrElse(anchor) { GwmAndroidAutoHeadUpView.Anchor.LEFT })
        }
        override val view: View get() = hud
        override fun setGuidance(guidance: DiConnectViews.Guidance?) {
            hud.setArrivalText(guidance?.arrivalText)
            hud.setRoundaboutImage(guidance?.roundaboutImage)
            hud.setGuidance(guidance(guidance))
        }
        override fun release() = Unit
    }
}

/** Registers [DiConnectPlayViews] when the app process starts, before any Android Auto session. */
internal class DiConnectViewsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(DiConnectPlayViews::install)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
