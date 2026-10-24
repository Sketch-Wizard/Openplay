package org.openplay.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory

/** HW = phone decoders only, HW+ = phone first then FFmpeg, SW = FFmpeg first. */
object Decoders {
    private val classNames = listOf(
        "io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory",
        "io.github.anilbeesetti.nextlib.media3ext.NextRenderersFactory"
    )

    private fun next(ctx: Context): DefaultRenderersFactory? {
        for (n in classNames) {
            try {
                return Class.forName(n).getConstructor(Context::class.java).newInstance(ctx) as DefaultRenderersFactory
            } catch (e: Throwable) { /* try next name */ }
        }
        return null
    }

    fun available(ctx: Context) = next(ctx) != null

    fun factory(ctx: Context): DefaultRenderersFactory {
        val mode = Prefs.int(ctx, "decoder", 1)
        val plain = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        if (mode == 0) return plain
        val f = next(ctx) ?: return plain
        f.setExtensionRendererMode(
            if (mode == 2) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        f.setEnableDecoderFallback(true)
        return f
    }
}
