package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Lets Home read the car's own media sessions (radio, USB, Bluetooth) for Now Playing.
 *
 * Android only hands active media sessions to an app whose notification listener the driver has
 * allowed. This service reads no notifications; it exists for that permission. Until it is
 * allowed (Android Settings > Apps > Special access > Notification access, or over ADB
 * `cmd notification allow_listener <package>/com.shilapi.xcertplay.HomeMediaListener`), Home shows
 * only the projection's music, as before.
 */
class HomeMediaListener : NotificationListenerService()

internal object HomeMediaSessions {
    /**
     * The H7's media source services, which each publish a session (21 Nov 2026 survey), and the
     * name Home shows for each. Other apps' sessions (Yandex Music, Spotify…) use their app label.
     */
    val FACTORY_SOURCES = mapOf(
        "com.gwm.app.media.server.radio" to "Radio",
        "com.gwm.app.media.server.usbmusic" to "USB",
        "com.gwm.app.media.server.usbvideo" to "USB video",
        "com.gwm.app.media.union" to "Media",
    )

    /**
     * Never shown: the car's Bluetooth music is kept off on purpose (it made the projections' audio
     * stutter), so a stale Bluetooth session must not appear as a source.
     */
    val IGNORED = setOf("com.gwm.app.media.server.bluetooth", "com.android.bluetooth")

    data class NowPlaying(val packageName: String, val source: String, val title: String?, val artist: String?,
        val playing: Boolean, val progress: Float?, val art: Bitmap?)

    fun allowed(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val mine = ComponentName(context, HomeMediaListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    /** The playing session, else the most recent one with a title; null when not allowed or none. */
    fun current(context: Context, now: Long = android.os.SystemClock.elapsedRealtime()): NowPlaying? {
        if (!allowed(context)) return null
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val sessions = runCatching { msm.getActiveSessions(ComponentName(context, HomeMediaListener::class.java)) }
            .getOrNull().orEmpty().filter { it.packageName != context.packageName && it.packageName !in IGNORED }
        val pick = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            ?: return null
        return describe(context, pick, now)
    }

    private fun describe(context: Context, c: MediaController, now: Long): NowPlaying {
        val md = c.metadata
        val state = c.playbackState
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val position = state?.let { s ->
            val elapsed = if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0)
                ((now - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0L
            (s.position + elapsed).takeIf { s.position >= 0 }
        }
        val label = FACTORY_SOURCES[c.packageName] ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        return NowPlaying(
            packageName = c.packageName,
            source = label,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            progress = if (duration != null && position != null) (position.toFloat() / duration).coerceIn(0f, 1f) else null,
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
        )
    }

    /** Sends a transport command to the session Home shows, instead of a broadcast media key. */
    fun control(context: Context, packageName: String, action: (MediaController.TransportControls) -> Unit): Boolean {
        if (!allowed(context)) return false
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
        val c = runCatching { msm.getActiveSessions(ComponentName(context, HomeMediaListener::class.java)) }
            .getOrNull()?.firstOrNull { it.packageName == packageName } ?: return false
        return runCatching { action(c.transportControls) }.isSuccess
    }
}
