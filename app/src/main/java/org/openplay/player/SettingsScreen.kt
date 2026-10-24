package org.openplay.player

import android.app.Activity
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val REPO = "https://github.com/Sketch-Wizard/Openplay"
private const val VERSION = "0.6.0"
private val speedValues = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    var dialog by remember { mutableStateOf<String?>(null) }
    var orient by remember { mutableIntStateOf(Prefs.int(ctx, "orient", 0)) }
    var speed by remember { mutableFloatStateOf(Prefs.float(ctx, "speed", 1f)) }
    var resize by remember { mutableIntStateOf(Prefs.int(ctx, "resize", 0)) }
    var decoder by remember { mutableIntStateOf(Prefs.int(ctx, "decoder", 1)) }
    var bg by remember { mutableStateOf(Prefs.bool(ctx, "bg", false)) }
    var resume by remember { mutableStateOf(Prefs.bool(ctx, "resume", true)) }
    var lang by remember { mutableStateOf(Prefs.str(ctx, "lang", "")) }
    val themes = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).map { stringResource(it) }
    val orients = listOf(R.string.orient_auto, R.string.orient_landscape, R.string.orient_portrait).map { stringResource(it) }
    val resizes = listOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom).map { stringResource(it) }
    val decoders = listOf(R.string.dec_hw, R.string.dec_hwplus, R.string.dec_sw).map { stringResource(it) }
    val sysDefault = stringResource(R.string.system_default)
    val langName = Locales.all.firstOrNull { it.first == lang }?.second ?: sysDefault

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF9800), Color(0xFFE8590C))))
                .statusBarsPadding().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Text(stringResource(R.string.settings), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.appearance))
            ValueRow(Icons.Rounded.Palette, stringResource(R.string.theme), themes[AppState.theme]) { dialog = "theme" }
            ValueRow(Icons.Rounded.Language, stringResource(R.string.language), langName) { dialog = "lang" }
            Section(stringResource(R.string.playback_defaults))
            ValueRow(Icons.Rounded.Memory, stringResource(R.string.decoder), decoders[decoder].substringBefore(" ")) { dialog = "decoder" }
            Text(
                stringResource(if (Decoders.available(ctx)) R.string.dec_status_ok else R.string.dec_status_missing),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 56.dp, bottom = 6.dp)
            )
            ValueRow(Icons.Rounded.ScreenRotation, stringResource(R.string.video_orientation), orients[orient]) { dialog = "orient" }
            ValueRow(Icons.Rounded.Speed, stringResource(R.string.playback_speed), "${speed}x") { dialog = "speed" }
            ValueRow(Icons.Rounded.AspectRatio, stringResource(R.string.aspect_ratio), resizes[resize]) { dialog = "resize" }
            SwitchRow(Icons.Rounded.Headphones, stringResource(R.string.bg_play), stringResource(R.string.bg_play_sub), bg) {
                bg = it; Prefs.setBool(ctx, "bg", it)
            }
            SwitchRow(Icons.Rounded.History, stringResource(R.string.resume), stringResource(R.string.resume_sub), resume) {
                resume = it; Prefs.setBool(ctx, "resume", it)
            }
            ValueRow(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_history), "") {
                Recents.clear(ctx); Toast.makeText(ctx, ctx.getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
            }
            Text(
                stringResource(R.string.settings_tip), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Section(stringResource(R.string.help))
            ValueRow(Icons.Rounded.Feedback, stringResource(R.string.feedback), stringResource(R.string.feedback_sub)) {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO/issues"))) }
                catch (e: Exception) { Toast.makeText(ctx, ctx.getString(R.string.no_browser), Toast.LENGTH_SHORT).show() }
            }
            ValueRow(Icons.Rounded.Info, stringResource(R.string.about), stringResource(R.string.version, VERSION)) { dialog = "about" }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(stringResource(R.string.theme), themes, AppState.theme, {
            AppState.theme = it; Prefs.setInt(ctx, "theme", it); dialog = null
        }, { dialog = null })
        "lang" -> ChoiceDialog(
            stringResource(R.string.language), listOf(sysDefault) + Locales.all.map { it.second },
            if (lang.isEmpty()) 0 else Locales.all.indexOfFirst { it.first == lang } + 1,
            { i ->
                val tag = if (i == 0) "" else Locales.all[i - 1].first
                dialog = null
                if (tag != lang) { lang = tag; Prefs.setStr(ctx, "lang", tag); (ctx as? Activity)?.recreate() }
            }, { dialog = null })
        "decoder" -> ChoiceDialog(stringResource(R.string.decoder), decoders, decoder, {
            decoder = it; Prefs.setInt(ctx, "decoder", it); dialog = null
        }, { dialog = null })
        "orient" -> ChoiceDialog(stringResource(R.string.video_orientation), orients, orient, {
            orient = it; Prefs.setInt(ctx, "orient", it); dialog = null
        }, { dialog = null })
        "speed" -> ChoiceDialog(stringResource(R.string.playback_speed), speedValues.map { "${it}x" }, speedValues.indexOf(speed), {
            speed = speedValues[it]; Prefs.setFloat(ctx, "speed", speed); dialog = null
        }, { dialog = null })
        "resize" -> ChoiceDialog(stringResource(R.string.aspect_ratio), resizes, resize, {
            resize = it; Prefs.setInt(ctx, "resize", it); dialog = null
        }, { dialog = null })
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp)); Text("OpenPlay $VERSION")
                }
            },
            text = { Text(stringResource(R.string.about_text)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } }
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
