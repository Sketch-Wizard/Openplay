package org.openplay.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * Background play OFF: the video plays in a plain in-app player, so no notification is ever shown.
 * Background play ON: playback runs in PlaybackService (media notification, keeps playing when you leave).
 */
class PlayerActivity : ComponentActivity() {
    private val ui = PlayerUiState()
    private var controller by mutableStateOf<Player?>(null)
    private var localPlayer: ExoPlayer? = null
    private var mediaController: MediaController? = null
    private var engineGen = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private var items: List<PlItem> = emptyList()
    private var startIndex = 0
    private var source = "all"
    private var audioGroups: List<Tracks.Group> = emptyList()
    private var textGroups: List<Tracks.Group> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private lateinit var sys: SystemControls
    private lateinit var actions: PlayerActions
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::addSubtitle) }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, getString(R.string.unsupported), Toast.LENGTH_LONG).show()
        }
        override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            controller?.let { refreshTracks(it.currentTracks) }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (ui.orientation == 0 && videoSize.width > videoSize.height) {
                applyOrientation(1, false)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { c ->
                ui.playing = c.isPlaying
                ui.position = c.currentPosition
                ui.duration = c.duration.coerceAtLeast(0L)
                ui.index = c.currentMediaItemIndex
                items.getOrNull(ui.index)?.let { ui.title = titleOf(it) }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (list, idx) = intent.readPlaylist()
        if (list.isEmpty()) { finish(); return }
        items = list; startIndex = idx
        source = intent.getStringExtra("pl_src") ?: "all"
        enterImmersive()
        sys = SystemControls(this)
        ui.speed = Prefs.float(this, "speed", 1f)
        ui.orientation = Prefs.int(this, "orient", 0)
        ui.bgPlay = Prefs.bool(this, "bg", false)
        ui.decoder = Prefs.int(this, "decoder", 1)
        ui.swipeGain = floatArrayOf(0.6f, 1f, 1.5f)[Prefs.int(this, "swipe", 1).coerceIn(0, 2)]
        ui.resizeIndex = Prefs.int(this, "resize", 0).coerceIn(0, 2)
        if (ui.orientation != 0) applyOrientation(ui.orientation, false)

        actions = PlayerActions(
            onBack = { finish() },
            onTogglePlay = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
            onSeek = { controller?.seekTo(it) },
            onSeekBy = { d ->
                controller?.let { c ->
                    val dur = c.duration
                    val t = c.currentPosition + d
                    c.seekTo(if (dur > 0) t.coerceIn(0L, dur) else t.coerceAtLeast(0L))
                }
            },
            onGetPosition = { controller?.currentPosition ?: 0L },
            onSpeed = { controller?.setPlaybackSpeed(it); ui.speed = it },
            onBgToggle = {
                ui.bgPlay = !ui.bgPlay
                ui.hud = getString(if (ui.bgPlay) R.string.hud_bg_on else R.string.hud_bg_off)
                switchEngine(ui.bgPlay)
            },
            onRotate = {
                ui.orientation = (ui.orientation + 1) % 3
                applyOrientation(ui.orientation, false)
                val names = listOf(R.string.orient_auto, R.string.orient_landscape, R.string.orient_portrait).map { getString(it) }
                ui.hud = names[ui.orientation] + (if (ui.orientation != 0) " · " + getString(R.string.hud_locked) else "")
            },
            onLoop = {
                ui.loop = !ui.loop
                controller?.repeatMode = if (ui.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                ui.hud = getString(R.string.opt_loop) + ": " + getString(if (ui.loop) R.string.on else R.string.off)
            },
            onDecoder = { changeDecoder(it) },
            onAudio = { selectAudio(it) },
            onSub = { selectSub(it) },
            onLoadSub = { pickSubtitle.launch(arrayOf("*/*")) },
            onPlayItem = { controller?.seekToDefaultPosition(it) },
            onNext = { controller?.seekToNextMediaItem() },
            onPrev = { controller?.seekToPreviousMediaItem() },
            onResize = {
                ui.resizeIndex = (ui.resizeIndex + 1) % resizeModes.size
                ui.hud = getString(listOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom)[ui.resizeIndex])
            },
            onBrightness = { ui.hud = sys.brightnessBy(it) },
            onVolumeSteps = { ui.hud = sys.volumeBy(it) },
            onSaveDefaults = {
                Prefs.setFloat(this, "speed", ui.speed); Prefs.setInt(this, "orient", ui.orientation)
                Prefs.setBool(this, "bg", ui.bgPlay); Prefs.setInt(this, "resize", ui.resizeIndex)
                ui.hud = getString(R.string.hud_saved)
            },
            onHold = { on -> controller?.setPlaybackSpeed(if (on) 2f else ui.speed) }
        )
        ui.playlist = items; ui.index = startIndex

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                        update = { it.player = controller; it.resizeMode = resizeModes[ui.resizeIndex] },
                        modifier = Modifier.fillMaxSize()
                    )
                    PlayerUi(ui, actions)
                }
            }
        }
        val resume = if (Prefs.bool(this, "resume", true)) prefs.getLong(items[startIndex].uri.toString(), 0L) else 0L
        startEngine(ui.bgPlay, startIndex, resume, true)
        handler.post(ticker)
    }

    // ---------- playback engine ----------

    private fun startEngine(bg: Boolean, index: Int, pos: Long, autoplay: Boolean) {
        val gen = ++engineGen
        if (bg) {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            future.addListener({
                if (gen != engineGen || isDestroyed) {
                    try { MediaController.releaseFuture(future) } catch (e: Exception) { /* ignore */ }
                    return@addListener
                }
                val c = future.get()
                mediaController = c
                prepare(c, index, pos, autoplay)
            }, ContextCompat.getMainExecutor(this))
        } else {
            val p = ExoPlayer.Builder(this)
                .setRenderersFactory(Decoders.factory(this))
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)   // snap to the nearest keyframe: seeks play instantly
                .build()
            localPlayer = p
            prepare(p, index, pos, autoplay)
        }
    }

    private fun prepare(p: Player, index: Int, pos: Long, autoplay: Boolean) {
        p.addListener(listener)
        controller = p
        p.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, index, pos)
        p.setPlaybackSpeed(ui.speed)
        p.repeatMode = if (ui.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.prepare()
        if (autoplay) p.play()
    }

    private fun releaseEngine() {
        engineGen++
        controller?.removeListener(listener)
        controller = null
        mediaController?.release(); mediaController = null
        localPlayer?.release(); localPlayer = null
    }

    /** Hands the video over to the other engine (or rebuilds it after a decoder change) at the same position. */
    private fun switchEngine(bg: Boolean, decoderChanged: Boolean = false) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        val pos = c.currentPosition
        val playing = c.playWhenReady
        c.pause()
        if (decoderChanged) Prefs.setInt(this, "decoder", ui.decoder)   // the service rebuilds its player on this change
        if (mediaController != null && !bg) { mediaController?.stop(); mediaController?.clearMediaItems() }
        releaseEngine()
        startEngine(bg, idx, pos, playing)
    }

    private fun changeDecoder(mode: Int) {
        if (mode == ui.decoder) return
        ui.decoder = mode
        switchEngine(ui.bgPlay, decoderChanged = true)
    }

    // ---------- tracks / subtitles ----------

    private fun trackName(f: Format, i: Int): String =
        f.label ?: f.language?.let { l -> Locale(l).displayLanguage.ifBlank { l } } ?: "Track ${i + 1}"

    private fun refreshTracks(tracks: Tracks) {
        audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        ui.audioNames = audioGroups.mapIndexed { i, g -> trackName(g.getTrackFormat(0), i) }
        ui.audioSel = audioGroups.indexOfFirst { it.isSelected }
        ui.subNames = textGroups.mapIndexed { i, g -> trackName(g.getTrackFormat(0), i) }
        val off = controller?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        ui.subSel = if (off) -1 else textGroups.indexOfFirst { it.isSelected }
    }

    private fun selectAudio(i: Int) {
        val c = controller ?: return
        val g = audioGroups.getOrNull(i) ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    private fun selectSub(i: Int) {
        val c = controller ?: return
        val b = c.trackSelectionParameters.buildUpon()
        if (i < 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else {
            val g = textGroups.getOrNull(i) ?: return
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        }
        c.trackSelectionParameters = b.build()
    }

    private fun addSubtitle(sub: Uri) {
        val c = controller ?: return
        val i = c.currentMediaItemIndex
        val base = items.getOrNull(i) ?: return
        val name = contentResolver.query(sub, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: ""
        val mime = when {
            name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val cfg = MediaItem.SubtitleConfiguration.Builder(sub)
            .setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val pos = c.currentPosition
        c.replaceMediaItem(i, MediaItem.Builder().setUri(base.uri).setSubtitleConfigurations(listOf(cfg)).build())
        c.seekTo(i, pos)
    }

    // ---------- lifecycle ----------

    override fun onUserLeaveHint() {
        if (controller?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        val c = controller ?: return
        items.getOrNull(c.currentMediaItemIndex)?.let {
            prefs.edit().putLong(it.uri.toString(), c.currentPosition).apply()
            Recents.record(this, it.uri, titleOf(it), c.currentPosition, c.duration.coerceAtLeast(0L), source)
        }
        if (!isInPictureInPictureMode && !ui.bgPlay) c.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releaseEngine()   // background mode keeps playing in the service; otherwise the player is simply released
        super.onDestroy()
    }
}
