package com.mineblocos.app

import android.content.Context

/** Face de um cubo do modelo: índice (0 topo, 1 base, 2 +X, 3 -X, 4 +Z, 5 -Z), instância de material e retângulo de UV (fração da textura). */
class XFace(val f: Int, val inst: String, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val uvRotation: Int = 0)

/** Cubo (possivelmente girado): 8 cantos no mundo, em fração do bloco, na ordem x + 2y + 4z. */
class XCube(val wor: FloatArray, val faces: Array<XFace>)

/** Uma variante do modelo (geometria, rotação, texturas, colisão e seleção) para um conjunto de estados. */
class XVar(
    val cubes: Array<XCube>,
    val rot: Int,
    val rects: HashMap<String, IntArray>,
    val coll: Array<Box>,
    val sel: Array<Box>,
    val tx: Float, val ty: Float, val tz: Float
)

/** Parte de um objeto de vários blocos: índice do segmento e deslocamentos (x,y,z) para cada direção 0..3. */
class XPart(val seg: Int, val off: IntArray)

/** Modelo 3D do addon com tabela de variantes por estado. */
class XModel(
    val key: String,
    val dims: String,
    val segN: Int,
    val famH: IntArray,
    val famV: IntArray,
    val variants: Array<XVar>,
    val table: ShortArray,
    val parts: Array<XPart>,
    val sit: Boolean = false,
    val emit: Int = 0
) {
    val solid: Boolean = variants.any { it.coll.isNotEmpty() }
    val hasH = dims.indexOf('h') >= 0
    val hasV = dims.indexOf('v') >= 0
    val hasOpen = dims.indexOf('o') >= 0
    val hasOn = dims.indexOf('t') >= 0
    val hasMount = dims.indexOf('m') >= 0
    val hasDoor = dims.indexOf('p') >= 0
    val hasHalf = dims.indexOf('d') >= 0
    val hasUp = dims.indexOf('u') >= 0
    val multi: Boolean get() = parts.isNotEmpty()

    private fun sameFam(a: IntArray, b: IntArray): Boolean {
        for (x in a) for (y in b) if (x == y) return true
        return false
    }

    private fun connectH(o: XModel?): Boolean = o != null && o.hasH && (o.key == key || sameFam(famH, o.famH))
    private fun connectV(o: XModel?): Boolean = o != null && o.hasV && (o.key == key || sameFam(famV, o.famV))

    private fun at(w: World, x: Int, y: Int, z: Int): XModel? {
        val b = w.get(x, y, z)
        return if (b > 0 && Shapes.isXModel(b)) Models3D.get(Reg.key(b)) else null
    }

    /** Variante para a posição/meta atuais (conexões vizinhas calculadas no mundo). */
    fun variantAt(w: World?, x: Int, y: Int, z: Int, meta: Int): XVar {
        var idx = meta and 3
        var mult = 4
        for (ch in dims) {
            var v = 0
            var size = 2
            when (ch) {
                'h' -> {
                    size = 16
                    if (w != null) {
                        if (connectH(at(w, x, y, z - 1))) v = v or 1
                        if (connectH(at(w, x + 1, y, z))) v = v or 2
                        if (connectH(at(w, x, y, z + 1))) v = v or 4
                        if (connectH(at(w, x - 1, y, z))) v = v or 8
                    }
                }
                'v' -> {
                    size = 4
                    if (w != null) {
                        if (connectV(at(w, x, y - 1, z))) v = v or 1
                        if (connectV(at(w, x, y + 1, z))) v = v or 2
                    }
                }
                't' -> v = if ((meta and 16) != 0) 1 else 0
                'o' -> v = if ((meta and 4) != 0) 1 else 0
                'm' -> v = if ((meta and 32) != 0) 1 else 0
                's' -> { size = segN; v = minOf((meta shr 2) and 15, segN - 1) }
                'd' -> { size = 3; v = if ((meta and 64) != 0) (if ((meta and 8) != 0) 2 else 1) else 0 }
                'u' -> v = if ((meta and 8) != 0) 1 else 0
                'p' -> { size = 4; v = (meta shr 3) and 3 }
                'e' -> v = if ((meta and 32) != 0) 1 else 0
                'r' -> v = if ((meta and 64) != 0) 1 else 0
            }
            idx += v * mult
            mult *= size
        }
        val t = if (idx < table.size) table[idx].toInt() else 0
        return variants[if (t in variants.indices) t else 0]
    }

    /** Variante usada nos ícones e na mão. */
    fun iconVariant(): XVar {
        var seg = 0
        for (p in parts) if (p.off.all { it == 0 }) { seg = p.seg; break }
        return variantAt(null, 0, 0, 0, if (multi) (seg shl 2) or 2 else 2)
    }
}

