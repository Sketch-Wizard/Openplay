package org.openplay.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Video(
    val uri: Uri, val title: String, val durationMs: Long, val folder: String,
    val size: Long, val dateAdded: Long
)

enum class SortKey(val label: String) {
    NAME("Name"), DATE("Date added"), SIZE("Size"), DURATION("Duration")
}

fun List<Video>.sortVideos(key: SortKey, asc: Boolean): List<Video> {
    val c: Comparator<Video> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortKey.DATE -> compareBy<Video> { it.dateAdded }
        SortKey.SIZE -> compareBy<Video> { it.size }
        SortKey.DURATION -> compareBy<Video> { it.durationMs }
    }
    return sortedWith(if (asc) c else c.reversed())
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= (1L shl 20) -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFFFA040), onPrimary = Color(0xFF3B1A00),
        primaryContainer = Color(0xFF5C2E00), onPrimaryContainer = Color(0xFFFFDDBB),
        secondary = Color(0xFFFFB74D), background = Color(0xFF14100D), onBackground = Color(0xFFF3E8DC),
        surface = Color(0xFF14100D), onSurface = Color(0xFFF3E8DC),
        surfaceVariant = Color(0xFF2A221B), onSurfaceVariant = Color(0xFFD9C8B6)
    ) else lightColorScheme(
        primary = Color(0xFFE8590C), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE0C2), onPrimaryContainer = Color(0xFF4A1C00),
        secondary = Color(0xFFFF8A00), background = Color(0xFFFFFAF5), onBackground = Color(0xFF2A1E14),
        surface = Color(0xFFFFFAF5), onSurface = Color(0xFF2A1E14),
        surfaceVariant = Color(0xFFFFEEDD), onSurfaceVariant = Color(0xFF55443A)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var sortKey by remember { mutableStateOf(SortKey.values().getOrElse(prefs.getInt("sort", 1)) { SortKey.DATE }) }
    var asc by remember { mutableStateOf(prefs.getBoolean("asc", false)) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", false)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perm) }
    LaunchedEffect(granted) { if (granted) videos = withContext(Dispatchers.IO) { queryVideos(ctx) } }
    val sorted = remember(videos, sortKey, asc) { videos.sortVideos(sortKey, asc) }

    Column(Modifier.fillMaxSize()) {
        Header()
        TabSection(tab) { tab = it }
        if (tab < 2 && granted) {
            val count = if (tab == 0) "${sorted.size} videos" else "${sorted.map { it.folder }.distinct().size} folders"
            Toolbar(count, sortKey, asc, grid,
                onSort = { sortKey = it; prefs.edit().putInt("sort", it.ordinal).apply() },
                onOrder = { asc = !asc; prefs.edit().putBoolean("asc", asc).apply() },
                onGrid = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() })
        }
        Box(Modifier.weight(1f)) {
            when {
                tab < 2 && !granted -> Column(Modifier.padding(24.dp)) {
                    Text("OpenPlay needs access to your videos.")
                    Button(onClick = { permLauncher.launch(perm) }) { Text("Grant access") }
                }
                tab == 0 -> VideoCollection(sorted, grid) { i -> ctx.openPlayer(sorted.map { PlItem(it.uri, it.title) }, i) }
                tab == 1 -> FoldersScreen(sorted, grid)
                tab == 2 -> StreamScreen()
                else -> PrivateScreen(0)
            }
        }
    }
}

@Composable
fun Header() {
    Row(
        Modifier.fillMaxWidth().height(44.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C))))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("OpenPlay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun TabSection(selected: Int, onSelect: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tabs: List<Pair<String, ImageVector>> = listOf(
        "Videos" to Icons.Rounded.VideoLibrary, "Folders" to Icons.Rounded.Folder,
        "Stream" to Icons.Rounded.Link, "Private" to Icons.Rounded.Lock
    )
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.28f), Color.Transparent)))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, (label, icon) ->
                val sel = i == selected
                val fg = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .background(if (sel) primary.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .clickable { onSelect(i) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
                    Text(label, color = fg, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun Toolbar(
    count: String, key: SortKey, asc: Boolean, grid: Boolean,
    onSort: (SortKey) -> Unit, onOrder: () -> Unit, onGrid: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SortKey.values().forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k.label) },
                        onClick = { onSort(k); menu = false },
                        trailingIcon = { if (k == key) Icon(Icons.Rounded.Check, null) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (asc) "Order: ascending" else "Order: descending") },
                    onClick = { onOrder(); menu = false }
                )
            }
        }
        IconButton(onClick = onGrid) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, "View style") }
    }
}

