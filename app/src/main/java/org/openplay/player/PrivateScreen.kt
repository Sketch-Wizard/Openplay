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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
fun PrivateScreen(refreshKey: Int, onChanged: () -> Unit, setBusy: (String?) -> Unit) {
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
    if (!unlocked) PinScreen { unlocked = true } else VaultList(refreshKey, onChanged, setBusy)
}

@Composable
private fun PinScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val creating = remember { !Vault.hasPin(ctx) }
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    val title = when {
        !creating -> "Enter PIN"
        first == null -> "Create a 5-digit PIN"
        else -> "Confirm your PIN"
    }

    fun press(d: String) {
        if (entry.length >= 5) return
        entry += d; msg = ""
        if (entry.length < 5) return
        val p = entry; entry = ""
        when {
            !creating -> if (Vault.check(ctx, p)) onUnlocked() else msg = "Wrong PIN"
            first == null -> first = p
            p == first -> { Vault.setPin(ctx, p); onUnlocked() }
            else -> { msg = "PINs didn't match. Try again."; first = null }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
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
                "There is no PIN recovery. If you forget it, your private videos can't be opened. " +
                    "They are stored inside the app and are deleted if the app is uninstalled.",
                fontSize = 12.sp, textAlign = TextAlign.Center,
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
                            .background(if (k.isEmpty()) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable(enabled = k.isNotEmpty()) { if (k == "<") { entry = entry.dropLast(1) } else press(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k == "<") Icon(Icons.Rounded.Backspace, null)
                        else Text(k, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultList(refreshKey: Int, onChanged: () -> Unit, setBusy: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var menu by remember { mutableStateOf<VaultItem?>(null) }
    var details by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }

    LaunchedEffect(refreshKey, tick) { items = withContext(Dispatchers.IO) { Vault.list(ctx) } }

    fun play(i: Int) {
        Vault.launchingPlayer = true
        ctx.openPlayer(items.map { PlItem(Uri.fromFile(Vault.file(ctx, it)), it.name) }, i)
    }

    if (items.isEmpty()) {
        Text("Your private folder is empty.\nPress and hold a video, then choose \"Move to private\".", Modifier.padding(24.dp))
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { play(i) }, onLongClick = { menu = it }).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Thumb(Uri.fromFile(Vault.file(ctx, it)), Modifier.width(132.dp).aspectRatio(16f / 9f))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text("${fmtTime(it.durationMs)} · ${fmtSize(it.size)}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }

    menu?.let { m ->
        ActionSheet(m.name, vaultMenu, onPick = { a ->
            menu = null
            when (a) {
                Act.Play -> play(items.indexOf(m).coerceAtLeast(0))
                Act.Details -> details = m
                Act.Delete -> confirmDelete = m
                Act.Restore -> scope.launch {
                    setBusy("Restoring…")
                    val ok = withContext(Dispatchers.IO) { Vault.restore(ctx, m) }
                    setBusy(null); tick++; onChanged()
                    Toast.makeText(ctx, if (ok) "Video restored to your library" else "Could not restore this video", Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }, onDismiss = { menu = null })
    }
    details?.let { m ->
        DetailsDialog(listOf(
            "Name" to m.name, "Original folder" to m.origPath.ifEmpty { "Unknown" },
            "Size" to fmtSize(m.size), "Duration" to fmtTime(m.durationMs),
            "Locked on" to java.text.DateFormat.getDateTimeInstance().format(java.util.Date(m.addedAt)),
            "Note" to "Names can't be changed while a video is private."
        )) { details = null }
    }
    confirmDelete?.let { m ->
        ConfirmDialog("Delete video?", "\"${m.name}\" will be permanently deleted.", "Delete",
            onOk = { Vault.remove(ctx, m); confirmDelete = null; tick++ }, onDismiss = { confirmDelete = null })
    }
}
