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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
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
        secondary = Color(0xFFFFB74D), secondaryContainer = Color(0xFF5C2E00), onSecondaryContainer = Color(0xFFFFDDBB),
        background = Color(0xFF14100D), onBackground = Color(0xFFF3E8DC),
        surface = Color(0xFF14100D), onSurface = Color(0xFFF3E8DC),
        surfaceVariant = Color(0xFF2A221B), onSurfaceVariant = Color(0xFFD9C8B6),
        surfaceContainer = Color(0xFF211A15)
    ) else lightColorScheme(
        primary = Color(0xFFE8590C), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE0C2), onPrimaryContainer = Color(0xFF4A1C00),
        secondary = Color(0xFFFF8A00), secondaryContainer = Color(0xFFFFE0C2), onSecondaryContainer = Color(0xFF4A1C00),
        background = Color(0xFFFFFAF5), onBackground = Color(0xFF2A1E14),
        surface = Color(0xFFFFFAF5), onSurface = Color(0xFF2A1E14),
        surfaceVariant = Color(0xFFFFEEDD), onSurfaceVariant = Color(0xFF55443A),
        surfaceContainer = Color(0xFFFFF1E4)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
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
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var openFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var vaultKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var recents by remember { mutableStateOf(Recents.list(ctx)) }

    DisposableEffect(Unit) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) recents = Recents.list(ctx) }
        activity.lifecycle.addObserver(o)
        onDispose { activity.lifecycle.removeObserver(o) }
    }

    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pending; pending = null
        if (r.resultCode == Activity.RESULT_OK) p?.then?.invoke() else p?.denied?.invoke()
        reload++; vaultKey++
    }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perm) }
    LaunchedEffect(granted, reload) { if (granted) videos = withContext(Dispatchers.IO) { queryVideos(ctx) } }
    LaunchedEffect(tab) { if (tab >= 2) { searchOpen = false; query = "" } }
    BackHandler(searchOpen && !showSettings) { searchOpen = false; query = "" }

    val q = query.trim()
    val sorted = remember(videos, sortKey, asc) { videos.sortVideos(sortKey, asc) }
    val shown = remember(sorted, q) { if (q.isEmpty()) sorted else sorted.filter { it.title.contains(q, ignoreCase = true) } }
    val folderCount = remember(sorted, q) {
        sorted.groupBy { it.folderKey }.values.count { q.isEmpty() || it.first().folder.contains(q, ignoreCase = true) }
    }
    val folderHits = remember(sorted, q) {
        if (q.isEmpty()) emptyList()
        else sorted.groupBy { it.folderKey }.entries.filter { it.value.first().folder.contains(q, ignoreCase = true) }
    }
    val recentsShown = remember(recents, videos) { recents.filter { r -> videos.any { it.uri == r.uri } } }

    fun toast(id: Int) = Toast.makeText(ctx, ctx.getString(id), Toast.LENGTH_LONG).show()

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
            catch (e: Exception) { denied?.invoke(); toast(R.string.t_delete_unsupported) }
            reload++
        }
    }

    fun doLock(list: List<Video>) {
        if (!Vault.hasPin(ctx)) { toast(R.string.t_create_pin); tab = 3; return }
        scope.launch {
            busy = ctx.getString(R.string.busy_private)
            val added = withContext(Dispatchers.IO) { list.mapNotNull { v -> Vault.add(ctx, v)?.let { v to it } } }
            busy = null
            if (added.isEmpty()) { toast(R.string.t_copy_videos_failed); return@launch }
            requestDelete(added.map { it.first.uri }) { added.forEach { Vault.remove(ctx, it.second) } }
        }
    }

    fun doRename(t: MenuTarget, name: String) {
        if (t.isFolder) {
            val rel = t.videos.first().relPath
            val parent = rel.trimEnd('/').substringBeforeLast('/', "")
            val dest = if (parent.isEmpty()) "" else "$parent/" + name.trim('/', ' ') + "/"
            if (dest.isEmpty() || !(dest.startsWith("Movies/") || dest.startsWith("DCIM/"))) {
                toast(R.string.t_cant_rename_folder); return
            }
            requestWrite(t.videos.map { it.uri }) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { t.videos.all { MediaOps.move(ctx, it, dest) } }
                    toast(if (ok) R.string.t_folder_renamed else R.string.t_rename_failed); reload++
                }
            }
        } else {
            val v = t.videos.first()
            val ext = if (v.title.contains('.')) "." + v.title.substringAfterLast('.') else ""
            val full = if (name.endsWith(ext, true)) name else name + ext
            requestWrite(listOf(v.uri)) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { MediaOps.rename(ctx, v, full) }
                    toast(if (ok) R.string.t_renamed else R.string.t_rename_failed); reload++
                }
            }
        }
    }

    fun detailRows(t: MenuTarget): List<Pair<String, String>> {
        val g = { id: Int -> ctx.getString(id) }
        if (t.isFolder) return listOf(
            g(R.string.sort_name) to t.title, g(R.string.d_location) to t.videos.first().relPath.ifEmpty { t.title },
            g(R.string.d_videos) to "${t.videos.size}", g(R.string.d_total_size) to fmtSize(t.videos.sumOf { it.size })
        )
        val v = t.videos.first()
        return listOfNotNull(
            g(R.string.sort_name) to v.title, g(R.string.d_location) to v.relPath.ifEmpty { v.folder },
            g(R.string.sort_size) to fmtSize(v.size), g(R.string.sort_duration) to fmtTime(v.durationMs),
            if (v.width > 0) g(R.string.d_resolution) to "${v.width} × ${v.height}" else null,
            g(R.string.d_type) to v.mime,
            g(R.string.sort_date) to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.dateAdded * 1000))
        )
    }

    val recentsHeader: @Composable () -> Unit = {
        RecentsRow(recentsShown) { r -> ctx.openPlayer(listOf(PlItem(r.uri, r.title)), 0) }
    }

    val folderHitsHeader: @Composable () -> Unit = {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(stringResource(R.string.nav_folders), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            folderHits.take(5).forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { openFolder = e.key; tab = 0; query = ""; searchOpen = false }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.value.first().folder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.videos_count, e.value.size), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    val onVideoLong: (Video) -> Unit = { v -> dlg = Dlg.Menu(MenuTarget(v.title, listOf(v), false)) }
    val onFolderLong: (List<Video>) -> Unit = { l -> dlg = Dlg.Menu(MenuTarget(l.first().folder, l, true)) }

    if (showSettings) { SettingsScreen { showSettings = false }; return }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Header(searchOpen, query, { query = it },
                    onSearch = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                    onMenu = { showSettings = true }, searchEnabled = tab < 2)
            },
            bottomBar = { NavBar(tab) { tab = it } }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (tab < 2 && granted) {
                    val count = if (tab == 1 || q.isNotEmpty()) stringResource(R.string.videos_count, shown.size)
                    else stringResource(R.string.folders_count, folderCount)
                    Toolbar(count, sortKey, asc, grid,
                        onSort = { sortKey = it; prefs.edit().putInt("sort", it.ordinal).apply() },
                        onOrder = { asc = !asc; prefs.edit().putBoolean("asc", asc).apply() },
                        onGrid = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() })
                }
                Box(Modifier.weight(1f)) {
                    when {
                        tab < 2 && !granted -> Column(Modifier.padding(24.dp)) {
                            Text(stringResource(R.string.grant_text))
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { permLauncher.launch(perm) }) { Text(stringResource(R.string.grant_access)) }
                        }
                        tab < 2 && q.isNotEmpty() -> VideoCollection(shown, grid, onVideoLong,
                            header = if (folderHits.isNotEmpty()) folderHitsHeader else null
                        ) { i -> ctx.openPlayer(shown.map { PlItem(it.uri, it.title) }, i) }
                        tab == 0 -> FoldersScreen(sorted, grid, openFolder, { openFolder = it }, onVideoLong, onFolderLong,
                            header = if (recentsShown.isNotEmpty()) recentsHeader else null)
                        tab == 1 -> VideoCollection(shown, grid, onVideoLong,
                            header = if (recentsShown.isNotEmpty()) recentsHeader else null
                        ) { i -> ctx.openPlayer(shown.map { PlItem(it.uri, it.title) }, i) }
                        tab == 2 -> StreamScreen()
                        else -> PrivateScreen(vaultKey, videos, onAdd = { doLock(it) }, onChanged = { reload++ }, setBusy = { busy = it })
                    }
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
                stringResource(if (d.t.isFolder) R.string.delete_folder_q else R.string.delete_video_q),
                if (d.t.isFolder) stringResource(R.string.delete_folder_msg, d.t.videos.size, d.t.title)
                else stringResource(R.string.delete_video_msg, d.t.title),
                stringResource(R.string.delete),
                onOk = { dlg = null; requestDelete(d.t.videos.map { it.uri }) }, onDismiss = { dlg = null })
            is Dlg.Dest -> {
                val opts = remember(d) { destinations(videos, d.videos.first().relPath) }
                ChoiceDialog(stringResource(if (d.copy) R.string.copy_to else R.string.move_to), opts, -1, onPick = { i ->
                    val dest = opts[i]; dlg = null
                    if (d.copy) scope.launch {
                        busy = ctx.getString(R.string.busy_copying)
                        val ok = withContext(Dispatchers.IO) { d.videos.all { MediaOps.copy(ctx, it, dest) } }
                        busy = null; toast(if (ok) R.string.t_copied else R.string.t_copy_failed); reload++
                    } else requestWrite(d.videos.map { it.uri }) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { d.videos.all { MediaOps.move(ctx, it, dest) } }
                            toast(if (ok) R.string.t_moved else R.string.t_move_failed); reload++
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
fun Header(
    searchOpen: Boolean, query: String, onQuery: (String) -> Unit,
    onSearch: () -> Unit, onMenu: () -> Unit, searchEnabled: Boolean
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C))))
            .statusBarsPadding().height(52.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchOpen) {
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
        } else {
            Image(painterResource(R.drawable.logo), null, Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("OpenPlay", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
            if (searchEnabled) IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = Color.White)
            }
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, stringResource(R.string.menu), tint = Color.White) }
        }
    }
}

