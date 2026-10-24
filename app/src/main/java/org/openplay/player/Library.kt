package org.openplay.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Video(
    val uri: Uri, val title: String, val durationMs: Long, val folder: String, val folderKey: String,
    val relPath: String, val size: Long, val dateAdded: Long, val mime: String, val width: Int, val height: Int
)

enum class SortKey(val label: Int) {
    NAME(R.string.sort_name), DATE(R.string.sort_date), SIZE(R.string.sort_size), DURATION(R.string.sort_duration)
}

fun List<Video>.sortVideos(key: SortKey, asc: Boolean): List<Video> {
    val c: Comparator<Video> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortKey.DATE -> compareBy<Video> { it.dateAdded }
        SortKey.SIZE -> compareBy<Video> { it.size }
        SortKey.DURATION -> compareBy<Video> { it.durationMs }
    }
    return sortedWith(if (asc) c else c.reversed())
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= (1L shl 20) -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

fun queryVideos(c: Context): List<Video> {
    val out = mutableListOf<Video>()
    val m = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val proj = mutableListOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT
    )
    val hasRel = Build.VERSION.SDK_INT >= 29
    if (hasRel) proj += MediaStore.Video.Media.RELATIVE_PATH
    c.contentResolver.query(m, proj.toTypedArray(), null, null, null)?.use { q ->
        while (q.moveToNext()) {
            val bucket = q.getString(3) ?: "Other"
            val rel = if (hasRel) q.getString(9) ?: "" else ""
            val display = rel.trimEnd('/').substringAfterLast('/').ifEmpty { bucket }
            out += Video(
                ContentUris.withAppendedId(m, q.getLong(0)), q.getString(1) ?: "Unknown", q.getLong(2),
                display, rel.ifEmpty { bucket }, rel, q.getLong(4), q.getLong(5),
                q.getString(6) ?: "video/*", q.getInt(7), q.getInt(8)
            )
        }
    }
    return out
}

/** Folders a video can be moved/copied to (Android only allows Movies/ and DCIM/ for videos). */
fun destinations(all: List<Video>, exclude: String): List<String> =
    (all.map { it.relPath } + listOf("Movies/", "DCIM/", "Movies/OpenPlay/"))
        .filter { it.isNotEmpty() && (it.startsWith("Movies/") || it.startsWith("DCIM/")) && it != exclude }
        .distinct().sorted()

object MediaOps {
    fun rename(c: Context, v: Video, fullName: String): Boolean = try {
        c.contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, fullName) }, null, null) > 0
    } catch (e: Exception) { false }

    fun move(c: Context, v: Video, dest: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            c.contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.RELATIVE_PATH, dest) }, null, null) > 0
        } catch (e: Exception) { false }
    }

    fun copy(c: Context, v: Video, dest: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, v.title)
                put(MediaStore.Video.Media.MIME_TYPE, v.mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, dest)
            }
            val out = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            c.contentResolver.openInputStream(v.uri)!!.use { i ->
                c.contentResolver.openOutputStream(out)!!.use { o -> i.copyTo(o) }
            }
            true
        } catch (e: Exception) { false }
    }

    fun share(c: Context, v: Video) {
        val i = Intent(Intent.ACTION_SEND).setType(v.mime)
            .putExtra(Intent.EXTRA_STREAM, v.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Share video"))
    }
}
