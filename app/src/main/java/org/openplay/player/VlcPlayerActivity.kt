package org.openplay.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Fallback player (libVLC) for AVI, WMV, RMVB, ASF and other formats ExoPlayer can't read. */
class VlcPlayerActivity : ComponentActivity() {
    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var sys: SystemControls
    private val ui = PlayerUiState()
    private var items: List<PlItem> = emptyList()
    private var index = 0
    private var pfd: ParcelFileDescriptor? = null
    private var resumed = false
    private var scaleIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private val scales = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    )
    private val scaleNames = arrayOf("Fit", "Fill", "Zoom")
    private var audioTracks: List<MediaPlayer.TrackDescription> = emptyList()
    private var spuTracks: List<MediaPlayer.TrackDescription> = emptyList()
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::addSubtitle) }

    private val ticker = object : Runnable {
        override fun run() {
            ui.playing = mp.isPlaying
            ui.position = mp.time
            ui.duration = mp.length.coerceAtLeast(0L)
            audioTracks = mp.audioTracks?.filter { it.id != -1 } ?: emptyList()
            spuTracks = mp.spuTracks?.filter { it.id != -1 } ?: emptyList()
            ui.audioNames = audioTracks.map { it.name }
            ui.audioSel = audioTracks.indexOfFirst { it.id == mp.audioTrack }
            ui.subNames = spuTracks.map { it.name }
            ui.subSel = if (mp.spuTrack == -1) -1 else spuTracks.indexOfFirst { it.id == mp.spuTrack }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (list, idx) = intent.readPlaylist()
        if (list.isEmpty()) { finish(); return }
        items = list; index = idx
        enterImmersive()
        sys = SystemControls(this)
        ui.speed = Prefs.float(this, "speed", 1f)
        ui.orientation = Prefs.int(this, "orient", 0)
        ui.bgPlay = Prefs.bool(this, "bg", false)
        scaleIndex = Prefs.int(this, "resize", 0).coerceIn(0, 2)
        ui.resizeName = scaleNames[scaleIndex]
        if (ui.orientation != 0) applyOrientation(ui.orientation, false)

        libVlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mp = MediaPlayer(libVlc)
        videoLayout = VLCVideoLayout(this)
        mp.attachViews(videoLayout, null, true, false)
        ui.playlist = items
        mp.videoScale = scales[scaleIndex]

        val actions = PlayerActions(
            onBack = { finish() },
            onTogglePlay = { if (mp.isPlaying) mp.pause() else mp.play() },
            onSeek = { mp.time = it },
            onSeekBy = { d -> mp.time = (mp.time + d).coerceIn(0L, mp.length.coerceAtLeast(0L)) },
            onSpeed = { mp.rate = it; ui.speed = it },
            onBgToggle = { ui.hud = "Background play isn't available for this format yet" },
            onRotate = {
                ui.rotationLocked = false
                ui.orientation = (ui.orientation + 1) % 3
                applyOrientation(ui.orientation, false)
                ui.hud = "Orientation: " + listOf("Auto", "Landscape", "Portrait")[ui.orientation]
            },
            onLockToggle = {
                ui.rotationLocked = !ui.rotationLocked
                applyOrientation(ui.orientation, ui.rotationLocked)
                ui.hud = if (ui.rotationLocked) "Rotation locked" else "Rotation unlocked"
            },
            onAudio = { audioTracks.getOrNull(it)?.let { t -> mp.audioTrack = t.id } },
            onSub = { i -> mp.spuTrack = if (i < 0) -1 else spuTracks.getOrNull(i)?.id ?: -1 },
            onLoadSub = { pickSubtitle.launch(arrayOf("*/*")) },
            onPlayItem = { openIndex(it) },
            onNext = { if (index + 1 < items.size) openIndex(index + 1) },
            onPrev = { if (index > 0) openIndex(index - 1) },
            onResize = {
                scaleIndex = (scaleIndex + 1) % scales.size
                mp.videoScale = scales[scaleIndex]
                ui.resizeName = scaleNames[scaleIndex]; ui.hud = ui.resizeName
            },
            onBrightness = { ui.hud = sys.brightnessBy(it) },
            onVolumeSteps = { ui.hud = sys.volumeBy(it) },
            onSaveDefaults = {
                Prefs.setFloat(this, "speed", ui.speed); Prefs.setInt(this, "orient", ui.orientation)
                Prefs.setBool(this, "bg", ui.bgPlay); Prefs.setInt(this, "resize", scaleIndex)
                ui.hud = "Saved as default"
            }
        )

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> if (!resumed) {
                    resumed = true
                    val saved = if (Prefs.bool(this, "resume", true)) prefs.getLong(items[index].uri.toString(), 0L) else 0L
                    if (saved > 0) mp.time = saved
                }
                MediaPlayer.Event.EndReached -> runOnUiThread { if (index + 1 < items.size) openIndex(index + 1) }
                MediaPlayer.Event.EncounteredError -> ui.hud = "Cannot play this file"
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())
                    PlayerUi(ui, actions)
                }
            }
        }
        openIndex(index)
        mp.rate = ui.speed
        handler.post(ticker)
    }

    private fun openIndex(i: Int) {
        saveResume()
        index = i; resumed = false
        val uri = items[i].uri
        pfd?.close(); pfd = null
        val media = if (uri.scheme == "content") {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            Media(libVlc, pfd!!.fileDescriptor)
        } else Media(libVlc, uri)
        media.setHWDecoderEnabled(true, false)
        mp.media = media
        media.release()
        mp.play()
        ui.index = i
        ui.title = titleOf(items[i])
    }

    private fun addSubtitle(sub: Uri) {
        val name = contentResolver.query(sub, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "sub.srt"
        val f = File(cacheDir, name)
        contentResolver.openInputStream(sub)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
    }

    private fun saveResume() {
        if (!::mp.isInitialized) return
        items.getOrNull(index)?.let { prefs.edit().putLong(it.uri.toString(), mp.time).apply() }
    }

    override fun onUserLeaveHint() {
        if (mp.isPlaying) enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        if (!isInPictureInPictureMode) mp.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (::mp.isInitialized) { mp.stop(); mp.detachViews(); mp.release(); libVlc.release() }
        pfd?.close()
        super.onDestroy()
    }
}
