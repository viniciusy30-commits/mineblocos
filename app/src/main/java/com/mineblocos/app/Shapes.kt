package com.mineblocos.app

/** Caixa de um modelo de bloco (0..1 dentro da célula). Textura mapeada automaticamente, como no Minecraft. */
class Box(
    val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float,
    val top: Int, val bot: Int, val side: Int,
    val faces: Int = 63
)

/** Modelo completo: caixas + (opcionalmente) um sprite em cruz, como a planta dentro do vaso. */
class Model(
    val boxes: Array<Box>,
    val cross: Int = -1,
    val crossScale: Float = 1f,
    val crossY: Float = 0f
)

/**
 * Formas reais dos blocos (cerca, portão, cacto, laje, vaso, composteira...).
 * A forma é decidida pelo id do bloco no registro.
 */
object Shapes {
    private const val NONE = 0
    private const val FENCE = 1
    private const val GATE = 2
    private const val CACTUS = 3
    private const val SLAB = 4
    private const val COMPOSTER = 5
    private const val POT = 6
    private const val POT_AZALEA = 7
    private const val FARMLAND = 8
    private const val PATH = 9
    private const val HEAVY_CORE = 10
    private const val AZALEA = 11
    private const val BUD_S = 12
    private const val BUD_M = 13
    private const val BUD_L = 14
    private const val SEA_PICKLE = 15
    private const val CARPET = 16

    private var cache = IntArray(0)

    private fun shapeOf(b: Int): Int {
        if (cache.size != Reg.count) {
            cache = IntArray(Reg.count) { i ->
                when (Reg.key(i)) {
                    "bamboo_fence" -> FENCE
                    "bamboo_fence_gate" -> GATE
                    "cactus" -> CACTUS
                    "stone_slab" -> SLAB
                    "compost", "compost_ready" -> COMPOSTER
                    "crimson_roots_pot", "warped_roots_pot", "fungus_crimson_3d_pot", "fungus_warped_3d_pot",
                    "mushroom_brown_3d_pot", "mushroom_red_3d_pot" -> POT
                    "potted_azalea_bush", "potted_flowering_azalea_bush" -> POT_AZALEA
                    "farmland_dry", "farmland_wet" -> FARMLAND
                    "grass_path" -> PATH
                    "heavy_core" -> HEAVY_CORE
                    "azalea" -> AZALEA
                    "small_amethyst_bud" -> BUD_S
                    "medium_amethyst_bud" -> BUD_M
                    "large_amethyst_bud" -> BUD_L
                    "sea_pickle" -> SEA_PICKLE
                    "pale_moss" -> CARPET
                    else -> NONE
                }
            }
        }
        return if (b > 0 && b < cache.size) cache[b] else NONE
    }

    private fun bx(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        top: Int, bot: Int, side: Int, faces: Int = 63
    ) = Box(x0 / 16f, y0 / 16f, z0 / 16f, x1 / 16f, y1 / 16f, z1 / 16f, top, bot, side, faces)

    private fun bx(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, t: Int) =
        bx(x0, y0, z0, x1, y1, z1, t, t, t)

    /** Blocos que têm forma própria (kind MODEL). */
    fun isModel(b: Int) = shapeOf(b) != NONE

    /** Cercas e portões: colisão de 1,5 bloco de altura. */
    fun tall(b: Int): Boolean { val s = shapeOf(b); return s == FENCE || s == GATE }

    /** Altura da colisão (0..1,5) para blocos sólidos. */
    fun top(b: Int): Float = when (shapeOf(b)) {
        FENCE, GATE -> 1.5f
        SLAB -> 0.5f
        FARMLAND, PATH -> 0.9375f
        POT, POT_AZALEA -> 0.375f
        HEAVY_CORE -> 0.5f
        else -> 1f
    }

    fun solid(b: Int): Boolean = when (shapeOf(b)) {
        FENCE, GATE, CACTUS, SLAB, COMPOSTER, POT, POT_AZALEA, FARMLAND, PATH, HEAVY_CORE, AZALEA -> true
        else -> false
    }

    private fun connects(w: World, x: Int, y: Int, z: Int): Boolean {
        val n = w.get(x, y, z)
        val s = shapeOf(n)
        return s == FENCE || s == GATE || Reg.opaque(n)
    }

