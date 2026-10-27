@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.openplay.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

class PlayerUiState {
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var bgPlay by mutableStateOf(false)
    var loop by mutableStateOf(false)
    var decoder by mutableIntStateOf(1)
    var orientation by mutableIntStateOf(0)
    var swipeGain by mutableFloatStateOf(1f)
    var audioNames by mutableStateOf<List<String>>(emptyList())
    var audioSel by mutableIntStateOf(-1)
    var subNames by mutableStateOf<List<String>>(emptyList())
    var subSel by mutableIntStateOf(-1)
    var playlist by mutableStateOf<List<PlItem>>(emptyList())
    var index by mutableIntStateOf(0)
    var resizeIndex by mutableIntStateOf(0)
    var inPip by mutableStateOf(false)
    var hud by mutableStateOf<String?>(null)
    var seekInfo by mutableStateOf<String?>(null)
    var boost by mutableStateOf(false)
}

class PlayerActions(
    val onBack: () -> Unit, val onTogglePlay: () -> Unit, val onSeek: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit, val onGetPosition: () -> Long, val onSpeed: (Float) -> Unit,
    val onBgToggle: () -> Unit, val onRotate: () -> Unit,
    val onLoop: () -> Unit, val onDecoder: (Int) -> Unit, val onAudio: (Int) -> Unit,
    val onSub: (Int) -> Unit, val onLoadSub: () -> Unit, val onPlayItem: (Int) -> Unit,
    val onNext: () -> Unit, val onPrev: () -> Unit, val onResize: () -> Unit,
    val onBrightness: (Float) -> Unit, val onVolumeSteps: (Int) -> Unit,
    val onSaveDefaults: () -> Unit, val onHold: (Boolean) -> Unit
)

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtSpeed(v: Float): String = if (v % 1f == 0f) "${v.toInt()}x" else "${v}x"

private enum class Sheet { None, Speed, Audio, Subs, Playlist, Decoder }
private val accent = Color(0xFFFFA040)
private val speedList = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private class Opt(val icon: ImageVector, val label: String, val active: Boolean, val onClick: () -> Unit)

