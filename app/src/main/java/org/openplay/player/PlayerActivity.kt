package org.openplay.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : ComponentActivity() {
    private val ui = PlayerUiState()
    private var controller by mutableStateOf<MediaController?>(null)
    private var resizeIndex by mutableIntStateOf(0)
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Fill", "Zoom")
    private var items: List<PlItem> = emptyList()
    private var startIndex = 0
    private var audioGroups: List<Tracks.Group> = emptyList()
    private var textGroups: List<Tracks.Group> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private lateinit var sys: SystemControls
    private lateinit var actions: PlayerActions
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::addSubtitle) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (list, idx) = intent.readPlaylist()
        if (list.isEmpty()) { finish(); return }
        if (Formats.needsVlc(this, list[idx].uri)) { openPlayer(list, idx, forceVlc = true); finish(); return }
        items = list; startIndex = idx
        enterImmersive()
        sys = SystemControls(this)
        ui.speed = Prefs.float(this, "speed", 1f)
        ui.orientation = Prefs.int(this, "orient", 0)
        ui.bgPlay = Prefs.bool(this, "bg", false)
        resizeIndex = Prefs.int(this, "resize", 0).coerceIn(0, 2)
        ui.resizeName = resizeNames[resizeIndex]
        if (ui.orientation != 0) applyOrientation(ui.orientation, false)
        actions = PlayerActions(
            onBack = { finish() },
            onTogglePlay = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
            onSeek = { controller?.seekTo(it) },
            onSeekBy = { d -> controller?.let { it.seekTo((it.currentPosition + d).coerceIn(0L, it.duration.coerceAtLeast(0L))) } },
            onSpeed = { controller?.setPlaybackSpeed(it); ui.speed = it },
            onBgToggle = { ui.bgPlay = !ui.bgPlay; ui.hud = "Background play ${if (ui.bgPlay) "on" else "off"}" },
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
            onAudio = { selectAudio(it) },
            onSub = { selectSub(it) },
            onLoadSub = { pickSubtitle.launch(arrayOf("*/*")) },
            onPlayItem = { controller?.seekToDefaultPosition(it) },
            onNext = { controller?.seekToNextMediaItem() },
            onPrev = { controller?.seekToPreviousMediaItem() },
            onResize = {
                resizeIndex = (resizeIndex + 1) % resizeModes.size
                ui.resizeName = resizeNames[resizeIndex]; ui.hud = ui.resizeName
            },
            onBrightness = { ui.hud = sys.brightnessBy(it) },
            onVolumeSteps = { ui.hud = sys.volumeBy(it) },
            onSaveDefaults = {
                Prefs.setFloat(this, "speed", ui.speed); Prefs.setInt(this, "orient", ui.orientation)
                Prefs.setBool(this, "bg", ui.bgPlay); Prefs.setInt(this, "resize", resizeIndex)
                ui.hud = "Saved as default"
            }
        )
        ui.playlist = items; ui.index = startIndex

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                        update = { it.player = controller; it.resizeMode = resizeModes[resizeIndex] },
                        modifier = Modifier.fillMaxSize()
                    )
                    PlayerUi(ui, actions)
                }
            }
        }
        connect()
        handler.post(ticker)
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    openPlayer(items, c.currentMediaItemIndex.coerceIn(0, items.size - 1), forceVlc = true)
                    finish()
                }
                override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)
                override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                    refreshTracks(c.currentTracks)
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (ui.orientation == 0 && !ui.rotationLocked && videoSize.width > videoSize.height) {
                        applyOrientation(1, false)
                    }
                }
            })
            controller = c
            val resume = if (Prefs.bool(this, "resume", true)) prefs.getLong(items[startIndex].uri.toString(), 0L) else 0L
            c.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, startIndex, resume)
            c.setPlaybackSpeed(ui.speed)
            c.prepare(); c.play()
        }, ContextCompat.getMainExecutor(this))
    }

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
        items.getOrNull(c.currentMediaItemIndex)?.let { prefs.edit().putLong(it.uri.toString(), c.currentPosition).apply() }
        if (!isInPictureInPictureMode && !ui.bgPlay) c.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        controller?.release()
        super.onDestroy()
    }
}
