package org.openplay.player

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class VaultItem(
    val id: String, val name: String, val ext: String, val origPath: String,
    val size: Long, val durationMs: Long, val mime: String, val addedAt: Long, val origAbs: String = ""
)

/** Private folder: files live in app-private storage under random IDs; names/paths are kept in an index. */
object Vault {
    @Volatile var launchingPlayer = false

    private fun prefs(c: Context) = c.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun hash(pin: String) = MessageDigest.getInstance("SHA-256")
        .digest("openplay5:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = prefs(c).contains("pin5")
    fun setPin(c: Context, pin: String) = prefs(c).edit().putString("pin5", hash(pin)).apply()
    fun check(c: Context, pin: String) = prefs(c).getString("pin5", null) == hash(pin)

    private fun dir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")
    fun file(c: Context, i: VaultItem) = File(dir(c), "${i.id}.${i.ext}")

    @Synchronized fun list(c: Context): List<VaultItem> = try {
        val arr = JSONArray(index(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            VaultItem(it.getString("id"), it.getString("name"), it.getString("ext"), it.optString("orig"),
                it.optLong("size"), it.optLong("dur"), it.optString("mime", "video/*"), it.optLong("added"), it.optString("abs"))
        }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) { emptyList() }

    @Synchronized private fun save(c: Context, items: List<VaultItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ext", it.ext).put("orig", it.origPath)
                .put("size", it.size).put("dur", it.durationMs).put("mime", it.mime).put("added", it.addedAt).put("abs", it.origAbs))
        }
        index(c).writeText(arr.toString())
    }

    fun add(c: Context, v: Video): VaultItem? {
        val ext = v.title.substringAfterLast('.', "mp4").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }.lowercase()
        val item = VaultItem(UUID.randomUUID().toString(), v.title, ext, v.relPath, v.size, v.durationMs, v.mime, System.currentTimeMillis(), File(v.path).parent ?: "")
        val ok = try {
            c.contentResolver.openInputStream(v.uri)?.use { i -> file(c, item).outputStream().use { o -> i.copyTo(o) } } != null
        } catch (e: Exception) { false }
        if (!ok) { file(c, item).delete(); return null }
        save(c, list(c) + item)
        return item
    }

    fun remove(c: Context, item: VaultItem) {
        file(c, item).delete()
        save(c, list(c).filter { it.id != item.id })
    }

    private fun folderExists(c: Context, rel: String): Boolean {
        if (rel.isBlank()) return false
        if (File(Environment.getExternalStorageDirectory(), rel).isDirectory) return true
        return try {
            c.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
                if (Build.VERSION.SDK_INT >= 29) "${MediaStore.Video.Media.RELATIVE_PATH}=?" else null,
                if (Build.VERSION.SDK_INT >= 29) arrayOf(rel) else null, null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
    }

    /** Puts the video back in its original folder, or in Movies/Restored/ if that folder no longer exists. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /** With "All files access": write straight back into the original folder (Movies/Restored only if it is gone). */
    private fun restoreDirect(c: Context, item: VaultItem, f: File): Boolean {
        return try {
            val root = Environment.getExternalStorageDirectory()
            val orig = item.origAbs.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: item.origPath.takeIf { it.isNotBlank() }?.let { File(root, it) }
            val dest = if (orig != null && orig.isDirectory) orig else File(root, "Movies/Restored")
            dest.mkdirs()
            val target = uniqueFile(dest, item.name)
            f.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            MediaScannerConnection.scanFile(c, arrayOf(target.absolutePath), arrayOf(item.mime), null)
            remove(c, item)
            true
        } catch (e: Exception) { false }
    }

    fun restore(c: Context, item: VaultItem): Boolean {
        val f = file(c, item)
        if (!f.exists()) return false
        if (AllFiles.granted(c) && restoreDirect(c, item, f)) return true
        val targets = listOfNotNull(item.origPath.takeIf { folderExists(c, it) }, "Movies/Restored/")
        for (rel in targets) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, item.name)
                    put(MediaStore.Video.Media.MIME_TYPE, item.mime)
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, rel)
                }
                val uri = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: continue
                try {
                    f.inputStream().use { i -> c.contentResolver.openOutputStream(uri)!!.use { o -> i.copyTo(o) } }
                } catch (e: Exception) { c.contentResolver.delete(uri, null, null); continue }
                remove(c, item)
                return true
            } catch (e: Exception) { /* try the next target */ }
        }
        return false
    }
}