@Composable
fun Thumb(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = Thumbs.load(ctx, uri) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2620)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier) {
    Text(
        fmtTime(ms), color = Color.White, fontSize = 11.sp,
        modifier = modifier.padding(4.dp).clip(RoundedCornerShape(5.dp))
            .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
fun VideoCollection(videos: List<Video>, grid: Boolean, onOpen: (Int) -> Unit) {
    if (videos.isEmpty()) { Text("No videos found.", Modifier.padding(24.dp)); return }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                Column(Modifier.clickable { onOpen(i) }) {
                    Box {
                        Thumb(v.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                    }
                    Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(fmtSize(v.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Thumb(v.uri, Modifier.width(132.dp).aspectRatio(16f / 9f))
                        DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${v.folder} · ${fmtSize(v.size)}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(videos: List<Video>, grid: Boolean) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }
    val groups = remember(videos) { videos.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val current = open
    if (current != null) {
        val list = groups[current].orEmpty()
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = null }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(current, fontWeight = FontWeight.Bold)
            }
            VideoCollection(list, grid) { i -> ctx.openPlayer(list.map { PlItem(it.uri, it.title) }, i) }
        }
        return
    }
    val folders = groups.entries.toList()
    if (folders.isEmpty()) { Text("No folders found.", Modifier.padding(24.dp)); return }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(folders, key = { it.key }) { e ->
                Column(Modifier.clickable { open = e.key }) {
                    Thumb(e.value.first().uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(e.key, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Text("${e.value.size} videos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            items(folders, key = { it.key }) { e ->
                Row(Modifier.fillMaxWidth().clickable { open = e.key }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(e.value.first().uri, Modifier.width(96.dp).aspectRatio(16f / 9f))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(e.key, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${e.value.size} videos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun StreamScreen() {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp)) {
        Text("Play a network stream (http/https, HLS .m3u8, DASH .mpd)")
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (url.startsWith("http")) ctx.play(Uri.parse(url.trim())) }) { Text("Play") }
    }
}

@Composable
fun PrivateScreen(refreshKey: Int) {
    val ctx = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(listOf<File>()) }
    val creating = !Vault.hasPin(ctx)

    if (!unlocked) {
        Column(Modifier.padding(24.dp)) {
            Text(if (creating) "Create a PIN (4–8 digits) for your private folder" else "Enter PIN")
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); error = false },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error, singleLine = true
            )
            Button(onClick = {
                if (creating) { if (pin.length >= 4) { Vault.setPin(ctx, pin); unlocked = true } else error = true }
                else if (Vault.check(ctx, pin)) unlocked = true else error = true
            }) { Text(if (creating) "Set PIN" else "Unlock") }
        }
    } else {
        LaunchedEffect(refreshKey) { files = Vault.list(ctx) }
        if (files.isEmpty()) Text("Private folder is empty. Locking videos returns in the next update.", Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(files) { f ->
                Row(Modifier.fillMaxWidth().clickable { ctx.play(Uri.fromFile(f)) }.padding(vertical = 10.dp)) {
                    Text(f.name, Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = { f.delete(); files = Vault.list(ctx) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

fun queryVideos(c: Context): List<Video> {
    val out = mutableListOf<Video>()
    val m = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED
    )
    c.contentResolver.query(m, proj, null, null, null)?.use { q ->
        while (q.moveToNext()) {
            out += Video(
                ContentUris.withAppendedId(m, q.getLong(0)), q.getString(1) ?: "Unknown",
                q.getLong(2), q.getString(3) ?: "Other", q.getLong(4), q.getLong(5)
            )
        }
    }
    return out
}
