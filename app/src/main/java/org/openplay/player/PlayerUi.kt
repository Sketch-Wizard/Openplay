package org.openplay.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
    var orientation by mutableIntStateOf(0)
    var rotationLocked by mutableStateOf(false)
    var audioNames by mutableStateOf<List<String>>(emptyList())
    var audioSel by mutableIntStateOf(-1)
    var subNames by mutableStateOf<List<String>>(emptyList())
    var subSel by mutableIntStateOf(-1)
    var playlist by mutableStateOf<List<PlItem>>(emptyList())
    var index by mutableIntStateOf(0)
    var resizeName by mutableStateOf("Fit")
    var inPip by mutableStateOf(false)
    var hud by mutableStateOf<String?>(null)
}

class PlayerActions(
    val onBack: () -> Unit, val onTogglePlay: () -> Unit, val onSeek: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit, val onSpeed: (Float) -> Unit, val onBgToggle: () -> Unit,
    val onRotate: () -> Unit, val onLockToggle: () -> Unit, val onAudio: (Int) -> Unit,
    val onSub: (Int) -> Unit, val onLoadSub: () -> Unit, val onPlayItem: (Int) -> Unit,
    val onNext: () -> Unit, val onPrev: () -> Unit, val onResize: () -> Unit,
    val onBrightness: (Float) -> Unit, val onVolumeSteps: (Int) -> Unit, val onSaveDefaults: () -> Unit
)

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private enum class Sheet { None, Speed, Audio, Subs, Playlist }
private val accent = Color(0xFFFFA040)
private val speedList = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val orientNames = listOf("Auto", "Landscape", "Portrait")

@Composable
fun PlayerUi(s: PlayerUiState, a: PlayerActions) {
    if (s.inPip) return
    var visible by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    var touch by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(visible, s.playing, sheet, options, touch) {
        if (visible && s.playing && sheet == Sheet.None) { delay(4000); visible = false; options = false }
    }
    LaunchedEffect(s.hud) { if (s.hud != null) { delay(900); s.hud = null } }
    fun act(f: () -> Unit) { touch++; f() }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { visible = !visible; touch++ },
                    onDoubleTap = { o -> a.onSeekBy(if (o.x < size.width / 2) -10_000L else 10_000L) }
                )
            }
            .pointerInput(Unit) {
                var startX = 0f; var mode = 0; var acc = 0f
                detectDragGestures(
                    onDragStart = { startX = it.x; mode = 0; acc = 0f },
                    onDrag = { _, d ->
                        if (mode == 0 && (abs(d.x) > 2 || abs(d.y) > 2))
                            mode = if (abs(d.x) > abs(d.y)) 1 else if (startX < size.width / 2) 2 else 3
                        when (mode) {
                            1 -> a.onSeekBy((d.x * 80).toLong())
                            2 -> a.onBrightness(-d.y / size.height)
                            3 -> { acc += -d.y; val st = (acc / 60f).toInt(); if (st != 0) { a.onVolumeSteps(st); acc -= st * 60f } }
                        }
                    }
                )
            }
    ) {
        s.hud?.let {
            Text(it, color = Color.White, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 10.dp))
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(Icons.Rounded.ArrowBack, 26) { a.onBack() }
                    Text(s.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                    IconBtn(if (options) Icons.Rounded.ExpandLess else Icons.Rounded.Tune, 26) { act { options = !options } }
                }

                AnimatedVisibility(
                    options, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 8.dp)
                ) {
                    Column(
                        Modifier.heightIn(max = 250.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.55f)).verticalScroll(rememberScrollState()).padding(6.dp)
                    ) {
                        OptionRow(Icons.Rounded.Speed, "Speed", "${s.speed}x") { act { sheet = Sheet.Speed } }
                        OptionRow(Icons.Rounded.Headphones, "Background play", if (s.bgPlay) "On" else "Off", s.bgPlay) { act { a.onBgToggle() } }
                        OptionRow(Icons.Rounded.ScreenRotation, "Orientation", orientNames[s.orientation]) { act { a.onRotate() } }
                        OptionRow(Icons.Rounded.ScreenLockRotation, "Lock rotation", if (s.rotationLocked) "On" else "Off", s.rotationLocked) { act { a.onLockToggle() } }
                        OptionRow(Icons.Rounded.Audiotrack, "Audio track") { act { sheet = Sheet.Audio } }
                        OptionRow(Icons.Rounded.Subtitles, "Subtitles") { act { sheet = Sheet.Subs } }
                        OptionRow(Icons.Rounded.AspectRatio, "Aspect", s.resizeName) { act { a.onResize() } }
                        OptionRow(Icons.Rounded.PlaylistPlay, "Playlist", "${s.playlist.size}") { act { sheet = Sheet.Playlist } }
                        OptionRow(Icons.Rounded.Save, "Save as default") { act { a.onSaveDefaults() } }
                    }
                }

                Row(
                    Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(Icons.Rounded.SkipPrevious, 38) { act { a.onPrev() } }
                    IconBtn(Icons.Rounded.Replay10, 38) { act { a.onSeekBy(-10_000L) } }
                    Box(
                        Modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                            .clickable { act { a.onTogglePlay() } },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                            tint = Color.White, modifier = Modifier.size(50.dp))
                    }
                    IconBtn(Icons.Rounded.Forward10, 38) { act { a.onSeekBy(10_000L) } }
                    IconBtn(Icons.Rounded.SkipNext, 38) { act { a.onNext() } }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val dur = s.duration.coerceAtLeast(1L)
                    Slider(
                        value = drag ?: (s.position.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { drag = it; touch++ },
                        onValueChangeFinished = { drag?.let { a.onSeek((it * dur).toLong()) }; drag = null },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtTime(drag?.let { (it * dur).toLong() } ?: s.position), color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text(fmtTime(s.duration), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        val close = { sheet = Sheet.None }
        when (sheet) {
            Sheet.Speed -> SheetList("Playback speed", speedList.map { "${it}x" }, speedList.indexOf(s.speed),
                { a.onSpeed(speedList[it]); close() }, close)
            Sheet.Audio -> SheetList("Audio track", s.audioNames.ifEmpty { listOf("No audio tracks") }, s.audioSel,
                { if (s.audioNames.isNotEmpty()) a.onAudio(it); close() }, close)
            Sheet.Subs -> {
                val entries = listOf("Off") + s.subNames + "Load from file…"
                SheetList("Subtitles", entries, s.subSel + 1, {
                    when (it) { 0 -> a.onSub(-1); entries.lastIndex -> a.onLoadSub(); else -> a.onSub(it - 1) }
                    close()
                }, close)
            }
            Sheet.Playlist -> SheetList("Playlist", s.playlist.map { it.title.ifBlank { "Video" } }, s.index,
                { a.onPlayItem(it); close() }, close)
            Sheet.None -> {}
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, size: Int, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(52.dp)) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size.dp)) }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, value: String? = null, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(min = 200.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (active) accent else Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
    }
}

@Composable
private fun SheetList(title: String, entries: List<String>, selected: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp).width(300.dp).heightIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(8.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            LazyColumn {
                itemsIndexed(entries) { i, e ->
                    Text(e, color = if (i == selected) accent else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(i) }.padding(12.dp))
                }
            }
        }
    }
}
