package com.mineblocos.app

import android.graphics.Bitmap
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** Atlas de texturas 4x4 (16px cada) desenhado 100% por código. */
object Atlas {
    val tileAvg = IntArray(16)
    val bitmap: Bitmap by lazy { generate() }

    private fun c(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    private fun n(rnd: Random, amp: Int) = rnd.nextInt(amp * 2 + 1) - amp

    private fun generate(): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val rnd = Random(12345)
        for (tile in 0 until 12) {
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    bmp.setPixel((tile % 4) * 16 + x, (tile / 4) * 16 + y, pixel(tile, x, y, rnd))
                }
            }
        }
        for (tile in 0 until 12) {
            var r = 0L; var g = 0L; var b = 0L; var cnt = 0L
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = bmp.getPixel((tile % 4) * 16 + x, (tile / 4) * 16 + y)
                if ((p ushr 24) > 0) {
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; cnt++
                }
            }
            if (cnt > 0) tileAvg[tile] = rgb((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
        }
        return bmp
    }

    private fun dirtPx(rnd: Random): Int {
        val v = n(rnd, 12)
        val speck = if (rnd.nextInt(100) < 9) -28 else 0
        return rgb(134 + v + speck, 96 + v + speck, 67 + v + speck)
    }

    private fun grassPx(rnd: Random): Int {
        val v = n(rnd, 14)
        return rgb(104 + v, 170 + v, 62 + v / 2)
    }

    private fun pixel(tile: Int, x: Int, y: Int, rnd: Random): Int {
        return when (tile) {
            0 -> grassPx(rnd)
            1 -> {
                val edge = 3 + ((x * 5 + 7) % 3)
                if (y < edge) grassPx(rnd) else dirtPx(rnd)
            }
            2 -> dirtPx(rnd)
            3 -> {
                val dark = if (rnd.nextInt(100) < 7) -26 else 0
                val g = 126 + n(rnd, 9) + dark
                rgb(g, g, g)
            }
            4 -> {
                val row = y / 5
                val off = (row % 2) * 2
                val col = (x + off) / 5
                if (y % 5 == 4 || (x + off) % 5 == 4) {
                    rgb(66, 66, 66)
                } else {
                    val h = ((col * 73856093) xor (row * 19349663)) and 0x3F
                    val g = 92 + h + n(rnd, 5)
                    rgb(g, g, g)
                }
            }
            5 -> {
                val v = if ((x * 3) % 4 == 0) -18 else n(rnd, 6)
                rgb(104 + v, 78 + v, 46 + v)
            }
            6 -> {
                val d = max(abs(x - 7.5f), abs(y - 7.5f))
                val v = n(rnd, 5)
                if (d >= 7f) rgb(92 + v, 68 + v, 40 + v)
                else if (d.toInt() % 2 == 0) rgb(188 + v, 152 + v, 94 + v)
                else rgb(160 + v, 126 + v, 74 + v)
            }
            7 -> {
                val row = y / 4
                var v = n(rnd, 6)
                if (y % 4 == 3) v -= 36
                val seam = if (row % 2 == 0) 3 else 11
                if (x == seam && y % 4 != 3) v -= 30
                rgb(178 + v, 140 + v, 86 + v)
            }
            8 -> {
                if (rnd.nextInt(100) < 18) 0
                else {
                    val v = n(rnd, 18)
                    rgb(54 + v, 128 + v, 40 + v / 2)
                }
            }
            9 -> {
                val v = n(rnd, 8)
                rgb(220 + v, 210 + v, 160 + v)
            }
            10 -> {
                if (x == 0 || y == 0 || x == 15 || y == 15) rgb(205, 232, 242)
                else if ((x + y == 5 || x + y == 6 || x + y == 9) && x in 2..8 && y in 2..8) rgb(255, 255, 255)
                else 0
            }
            else -> {
                val row = y / 4
                val off = (row % 2) * 4
                if (y % 4 == 3 || (x + off) % 8 == 7) rgb(196, 190, 180)
                else {
                    val v = n(rnd, 8)
                    rgb(156 + v, 70 + v, 52 + v)
                }
            }
        }
    }
}