/** Móveis e objetos do addon (assets/geo3d.txt + assets/blocks3d.tsv). */
object Models3D {
    private val map = HashMap<String, XModel>()

    private fun parseBoxes(s: String): Array<Box> {
        if (s == "-") return emptyArray()
        if (s == "full") return arrayOf(Box(0f, 0f, 0f, 1f, 1f, 1f, 0, 0, 0))
        val out = ArrayList<Box>()
        for (one in s.split(';')) {
            val p = one.split('_')
            if (p.size < 6) continue
            val f = FloatArray(6) { p[it].toFloat() / 16f }
            out.add(Box(f[0], f[1], f[2], f[3], f[4], f[5], 0, 0, 0))
        }
        return out.toTypedArray()
    }

    private fun ints(s: String): IntArray = if (s == "-" || s.isEmpty()) IntArray(0) else s.split(',').map { it.toInt() }.toIntArray()

    @Synchronized
    fun load(c: Context) {
        if (map.isNotEmpty()) return
        try {
            val geos = HashMap<String, Array<XCube>>()
            var cur = ""
            var list = ArrayList<XCube>()
            c.assets.open("geo3d.txt").bufferedReader(Charsets.UTF_8).forEachLine { l ->
                if (l.isEmpty()) return@forEachLine
                if (l[0] == '#') {
                    if (cur.isNotEmpty()) geos[cur] = list.toTypedArray()
                    cur = l.substring(1).trim()
                    list = ArrayList()
                    return@forEachLine
                }
                val p = l.split(' ')
                val wor = FloatArray(24) { p[it + 1].toFloat() / 16f }
                val faces = ArrayList<XFace>()
                for (i in 25 until p.size) {
                    val a = p[i].split(',')
                    if (a.size < 6) continue
                    faces.add(XFace(a[0].toInt(), a[1], a[2].toFloat(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat()))
                }
                list.add(XCube(wor, faces.toTypedArray()))
            }
            if (cur.isNotEmpty()) geos[cur] = list.toTypedArray()
            c.assets.open("blocks3d.tsv").bufferedReader(Charsets.UTF_8).forEachLine { l ->
                val p = l.split('\t')
                if (p.size < 10) return@forEachLine
                try {
                    val texsets = p[6].split('|').map { ts ->
                        val m = HashMap<String, IntArray>()
                        for (t in ts.split(';')) {
                            val e = t.split('=')
                            if (e.size < 2) continue
                            val r = e[1].split(',')
                            m[e[0]] = IntArray(4) { r[it].toInt() }
                        }
                        m
                    }
                    val vars = ArrayList<XVar>()
                    for (v in p[7].split('|')) {
                        val q = v.split(',')
                        val g = geos[q[0]] ?: emptyArray()
                        val tr = q[5].split('_')
                        vars.add(
                            XVar(
                                g, q[1].toInt(), texsets[q[2].toInt().coerceIn(0, texsets.size - 1)],
                                parseBoxes(q[3]), parseBoxes(q[4]).let { if (it.isEmpty()) arrayOf(Box(0f, 0f, 0f, 1f, 1f, 1f, 0, 0, 0)) else it },
                                tr[0].toFloat(), tr[1].toFloat(), tr[2].toFloat()
                            )
                        )
                    }
                    val table = ShortArray(0).let { _ -> p[8].split(',').map { it.toShort() }.toShortArray() }
                    val parts = ArrayList<XPart>()
                    if (p[9] != "-") for (pt in p[9].split('/')) {
                        val n = pt.split(',').map { it.toInt() }
                        if (n.size >= 13) parts.add(XPart(n[0], IntArray(12) { n[it + 1] }))
                    }
                    map[p[0]] = XModel(
                        p[0], if (p[2] == "-") "" else p[2], p[3].toInt(), ints(p[4]), ints(p[5]),
                        vars.toTypedArray(), table, parts.toTypedArray(),
                        p.size > 10 && p[10] == "sit", if (p.size > 11) (p[11].toIntOrNull() ?: 0) else 0
                    )
                } catch (e: Throwable) {
                    android.util.Log.e("Mineblocos", "modelo inválido: " + p[0], e)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "modelos 3D ausentes", e)
        }
    }

    @Synchronized fun register(model: XModel) { map[model.key] = model }
    fun get(key: String): XModel? = map[key]
    fun has(key: String): Boolean = map.containsKey(key)
}
