package com.andrerinas.openheadunit

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import com.andrerinas.openheadunit.gwm.GwmMusicState
import com.andrerinas.openheadunit.hud.BydGuidance

/** Lets Di-Connect's D-Play side draw Android Auto's GWM cluster layer and HUD with its own views. */
object DiConnectViews {
    /** Next turn in the AMap broadcast codes D-Play's views draw (DiAuto's cluster codes). */
    data class Guidance(
        val icon: Int,
        val roundaboutExit: Int,
        val distanceMeters: Int,
        val road: String,
        val remainingMeters: Int,
        val remainingSeconds: Int,
        val arrivalText: String? = null,
        val roundaboutImage: ByteArray? = null,
    )

    data class Music(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?,
        val durationMillis: Long?,
        val positionMillis: Long?,
        val playing: Boolean,
    )

    interface Layer {
        val view: View
        fun setGuidance(guidance: Guidance?)
        fun release()
    }

    /** Where on the cluster panel the map goes, as fractions of its width (0-1 is the whole panel). */
    data class MapWindow(val left: Float, val right: Float) {
        val width: Float get() = right - left
        val full: Boolean get() = left <= 0f && right >= 1f
        companion object { val FULL = MapWindow(0f, 1f) }
    }

    /** The map window the chosen cluster theme wants; read when an Android Auto session starts. */
    @Volatile var clusterMapWindow: ((Context) -> MapWindow)? = null

    /** Set by Di-Connect's D-Play side at start-up; while null, DiAuto's own views are used. */
    @Volatile var cluster: ((Context) -> Layer)? = null
    /** [anchor]: 0 left, 1 centre, 2 right, as both apps store it. */
    @Volatile var hud: ((Context, Int) -> Layer)? = null
    /** Reuse the already-translated parked HUD test label from the CarPlay host. */
    @Volatile var hudTestLabel: ((Context) -> String)? = null

    fun observeMusic(observer: (Music) -> Unit): () -> Unit = GwmMusicState.observe { s ->
        observer(Music(s.title, s.artist, s.album, s.artwork, s.durationMillis, s.positionNow(), s.playing))
    }

    internal fun from(guidance: BydGuidance?): Guidance? = guidance?.let {
        Guidance(it.clusterIcon, it.roundaboutExit, it.distanceMeters, it.road, it.remainingMeters, it.remainingSeconds, it.arrivalText, it.roundaboutImage?.toByteArray())
    }
}
