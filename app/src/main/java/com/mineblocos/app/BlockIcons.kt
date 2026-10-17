package com.mineblocos.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader

/** Ícones isométricos dos blocos, desenhados a partir do atlas. */
object BlockIcons {
    fun make(b: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val atlas = Atlas.bitmap
        val s = size / 2f * 0.96f
        val cx = size / 2f
        val cy = size / 2f
        val k = 0.866f * s
        val top = PointF(cx, cy - s)
        val right = PointF(cx + k, cy - s / 2f)
        val center = PointF(cx, cy)
        val left = PointF(cx - k, cy - s / 2f)
        val bl = PointF(cx - k, cy + s / 2f)
        val br = PointF(cx + k, cy + s / 2f)
        val bottom = PointF(cx, cy + s)
        face(cv, atlas, Blocks.tile(b, 0), top, right, left, center, 0f)
        face(cv, atlas, Blocks.tile(b, 2), left, center, bl, bottom, 0.28f)
        face(cv, atlas, Blocks.tile(b, 2), center, right, bottom, br, 0.5f)
        return bmp
    }

    private fun face(cv: Canvas, atlas: Bitmap, tile: Int, p0: PointF, p1: PointF, p2: PointF, p3: PointF, dark: Float) {
        val tx = (tile % 4) * 16f
        val ty = (tile / 4) * 16f
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(tx, ty, tx + 16f, ty, tx, ty + 16f), 0,
            floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y), 0, 3
        )
        val path = Path()
        path.moveTo(p0.x, p0.y)
        path.lineTo(p1.x, p1.y)
        path.lineTo(p3.x, p3.y)
        path.lineTo(p2.x, p2.y)
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.isFilterBitmap = false
        val sh = BitmapShader(atlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sh.setLocalMatrix(m)
        paint.shader = sh
        cv.drawPath(path, paint)
        if (dark > 0f) {
            val p2n = Paint(Paint.ANTI_ALIAS_FLAG)
            p2n.color = Color.argb((dark * 255).toInt(), 0, 0, 0)
            cv.drawPath(path, p2n)
        }
    }
}
