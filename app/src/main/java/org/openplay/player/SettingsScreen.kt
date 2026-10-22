package org.openplay.player

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val REPO = "https://github.com/Sketch-Wizard/Openplay"
private val speedValues = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var dialog by remember { mutableStateOf<String?>(null) }
    var orient by remember { mutableIntStateOf(Prefs.int(ctx, "orient", 0)) }
    var speed by remember { mutableFloatStateOf(Prefs.float(ctx, "speed", 1f)) }
    var resize by remember { mutableIntStateOf(Prefs.int(ctx, "resize", 0)) }
    var bg by remember { mutableStateOf(Prefs.bool(ctx, "bg", false)) }
    var resume by remember { mutableStateOf(Prefs.bool(ctx, "resume", true)) }
    val themes = listOf("Follow system", "Light", "Dark")
    val orients = listOf("Auto", "Landscape", "Portrait")
    val resizes = listOf("Fit", "Fill", "Zoom")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C)))).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("Appearance")
            ValueRow(Icons.Rounded.Palette, "Theme", themes[AppState.theme]) { dialog = "theme" }
            ValueRow(Icons.Rounded.Language, "Language", "English") { dialog = "lang" }
            Section("Playback defaults")
            ValueRow(Icons.Rounded.ScreenRotation, "Video orientation", orients[orient]) { dialog = "orient" }
            ValueRow(Icons.Rounded.Speed, "Playback speed", "${speed}x") { dialog = "speed" }
            ValueRow(Icons.Rounded.AspectRatio, "Aspect ratio", resizes[resize]) { dialog = "resize" }
            SwitchRow(Icons.Rounded.Headphones, "Background play", "Keep playing when you leave the app", bg) {
                bg = it; Prefs.setBool(ctx, "bg", it)
            }
            SwitchRow(Icons.Rounded.History, "Resume playback", "Continue videos where you left off", resume) {
                resume = it; Prefs.setBool(ctx, "resume", it)
            }
            Text(
                "Tip: in the player, open the options panel and tap \"Save as default\" to use the current settings for every video.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Section("Help")
            ValueRow(Icons.Rounded.Feedback, "Send feedback", "Opens GitHub issues") {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO/issues"))) }
                catch (e: Exception) { Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show() }
            }
            ValueRow(Icons.Rounded.Info, "About OpenPlay", "Version 0.5.0") { dialog = "about" }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog("Theme", themes, AppState.theme, {
            AppState.theme = it; Prefs.setInt(ctx, "theme", it); dialog = null
        }, { dialog = null })
        "lang" -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text("Language") },
            text = { Text("OpenPlay is available in English for now. More languages can be added by contributors on GitHub.") },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } }
        )
        "orient" -> ChoiceDialog("Video orientation", orients, orient, {
            orient = it; Prefs.setInt(ctx, "orient", it); dialog = null
        }, { dialog = null })
        "speed" -> ChoiceDialog("Playback speed", speedValues.map { "${it}x" }, speedValues.indexOf(speed), {
            speed = speedValues[it]; Prefs.setFloat(ctx, "speed", speed); dialog = null
        }, { dialog = null })
        "resize" -> ChoiceDialog("Aspect ratio", resizes, resize, {
            resize = it; Prefs.setInt(ctx, "resize", it); dialog = null
        }, { dialog = null })
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp)); Text("OpenPlay")
                }
            },
            text = {
                Text(
                    "Version 0.5.0\n\nA free, open-source, ad-free video player.\n\n" +
                        "Built with Jetpack Media3 (Apache-2.0), libVLC (LGPL-2.1+) and Jetpack Compose.\n\n" +
                        "OpenPlay is licensed under the MIT License.\n$REPO"
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), fontSize = 16.sp)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
