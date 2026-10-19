package com.mineblocos.app

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vitrine do mundo plano: todos os blocos do jogo (e decorações) organizados por categoria
 * (assets/vitrine.tsv). Cada categoria começa com uma coluna luminosa e o nome dela aparece no topo da tela
 * quando você passa por ali. Os blocos da vitrine são indestrutíveis e podem ser copiados para a hotbar.
 */
object Showcase {
    private const val SINGLE = 0
    private const val LINE = 1      // 3 peças em fila (conexão horizontal)
    private const val COLUMN = 2    // 3 peças empilhadas (conexão vertical)
    private const val MULTI = 3     // objeto de vários blocos completo
    private const val DOOR3 = 4     // porta do addon de 3 blocos
    private const val DOOR2 = 5     // porta do addon de 2 blocos
    private const val DOOR_V = 6    // porta original (baixo + cima)
    private const val BED = 7

    private class Pose(val meta: Int, val mode: Int)
    private class Slot(val id: Int, val poses: List<Pose>, val sp: Int) {
        fun pw(p: Pose): Int = if (p.mode == LINE) max(sp, 3) + 1 else sp
        val width: Int get() = poses.sumOf { pw(it) }
    }

    private val FAMILIES = arrayOf(
        "_drz_stairs", "_drz_slab", "_drz_vslab", "_drz_pilar", "_drz_quinae", "_drz_quinad",
        "_drz_circulo", "_drz_curva", "_drz_curvah", "_drz_diagonal", "_drz_ventila", "_drz_muro",
        "_drz_vescl", "_drz_vescr"
    )

    private fun derived(k: String): Boolean = FAMILIES.any { k.endsWith(it) }

    private fun partsExtent(xm: XModel): Int {
        var ext = 1
        for (f in 0 until 4) {
            var x0 = 0; var x1 = 0; var z0 = 0; var z1 = 0
            for (p in xm.parts) {
                x0 = min(x0, p.off[f * 3]); x1 = max(x1, p.off[f * 3])
                z0 = min(z0, p.off[f * 3 + 2]); z1 = max(z1, p.off[f * 3 + 2])
            }
            ext = max(ext, max(x1 - x0, z1 - z0) + 1)
        }
        return ext
    }

