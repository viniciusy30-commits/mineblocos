package com.mineblocos.app

import kotlin.math.floor

object Noise {
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = seed
        h = h xor (x * 0x27d4eb2d)
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = h xor (y * 0x165667b1)
        h = (h xor (h ushr 13)) * 0x297a2d39
        h = h xor (h ushr 16)
        return h
    }

    fun rnd(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) and 0xFFFF) / 65535f

    fun value(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = rnd(x0, y0, seed)
        val b = rnd(x0 + 1, y0, seed)
        val c = rnd(x0, y0 + 1, seed)
        val d = rnd(x0 + 1, y0 + 1, seed)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    fun fbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
        var amp = 1f
        var freq = 1f
        var sum = 0f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += value(x * freq, y * freq, seed + i * 101) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }
}
