package com.mineblocos.app

class FloatList(cap: Int) {
    var a = FloatArray(cap)
    var n = 0
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
}

class MeshData(val data: FloatArray, val size: Int)

/** Gera a malha (x,y,z,u,v,luz por vértice) de um chunk, com oclusão ambiente. */
object Mesher {
    // faces: 0=+Y 1=-Y 2=+X 3=-X 4=+Z 5=-Z
    private val CORNERS = arrayOf(
        intArrayOf(0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0),
        intArrayOf(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1),
        intArrayOf(1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1),
        intArrayOf(0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0),
        intArrayOf(0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1),
        intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0)
    )
    private val UVS = arrayOf(
        intArrayOf(0, 0, 0, 1, 1, 1, 1, 0),
        intArrayOf(0, 0, 1, 0, 1, 1, 0, 1),
        intArrayOf(0, 1, 0, 0, 1, 0, 1, 1),
        intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
        intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
        intArrayOf(0, 1, 0, 0, 1, 0, 1, 1)
    )
    private val NORMALS = arrayOf(
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(1, 0, 0),
        intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
    )
    private val AXIS = intArrayOf(1, 1, 0, 0, 2, 2)
    private val SHADE = floatArrayOf(1f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f)
    private val ORDER_A = intArrayOf(0, 1, 2, 0, 2, 3)
    private val ORDER_B = intArrayOf(1, 2, 3, 1, 3, 0)

    private val tmp = IntArray(3)
    private val lights = FloatArray(4)

    /** Vértices do cubo (36) para pintura sólida: usado pelo renderer. */
    fun cubeTriangles(): FloatArray {
        val out = FloatArray(6 * 6 * 3)
        var p = 0
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        for (f in 0 until 6) {
            for (k in order) {
                out[p++] = CORNERS[f][k * 3].toFloat()
                out[p++] = CORNERS[f][k * 3 + 1].toFloat()
                out[p++] = CORNERS[f][k * 3 + 2].toFloat()
            }
        }
        return out
    }

    fun cubeLines(): FloatArray {
        val out = FloatArray(12 * 2 * 3)
        var p = 0
        for (i in 0 until 8) {
            for (bit in 0 until 3) {
                if ((i and (1 shl bit)) == 0) {
                    val j = i or (1 shl bit)
                    out[p++] = (i and 1).toFloat(); out[p++] = ((i shr 1) and 1).toFloat(); out[p++] = ((i shr 2) and 1).toFloat()
                    out[p++] = (j and 1).toFloat(); out[p++] = ((j shr 1) and 1).toFloat(); out[p++] = ((j shr 2) and 1).toFloat()
                }
            }
        }
        return out
    }

    private fun kind(f: Int) = if (f == 0) 0 else if (f == 1) 1 else 2

    private fun emitQuad(out: FloatList, f: Int, ox: Float, oy: Float, oz: Float, tile: Int, l: FloatArray) {
        val c = CORNERS[f]
        val uv = UVS[f]
        val tx = (tile % 4) * 16f
        val ty = (tile / 4) * 16f
        val order = if (l[0] + l[2] >= l[1] + l[3]) ORDER_A else ORDER_B
        for (k in order) {
            out.add(ox + c[k * 3])
            out.add(oy + c[k * 3 + 1])
            out.add(oz + c[k * 3 + 2])
            out.add((tx + 0.05f + uv[k * 2] * 15.9f) / 64f)
            out.add((ty + 0.05f + uv[k * 2 + 1] * 15.9f) / 64f)
            out.add(l[k])
        }
    }

    private fun occ(w: World, bx: Int, by: Int, bz: Int, t1: Int, s1: Int, t2: Int, s2: Int): Int {
        tmp[0] = bx; tmp[1] = by; tmp[2] = bz
        tmp[t1] += s1
        tmp[t2] += s2
        return if (Blocks.opaque(w.getMesh(tmp[0], tmp[1], tmp[2]))) 1 else 0
    }

    fun buildChunk(w: World, cx: Int, cz: Int): MeshData {
        val out = FloatList(24000)
        val x0 = cx * World.CS
        val z0 = cz * World.CS
        for (y in 0 until World.H) {
            for (z in z0 until z0 + World.CS) {
                for (x in x0 until x0 + World.CS) {
                    val b = w.get(x, y, z)
                    if (b == Blocks.AIR) continue
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val bx = x + n[0]
                        val by = y + n[1]
                        val bz = z + n[2]
                        val nb = w.getMesh(bx, by, bz)
                        if (Blocks.opaque(nb)) continue
                        if (b == Blocks.GLASS && nb == Blocks.GLASS) continue
                        val t1 = (AXIS[f] + 1) % 3
                        val t2 = (AXIS[f] + 2) % 3
                        val c = CORNERS[f]
                        for (i in 0 until 4) {
                            val s1 = if (c[i * 3 + t1] == 1) 1 else -1
                            val s2 = if (c[i * 3 + t2] == 1) 1 else -1
                            val a = occ(w, bx, by, bz, t1, s1, t2, 0)
                            val d = occ(w, bx, by, bz, t1, 0, t2, s2)
                            val e = occ(w, bx, by, bz, t1, s1, t2, s2)
                            val ao = if (a == 1 && d == 1) 0 else 3 - (a + d + e)
                            lights[i] = SHADE[f] * (0.5f + 0.5f * ao / 3f)
                        }
                        emitQuad(out, f, x.toFloat(), y.toFloat(), z.toFloat(), Blocks.tile(b, kind(f)), lights)
                    }
                }
            }
        }
        return MeshData(out.a, out.n)
    }

    /** Cubo 0..1 com sombreamento simples, para a "mão" do jogador. */
    fun handMesh(b: Int): MeshData {
        val out = FloatList(256)
        val l = FloatArray(4)
        for (f in 0 until 6) {
            for (i in 0 until 4) l[i] = SHADE[f]
            emitQuad(out, f, 0f, 0f, 0f, Blocks.tile(b, kind(f)), l)
        }
        return MeshData(out.a, out.n)
    }
}
