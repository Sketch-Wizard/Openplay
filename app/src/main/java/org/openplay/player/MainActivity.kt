@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.openplay.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.theme = Prefs.int(this, "theme", 0)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (AppState.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
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
    val scope = rememberCoroutineScope()
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var vaultKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pending; pending = null
        if (r.resultCode == Activity.RESULT_OK) p?.then?.invoke() else p?.denied?.invoke()
        reload++; vaultKey++
    }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perm) }
    LaunchedEffect(granted, reload) { if (granted) videos = withContext(Dispatchers.IO) { queryVideos(ctx) } }
    val sorted = remember(videos, sortKey, asc) { videos.sortVideos(sortKey, asc) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun requestWrite(uris: List<Uri>, then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            pending = Pending(then, null)
            sender.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(ctx.contentResolver, uris).intentSender).build())
        } else then()
    }

    fun requestDelete(uris: List<Uri>, denied: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= 30) {
            pending = Pending(null, denied)
            sender.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
        } else {
            try { uris.forEach { ctx.contentResolver.delete(it, null, null) } }
            catch (e: Exception) { denied?.invoke(); toast("Could not delete on this Android version") }
            reload++
        }
    }

    fun doLock(list: List<Video>) {
        if (!Vault.hasPin(ctx)) { toast("Create a PIN in the Private tab first"); tab = 3; return }
        scope.launch {
            busy = "Moving to private…"
            val added = withContext(Dispatchers.IO) { list.mapNotNull { v -> Vault.add(ctx, v)?.let { v to it } } }
            busy = null
            if (added.isEmpty()) { toast("Could not copy the video(s)"); return@launch }
            requestDelete(added.map { it.first.uri }) { added.forEach { Vault.remove(ctx, it.second) } }
        }
    }

    fun doRename(t: MenuTarget, name: String) {
        if (t.isFolder) {
            val rel = t.videos.first().relPath
            val parent = rel.trimEnd('/').substringBeforeLast('/', "")
            val dest = if (parent.isEmpty()) "" else "$parent/" + name.trim('/', ' ') + "/"
            if (dest.isEmpty() || !(dest.startsWith("Movies/") || dest.startsWith("DCIM/"))) {
                toast("This folder can't be renamed"); return
            }
            requestWrite(t.videos.map { it.uri }) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { t.videos.all { MediaOps.move(ctx, it, dest) } }
                    toast(if (ok) "Folder renamed" else "Rename failed"); reload++
                }
            }
        } else {
            val v = t.videos.first()
            val ext = if (v.title.contains('.')) "." + v.title.substringAfterLast('.') else ""
            val full = if (name.endsWith(ext, true)) name else name + ext
            requestWrite(listOf(v.uri)) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { MediaOps.rename(ctx, v, full) }
                    toast(if (ok) "Renamed" else "Rename failed"); reload++
                }
            }
        }
    }

    fun detailRows(t: MenuTarget): List<Pair<String, String>> {
        if (t.isFolder) return listOf(
            "Name" to t.title, "Location" to t.videos.first().relPath.ifEmpty { t.title },
            "Videos" to "${t.videos.size}", "Total size" to fmtSize(t.videos.sumOf { it.size })
        )
        val v = t.videos.first()
        return listOfNotNull(
            "Name" to v.title, "Location" to v.relPath.ifEmpty { v.folder }, "Size" to fmtSize(v.size),
            "Duration" to fmtTime(v.durationMs),
            if (v.width > 0) "Resolution" to "${v.width} × ${v.height}" else null,
            "Type" to v.mime,
            "Added" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.dateAdded * 1000))
        )
    }

    val onVideoLong: (Video) -> Unit = { v -> dlg = Dlg.Menu(MenuTarget(v.title, listOf(v), false)) }
    val onFolderLong: (List<Video>) -> Unit = { l -> dlg = Dlg.Menu(MenuTarget(l.first().folder, l, true)) }

    if (showSettings) { SettingsScreen { showSettings = false }; return }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header { showSettings = true }
            TabSection(tab) { tab = it }
            if (tab < 2 && granted) {
                val count = if (tab == 0) "${sorted.size} videos" else "${sorted.map { it.folderKey }.distinct().size} folders"
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
                    tab == 0 -> VideoCollection(sorted, grid, onVideoLong) { i ->
                        ctx.openPlayer(sorted.map { PlItem(it.uri, it.title) }, i)
                    }
                    tab == 1 -> FoldersScreen(sorted, grid, onVideoLong, onFolderLong)
                    tab == 2 -> StreamScreen()
                    else -> PrivateScreen(vaultKey, onChanged = { reload++ }, setBusy = { busy = it })
                }
            }
        }

        when (val d = dlg) {
            is Dlg.Menu -> ActionSheet(d.t.title, if (d.t.isFolder) folderMenu else videoMenu, onPick = { a ->
                dlg = when (a) {
                    Act.Rename -> Dlg.Rename(d.t)
                    Act.Details -> Dlg.Details(d.t)
                    Act.Delete -> Dlg.Delete(d.t)
                    Act.Move -> Dlg.Dest(d.t.videos, false)
                    Act.Copy -> Dlg.Dest(d.t.videos, true)
                    Act.Share -> { MediaOps.share(ctx, d.t.videos.first()); null }
                    Act.Private -> { doLock(d.t.videos); null }
                    else -> null
                }
            }, onDismiss = { dlg = null })
            is Dlg.Rename -> RenameDialog(
                if (d.t.isFolder) d.t.title else d.t.title.substringBeforeLast('.'),
                onOk = { n -> dlg = null; doRename(d.t, n) }, onDismiss = { dlg = null })
            is Dlg.Details -> DetailsDialog(detailRows(d.t)) { dlg = null }
            is Dlg.Delete -> ConfirmDialog(
                if (d.t.isFolder) "Delete folder?" else "Delete video?",
                if (d.t.isFolder) "All ${d.t.videos.size} videos in \"${d.t.title}\" will be permanently deleted."
                else "\"${d.t.title}\" will be permanently deleted.",
                "Delete", onOk = { dlg = null; requestDelete(d.t.videos.map { it.uri }) }, onDismiss = { dlg = null })
            is Dlg.Dest -> {
                val opts = remember(d) { destinations(videos, d.videos.first().relPath) }
                ChoiceDialog(if (d.copy) "Copy to…" else "Move to…", opts, -1, onPick = { i ->
                    val dest = opts[i]; dlg = null
                    if (d.copy) scope.launch {
                        busy = "Copying…"
                        val ok = withContext(Dispatchers.IO) { d.videos.all { MediaOps.copy(ctx, it, dest) } }
                        busy = null; toast(if (ok) "Copied" else "Copy failed (needs Android 10 or newer)"); reload++
                    } else requestWrite(d.videos.map { it.uri }) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { d.videos.all { MediaOps.move(ctx, it, dest) } }
                            toast(if (ok) "Moved" else "Move failed (needs Android 10 or newer)"); reload++
                        }
                    }
                }, onDismiss = { dlg = null })
            }
            null -> {}
        }

        busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun Header(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C))))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("OpenPlay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
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
fun VideoCollection(videos: List<Video>, grid: Boolean, onLong: (Video) -> Unit, onOpen: (Int) -> Unit) {
    if (videos.isEmpty()) { Text("No videos found.", Modifier.padding(24.dp)); return }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                Column(Modifier.combinedClickable(onClick = { onOpen(i) }, onLongClick = { onLong(v) })) {
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
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(i) }, onLongClick = { onLong(v) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
fun FoldersScreen(videos: List<Video>, grid: Boolean, onVideoLong: (Video) -> Unit, onFolderLong: (List<Video>) -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(open != null) { open = null }
    val groups = remember(videos) { videos.groupBy { it.folderKey }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val current = open
    if (current != null) {
        val list = groups[current].orEmpty()
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = null }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(list.firstOrNull()?.folder ?: current, fontWeight = FontWeight.Bold)
            }
            VideoCollection(list, grid, onVideoLong) { i -> ctx.openPlayer(list.map { PlItem(it.uri, it.title) }, i) }
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
                Column(Modifier.combinedClickable(onClick = { open = e.key }, onLongClick = { onFolderLong(e.value) })) {
                    Thumb(e.value.first().uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(e.value.first().folder, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    Text("${e.value.size} videos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            items(folders, key = { it.key }) { e ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { open = e.key }, onLongClick = { onFolderLong(e.value) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(e.value.first().uri, Modifier.width(96.dp).aspectRatio(16f / 9f))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(e.value.first().folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
