package org.openplay.player

import android.content.SharedPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Owns the player so playback can continue in the background with a notification. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var boost: Boost? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "decoder") swapPlayer()
        if (key == "boost") boost?.set(Prefs.int(this, "boost", 100))
    }

    private fun build(): ExoPlayer {
        val p = ExoPlayer.Builder(this).setRenderersFactory(Decoders.factory(this))
            .setSeekParameters(SeekParameters.CLOSEST_SYNC).build()
        boost?.release()
        boost = Boost(p).also { it.set(Prefs.int(this, "boost", 100)) }
        return p
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, build()).build()
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun swapPlayer() {
        val s = session ?: return
        val old = s.player
        s.player = build()
        old.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        boost?.release(); boost = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
