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
        if (Reg.kind(b) == Reg.K_MODEL && !Shapes.spriteItem(b)) {
            modelIcon(cv, atlas, Shapes.model(null, 0, 0, 0, b), size)
            return bmp
        }
        if (Reg.kind(b) != Reg.K_CUBE) {
            val t = Reg.tile(b, 2)
            val sx = (t % Atlas.COLS) * 16
            val sy = (t / Atlas.COLS) * 16
            val pad = (size * 0.1f).toInt()
            val pf = Paint()
            pf.isFilterBitmap = false
            cv.drawBitmap(atlas, android.graphics.Rect(sx, sy, sx + 16, sy + 16), android.graphics.Rect(pad, pad, size - pad, size - pad), pf)
            return bmp
        }
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

    /** Ícone isométrico de um modelo com várias caixas. */
    private fun modelIcon(cv: Canvas, atlas: Bitmap, m: Model, size: Int) {
        val s = size / 2f * 0.96f
        val cx = size / 2f
        val cy = size / 2f
        val k = 0.866f * s
        fun pr(x: Float, y: Float, z: Float) = PointF(cx + (x - z) * k, (cy - s) + (x + z) * s / 2f + (1f - y) * s)
        // do fundo para a frente: menor x+z primeiro, depois menor y
        val order = m.boxes.sortedWith(compareBy({ it.x0 + it.z0 }, { it.y0 }))
        for (b in order) {
            val w = (b.x1 - b.x0) * 16f
            val d = (b.z1 - b.z0) * 16f
            val h = (b.y1 - b.y0) * 16f
            // topo
            quad(cv, atlas, b.top, b.x0 * 16f, b.z0 * 16f, w, d,
                pr(b.x0, b.y1, b.z0), pr(b.x1, b.y1, b.z0), pr(b.x0, b.y1, b.z1), pr(b.x1, b.y1, b.z1), 0f)
            // frente esquerda (+Z)
            quad(cv, atlas, b.side, b.x0 * 16f, (1f - b.y1) * 16f, w, h,
                pr(b.x0, b.y1, b.z1), pr(b.x1, b.y1, b.z1), pr(b.x0, b.y0, b.z1), pr(b.x1, b.y0, b.z1), 0.28f)
            // frente direita (+X)
            quad(cv, atlas, b.side, (1f - b.z1) * 16f, (1f - b.y1) * 16f, d, h,
                pr(b.x1, b.y1, b.z1), pr(b.x1, b.y1, b.z0), pr(b.x1, b.y0, b.z1), pr(b.x1, b.y0, b.z0), 0.5f)
        }
        if (m.cross >= 0) {
            val sx = (m.cross % Atlas.COLS) * 16
            val sy = (m.cross / Atlas.COLS) * 16
            val pf = Paint()
            pf.isFilterBitmap = false
            val sc = m.crossScale
            val x0 = cx - s * 0.7f * sc
            val x1 = cx + s * 0.7f * sc
            val yb = cy + s * 0.55f - m.crossY * s
            val yt = yb - s * 1.4f * sc
            cv.drawBitmap(atlas, android.graphics.Rect(sx, sy, sx + 16, sy + 16), android.graphics.RectF(x0, yt, x1, yb), pf)
        }
    }

    private fun quad(cv: Canvas, atlas: Bitmap, tile: Int, u: Float, v: Float, uw: Float, vh: Float,
                     p0: PointF, p1: PointF, p2: PointF, p3: PointF, dark: Float) {
        val tx = (tile % Atlas.COLS) * 16f + u
        val ty = (tile / Atlas.COLS) * 16f + v
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(tx, ty, tx + uw, ty, tx, ty + vh), 0,
            floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y), 0, 3
        )
        val path = Path()
        path.moveTo(p0.x, p0.y); path.lineTo(p1.x, p1.y); path.lineTo(p3.x, p3.y); path.lineTo(p2.x, p2.y); path.close()
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

    private fun face(cv: Canvas, atlas: Bitmap, tile: Int, p0: PointF, p1: PointF, p2: PointF, p3: PointF, dark: Float) {
        val tx = (tile % Atlas.COLS) * 16f
        val ty = (tile / Atlas.COLS) * 16f
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