@Composable
fun NavBar(selected: Int, onSelect: (Int) -> Unit) {
    val items: List<Pair<Int, ImageVector>> = listOf(
        R.string.nav_folders to Icons.Rounded.Folder, R.string.nav_videos to Icons.Rounded.VideoLibrary,
        R.string.nav_stream to Icons.Rounded.Link, R.string.nav_private to Icons.Rounded.Lock
    )
    NavigationBar {
        items.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = selected == i, onClick = { onSelect(i) },
                icon = { Icon(icon, null) }, label = { Text(stringResource(label), maxLines = 1, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
fun Toolbar(
    count: String, key: SortKey, asc: Boolean, grid: Boolean,
    onSort: (SortKey) -> Unit, onOrder: () -> Unit, onGrid: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Sort, stringResource(R.string.sort)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SortKey.values().forEach { k ->
                    DropdownMenuItem(
                        text = { Text(stringResource(k.label)) },
                        onClick = { onSort(k); menu = false },
                        trailingIcon = { if (k == key) Icon(Icons.Rounded.Check, null) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(if (asc) R.string.order_asc else R.string.order_desc)) },
                    onClick = { onOrder(); menu = false }
                )
            }
        }
        IconButton(onClick = onGrid) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, stringResource(R.string.view_style)) }
    }
}