    private fun fenceBoxes(t: Int, n: Boolean, s: Boolean, e: Boolean, wst: Boolean, hit: Boolean): Array<Box> {
        val l = ArrayList<Box>()
        l.add(bx(6f, 0f, 6f, 10f, 16f, 10f, t))
        if (hit) {
            if (n) l.add(bx(7f, 6f, 0f, 9f, 15f, 6f, t))
            if (s) l.add(bx(7f, 6f, 10f, 9f, 15f, 16f, t))
            if (wst) l.add(bx(0f, 6f, 7f, 6f, 15f, 9f, t))
            if (e) l.add(bx(10f, 6f, 7f, 16f, 15f, 9f, t))
        } else {
            if (n) { l.add(bx(7f, 12f, 0f, 9f, 15f, 6f, t)); l.add(bx(7f, 6f, 0f, 9f, 9f, 6f, t)) }
            if (s) { l.add(bx(7f, 12f, 10f, 9f, 15f, 16f, t)); l.add(bx(7f, 6f, 10f, 9f, 9f, 16f, t)) }
            if (wst) { l.add(bx(0f, 12f, 7f, 6f, 15f, 9f, t)); l.add(bx(0f, 6f, 7f, 6f, 9f, 9f, t)) }
            if (e) { l.add(bx(10f, 12f, 7f, 16f, 15f, 9f, t)); l.add(bx(10f, 6f, 7f, 16f, 9f, 9f, t)) }
        }
        return l.toTypedArray()
    }

    private fun gateBoxes(t: Int, alongZ: Boolean, hit: Boolean): Array<Box> {
        val l = ArrayList<Box>()
        if (hit) {
            l.add(bx(0f, 5f, 7f, 16f, 16f, 9f, t))
        } else {
            l.add(bx(0f, 5f, 7f, 2f, 16f, 9f, t))
            l.add(bx(14f, 5f, 7f, 16f, 16f, 9f, t))
            l.add(bx(2f, 6f, 7f, 14f, 9f, 9f, t))
            l.add(bx(2f, 12f, 7f, 14f, 15f, 9f, t))
            l.add(bx(2f, 9f, 7f, 6f, 12f, 9f, t))
            l.add(bx(10f, 9f, 7f, 14f, 12f, 9f, t))
            l.add(bx(6f, 9f, 7f, 10f, 12f, 9f, t))
        }
        if (!alongZ) return l.toTypedArray()
        return Array(l.size) { i ->
            val b = l[i]
            Box(b.z0, b.y0, b.x0, b.z1, b.y1, b.x1, b.top, b.bot, b.side, b.faces)
        }
    }

