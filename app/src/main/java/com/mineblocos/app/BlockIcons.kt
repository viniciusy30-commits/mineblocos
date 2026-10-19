package com.mineblocos.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Rect
import kotlin.math.max
import android.graphics.Shader

/** Ícones isométricos dos blocos, desenhados a partir do atlas. */
object BlockIcons {
    fun make(b: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val atlas = Atlas.bitmap
        if (Reg.kind(b) == Reg.K_MODEL && Shapes.isXModel(b)) {
            Models3D.get(Reg.key(b))?.let { xIcon(cv, atlas, it, size) }
            return bmp
        }
        if (Reg.kind(b) == Reg.K_MODEL && !Shapes.spriteItem(b)) {
            val m0 = Shapes.model(null, 0, 0, 0, b)
            modelIcon(cv, atlas, if (m0.rc != null) Shapes.model(null, 0, 0, 0, b, true) else m0, size)
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

    /** Ícone isométrico de um móvel/objeto do addon, com as texturas reais de cada face. */
    private fun xIcon(cv: Canvas, atlas: Bitmap, xm: XModel, size: Int) {
        val xv = xm.iconVariant()
        val ic = xv.rects["\$icon"]
        if (ic != null) {
            // item: ícone original, em escala inteira quando possível
            val src = Rect(ic[0], ic[1], ic[0] + ic[2], ic[1] + ic[3])
            val pad = size * 0.06f
            val sc = (size - 2 * pad) / max(ic[2], ic[3]).toFloat()
            val w = ic[2] * sc; val h = ic[3] * sc
            val dst = RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f)
            val p = Paint()
            p.isFilterBitmap = false
            cv.drawBitmap(atlas, src, dst, p)
            return
        }
        val s = size / 2f * 0.96f
        val cx = size / 2f
        val cy = size / 2f
        val k = 0.866f * s
        var minX = 9f; var minY = 9f; var minZ = 9f; var maxX = -9f; var maxY = -9f; var maxZ = -9f
        for (q in xv.cubes) for (i in 0 until 8) {
            val x = q.wor[i * 3]; val y = q.wor[i * 3 + 1]; val z = q.wor[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        if (maxX < minX) return
        val dim = max(max(maxX - minX, maxZ - minZ), max(maxY - minY, 0.25f))
        val sc = 0.9f / dim
        val mx = (minX + maxX) / 2f; val mz = (minZ + maxZ) / 2f
        fun pr(x: Float, y: Float, z: Float): PointF {
            val nx = (x - mx) * sc + 0.5f; val nz = (z - mz) * sc + 0.5f; val ny = (y - minY) * sc + 0.05f
            return PointF(cx + (nx - nz) * k, (cy - s) + (nx + nz) * s / 2f + (1f - ny) * s)
        }
        val star = xv.rects["*"]
        val order = xv.cubes.sortedWith(compareBy({ q -> (q.wor[0] + q.wor[2]) + (q.wor[21] + q.wor[23]) }, { q -> q.wor[1] }))
        for (q in order) {
            var x0 = 9f; var y0 = 9f; var z0 = 9f; var x1 = -9f; var y1 = -9f; var z1 = -9f
            for (i in 0 until 8) {
                val x = q.wor[i * 3]; val y = q.wor[i * 3 + 1]; val z = q.wor[i * 3 + 2]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (z < z0) z0 = z; if (z > z1) z1 = z
            }
            for (fc in q.faces) {
                if (fc.f != 0 && fc.f != 4 && fc.f != 2) continue
                val r = xv.rects[fc.inst] ?: star ?: continue
                val p0: PointF; val p1: PointF; val p2: PointF; val dark: Float
                when (fc.f) {
                    0 -> { p0 = pr(x0, y1, z0); p1 = pr(x1, y1, z0); p2 = pr(x0, y1, z1); dark = 0f }
                    4 -> { p0 = pr(x0, y1, z1); p1 = pr(x1, y1, z1); p2 = pr(x0, y0, z1); dark = 0.28f }
                    else -> { p0 = pr(x1, y1, z1); p1 = pr(x1, y1, z0); p2 = pr(x1, y0, z1); dark = 0.5f }
                }
                val p3 = PointF(p1.x + p2.x - p0.x, p1.y + p2.y - p0.y)
                val rx = r[0].toFloat(); val ry = r[1].toFloat(); val rw = r[2].toFloat(); val rh = r[3].toFloat()
                val m = Matrix()
                m.setPolyToPoly(
                    floatArrayOf(rx + fc.u0 * rw, ry + fc.v0 * rh, rx + fc.u1 * rw, ry + fc.v0 * rh, rx + fc.u0 * rw, ry + fc.v1 * rh), 0,
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
                    val pd = Paint(Paint.ANTI_ALIAS_FLAG)
                    pd.color = Color.argb((dark * 255).toInt(), 0, 0, 0)
                    cv.drawPath(path, pd)
                }
            }
        }
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
