package com.mineblocos.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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

    /** Trecho da vitrine com o nome da categoria (mostrado na tela quando o jogador passa por ele). */
    class Area(val name: String, val x0: Int, val x1: Int, val z0: Int, val z1: Int)

    /** Células que pertencem à vitrine: não podem ser quebradas nem substituídas. */
    val showcase = java.util.BitSet(W * H * D)
    val areas = ArrayList<Area>()

    fun isShowcase(x: Int, y: Int, z: Int): Boolean = inBounds(x, y, z) && showcase.get(idx(x, y, z))

    fun areaAt(x: Int, z: Int): String? {
        for (a in areas) if (x >= a.x0 && x <= a.x1 && z >= a.z0 && z <= a.z1) return a.name
        return null
    }

    /** Estado do bloco: bits 0-1 direção, 2 aberto, 3 metade de cima, 4 segunda parte (laje dupla, cama, porta). */
    val meta = ByteArray(W * H * D)
    val dirty = BooleanArray(CX * CZ)

    private fun idx(x: Int, y: Int, z: Int) = (y * D + z) * W + x

    fun getMeta(x: Int, y: Int, z: Int): Int = if (inBounds(x, y, z)) meta[idx(x, y, z)].toInt() else 0

    fun setMeta(x: Int, y: Int, z: Int, m: Int) {
        if (!inBounds(x, y, z)) return
        meta[idx(x, y, z)] = m.toByte()
        touch(x, z)
    }

    /** Tem água nesta célula? */
    fun isWater(x: Int, y: Int, z: Int): Boolean {
        val w = Reg.water
        return w > 0 && get(x, y, z) == w
    }

    /** Quantas células de água seguidas existem daqui para baixo. */
    fun waterDepth(x: Int, y: Int, z: Int): Int {
        var n = 0
        var yy = y
        while (yy >= 0 && isWater(x, yy, z)) { n++; yy-- }
        return n
    }

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
        val i = idx(x, y, z)
        val b = blocks[i].toInt()
        return Reg.solid(b) && Shapes.solidAt(b, meta[i].toInt())
    }

    private fun setRaw(x: Int, y: Int, z: Int, b: Int) {
        if (inBounds(x, y, z)) blocks[idx(x, y, z)] = b.toShort()
    }

    private fun markDirty(cx: Int, cz: Int) {
        if (cx in 0 until CX && cz in 0 until CZ) dirty[cz * CX + cx] = true
    }

    // ---- luz de bloco (lâmpadas e outros emissores), espalhada até 15 blocos ----
    private val lightChunks = arrayOfNulls<ByteArray>(CX * CZ)
    private val emitters = HashSet<Int>()
    @Volatile var hasLight = false

    fun blockLight(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y >= H || x < 0 || x >= W || z < 0 || z >= D) return 0
        val a = lightChunks[(z shr 4) * CX + (x shr 4)] ?: return 0
        return a[((y shl 4) + (z and 15)) * 16 + (x and 15)].toInt()
    }

    private fun putLight(x: Int, y: Int, z: Int, v: Int) {
        val ci = (z shr 4) * CX + (x shr 4)
        var a = lightChunks[ci]
        if (a == null) {
            if (v == 0) return
            a = ByteArray(16 * H * 16)
            lightChunks[ci] = a
        }
        a[((y shl 4) + (z and 15)) * 16 + (x and 15)] = v.toByte()
    }

    /** Maior luz de bloco na célula e nas 6 vizinhas (usada pela malha). */
    fun lightAround(x: Int, y: Int, z: Int): Int {
        if (!hasLight) return 0
        var m = blockLight(x, y, z)
        val a = blockLight(x + 1, y, z); if (a > m) m = a
        val b = blockLight(x - 1, y, z); if (b > m) m = b
        val c = blockLight(x, y + 1, z); if (c > m) m = c
        val d = blockLight(x, y - 1, z); if (d > m) m = d
        val e = blockLight(x, y, z + 1); if (e > m) m = e
        val f = blockLight(x, y, z - 1); if (f > m) m = f
        return m
    }

    private fun spread(q: ArrayDeque<Int>, x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int) {
        val dx = intArrayOf(1, -1, 0, 0, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1, 0, 0)
        val dz = intArrayOf(0, 0, 0, 0, 1, -1)
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % W
            val t = i / W
            val z = t % D
            val y = t / D
            val l = blockLight(x, y, z)
            if (l <= 1) continue
            for (k in 0 until 6) {
                val nx = x + dx[k]; val ny = y + dy[k]; val nz = z + dz[k]
                if (nx < x0 || nx > x1 || ny < y0 || ny > y1 || nz < z0 || nz > z1) continue
                if (Reg.opaque(blocks[idx(nx, ny, nz)].toInt())) continue
                if (blockLight(nx, ny, nz) < l - 1) {
                    putLight(nx, ny, nz, l - 1)
                    q.addLast(idx(nx, ny, nz))
                }
            }
        }
    }

    /** Recalcula toda a luz do mundo (ao gerar ou carregar). */
    fun relightFull() {
        for (i in lightChunks.indices) lightChunks[i] = null
        emitters.clear()
        for (i in blocks.indices) {
            val b = blocks[i].toInt()
            if (b != 0 && Reg.emission(b) > 0) emitters.add(i)
        }
        hasLight = emitters.isNotEmpty()
        if (!hasLight) return
        val q = ArrayDeque<Int>()
        for (e in emitters) {
            val x = e % W; val t = e / W
            putLight(x, t / D, t % D, Reg.emission(blocks[e].toInt()))
            q.addLast(e)
        }
        spread(q, 0, W - 1, 0, H - 1, 0, D - 1)
    }

    private fun nearEmitter(px: Int, py: Int, pz: Int): Boolean {
        for (e in emitters) {
            val x = e % W; val t = e / W
            if (abs(x - px) <= 15 && abs(t / D - py) <= 15 && abs(t % D - pz) <= 15) return true
        }
        return false
    }

    /** Recalcula a luz numa caixa de 15 blocos ao redor de uma mudança. */
    private fun relightAround(px: Int, py: Int, pz: Int) {
        val x0 = max(0, px - 15); val x1 = minOf(W - 1, px + 15)
        val y0 = max(0, py - 15); val y1 = minOf(H - 1, py + 15)
        val z0 = max(0, pz - 15); val z1 = minOf(D - 1, pz + 15)
        for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) putLight(x, y, z, 0)
        val q = ArrayDeque<Int>()
        for (e in emitters) {
            val x = e % W; val t = e / W
            val z = t % D; val y = t / D
            if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue
            putLight(x, y, z, Reg.emission(blocks[e].toInt()))
            q.addLast(e)
        }
        // casca ao redor da caixa: a luz que já existia fora dela entra de volta
        for (y in max(0, y0 - 1)..minOf(H - 1, y1 + 1)) for (z in max(0, z0 - 1)..minOf(D - 1, z1 + 1)) for (x in max(0, x0 - 1)..minOf(W - 1, x1 + 1)) {
            if (x in x0..x1 && y in y0..y1 && z in z0..z1) continue
            if (blockLight(x, y, z) > 1) q.addLast(idx(x, y, z))
        }
        spread(q, x0, x1, y0, y1, z0, z1)
        for (cx in (x0 shr 4)..(x1 shr 4)) for (cz in (z0 shr 4)..(z1 shr 4)) markDirty(cx, cz)
    }

    fun markAllDirty() {
        relightFull()
        for (i in dirty.indices) dirty[i] = true
    }

    fun set(x: Int, y: Int, z: Int, b: Int, m: Int = 0) {
        if (!inBounds(x, y, z)) return
        if (showcase.get(idx(x, y, z))) return // bloco da vitrine é indestrutível
        val pb = blocks[idx(x, y, z)].toInt()
        blocks[idx(x, y, z)] = b.toShort()
        meta[idx(x, y, z)] = m.toByte()
        touch(x, z)
        val ep = Reg.emission(pb)
        val eb = Reg.emission(b)
        if (ep != eb) {
            if (eb > 0) emitters.add(idx(x, y, z)) else emitters.remove(idx(x, y, z))
            hasLight = emitters.isNotEmpty()
            relightAround(x, y, z)
        } else if (hasLight && Reg.opaque(pb) != Reg.opaque(b) && nearEmitter(x, y, z)) {
            relightAround(x, y, z)
        }
    }

    private fun touch(x: Int, z: Int) {
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

    /** Escreve um bloco sem luz/remalha (usado pela vitrine; markAllDirty recalcula tudo no fim). */
    fun put(x: Int, y: Int, z: Int, b: Int, m: Int = 0) {
        if (!inBounds(x, y, z)) return
        blocks[idx(x, y, z)] = b.toShort()
        meta[idx(x, y, z)] = m.toByte()
    }

    /** Escreve um bloco da vitrine (e o marca como indestrutível). */
    fun putShowcase(x: Int, y: Int, z: Int, b: Int, m: Int = 0) {
        if (!inBounds(x, y, z)) return
        put(x, y, z, b, m)
        showcase.set(idx(x, y, z))
    }

    /** Apaga a vitrine atual e monta de novo só com as categorias escolhidas. */
    fun rebuildShowcase(selected: Set<String>) {
        var i = showcase.nextSetBit(0)
        while (i >= 0) {
            blocks[i] = 0
            meta[i] = 0
            i = showcase.nextSetBit(i + 1)
        }
        showcase.clear()
        areas.clear()
        Showcase.build(this, 20, selected)
        markAllDirty()
    }

    /**
     * Rio que atravessa o mundo de leste a oeste, ao lado do ponto de partida (z ≈ 259..273).
     * Tem 3 a 5 blocos de profundidade no meio, margens de areia e fundo de areia.
     * A superfície da água fica a 0,1 do topo do chão, como no Minecraft.
     */
    private fun carveRiver(ground: Int) {
        val wid = Reg.water
        if (wid <= 0) return
        val sand = Reg.id("sand").let { if (it > 0) it else Blocks.DIRT }
        for (x in 0 until W) {
            val c = 266f + 2.5f * sin(x * 0.045f)          // linha central do rio (meandro)
            val hw = 4.2f + 1.0f * sin(x * 0.031f + 1f)     // meia-largura
            val z0 = floor(c - hw * 1.4f - 1f).toInt()
            val z1 = ceil(c + hw * 1.4f + 1f).toInt()
            for (z in z0..z1) {
                if (z !in 0 until D) continue
                val t = abs(z + 0.5f - c) / hw
                if (t <= 1f) {
                    val depth = 1 + Math.round(3.5f * (1f - t * t))
                    val bed = ground - depth
                    for (y in (bed + 1)..ground) blocks[idx(x, y, z)] = wid.toShort()
                    blocks[idx(x, bed, z)] = sand.toShort()
                    if (bed - 1 >= 1) blocks[idx(x, bed - 1, z)] = sand.toShort()
                } else if (t <= 1.4f) {
                    blocks[idx(x, ground, z)] = sand.toShort()
                    blocks[idx(x, ground - 1, z)] = sand.toShort()
                }
            }
        }
    }

    /** Mundo plano, sem árvores, com um rio, e a vitrine de todos os blocos. */
    fun generate(progress: (Int) -> Unit) {
        val ground = 20
        for (z in 0 until D) {
            for (x in 0 until W) {
                for (y in 0..ground) {
                    val b = when {
                        y == 0 -> Blocks.STONE
                        y == ground -> Blocks.GRASS
                        y >= ground - 3 -> Blocks.DIRT
                        else -> Blocks.STONE
                    }
                    blocks[idx(x, y, z)] = b.toShort()
                }
            }
            progress(z * 90 / D)
        }
        carveRiver(ground)
        areas.clear() // a vitrine é montada depois, com as categorias que o jogador escolher
        progress(100)
        markAllDirty()
    }
}
