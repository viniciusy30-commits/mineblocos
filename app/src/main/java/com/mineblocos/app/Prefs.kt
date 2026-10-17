package com.mineblocos.app

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun sens(c: Context) = p(c).getInt("sens", 5)
    fun setSens(c: Context, v: Int) = p(c).edit().putInt("sens", v).apply()
    fun sensRad(c: Context) = 0.0015f + sens(c) * 0.0007f

    fun dist(c: Context) = p(c).getInt("dist", 6)
    fun setDist(c: Context, v: Int) = p(c).edit().putInt("dist", v).apply()

    fun showFps(c: Context) = p(c).getBoolean("fps", false)
    fun setShowFps(c: Context, v: Boolean) = p(c).edit().putBoolean("fps", v).apply()

    fun hotbar(c: Context): IntArray? {
        val s = p(c).getString("hotbar", null) ?: return null
        val v = s.split(',').mapNotNull { it.toIntOrNull() }
        return if (v.size == 10) v.toIntArray() else null
    }
    fun setHotbar(c: Context, v: IntArray) = p(c).edit().putString("hotbar", v.joinToString(",")).apply()
}
