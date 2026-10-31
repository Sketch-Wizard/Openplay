package org.openplay.player

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.log10

/** Software volume boost above 100% (up to 200%) using the system loudness enhancer, for quiet videos. */
class Boost(private val player: ExoPlayer) {
    private var enhancer: LoudnessEnhancer? = null
    private var percent = 100
    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { rebuild(audioSessionId) }
    }

    init { player.addListener(listener); rebuild(player.audioSessionId) }

    fun set(pct: Int) { percent = pct.coerceIn(100, 200); apply() }

    private fun rebuild(id: Int) {
        try { enhancer?.release() } catch (e: Exception) { /* ignore */ }
        enhancer = if (id != C.AUDIO_SESSION_ID_UNSET) try { LoudnessEnhancer(id) } catch (e: Exception) { null } else null
        apply()
    }

    private fun apply() {
        val e = enhancer ?: return
        try {
            e.setTargetGain((2000 * log10(percent / 100.0)).toInt())   // 200% = +6 dB
            e.enabled = percent > 100
        } catch (x: Exception) { /* ignore */ }
    }

    fun release() {
        player.removeListener(listener)
        try { enhancer?.release() } catch (e: Exception) { /* ignore */ }
        enhancer = null
    }
}
