@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.openplay.player

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrivateScreen(refreshKey: Int, library: List<Video>, onAdd: (List<Video>) -> Unit, onChanged: () -> Unit, setBusy: (String?) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    var unlocked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !Vault.launchingPlayer) unlocked = false
            if (e == Lifecycle.Event.ON_START) Vault.launchingPlayer = false
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }
    if (!unlocked) PinScreen { unlocked = true } else VaultList(refreshKey, library, onAdd, onChanged, setBusy)
}

@Composable
private fun PinScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val creating = remember { !Vault.hasPin(ctx) }
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    val title = stringResource(
        when {
            !creating -> R.string.pin_enter
            first == null -> R.string.pin_create
            else -> R.string.pin_confirm
        }
    )

    fun press(d: String) {
        if (entry.length >= 5) return
        entry += d; msg = ""
        if (entry.length < 5) return
        val p = entry; entry = ""
        when {
            !creating -> if (Vault.check(ctx, p)) onUnlocked() else msg = ctx.getString(R.string.pin_wrong)
            first == null -> first = p
            p == first -> { Vault.setPin(ctx, p); onUnlocked() }
            else -> { msg = ctx.getString(R.string.pin_mismatch); first = null }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 20.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < entry.length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        if (creating) {
            Text(
                stringResource(R.string.pin_warning), fontSize = 12.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (k.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable(enabled = k.isNotEmpty()) { if (k == "<") { entry = entry.dropLast(1) } else press(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k == "<") Icon(Icons.Rounded.Backspace, null) else Text(k, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultList(
    refreshKey: Int, library: List<Video>, onAdd: (List<Video>) -> Unit,
    onChanged: () -> Unit, setBusy: (String?) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember { ctx.getSharedPreferences("private_ui", Context.MODE_PRIVATE) }   // separate from the main app's sort/view
    var sortKey by remember { mutableStateOf(SortKey.values().getOrElse(ui.getInt("sort", 1)) { SortKey.DATE }) }
    var asc by remember { mutableStateOf(ui.getBoolean("asc", false)) }
    var grid by remember { mutableStateOf(ui.getBoolean("grid", false)) }
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var menu by remember { mutableStateOf<VaultItem?>(null) }
    var details by remember { mutableStateOf<List<VaultItem>?>(null) }
    var confirmDelete by remember { mutableStateOf<List<VaultItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    LaunchedEffect(refreshKey, tick) { items = withContext(Dispatchers.IO) { Vault.list(ctx) } }
    val shown = remember(items, q, sortKey, asc) {
        val f = if (q.isBlank()) items else items.filter { it.name.contains(q.trim(), ignoreCase = true) }
        val c: Comparator<VaultItem> = when (sortKey) {
            SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortKey.DATE -> compareBy<VaultItem> { it.addedAt }
            SortKey.SIZE -> compareBy<VaultItem> { it.size }
            SortKey.DURATION -> compareBy<VaultItem> { it.durationMs }
        }
        f.sortedWith(if (asc) c else c.reversed())
    }
    val allSelected = shown.isNotEmpty() && selected.containsAll(shown.map { it.id })

    fun exitSelect() { selectMode = false; selected.clear() }
    fun toggle(id: String) { if (!selected.remove(id)) selected.add(id) }
    fun play(i: Int) {
        Vault.launchingPlayer = true
        ctx.openPlayer(shown.map { PlItem(Uri.fromFile(Vault.file(ctx, it)), it.name) }, i)
    }
    fun restoreItems(list: List<VaultItem>) {
        scope.launch {
            setBusy(ctx.getString(R.string.busy_restoring))
            val ok = withContext(Dispatchers.IO) { list.count { Vault.restore(ctx, it) } }
            setBusy(null); exitSelect(); tick++; onChanged()
            Toast.makeText(ctx, ctx.getString(if (ok == list.size) R.string.t_restored else R.string.t_restore_failed), Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                selectMode -> Row(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C)))).height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { exitSelect() }) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
                    Text(stringResource(R.string.n_selected, selected.size), color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (allSelected) selected.removeAll(shown.map { it.id }) else shown.forEach { if (!selected.contains(it.id)) selected.add(it.id) } }) {
                        Icon(if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                            stringResource(if (allSelected) R.string.unselect_all else R.string.select_all), tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { more = true }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            listOf(Act.Restore, Act.Details, Act.Delete).mapNotNull { a -> vaultMenu.firstOrNull { it.first == a } }
                                .forEach { (act, icon, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) },
                                        onClick = {
                                            more = false
                                            val sel = items.filter { selected.contains(it.id) }
                                            when (act) {
                                                Act.Restore -> restoreItems(sel)
                                                Act.Details -> details = sel
                                                Act.Delete -> confirmDelete = sel
                                                else -> {}
                                            }
                                        }
                                    )
                                }
                        }
                    }
                }
                searching -> Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    IconButton(onClick = { searching = false; q = "" }) { Icon(Icons.Rounded.ArrowBack, null) }
                    BasicTextField(
                        value = q, onValueChange = { q = it }, singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (q.isEmpty()) Text(stringResource(R.string.private_search_hint), fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                inner()
                            }
                        }
                    )
                    if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, null) }
                }
                else -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.videos_count, shown.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, stringResource(R.string.search)) }
                    IconButton(onClick = { selectMode = true; selected.clear() }) { Icon(Icons.Rounded.CheckBox, stringResource(R.string.select)) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, stringResource(R.string.sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortKey.values().forEach { k ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(k.label)) },
                                    onClick = { sortKey = k; ui.edit().putInt("sort", k.ordinal).apply(); sortMenu = false },
                                    trailingIcon = { if (k == sortKey) Icon(Icons.Rounded.Check, null) }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(if (asc) R.string.order_asc else R.string.order_desc)) },
                                onClick = { asc = !asc; ui.edit().putBoolean("asc", asc).apply(); sortMenu = false }
                            )
                        }
                    }
                    IconButton(onClick = { grid = !grid; ui.edit().putBoolean("grid", grid).apply() }) {
                        Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, stringResource(R.string.view_style))
                    }
                }
            }

            if (items.isEmpty()) Text(stringResource(R.string.private_empty), Modifier.padding(24.dp))
            Box(Modifier.weight(1f)) {
                if (grid) {
                    LazyVerticalGrid(
                        GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        gridItemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                            val isSel = selected.contains(v.id)
                            Column(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(if (isSel) tint else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { if (selectMode) toggle(v.id) else play(i) },
                                        onLongClick = { if (selectMode) toggle(v.id) else menu = v }
                                    ).padding(4.dp)
                            ) {
                                Box {
                                    Thumb(Uri.fromFile(Vault.file(ctx, v)), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                    DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                                    if (isSel) SelectOverlay(Modifier.matchParentSize())
                                }
                                Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                                Text(fmtSize(v.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp)) {
                        itemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                            val isSel = selected.contains(v.id)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (isSel) tint else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { if (selectMode) toggle(v.id) else play(i) },
                                        onLongClick = { if (selectMode) toggle(v.id) else menu = v }
                                    ).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box {
                                    Thumb(Uri.fromFile(Vault.file(ctx, v)), Modifier.width(128.dp).aspectRatio(16f / 9f))
                                    DurationBadge(v.durationMs, Modifier.align(Alignment.BottomEnd))
                                    if (isSel) SelectOverlay(Modifier.matchParentSize())
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(fmtSize(v.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!selectMode) {
            FloatingActionButton(
                onClick = { picker = true },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.add_private)) }
        }
    }

    if (picker) PrivatePicker(library, onDone = { picker = false; if (it.isNotEmpty()) onAdd(it) }, onClose = { picker = false })

    menu?.let { m ->
        ActionSheet(m.name, vaultMenu, onPick = { a ->
            menu = null
            when (a) {
                Act.Play -> play(shown.indexOf(m).coerceAtLeast(0))
                Act.Select -> { selectMode = true; selected.clear(); selected.add(m.id) }
                Act.Details -> details = listOf(m)
                Act.Delete -> confirmDelete = listOf(m)
                Act.Restore -> restoreItems(listOf(m))
                else -> {}
            }
        }, onDismiss = { menu = null })
    }
    details?.let { list ->
        val g = { id: Int -> ctx.getString(id) }
        val rows = if (list.size == 1) {
            val m = list.first()
            listOf(
                g(R.string.sort_name) to m.name,
                g(R.string.d_original) to (m.origAbs.ifEmpty { m.origPath }.ifEmpty { g(R.string.unknown) }),
                g(R.string.sort_size) to fmtSize(m.size), g(R.string.sort_duration) to fmtTime(m.durationMs),
                g(R.string.d_locked_on) to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(m.addedAt)),
                g(R.string.d_note) to g(R.string.d_note_text)
            )
        } else listOf(
            g(R.string.sort_name) to ctx.getString(R.string.videos_count, list.size),
            g(R.string.d_total_size) to fmtSize(list.sumOf { it.size }),
            g(R.string.sort_duration) to fmtTime(list.sumOf { it.durationMs })
        )
        DetailsDialog(rows) { details = null }
    }
    confirmDelete?.let { list ->
        val multi = list.size > 1
        ConfirmDialog(
            stringResource(if (multi) R.string.delete else R.string.delete_video_q),
            stringResource(R.string.delete_video_msg, if (multi) stringResource(R.string.videos_count, list.size) else list.first().name),
            stringResource(R.string.delete),
            onOk = { list.forEach { Vault.remove(ctx, it) }; confirmDelete = null; exitSelect(); tick++ },
            onDismiss = { confirmDelete = null }
        )
    }
}

/** Full-screen picker: browse folders like the main page, tick videos, move them to the private folder. */
@Composable
private fun PrivatePicker(library: List<Video>, onDone: (List<Video>) -> Unit, onClose: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val sorted = remember(library) { library.sortVideos(SortKey.NAME, true) }
    val sel = SelectionState(true, selected.toSet()) { k -> if (!selected.remove(k)) selected.add(k) }
    val folderKeys = remember(sorted, open) { sorted.filter { it.folderKey == open }.map { it.uri.toString() } }
    val allInFolder = folderKeys.isNotEmpty() && selected.containsAll(folderKeys)
    Dialog(
        onDismissRequest = { if (open != null) open = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C))))
                        .statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
                    Text(stringResource(R.string.select_videos), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    if (open != null) IconButton(onClick = {
                        if (allInFolder) selected.removeAll(folderKeys) else folderKeys.forEach { if (!selected.contains(it)) selected.add(it) }
                    }) {
                        Icon(if (allInFolder) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                            stringResource(R.string.select_all), tint = Color.White)
                    }
                }
                Box(Modifier.weight(1f)) {
                    FoldersScreen(sorted, false, open, { open = it }, {}, {}, null, sel, folderSelectable = false)
                }
                Button(
                    onClick = { onDone(sorted.filter { selected.contains(it.uri.toString()) }) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                ) { Text(stringResource(R.string.move_n_private, selected.size)) }
            }
        }
    }
}
