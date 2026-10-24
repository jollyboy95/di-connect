package com.andrerinas.openheadunit.gwm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.protoUint32ToLong
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Now-playing for the GWM cluster music card, taken from Android Auto's own media playback
 * channel (MediaMetaData and MediaPlaybackStatus), not from Android media sessions. It is fed
 * whatever the "sync media session" setting says, so the card does not depend on it.
 *
 * Artwork is the album_art bytes the phone sends, decoded once per track and scaled down.
 */
internal object GwmMusicState {
    data class Snapshot(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artwork: Bitmap? = null,
        val durationMillis: Long? = null,
        val positionMillis: Long? = null,
        val playing: Boolean = false,
        /** elapsedRealtime of the last position report, for advancing the bar between reports. */
        val positionAt: Long = 0L,
    ) {
        val hasTrack: Boolean get() = !title.isNullOrBlank()

        /** Position now, advanced from the last report while playing, never beyond the duration. */
        fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long? {
            val base = positionMillis ?: return null
            val moved = if (playing && positionAt > 0) (now - positionAt).coerceAtLeast(0) else 0L
            val value = base + moved
            return durationMillis?.let { value.coerceAtMost(it) } ?: value
        }
    }

    @Volatile var current = Snapshot()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "diauto-gwm-artwork").apply { isDaemon = true } }
    private val observers = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    @Volatile private var generation = 0

    /** Observers run on the main thread. Returns a function that stops observing. */
    fun observe(observer: (Snapshot) -> Unit): () -> Unit {
        observers += observer
        main.post { observer(current) }
        return { observers -= observer }
    }

    fun onMetadata(meta: MediaPlayback.MediaMetaData) {
        val track = ++generation
        val title = meta.song.takeIf { meta.hasSong() && it.isNotBlank() }
        val sameTrack = title == current.title && meta.artist == current.artist
        publish(current.copy(
            title = title,
            artist = meta.artist.takeIf { meta.hasArtist() && it.isNotBlank() },
            album = meta.album.takeIf { meta.hasAlbum() && it.isNotBlank() },
            durationMillis = meta.durationSeconds.protoUint32ToLong().takeIf { meta.hasDurationSeconds() && it > 0 }?.times(1000L),
            // A new track keeps no artwork until its own has decoded.
            artwork = if (sameTrack) current.artwork else null,
            positionMillis = if (sameTrack) current.positionMillis else null,
        ))
        if (!meta.hasAlbumArt() || meta.albumArt.isEmpty) return
        val bytes = meta.albumArt.toByteArray()
        decoder.execute {
            val bitmap = runCatching { decodeArtwork(bytes) }.getOrNull() ?: return@execute
            main.post { if (track == generation) publish(current.copy(artwork = bitmap)) }
        }
    }

    fun onStatus(status: MediaPlayback.MediaPlaybackStatus) {
        val playing = if (status.hasState()) status.state == MediaPlayback.MediaPlaybackStatus.State.PLAYING else current.playing
        val position = if (status.hasPlaybackSeconds()) status.playbackSeconds.protoUint32ToLong() * 1000L else current.positionMillis
        publish(current.copy(playing = playing, positionMillis = position, positionAt = SystemClock.elapsedRealtime()))
    }

    /** The phone disconnected: nothing it reported still applies. */
    fun clear() {
        generation++
        publish(Snapshot())
    }

    private fun publish(next: Snapshot) {
        current = next
        if (Looper.myLooper() == Looper.getMainLooper()) observers.forEach { it(next) }
        else main.post { observers.forEach { it(current) } }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ARTWORK_PX && bounds.outHeight / (sample * 2) >= ARTWORK_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The music card shows artwork at roughly 100 px on a 720 px tall cluster. */
    private const val ARTWORK_PX = 192
}