    private fun xSpacing(xm: XModel): Int {
        val v = xm.iconVariant()
        var minX = 9f; var maxX = -9f; var minZ = 9f; var maxZ = -9f
        for (q in v.cubes) for (i in 0 until 8) {
            val x = q.wor[i * 3]; val z = q.wor[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        var ext = if (maxX >= minX) max(maxX - minX, maxZ - minZ) else 1f
        ext = max(1f, ext)
        var cells = ceil(ext.toDouble() - 0.001).toInt()
        if (xm.multi) cells = max(cells, partsExtent(xm))
        return cells + 1
    }

    private fun slotFor(b: Int): Slot? {
        val k = Reg.key(b)
        val l = ArrayList<Pose>()
        val xm = Models3D.get(k)
        if (xm != null) {
            if (xm.hasDoor) {
                for (open in intArrayOf(0, 4)) for (f in 0 until 4) l.add(Pose(f or open, DOOR3))
                for (f in 0 until 4) l.add(Pose(f, DOOR2))
                return Slot(b, l, 3)
            }
            val extras = ArrayList<Int>()
            extras.add(0)
            if (!xm.multi) {
                val o = if (xm.hasOpen) 4 else 0
                val t = if (xm.hasOn) 16 else 0
                if (o != 0) extras.add(o)
                if (t != 0) extras.add(t)
                if (o != 0 && t != 0) extras.add(o or t)
                if (xm.hasMount) extras.add(32)
                if (xm.hasHalf) { extras.add(64); extras.add(64 or 8) }
                if (xm.hasUp) extras.add(8)
            }
            for (e in extras) for (f in 0 until 4) {
                // estado sem efeito visual neste objeto: não repete a pose
                if (e != 0 && xm.variantAt(null, 0, 0, 0, f or e) === xm.variantAt(null, 0, 0, 0, f)) continue
                l.add(Pose(f or e, if (xm.multi) MULTI else SINGLE))
            }
            if (xm.hasH) l.add(Pose(0, LINE))
            if (xm.hasV) l.add(Pose(0, COLUMN))
            return Slot(b, l, xSpacing(xm))
        }
        val kd = Reg.kind(b)
        if (kd == Reg.K_ITEM || kd == Reg.K_HIDDEN) return null
        if (kd != Reg.K_MODEL) { l.add(Pose(0, SINGLE)); return Slot(b, l, 2) }
        // modelos do jogo / formas derivadas dos cubos do addon
        // (formas derivadas mostram só 1–2 poses: dá para girar ao colocar; assim cabem todos os materiais)
        val der = derived(k)
        when {
            k.endsWith("_drz_stairs") || k.endsWith("_drz_circulo") || k.endsWith("_drz_curva") -> {
                l.add(Pose(0, SINGLE)); l.add(Pose(8, SINGLE))
            }
            Shapes.isSlab(b) -> { l.add(Pose(0, SINGLE)); l.add(Pose(8, SINGLE)); if (!der) l.add(Pose(16, SINGLE)) }
            k.endsWith("_drz_pilar") -> l.add(Pose(0, SINGLE))
            k.endsWith("_drz_muro") -> { l.add(Pose(0, SINGLE)); if (!der) l.add(Pose(1, SINGLE)) }
            Shapes.isDoor(b) -> for (open in intArrayOf(0, 4)) for (f in 0 until 4) l.add(Pose(f or open, DOOR_V))
            Shapes.isBed(b) -> for (f in 0 until 4) l.add(Pose(f, BED))
            der -> l.add(Pose(0, SINGLE))
            else -> {
                for (f in 0 until 4) l.add(Pose(f, SINGLE))
                if (Shapes.interactive(b)) for (f in 0 until 4) l.add(Pose(f or 4, SINGLE))
            }
        }
        return Slot(b, l, if (Shapes.isBed(b)) 3 else 2)
    }

    private class Section(val name: String, val ids: List<Int>)

    private fun eligible(b: Int): Boolean {
        val kd = Reg.kind(b)
        return b > 0 && kd != Reg.K_ITEM && kd != Reg.K_HIDDEN
    }

    /** Lê as categorias de assets/vitrine.tsv. Tudo que não estiver listado cai em "Diversos". */
    private fun sections(): List<Section> {
        val out = ArrayList<Section>()
        val used = HashSet<Int>()
        var lines: List<String> = emptyList()
        try {
            val c = Atlas.context()
            if (c != null) lines = c.assets.open("vitrine.tsv").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "vitrine.tsv ausente; usando categoria única", e)
        }
        var name = ""
        var cur = ArrayList<Int>()
        fun flush() {
            if (name.isNotEmpty() && cur.isNotEmpty()) out.add(Section(name, cur))
            cur = ArrayList()
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                flush()
                name = line.substring(1).trim()
            } else if (line[0] == '@') {
                flush()
                name = ""
                val p = line.split('\t')
                if (p.size >= 3) {
                    val l = ArrayList<Int>()
                    for (b in 1 until Reg.count) {
                        if (Reg.kind(b) == Reg.K_MODEL && Reg.key(b).endsWith(p[1])) { l.add(b); used.add(b) }
                    }
                    if (l.isNotEmpty()) out.add(Section(p[2], l))
                }
            } else {
                val b = Reg.id(line.trim())
                if (b > 0 && eligible(b) && used.add(b)) cur.add(b)
            }
        }
        flush()
        val rest = ArrayList<Int>()
        for (b in 1 until Reg.count) {
            if (eligible(b) && !used.contains(b) && !derived(Reg.key(b))) rest.add(b)
        }
        if (rest.isNotEmpty()) out.add(Section("Diversos", rest))
        return out
    }

    private fun put(w: World, x: Int, y: Int, z: Int, id: Int, meta: Int) {
        w.putShowcase(x, y, z, id, meta)
    }