    /**
     * Modelo do bloco. Com [w] nulo (mão / ícone de inventário) mostra a cerca com os braços dos dois lados.
     * Para [hit] = true devolve as caixas de seleção (mira), mescladas.
     */
    fun model(w: World?, x: Int, y: Int, z: Int, b: Int, hit: Boolean = false): Model {
        val side = Reg.tile(b, 2)
        val top = Reg.tile(b, 0)
        val bot = Reg.tile(b, 1)
        when (shapeOf(b)) {
            FENCE -> {
                val t = Reg.tile(Reg.id("bamboo_planks"), 2)
                return if (w == null) Model(fenceBoxes(t, false, false, true, true, hit))
                else Model(
                    fenceBoxes(
                        t, connects(w, x, y, z - 1), connects(w, x, y, z + 1),
                        connects(w, x + 1, y, z), connects(w, x - 1, y, z), hit
                    )
                )
            }
            GATE -> {
                val t = Reg.tile(Reg.id("bamboo_planks"), 2)
                var alongZ = false
                if (w != null) {
                    val ns = connects(w, x, y, z - 1) || connects(w, x, y, z + 1)
                    val ew = connects(w, x + 1, y, z) || connects(w, x - 1, y, z)
                    alongZ = ns && !ew
                }
                return Model(gateBoxes(t, alongZ, hit))
            }
            CACTUS -> return Model(arrayOf(bx(1f, 0f, 1f, 15f, 16f, 15f, top, bot, side)))
            SLAB -> return Model(arrayOf(bx(0f, 0f, 0f, 16f, 8f, 16f, top)))
            COMPOSTER -> {
                val wall = Reg.tile(Reg.id("planks_spruce"), 2)
                val level = if (Reg.key(b) == "compost_ready") 13f else 7f
                return Model(
                    arrayOf(
                        bx(0f, 0f, 0f, 16f, 2f, 16f, wall),
                        bx(0f, 2f, 0f, 16f, 16f, 2f, wall),
                        bx(0f, 2f, 14f, 16f, 16f, 16f, wall),
                        bx(0f, 2f, 2f, 2f, 16f, 14f, wall),
                        bx(14f, 2f, 2f, 16f, 16f, 14f, wall),
                        bx(2f, 2f, 2f, 14f, level, 14f, top, top, top, 1)
                    )
                )
            }
            POT -> {
                val clay = Reg.tile(Reg.id("hardened_clay"), 2)
                val dirt = Reg.tile(Reg.id("dirt"), 2)
                return Model(
                    arrayOf(
                        bx(5f, 0f, 5f, 11f, 1f, 11f, clay),
                        bx(5f, 1f, 5f, 11f, 6f, 6f, clay),
                        bx(5f, 1f, 10f, 11f, 6f, 11f, clay),
                        bx(5f, 1f, 6f, 6f, 6f, 10f, clay),
                        bx(10f, 1f, 6f, 11f, 6f, 10f, clay),
                        bx(6f, 1f, 6f, 10f, 4f, 10f, dirt)
                    ),
                    cross = side, crossScale = 0.75f, crossY = 0.3f
                )
            }
            POT_AZALEA -> {
                val clay = Reg.tile(Reg.id("hardened_clay"), 2)
                val dirt = Reg.tile(Reg.id("dirt"), 2)
                return Model(
                    arrayOf(
                        bx(5f, 0f, 5f, 11f, 1f, 11f, clay),
                        bx(5f, 1f, 5f, 11f, 6f, 6f, clay),
                        bx(5f, 1f, 10f, 11f, 6f, 11f, clay),
                        bx(5f, 1f, 6f, 6f, 6f, 10f, clay),
                        bx(10f, 1f, 6f, 11f, 6f, 10f, clay),
                        bx(6f, 1f, 6f, 10f, 4f, 10f, dirt),
                        bx(3f, 6f, 3f, 13f, 14f, 13f, top, top, side)
                    )
                )
            }
            FARMLAND -> return Model(arrayOf(bx(0f, 0f, 0f, 16f, 15f, 16f, top, Reg.tile(Reg.id("dirt"), 2), Reg.tile(Reg.id("dirt"), 2))))
            PATH -> return Model(arrayOf(bx(0f, 0f, 0f, 16f, 15f, 16f, top, bot, side)))
            HEAVY_CORE -> return Model(arrayOf(bx(4f, 0f, 4f, 12f, 8f, 12f, top, bot, side)))
            AZALEA -> {
                val log = Reg.tile(Reg.id("log"), 2)
                return Model(
                    arrayOf(
                        bx(7f, 0f, 7f, 9f, 8f, 9f, log),
                        bx(0f, 8f, 0f, 16f, 16f, 16f, top, top, side)
                    )
                )
            }
            BUD_S -> {
                val t = Reg.tile(Reg.id("amethyst_block"), 2)
                return Model(arrayOf(bx(5f, 0f, 5f, 11f, 2f, 11f, t), bx(6f, 2f, 6f, 10f, 4f, 10f, t)))
            }
            BUD_M -> {
                val t = Reg.tile(Reg.id("amethyst_block"), 2)
                return Model(arrayOf(bx(4f, 0f, 4f, 12f, 3f, 12f, t), bx(5f, 3f, 5f, 11f, 5f, 11f, t), bx(6f, 5f, 6f, 10f, 7f, 10f, t)))
            }
            BUD_L -> {
                val t = Reg.tile(Reg.id("amethyst_block"), 2)
                return Model(
                    arrayOf(
                        bx(3f, 0f, 3f, 13f, 3f, 13f, t), bx(4f, 3f, 4f, 12f, 6f, 12f, t),
                        bx(5f, 6f, 5f, 11f, 8f, 11f, t), bx(6f, 8f, 6f, 10f, 10f, 10f, t)
                    )
                )
            }
            SEA_PICKLE -> return Model(arrayOf(bx(6f, 0f, 6f, 10f, 6f, 10f, top, bot, side)))
            CARPET -> return Model(arrayOf(bx(0f, 0f, 0f, 16f, 1f, 16f, top, bot, side)))
        }
        return Model(arrayOf(bx(0f, 0f, 0f, 16f, 16f, 16f, top, bot, side)))
    }

    private val plantBox = floatArrayOf(0.15f, 0f, 0.15f, 0.85f, 0.8f, 0.85f)
    private val flatBox = floatArrayOf(0f, 0f, 0f, 1f, 0.0625f, 1f)

    /** Caixas de seleção em coordenadas 0..1, ou null para cubo inteiro. */
    fun hitBoxes(w: World, x: Int, y: Int, z: Int, b: Int): FloatArray? {
        when (Reg.kind(b)) {
            Reg.K_CROSS -> return plantBox
            Reg.K_FLAT -> return flatBox
            Reg.K_MODEL -> {
                val bs = model(w, x, y, z, b, true).boxes
                val out = FloatArray(bs.size * 6)
                for ((i, q) in bs.withIndex()) {
                    out[i * 6] = q.x0; out[i * 6 + 1] = q.y0; out[i * 6 + 2] = q.z0
                    out[i * 6 + 3] = q.x1; out[i * 6 + 4] = q.y1; out[i * 6 + 5] = q.z1
                }
                return out
            }
        }
        return null
    }

    /** Caixa que envolve tudo (para o contorno da mira). */
    fun bounds(w: World, x: Int, y: Int, z: Int, b: Int): FloatArray? {
        val h = hitBoxes(w, x, y, z, b) ?: return null
        val o = floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f)
        var i = 0
        while (i < h.size) {
            for (k in 0 until 3) {
                if (h[i + k] < o[k]) o[k] = h[i + k]
                if (h[i + 3 + k] > o[3 + k]) o[3 + k] = h[i + 3 + k]
            }
            i += 6
        }
        return o
    }
}
