package org.openplay.player

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** "All files access" (Android 11+): lets OpenPlay move videos into the private folder and back without popups. */
object AllFiles {
    fun supported() = Build.VERSION.SDK_INT >= 30
    fun granted(c: Context) = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun settingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))

    /** Deletes the real file and its library entry. Returns true when the file is gone. */
    fun delete(c: Context, v: Video): Boolean {
        var ok = false
        if (v.path.isNotBlank()) ok = try { File(v.path).let { !it.exists() || it.delete() } } catch (e: Exception) { false }
        try { c.contentResolver.delete(v.uri, null, null) } catch (e: Exception) { /* library entry may already be gone */ }
        if (v.path.isNotBlank()) MediaScannerConnection.scanFile(c, arrayOf(v.path), null, null)
        return ok
    }
}
