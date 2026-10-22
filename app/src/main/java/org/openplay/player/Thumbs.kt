package org.openplay.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)

    suspend fun load(c: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }
        var bmp: Bitmap? = try {
            if (uri.scheme == "content") {
                if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(uri, Size(360, 202), null)
                else @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    c.contentResolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            } else null
        } catch (e: Exception) { null }
        if (bmp == null) bmp = frame(c, uri)
        bmp?.also { cache.put(key, it) }
    }

    private fun frame(c: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(c, uri)
            val f = r.getFrameAtTime(1_000_000) ?: r.getFrameAtTime(0)
            f?.let { Bitmap.createScaledBitmap(it, 360, (360f * it.height / it.width).toInt().coerceAtLeast(1), true) }
        } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) {} }
    }
}
