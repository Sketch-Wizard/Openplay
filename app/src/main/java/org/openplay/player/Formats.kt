package org.openplay.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object Formats {
    fun nameOf(c: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: ""
            }
        }
        return uri.lastPathSegment ?: ""
    }
}
