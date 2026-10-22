package org.openplay.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class Act { Rename, Details, Share, Private, Move, Copy, Delete, Restore, Play }

data class MenuTarget(val title: String, val videos: List<Video>, val isFolder: Boolean)

sealed interface Dlg {
    data class Menu(val t: MenuTarget) : Dlg
    data class Rename(val t: MenuTarget) : Dlg
    data class Details(val t: MenuTarget) : Dlg
    data class Delete(val t: MenuTarget) : Dlg
    data class Dest(val videos: List<Video>, val copy: Boolean) : Dlg
}

class Pending(val then: (() -> Unit)?, val denied: (() -> Unit)?)

typealias MenuEntry = Triple<Act, ImageVector, String>

val videoMenu: List<MenuEntry> = listOf(
    Triple(Act.Rename, Icons.Rounded.Edit, "Rename"),
    Triple(Act.Details, Icons.Rounded.Info, "Details"),
    Triple(Act.Share, Icons.Rounded.Share, "Share"),
    Triple(Act.Move, Icons.Rounded.FolderOpen, "Move to…"),
    Triple(Act.Copy, Icons.Rounded.ContentCopy, "Copy to…"),
    Triple(Act.Private, Icons.Rounded.Lock, "Move to private"),
    Triple(Act.Delete, Icons.Rounded.Delete, "Delete")
)

val folderMenu: List<MenuEntry> = listOf(
    Triple(Act.Rename, Icons.Rounded.Edit, "Rename folder"),
    Triple(Act.Details, Icons.Rounded.Info, "Details"),
    Triple(Act.Private, Icons.Rounded.Lock, "Move all to private"),
    Triple(Act.Delete, Icons.Rounded.Delete, "Delete folder")
)

val vaultMenu: List<MenuEntry> = listOf(
    Triple(Act.Play, Icons.Rounded.PlayArrow, "Play"),
    Triple(Act.Restore, Icons.Rounded.LockOpen, "Remove from private"),
    Triple(Act.Details, Icons.Rounded.Info, "Details"),
    Triple(Act.Delete, Icons.Rounded.Delete, "Delete")
)

@Composable
fun ActionSheet(title: String, actions: List<MenuEntry>, onPick: (Act) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = {},
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                actions.forEach { (act, icon, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(act) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null)
                        Spacer(Modifier.width(16.dp))
                        Text(label)
                    }
                }
            }
        }
    )
}

@Composable
fun RenameDialog(initial: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DetailsDialog(rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text(v)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** selected < 0 hides the radio buttons (plain list). */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            LazyColumn {
                itemsIndexed(options) { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected >= 0) RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(o, Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
