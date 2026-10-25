@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.openplay.player

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Lock
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
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var menu by remember { mutableStateOf<VaultItem?>(null) }
    var details by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }
    var q by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey, tick) { items = withContext(Dispatchers.IO) { Vault.list(ctx) } }
    val shown = remember(items, q) { if (q.isBlank()) items else items.filter { it.name.contains(q.trim(), ignoreCase = true) } }

    fun play(i: Int) {
        Vault.launchingPlayer = true
        ctx.openPlayer(shown.map { PlItem(Uri.fromFile(Vault.file(ctx, it)), it.name) }, i)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.private_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (items.isEmpty()) Text(stringResource(R.string.private_empty), Modifier.padding(24.dp))
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp)
            ) {
                itemsIndexed(shown, key = { _, v -> v.id }) { i, v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .combinedClickable(onClick = { play(i) }, onLongClick = { menu = v }).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(Uri.fromFile(Vault.file(ctx, v)), Modifier.width(128.dp).aspectRatio(16f / 9f))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text("${fmtTime(v.durationMs)} · ${fmtSize(v.size)}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { picker = true },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Rounded.Add, stringResource(R.string.add_private)) }
    }

    if (picker) LibraryPicker(library, onDone = { picker = false; if (it.isNotEmpty()) onAdd(it) }, onDismiss = { picker = false })

    menu?.let { m ->
        ActionSheet(m.name, vaultMenu, onPick = { a ->
            menu = null
            when (a) {
                Act.Play -> play(shown.indexOf(m).coerceAtLeast(0))
                Act.Details -> details = m
                Act.Delete -> confirmDelete = m
                Act.Restore -> scope.launch {
                    setBusy(ctx.getString(R.string.busy_restoring))
                    val ok = withContext(Dispatchers.IO) { Vault.restore(ctx, m) }
                    setBusy(null); tick++; onChanged()
                    Toast.makeText(ctx, ctx.getString(if (ok) R.string.t_restored else R.string.t_restore_failed), Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }, onDismiss = { menu = null })
    }
    details?.let { m ->
        DetailsDialog(listOf(
            ctx.getString(R.string.sort_name) to m.name,
            ctx.getString(R.string.d_original) to m.origPath.ifEmpty { ctx.getString(R.string.unknown) },
            ctx.getString(R.string.sort_size) to fmtSize(m.size),
            ctx.getString(R.string.sort_duration) to fmtTime(m.durationMs),
            ctx.getString(R.string.d_locked_on) to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(m.addedAt)),
            ctx.getString(R.string.d_note) to ctx.getString(R.string.d_note_text)
        )) { details = null }
    }
    confirmDelete?.let { m ->
        ConfirmDialog(stringResource(R.string.delete_video_q), stringResource(R.string.delete_video_msg, m.name), stringResource(R.string.delete),
            onOk = { Vault.remove(ctx, m); confirmDelete = null; tick++ }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun LibraryPicker(library: List<Video>, onDone: (List<Video>) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val list = remember(library, q) { library.filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_videos)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_hint)) })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.uri.toString() }) { v ->
                        val k = v.uri.toString()
                        val on = selected.contains(k)
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) selected.remove(k) else selected.add(k) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (on) selected.remove(k) else selected.add(k) })
                            Thumb(v.uri, Modifier.width(72.dp).aspectRatio(16f / 9f))
                            Spacer(Modifier.width(10.dp))
                            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onDone(library.filter { selected.contains(it.uri.toString()) }) }) {
                Text(stringResource(R.string.move_n_private, selected.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
