package com.mineblocos.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class World(val seed: Int) {
    companion object {
        const val W = 512
        const val H = 48
        const val D = 512
        const val CS = 16
        const val CX = W / CS
        const val CZ = D / CS
    }

    val blocks = ShortArray(W * H * D)
    val dirty = BooleanArray(CX * CZ)

    private fun idx(x: Int, y: Int, z: Int) = (y * D + z) * W + x

    fun inBounds(x: Int, y: Int, z: Int) = x in 0 until W && y in 0 until H && z in 0 until D

    fun get(x: Int, y: Int, z: Int): Int = if (inBounds(x, y, z)) blocks[idx(x, y, z)].toInt() else 0

    /** Usado pela malha: fora do mundo conta como pedra (não gera faces). */
    fun getMesh(x: Int, y: Int, z: Int): Int {
        if (y < 0) return Blocks.STONE
        if (y >= H) return 0
        if (x < 0 || x >= W || z < 0 || z >= D) return Blocks.STONE
        return blocks[idx(x, y, z)].toInt()
    }

    fun solid(x: Int, y: Int, z: Int): Boolean {
        if (y < 0) return true
        if (y >= H) return false
        if (x < 0 || x >= W || z < 0 || z >= D) return true
        return Reg.solid(blocks[idx(x, y, z)].toInt())
    }

    private fun setRaw(x: Int, y: Int, z: Int, b: Int) {
        if (inBounds(x, y, z)) blocks[idx(x, y, z)] = b.toShort()
    }

    private fun markDirty(cx: Int, cz: Int) {
        if (cx in 0 until CX && cz in 0 until CZ) dirty[cz * CX + cx] = true
    }

    fun markAllDirty() {
        for (i in dirty.indices) dirty[i] = true
    }

    fun set(x: Int, y: Int, z: Int, b: Int) {
        if (!inBounds(x, y, z)) return
        blocks[idx(x, y, z)] = b.toShort()
        val cx = x shr 4
        val cz = z shr 4
        markDirty(cx, cz)
        if ((x and 15) == 0) markDirty(cx - 1, cz)
        if ((x and 15) == 15) markDirty(cx + 1, cz)
        if ((z and 15) == 0) markDirty(cx, cz - 1)
        if ((z and 15) == 15) markDirty(cx, cz + 1)
    }

    fun surfaceY(x: Int, z: Int): Int {
        for (y in H - 1 downTo 0) if (blocks[idx(x, y, z)].toInt() != 0) return y
        return -1
    }

    fun generate(progress: (Int) -> Unit) {
        val heights = IntArray(W * D)
        val water = Reg.id("water")
        val ground = 20
        for (z in 0 until D) {
            for (x in 0 until W) {
                // rio sinuoso cruzando o mundo
                val cz = D * 0.65f + sin(x / 70f) * 32f + sin(x / 23f + seed) * 9f
                val width = 6f + 2.5f * sin(x / 41f)
                val dist = abs(z - cz)
                val inRiver = dist < width
                val bank = dist < width + 2.5f
                heights[z * W + x] = ground
                for (y in 0..ground) {
                    var b = when {
                        y == 0 -> Blocks.STONE
                        y == ground -> if (bank) Blocks.SAND else Blocks.GRASS
                        y >= ground - 3 -> if (bank) Blocks.SAND else Blocks.DIRT
                        else -> Blocks.STONE
                    }
                    if (inRiver && y >= ground - 3) {
                        // leito do rio mais fundo no meio
                        val depth = if (dist < width * 0.5f) 3 else 2
                        b = if (y > ground - depth) Blocks.AIR else Blocks.SAND
                    }
                    blocks[idx(x, y, z)] = b.toShort()
                }
                if (inRiver) {
                    val depth = if (dist < width * 0.5f) 3 else 2
                    for (y in ground - depth + 1 until ground) blocks[idx(x, y, z)] = water.toShort()
                }
            }
            progress(z * 100 / D)
        }
        for (z in 3 until D - 3) {
            for (x in 3 until W - 3) {
                val hi = heights[z * W + x]
                if (get(x, hi, z) != Blocks.GRASS) continue
                if (Noise.rnd(x, z, seed + 99) > 0.004f) continue
                val th = 4 + (Noise.hash(x, z, seed + 5) and 1)
                for (i in 1..th) setRaw(x, hi + i, z, Blocks.LOG)
                for (dy in (th - 2)..(th + 1)) {
                    val r = if (dy >= th) 1 else 2
                    for (dz in -r..r) {
                        for (dx in -r..r) {
                            if (r == 2 && abs(dx) == 2 && abs(dz) == 2 &&
                                Noise.rnd(x + dx, z + dz + dy, seed) < 0.5f
                            ) continue
                            if (get(x + dx, hi + dy, z + dz) == Blocks.AIR) {
                                setRaw(x + dx, hi + dy, z + dz, Blocks.LEAVES)
                            }
                        }
                    }
                }
            }
        }
        markAllDirty()
    }
}
