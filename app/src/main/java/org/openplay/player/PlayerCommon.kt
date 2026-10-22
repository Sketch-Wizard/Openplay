package org.openplay.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

data class PlItem(val uri: Uri, val title: String)

/** Opens the right engine (ExoPlayer or libVLC) with a playlist. */
fun Context.openPlayer(items: List<PlItem>, index: Int, forceVlc: Boolean = false) {
    if (items.isEmpty()) return
    val item = items[index]
    val cls = if (forceVlc || Formats.needsVlc(this, item.uri)) VlcPlayerActivity::class.java
    else PlayerActivity::class.java
    startActivity(
        Intent(this, cls).setData(item.uri)
            .putStringArrayListExtra("pl_uris", ArrayList(items.map { it.uri.toString() }))
            .putStringArrayListExtra("pl_titles", ArrayList(items.map { it.title }))
            .putExtra("pl_index", index)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}

fun Context.play(uri: Uri) = openPlayer(listOf(PlItem(uri, "")), 0)

fun Intent.readPlaylist(): Pair<List<PlItem>, Int> {
    val uris = getStringArrayListExtra("pl_uris")
    val titles = getStringArrayListExtra("pl_titles")
    if (uris == null || titles == null || uris.isEmpty()) {
        val d = data ?: return emptyList<PlItem>() to 0
        return listOf(PlItem(d, "")) to 0
    }
    val list = uris.mapIndexed { i, u -> PlItem(Uri.parse(u), titles.getOrElse(i) { "" }) }
    return list to getIntExtra("pl_index", 0).coerceIn(0, list.size - 1)
}

fun Context.titleOf(item: PlItem) = item.title.ifBlank { Formats.nameOf(this, item.uri) }

fun Activity.enterImmersive() {
    WindowCompat.getInsetsController(window, window.decorView).let {
        it.hide(WindowInsetsCompat.Type.systemBars())
        it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/** mode: 0 = auto, 1 = landscape, 2 = portrait. locked freezes the current orientation. */
fun Activity.applyOrientation(mode: Int, locked: Boolean) {
    requestedOrientation = when {
        locked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        mode == 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        mode == 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
}

class SystemControls(private val a: Activity) {
    private val audio = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var brightness: Float = try {
        Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) { 0.5f }

    fun brightnessBy(d: Float): String {
        brightness = (brightness + d).coerceIn(0.02f, 1f)
        a.window.attributes = a.window.attributes.also { it.screenBrightness = brightness }
        return "Brightness ${(brightness * 100).toInt()}%"
    }

    fun volumeBy(n: Int): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + n).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        return "Volume ${v * 100 / max}%"
    }
}
