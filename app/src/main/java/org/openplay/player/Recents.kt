package org.openplay.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: Uri, val title: String, val posMs: Long, val durMs: Long, val source: String = "all")

/** Last played videos (private-folder and network videos are never recorded). */
object Recents {
    private fun sp(c: Context) = c.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(c: Context): List<Recent> = try {
        val a = JSONArray(sp(c).getString("l", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Recent(Uri.parse(it.getString("u")), it.getString("t"), it.optLong("p"), it.optLong("d"), it.optString("s", "all"))
        }
    } catch (e: Exception) { emptyList() }

    fun record(c: Context, uri: Uri, title: String, pos: Long, dur: Long, source: String = "all") {
        if (uri.scheme != "content") return
        val all = (listOf(Recent(uri, title, pos, dur, source)) + list(c).filter { it.uri != uri }).take(10)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("t", it.title).put("p", it.posMs).put("d", it.durMs).put("s", it.source)) }
        sp(c).edit().putString("l", a.toString()).apply()
    }

    fun clear(c: Context) = sp(c).edit().remove("l").apply()
}