@Composable
fun Thumb(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) { value = Thumbs.load(ctx, uri) }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2620)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun DurationBadge(ms: Long, modifier: Modifier) {
    Text(
        fmtTime(ms), color = Color.White, fontSize = 11.sp,
        modifier = modifier.padding(5.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
fun RecentsRow(recents: List<Recent>, onOpen: (Recent) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.18f), primary.copy(alpha = 0.06f))))
            .padding(bottom = 12.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.continue_watching), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = primary)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recents, key = { it.uri.toString() }) { r ->
                Column(Modifier.width(170.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(r) }) {
                    Box {
                        Thumb(r.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        val frac = if (r.durMs > 0) (r.posMs.toFloat() / r.durMs).coerceIn(0f, 1f) else 0f
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f)))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp).background(primary))
                    }
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
fun VideoCollection(
    videos: List<Video>, grid: Boolean, onLong: (Video) -> Unit,
    header: (@Composable () -> Unit)? = null, onOpen: (Int) -> Unit
) {
    if (videos.isEmpty()) {
        Column { header?.invoke(); Text(stringResource(R.string.no_videos), Modifier.padding(24.dp)) }
        return
    }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(i) }, onLongClick = { onLong(v) })) {
                    Box {
                        Thumb(v.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                    }
                    Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text(fmtSize(v.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (header != null) item { header() }
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onOpen(i) }, onLongClick = { onLong(v) }).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Thumb(v.uri, Modifier.width(128.dp).aspectRatio(16f / 9f))
                        DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("${v.folder} · ${fmtSize(v.size)}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(
    videos: List<Video>, grid: Boolean, open: String?, onOpen: (String?) -> Unit,
    onVideoLong: (Video) -> Unit, onFolderLong: (List<Video>) -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    val ctx = LocalContext.current
    BackHandler(open != null) { onOpen(null) }
    val groups = remember(videos) { videos.groupBy { it.folderKey }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val current = open
    if (current != null) {
        val list = groups[current].orEmpty()
        Column {
            Row(Modifier.fillMaxWidth().clickable { onOpen(null) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(list.firstOrNull()?.folder ?: current, fontWeight = FontWeight.Bold)
            }
            VideoCollection(list, grid, onVideoLong) { i -> ctx.openPlayer(list.map { PlItem(it.uri, it.title) }, i) }
        }
        return
    }
    val folders = groups.entries.toList()
    if (folders.isEmpty()) {
        Column { header?.invoke(); Text(stringResource(R.string.no_folders), Modifier.padding(24.dp)) }
        return
    }
    if (grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(folders, key = { it.key }) { e ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(e.key) }, onLongClick = { onFolderLong(e.value) })) {
                    Thumb(e.value.first().uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(e.value.first().folder, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    Text(stringResource(R.string.videos_count, e.value.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (header != null) item { header() }
            items(folders, key = { it.key }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onOpen(e.key) }, onLongClick = { onFolderLong(e.value) }).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Thumb(e.value.first().uri, Modifier.width(110.dp).aspectRatio(16f / 9f))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(e.value.first().folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.videos_count, e.value.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
        Text(stringResource(R.string.stream_hint))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { if (url.startsWith("http")) ctx.play(Uri.parse(url.trim())) }) { Text(stringResource(R.string.play)) }
    }
}