    private fun place(w: World, id: Int, pose: Pose, x: Int, y: Int, z: Int, sp: Int) {
        val f = pose.meta and 3
        val c = (sp - 1) / 2
        val xx = x + c
        val zz = z + c
        when (pose.mode) {
            SINGLE -> put(w, xx, y, zz, id, pose.meta)
            LINE -> for (i in 0 until 3) put(w, x + i, y, zz, id, pose.meta)
            COLUMN -> for (i in 0 until 3) put(w, xx, y + i, zz, id, pose.meta)
            MULTI -> {
                val xm = Models3D.get(Reg.key(id)) ?: return
                var mx = 0; var mz = 0
                for (p in xm.parts) { mx = min(mx, p.off[f * 3]); mz = min(mz, p.off[f * 3 + 2]) }
                for (p in xm.parts) put(w, x - mx + p.off[f * 3], y + p.off[f * 3 + 1], z - mz + p.off[f * 3 + 2], id, f or (p.seg shl 2))
            }
            DOOR3 -> {
                val open = pose.meta and 4
                val types = intArrayOf(3, 1, 0)
                for (i in types.indices) put(w, xx, y + i, zz, id, f or open or (types[i] shl 3) or 32)
            }
            DOOR2 -> {
                val types = intArrayOf(2, 0)
                for (i in types.indices) put(w, xx, y + i, zz, id, f or (types[i] shl 3))
            }
            DOOR_V -> {
                put(w, xx, y, zz, id, pose.meta)
                put(w, xx, y + 1, zz, id, pose.meta or Shapes.M_UPPER)
            }
            BED -> {
                put(w, xx, y, zz, id, f)
                put(w, xx + Shapes.dirX(f), y, zz + Shapes.dirZ(f), id, f or Shapes.M_SECOND)
            }
        }
    }

    /** Nomes de todas as categorias da vitrine, na ordem em que aparecem no mundo. */
    fun categoryNames(): List<String> = sections().map { it.name }

    /** Monta a vitrine só com as categorias em [selected] (null = todas). */
    fun build(w: World, ground: Int, selected: Set<String>? = null) {
        val y = ground + 1
        val x0 = 8
        val x1 = World.W - 8
        // faixas: sul (a partir do ponto de partida, deixando o spawn livre), norte e uma faixa central
        val bands = arrayOf(intArrayOf(270, World.D - 8), intArrayOf(8, 230), intArrayOf(232, 250))
        var band = 0
        var cx = x0
        var cz = bands[0][0]
        var rowH = 0
        var lineStart = cz
        var lineName = ""
        val marker = Reg.id("glowstone")
        var full = false
        w.areas.clear()

        fun closeLine() {
            if (rowH > 0) w.areas.add(World.Area(lineName, x0 - 6, x1 + 4, lineStart, lineStart + rowH))
        }

        fun newLine(gap: Int) {
            closeLine()
            cx = x0
            cz += rowH + gap
            rowH = 0
            lineStart = cz
        }

        for (sec in sections()) {
            if (full) break
            if (selected != null && sec.name !in selected) continue
            // começo de categoria: nova linha e uma coluna luminosa como marco
            if (cx != x0 || rowH > 0) newLine(1)
            lineName = sec.name
            if (marker > 0) for (i in 0 until 10) put(w, x0 - 4, y + i, cz, marker, 0)
            // peças de mesmo tamanho ficam juntas (linhas uniformes), em ordem alfabética
            val slots = ArrayList<Slot>()
            for (b in sec.ids) { val sl = slotFor(b); if (sl != null) slots.add(sl) }
            slots.sortWith(compareBy<Slot>({ it.sp }, { Reg.name(it.id).lowercase() }))
            for (slot in slots) {
                val sw = slot.width
                if (cx + sw > x1) newLine(0)
                if (cz + slot.sp > bands[band][1]) {
                    closeLine()
                    band++
                    if (band >= bands.size) { full = true; break }
                    cx = x0; cz = bands[band][0]; rowH = 0; lineStart = cz
                }
                var px = cx
                for (p in slot.poses) {
                    place(w, slot.id, p, px, y, cz, slot.sp)
                    px += slot.pw(p)
                }
                cx += sw
                rowH = max(rowH, slot.sp)
            }
        }
        closeLine()
    }
}
