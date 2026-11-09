package com.reactivenotch.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.reactivenotch.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Track(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** positionMs 가 측정된 SystemClock.elapsedRealtime() 시각 */
    val updatedAt: Long,
    val speed: Float,
) {
    fun positionNow(): Long {
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - updatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

class MediaRepository(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    private var registered = false
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 권한이 나중에 허용될 수 있으므로 여러 번 호출해도 안전하다. */
    fun ensureStarted() {
        if (!hasAccess()) {
            _track.value = null
            return
        }
        try {
            if (!registered) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                registered = true
            }
            bind(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            DebugLog.add("media", "미디어 세션 접근 거부: ${e.message}")
            _track.value = null
        }
    }

    fun stop() {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        current = null
        if (registered) {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            registered = false
        }
        _track.value = null
    }

    private fun refreshSessions() {
        try {
            bind(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            _track.value = null
        }
    }

    private fun bind(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    private fun isActive(c: MediaController): Boolean {
        val s = c.playbackState?.state
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    private fun publish() {
        val chosen = controllers.firstOrNull { isActive(it) }
            ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        current = chosen
        _track.value = chosen?.let { toTrack(it) }
    }

    private fun toTrack(c: MediaController): Track? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val ps = c.playbackState
        return Track(
            packageName = c.packageName,
            title = title,
            artist = artist,
            art = art?.downscaled(256),
            playing = isActive(c),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0,
            updatedAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    private fun Bitmap.downscaled(max: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= max) return this
        val f = max.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1), true)
    }

    fun playPause() {
        val c = current ?: return
        if (isActive(c)) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = current?.transportControls?.skipToNext()
    fun previous() = current?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = current?.transportControls?.seekTo(ms)
}
