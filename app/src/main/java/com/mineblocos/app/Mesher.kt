package com.mineblocos.app

import kotlin.math.abs

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
        val tx = (tile % Atlas.COLS) * 16f
        val ty = (tile / Atlas.COLS) * 16f
        val aw = Atlas.bitmap.width.toFloat()
        val ah = Atlas.bitmap.height.toFloat()
        val order = if (l[0] + l[2] >= l[1] + l[3]) ORDER_A else ORDER_B
        for (k in order) {
            out.add(ox + c[k * 3])
            out.add(oy + c[k * 3 + 1])
            out.add(oz + c[k * 3 + 2])
            out.add((tx + 0.05f + uv[k * 2] * 15.9f) / aw)
            out.add((ty + 0.05f + uv[k * 2 + 1] * 15.9f) / ah)
            out.add(l[k])
        }
    }

    private fun occ(w: World, bx: Int, by: Int, bz: Int, t1: Int, s1: Int, t2: Int, s2: Int): Int {
        tmp[0] = bx; tmp[1] = by; tmp[2] = bz
        tmp[t1] += s1
        tmp[t2] += s2
        return if (Blocks.opaque(w.getMesh(tmp[0], tmp[1], tmp[2]))) 1 else 0
    }

    private fun emitCross(out: FloatList, ox: Float, oy: Float, oz: Float, tile: Int, sc: Float = 1f, yo: Float = 0f) {
        val tx = (tile % Atlas.COLS) * 16f
        val ty = (tile / Atlas.COLS) * 16f
        val aw = Atlas.bitmap.width.toFloat()
        val ah = Atlas.bitmap.height.toFloat()
        val px = floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f,   1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f)
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        for (q in 0 until 2) {
            for (k in order) {
                out.add(ox + 0.5f + (px[q * 12 + k * 3] - 0.5f) * sc)
                out.add(oy + yo + px[q * 12 + k * 3 + 1] * sc)
                out.add(oz + 0.5f + (px[q * 12 + k * 3 + 2] - 0.5f) * sc)
                out.add((tx + 0.05f + uvs[k * 2] * 15.9f) / aw)
                out.add((ty + 0.05f + uvs[k * 2 + 1] * 15.9f) / ah)
                out.add(0.95f)
            }
        }
    }


    /** UV automático por face (como no Minecraft): u,v em fração da célula. */
    private fun faceU(f: Int, x: Float, z: Float): Float = when (f) {
        0, 1, 4 -> x
        2 -> 1f - z
        3 -> z
        else -> 1f - x
    }

    private fun faceV(f: Int, y: Float, z: Float): Float = if (f == 0 || f == 1) z else 1f - y

    /** Emite uma caixa de modelo. [w] nulo = sem esconder faces (mão). */
    private fun emitBox(out: FloatList, bx: Box, ox: Float, oy: Float, oz: Float, w: World?, cx: Int, cy: Int, cz: Int, lightMul: Float = 1f) {
        val aw = Atlas.bitmap.width.toFloat()
        val ah = Atlas.bitmap.height.toFloat()
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        for (f in 0 until 6) {
            if ((bx.faces and (1 shl f)) == 0) continue
            if (w != null) {
                val edge = when (f) {
                    0 -> bx.y1 >= 0.999f
                    1 -> bx.y0 <= 0.001f
                    2 -> bx.x1 >= 0.999f
                    3 -> bx.x0 <= 0.001f
                    4 -> bx.z1 >= 0.999f
                    else -> bx.z0 <= 0.001f
                }
                if (edge) {
                    val n = NORMALS[f]
                    if (Blocks.opaque(w.getMesh(cx + n[0], cy + n[1], cz + n[2]))) continue
                }
            }
            val tile = if (f == 0) bx.top else if (f == 1) bx.bot else bx.side
            val tx = (tile % Atlas.COLS) * 16f
            val ty = (tile / Atlas.COLS) * 16f
            val c = CORNERS[f]
            val light = SHADE[f] * 0.97f * lightMul
            for (k in order) {
                val fx = if (c[k * 3] == 1) bx.x1 else bx.x0
                val fy = if (c[k * 3 + 1] == 1) bx.y1 else bx.y0
                val fz = if (c[k * 3 + 2] == 1) bx.z1 else bx.z0
                out.add(ox + fx)
                out.add(oy + fy)
                out.add(oz + fz)
                out.add((tx + 0.02f + faceU(f, fx, fz) * 15.96f) / aw)
                val vv = faceV(f, fy, fz)
                out.add((ty + 0.02f + (if (f >= 2) bx.vOff + vv * bx.vMul else vv) * 15.96f) / ah)
                out.add(light)
            }
        }
    }

    

    /** Planta rasteira: camada fina no chão (pétalas, líquen, ovos de sapo...). */
    private fun emitFloor(out: FloatList, ox: Float, oy: Float, oz: Float, tile: Int) {
        val bx = Box(0f, 0.01f, 0f, 1f, 0.0625f, 1f, tile, tile, tile, 3)
        emitBox(out, bx, ox, oy, oz, null, 0, 0, 0, 0.97f)
    }

    /** Caixas giradas (círculo, curva, diagonal...): UV pela posição sem rotação, luz pela normal girada. */
    private fun emitRot(out: FloatList, m: Model, rc: Array<RCube>, ox: Float, oy: Float, oz: Float) {
        val aw = Atlas.bitmap.width.toFloat()
        val ah = Atlas.bitmap.height.toFloat()
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        val rw = FloatArray(24)
        for (q in rc) {
            var mx = 0f; var my = 0f; var mz = 0f
            for (i in 0 until 8) {
                var wx = q.wor[i * 3]; val wy = q.wor[i * 3 + 1]; var wz = q.wor[i * 3 + 2]
                for (r in 0 until m.rf) { val nx = 1f - wz; val nz = wx; wx = nx; wz = nz }
                rw[i * 3] = wx; rw[i * 3 + 1] = wy; rw[i * 3 + 2] = wz
                mx += wx; my += wy; mz += wz
            }
            mx /= 8f; my /= 8f; mz /= 8f
            for (f in 0 until 6) {
                val c = CORNERS[f]
                var fx = 0f; var fy = 0f; var fz = 0f
                for (k in 0 until 4) {
                    val ci = (if (c[k * 3] == 1) 1 else 0) + (if (c[k * 3 + 1] == 1) 2 else 0) + (if (c[k * 3 + 2] == 1) 4 else 0)
                    fx += rw[ci * 3]; fy += rw[ci * 3 + 1]; fz += rw[ci * 3 + 2]
                }
                val nx = fx / 4f - mx; val ny = fy / 4f - my; val nz = fz / 4f - mz
                val idx = if (abs(ny) >= abs(nx) && abs(ny) >= abs(nz)) (if (ny > 0f) 0 else 1)
                else if (abs(nx) >= abs(nz)) (if (nx > 0f) 2 else 3)
                else (if (nz > 0f) 4 else 5)
                val light = SHADE[idx] * 0.97f
                val tile = if (f == 0) m.rt else if (f == 1) m.rb else m.rs
                val tx = (tile % Atlas.COLS) * 16f
                val ty = (tile / Atlas.COLS) * 16f
                for (k in order) {
                    val ci = (if (c[k * 3] == 1) 1 else 0) + (if (c[k * 3 + 1] == 1) 2 else 0) + (if (c[k * 3 + 2] == 1) 4 else 0)
                    out.add(ox + rw[ci * 3])
                    out.add(oy + rw[ci * 3 + 1])
                    out.add(oz + rw[ci * 3 + 2])
                    val lx = q.loc[ci * 3].coerceIn(0f, 1f)
                    val ly = q.loc[ci * 3 + 1].coerceIn(0f, 1f)
                    val lz = q.loc[ci * 3 + 2].coerceIn(0f, 1f)
                    out.add((tx + 0.02f + faceU(f, lx, lz) * 15.96f) / aw)
                    out.add((ty + 0.02f + faceV(f, ly, lz) * 15.96f) / ah)
                    out.add(light)
                }
            }
        }
    }

    private fun emitModel(out: FloatList, m: Model, ox: Float, oy: Float, oz: Float, w: World?, cx: Int, cy: Int, cz: Int) {
        for (bx in m.boxes) emitBox(out, bx, ox, oy, oz, w, cx, cy, cz)
        val rc = m.rc
        if (rc != null && rc.isNotEmpty()) emitRot(out, m, rc, ox, oy, oz)
        if (m.cross >= 0) emitCross(out, ox, oy, oz, m.cross, m.crossScale, m.crossY)
    }

    /** Sprite plano com camadas finas (pseudo-espessura), para itens e plantas na mão. */
    private fun emitFlat(out: FloatList, tile: Int) {
        val tx = (tile % Atlas.COLS) * 16f
        val ty = (tile / Atlas.COLS) * 16f
        val aw = Atlas.bitmap.width.toFloat()
        val ah = Atlas.bitmap.height.toFloat()
        val px = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val uvs = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        val order = intArrayOf(0, 1, 2, 0, 2, 3)
        for (layer in 0 until 5) {
            val z = 0.45f + layer * 0.025f
            val light = if (layer == 2) 1f else 0.8f
            for (k in order) {
                out.add(px[k * 2])
                out.add(px[k * 2 + 1])
                out.add(z)
                out.add((tx + 0.05f + uvs[k * 2] * 15.9f) / aw)
                out.add((ty + 0.05f + uvs[k * 2 + 1] * 15.9f) / ah)
                out.add(light)
            }
        }
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
                    if (Reg.kind(b) == Reg.K_CROSS) {
                        emitCross(out, x.toFloat(), y.toFloat(), z.toFloat(), Reg.tile(b, 2))
                        continue
                    }
                    val kd = Reg.kind(b)
                    if (kd == Reg.K_FLAT) {
                        emitFloor(out, x.toFloat(), y.toFloat(), z.toFloat(), Reg.tile(b, 2))
                        continue
                    }
                    if (kd == Reg.K_MODEL) {
                        emitModel(out, Shapes.model(w, x, y, z, b), x.toFloat(), y.toFloat(), z.toFloat(), w, x, y, z)
                        continue
                    }
                    if (kd != Reg.K_CUBE) continue
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val bx = x + n[0]
                        val by = y + n[1]
                        val bz = z + n[2]
                        val nb = w.getMesh(bx, by, bz)
                        if (Blocks.opaque(nb)) continue
                        if (b == nb && Reg.glassy(b)) continue
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
        if (Reg.kind(b) == Reg.K_MODEL && !Shapes.spriteItem(b)) {
            emitModel(out, Shapes.model(null, 0, 0, 0, b), 0f, 0f, 0f, null, 0, 0, 0)
            return MeshData(out.a, out.n)
        }
        if (Reg.kind(b) != Reg.K_CUBE) {
            emitFlat(out, Reg.tile(b, 2))
            return MeshData(out.a, out.n)
        }
        for (f in 0 until 6) {
            for (i in 0 until 4) l[i] = SHADE[f]
            emitQuad(out, f, 0f, 0f, 0f, Blocks.tile(b, kind(f)), l)
        }
        return MeshData(out.a, out.n)
    }
}
