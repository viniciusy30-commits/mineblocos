package com.mineblocos.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils

/** Texturas animadas do addon (flipbook): troca o quadro direto no atlas da GPU. */
object Anim3D {
    private class Anim(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val ticks: Int, val n: Int, val ox: Int, val oy: Int
    ) { var cur = -1 }

    private val list = ArrayList<Anim>()
    private var sheet: Bitmap? = null

    @Synchronized
    fun load(c: Context) {
        if (list.isNotEmpty()) return
        try {
            c.assets.open("anim3d.txt").bufferedReader().forEachLine { l ->
                val p = l.trim().split(' ')
                if (p.size >= 8) list.add(Anim(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), maxOf(1, p[4].toInt()), p[5].toInt(), p[6].toInt(), p[7].toInt()))
            }
            if (list.isNotEmpty()) c.assets.open("anim3d.png").use { sheet = BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "animações ausentes", e)
            list.clear()
        }
    }

    /** Chamar com o atlas já ligado (GL_TEXTURE_2D). */
    fun tick(nowMs: Long) {
        val sh = sheet ?: return
        val t = nowMs / 50L
        for (a in list) {
            if (a.n < 2) continue
            val f = ((t / a.ticks) % a.n).toInt()
            if (f == a.cur) continue
            a.cur = f
            try {
                val bmp = Bitmap.createBitmap(sh, a.ox, a.oy + f * a.h, a.w, a.h)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, a.x, a.y, bmp)
                bmp.recycle()
            } catch (e: Throwable) {
                // quadro inválido: ignora
            }
        }
    }
}