@Composable
fun PlayerUi(s: PlayerUiState, a: PlayerActions) {
    if (s.inPip) return
    var visible by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    var touch by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    val offTxt = stringResource(R.string.off)
    val orientIcon = when (s.orientation) {
        1 -> Icons.Rounded.ScreenLockLandscape
        2 -> Icons.Rounded.ScreenLockPortrait
        else -> Icons.Rounded.ScreenRotation
    }
    val decoderLabels = listOf(R.string.dec_hw, R.string.dec_hwplus, R.string.dec_sw).map { stringResource(it) }

    LaunchedEffect(visible, s.playing, sheet, options, touch) {
        if (visible && s.playing && sheet == Sheet.None) { delay(4000); visible = false; options = false }
    }
    LaunchedEffect(s.hud) { if (s.hud != null) { delay(900); s.hud = null } }
    LaunchedEffect(tip) { if (tip != null) { delay(1600); tip = null } }
    fun act(f: () -> Unit) { touch++; f() }

    val opts = listOf(
        Opt(Icons.Rounded.Speed, stringResource(R.string.opt_speed), false) { sheet = Sheet.Speed },
        Opt(Icons.Rounded.Audiotrack, stringResource(R.string.opt_audio), false) { sheet = Sheet.Audio },
        Opt(Icons.Rounded.AspectRatio, stringResource(R.string.opt_aspect), false) { a.onResize() },
        Opt(orientIcon, stringResource(R.string.opt_orientation), s.orientation != 0) { a.onRotate() },
        Opt(Icons.Rounded.Headphones, stringResource(R.string.bg_play), s.bgPlay) { a.onBgToggle() },
        Opt(Icons.Rounded.Repeat, stringResource(R.string.opt_loop), s.loop) { a.onLoop() },
        Opt(Icons.Rounded.Memory, stringResource(R.string.decoder), false) { sheet = Sheet.Decoder },
        Opt(Icons.Rounded.Save, stringResource(R.string.opt_save_default), false) { a.onSaveDefaults() }
    )

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                var holding = false
                detectTapGestures(
                    onPress = {
                        tryAwaitRelease()
                        if (holding) { holding = false; s.boost = false; a.onHold(false) }
                    },
                    onLongPress = { holding = true; s.boost = true; a.onHold(true) },
                    onDoubleTap = { o ->
                        val w = size.width
                        when {
                            o.x < w * 0.35f -> { a.onSeekBy(-10_000L); s.hud = "−10s" }
                            o.x > w * 0.65f -> { a.onSeekBy(10_000L); s.hud = "+10s" }
                            else -> a.onTogglePlay()
                        }
                    },
                    onTap = { visible = !visible; touch++ }
                )
            }
            .pointerInput(Unit) {
                var startX = 0f; var mode = 0; var acc = 0f
                var startPos = 0L; var target = 0L; var ema = 0f; var lastSeek = 0L
                detectDragGestures(
                    onDragStart = { startX = it.x; mode = 0; acc = 0f; ema = 0f; lastSeek = 0L },
                    onDragEnd = { if (mode == 1) { a.onSeek(target); s.seekInfo = null }; mode = 0 },
                    onDragCancel = { if (mode == 1) { a.onSeek(target); s.seekInfo = null }; mode = 0 },
                    onDrag = { change, d ->
                        if (mode == 0 && (abs(d.x) > 2 || abs(d.y) > 2)) {
                            mode = if (abs(d.x) > abs(d.y)) 1 else if (startX < size.width / 2) 2 else 3
                            if (mode == 1) { startPos = a.onGetPosition(); target = startPos }
                        }
                        when (mode) {
                            1 -> {
                                // swipe intensity decides the speed: slow swipe = about a second at a time, fast swipe = much faster
                                val dt = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                                val inst = abs(d.x) / dt
                                ema = if (ema == 0f) inst else ema * 0.7f + inst * 0.3f
                                // seconds per pixel: about 0.02 for a slow drag, 0.04 for a normal swipe, up to about 0.12 for a very fast one
                                val n = ema.coerceAtMost(2.5f) / 2.5f
                                val k = (0.02f + 0.10f * n * n) * s.swipeGain
                                val dur = s.duration
                                val next = target + (d.x * k * 1000f).toLong()
                                target = if (dur > 0) next.coerceIn(0L, dur) else next.coerceAtLeast(0L)
                                if (change.uptimeMillis - lastSeek >= 90) { a.onSeek(target); lastSeek = change.uptimeMillis }
                                val delta = target - startPos
                                s.seekInfo = fmtTime(target) + "   [" + (if (delta >= 0) "+" else "−") + fmtTime(abs(delta)) + "]"
                            }
                            2 -> a.onBrightness(-d.y / size.height)
                            3 -> { acc += -d.y; val st = (acc / 60f).toInt(); if (st != 0) { a.onVolumeSteps(st); acc -= st * 60f } }
                        }
                    }
                )
            }
    ) {
        s.hud?.let {
            Text(it, color = Color.White, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        s.seekInfo?.let {
            Text(it, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = (-40).dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 22.dp, vertical = 12.dp))
        }
        if (s.boost) {
            Text(stringResource(R.string.boost), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp).clip(RoundedCornerShape(50))
                    .background(accent).padding(horizontal = 16.dp, vertical = 6.dp))
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(Icons.Rounded.ArrowBack, 26) { a.onBack() }
                    Text(s.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                    IconBtn(Icons.Rounded.Subtitles, 24, if (s.subSel >= 0) accent else Color.White) { act { sheet = Sheet.Subs } }
                    IconBtn(Icons.Rounded.Tune, 26, if (options) accent else Color.White) { act { options = !options } }
                    IconBtn(Icons.Rounded.PlaylistPlay, 26) { act { sheet = Sheet.Playlist } }
                }

                AnimatedVisibility(
                    options, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        tip?.let {
                            Text(it, color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(6.dp)) {
                            opts.chunked(3).forEach { row ->
                                Row {
                                    row.forEach { o ->
                                        GridIcon(o.icon, o.active, onClick = { act { o.onClick() } }, onLongPress = { touch++; tip = o.label })
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 6.dp)
                ) {
                    val dur = s.duration.coerceAtLeast(1L)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(drag?.let { (it * dur).toLong() } ?: s.position), color = Color.White, fontSize = 12.sp)
                        ThinSeekBar(
                            fraction = drag ?: (s.position.toFloat() / dur).coerceIn(0f, 1f),
                            onSeeking = { drag = it; touch++ },
                            onDone = { a.onSeek((it * dur).toLong()); drag = null; touch++ },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        Text(fmtTime(s.duration), color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconBtn(orientIcon, 24, if (s.orientation != 0) accent else Color.White) { act { a.onRotate() } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBtn(Icons.Rounded.SkipPrevious, 30) { act { a.onPrev() } }
                            IconBtn(Icons.Rounded.Replay10, 30) { act { a.onSeekBy(-10_000L) } }
                            Box(
                                Modifier.padding(horizontal = 6.dp).size(56.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f)).clickable { act { a.onTogglePlay() } },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                                    tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconBtn(Icons.Rounded.Forward10, 30) { act { a.onSeekBy(10_000L) } }
                            IconBtn(Icons.Rounded.SkipNext, 30) { act { a.onNext() } }
                        }
                        IconBtn(Icons.Rounded.AspectRatio, 24) { act { a.onResize() } }
                    }
                }
            }
        }

        val close = { sheet = Sheet.None }
        when (sheet) {
            Sheet.Speed -> SpeedSheet(stringResource(R.string.playback_speed), s.speed, { a.onSpeed(it); close() }, close)
            Sheet.Audio -> SheetList(stringResource(R.string.opt_audio), s.audioNames.ifEmpty { listOf(stringResource(R.string.no_audio)) }, s.audioSel,
                { if (s.audioNames.isNotEmpty()) a.onAudio(it); close() }, close)
            Sheet.Subs -> {
                val entries = listOf(offTxt) + s.subNames + stringResource(R.string.subs_load)
                SheetList(stringResource(R.string.opt_subs), entries, s.subSel + 1, {
                    when (it) { 0 -> a.onSub(-1); entries.lastIndex -> a.onLoadSub(); else -> a.onSub(it - 1) }
                    close()
                }, close)
            }
            Sheet.Playlist -> PlaylistSheet(stringResource(R.string.opt_playlist), s.playlist, s.index, { a.onPlayItem(it); close() }, close)
            Sheet.Decoder -> SheetList(stringResource(R.string.decoder), decoderLabels, s.decoder, { a.onDecoder(it); close() }, close)
            Sheet.None -> {}
        }
    }
}

/** Thin timeline: 3dp track, small thumb. Tap or drag to seek. */
@Composable
private fun ThinSeekBar(fraction: Float, onSeeking: (Float) -> Unit, onDone: (Float) -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.height(32.dp)
            .pointerInput(Unit) { detectTapGestures { o -> onDone((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onSeeking(last) },
                    onDragEnd = { onDone(last) },
                    onDragCancel = { onDone(last) },
                    onHorizontalDrag = { c, _ -> last = (c.position.x / size.width).coerceIn(0f, 1f); onSeeking(last) }
                )
            }
    ) {
        val h = 3.dp.toPx(); val cy = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(accent, Offset(0f, cy - h / 2), Size(size.width * fraction, h), CornerRadius(h / 2))
        drawCircle(accent, 6.dp.toPx(), Offset(size.width * fraction, cy))
    }
}

@Composable
private fun IconBtn(icon: ImageVector, size: Int, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) { Icon(icon, null, tint = tint, modifier = Modifier.size(size.dp)) }
}

/** Icon-only cell of the options grid. Orange = on, white = off. Long press shows its name. */
@Composable
private fun GridIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) accent else Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SpeedSheet(title: String, current: Float, onPick: (Float) -> Unit, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.82f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            speedList.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { v ->
                        val sel = v == current
                        Box(
                            Modifier.size(62.dp).clip(CircleShape)
                                .background(if (sel) accent else accent.copy(alpha = 0.25f))
                                .border(2.dp, accent, CircleShape).clickable { onPick(v) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(fmtSpeed(v), color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetList(title: String, entries: List<String>, selected: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp).width(300.dp).heightIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(8.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            LazyColumn {
                itemsIndexed(entries) { i, e ->
                    Text(e, color = if (i == selected) accent else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(i) }.padding(12.dp))
                }
            }
        }
    }
}

/** Playlist with a thumbnail next to every title; the playing video is highlighted and scrolled into view. */
@Composable
private fun PlaylistSheet(title: String, items: List<PlItem>, current: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp).width(340.dp).heightIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(8.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            LazyColumn(state = listState) {
                itemsIndexed(items) { i, item ->
                    val sel = i == current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (sel) accent.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { onPick(i) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(item.uri, Modifier.width(104.dp).aspectRatio(16f / 9f))
                        Spacer(Modifier.width(10.dp))
                        Text(item.title.ifBlank { "Video" }, color = if (sel) accent else Color.White, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
