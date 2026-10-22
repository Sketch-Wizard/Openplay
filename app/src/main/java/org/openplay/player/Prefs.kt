package org.openplay.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun float(c: Context, k: String, d: Float) = sp(c).getFloat(k, d)
    fun setFloat(c: Context, k: String, v: Float) = sp(c).edit().putFloat(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/** 0 = follow system, 1 = light, 2 = dark */
object AppState {
    var theme by mutableIntStateOf(0)
}
