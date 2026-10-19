package com.mineblocos.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Fundo animado do menu: textura de terra rolando + blocos flutuando. */
class MenuBackgroundView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val tileBmp: Bitmap = Bitmap.createBitmap(Atlas.bitmap, 32, 0, 16, 16)
    private val shader = BitmapShader(tileBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val tilePaint = Paint().apply { shader = this@MenuBackgroundView.shader; isFilterBitmap = false }
    private val shade = Paint()
    private val m = Matrix()
    private val blocks = intArrayOf(
        Blocks.GRASS, Blocks.STONE, Blocks.BRICK, Blocks.LOG, Blocks.SAND,
        Blocks.PLANKS, Blocks.GLASS, Blocks.COBBLE, Blocks.LEAVES
    )
    private var icons: List<Bitmap> = emptyList()
    private val start = SystemClock.uptimeMillis()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val sz = (62 * d).toInt()
        icons = blocks.map { BlockIcons.make(it, sz) }
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xDD0B0E12.toInt(), 0x88101820.toInt(), 0xEE0B0E12.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val sc = 6f * d
        m.setScale(sc, sc)
        m.postTranslate(t * 14f * d, t * 6f * d)
        shader.setLocalMatrix(m)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tilePaint)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        for (i in icons.indices) {
            val ic = icons[i]
            val fx = (i * 0.137f + 0.04f) % 1f
            val fy = 0.18f + 0.62f * ((i * 0.39f) % 1f)
            val x = fx * width - ic.width / 2f
            val y = fy * height + sin(t * 1.2f + i * 1.7f) * 12f * d - ic.height / 2f
            c.save()
            c.rotate(sin(t * 0.6f + i) * 7f, x + ic.width / 2f, y + ic.height / 2f)
            c.drawBitmap(ic, x, y, null)
            c.restore()
        }
        postInvalidateOnAnimation()
    }
}
