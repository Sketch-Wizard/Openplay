package org.openplay.player

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class Act { Rename, Details, Share, Private, Move, Copy, Delete, Restore, Play, Select }

data class MenuTarget(val title: String, val videos: List<Video>, val isFolder: Boolean, val multi: Boolean = false)

sealed interface Dlg {
    data class Menu(val t: MenuTarget) : Dlg
    data class Rename(val t: MenuTarget) : Dlg
    data class Details(val t: MenuTarget) : Dlg
    data class Delete(val t: MenuTarget) : Dlg
    data class Dest(val videos: List<Video>, val copy: Boolean) : Dlg
}

class Pending(val then: (() -> Unit)?, val denied: (() -> Unit)?)

typealias MenuEntry = Triple<Act, ImageVector, Int>

val videoMenu: List<MenuEntry> = listOf(
    Triple(Act.Select, Icons.Rounded.CheckBox, R.string.select),
    Triple(Act.Rename, Icons.Rounded.Edit, R.string.rename),
    Triple(Act.Details, Icons.Rounded.Info, R.string.details),
    Triple(Act.Share, Icons.Rounded.Share, R.string.share),
    Triple(Act.Move, Icons.Rounded.FolderOpen, R.string.move_to),
    Triple(Act.Copy, Icons.Rounded.ContentCopy, R.string.copy_to),
    Triple(Act.Private, Icons.Rounded.Lock, R.string.move_private),
    Triple(Act.Delete, Icons.Rounded.Delete, R.string.delete)
)

val folderMenu: List<MenuEntry> = listOf(
    Triple(Act.Select, Icons.Rounded.CheckBox, R.string.select),
    Triple(Act.Rename, Icons.Rounded.Edit, R.string.rename_folder),
    Triple(Act.Details, Icons.Rounded.Info, R.string.details),
    Triple(Act.Private, Icons.Rounded.Lock, R.string.move_all_private),
    Triple(Act.Delete, Icons.Rounded.Delete, R.string.delete_folder)
)

val vaultMenu: List<MenuEntry> = listOf(
    Triple(Act.Play, Icons.Rounded.PlayArrow, R.string.play),
    Triple(Act.Restore, Icons.Rounded.LockOpen, R.string.remove_private),
    Triple(Act.Details, Icons.Rounded.Info, R.string.details),
    Triple(Act.Delete, Icons.Rounded.Delete, R.string.delete)
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(act) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(label))
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
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun DetailsDialog(rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.details)) },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
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
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
