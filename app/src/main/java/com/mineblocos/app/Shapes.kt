package com.mineblocos.app

/** Caixa de um modelo de bloco (0..1 dentro da célula). Textura mapeada automaticamente, como no Minecraft. */
class Box(
    val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float,
    val top: Int, val bot: Int, val side: Int,
    val faces: Int = 63,
    val vOff: Float = 0f,
    val vMul: Float = 1f
)

/** Modelo completo: caixas + (opcionalmente) um sprite em cruz, como a planta dentro do vaso. */
class Model(
    val boxes: Array<Box>,
    val cross: Int = -1,
    val crossScale: Float = 1f,
    val crossY: Float = 0f,
    val rc: Array<RCube>? = null,
    val rf: Int = 0,
    val rt: Int = 0,
    val rb: Int = 0,
    val rs: Int = 0
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
    private const val DOOR = 17
    private const val BED = 18
    private const val SIGN = 19
    private const val LANTERN = 20
    private const val CHAIN = 21
    private const val CAMPFIRE = 22
    private const val CAULDRON = 23
    private const val HOPPER = 24
    private const val BREWING = 25
    private const val LEVER = 26
    private const val POT_EMPTY = 27
    private const val CAKE = 28
    private const val DIODE = 29
    private const val STAIRS = 30
    private const val VSLAB = 31
    private const val PILLAR = 32
    private const val QCL = 33
    private const val QCR = 34
    private const val CIRCLE = 35
    private const val CURVE = 36
    private const val CURVEH = 37
    private const val DIAG = 38
    private const val VENT = 39
    private const val FWALL = 40

    /** Bits do estado do bloco (World.meta). */
    const val M_OPEN = 4
    const val M_UPPER = 8
    const val M_SECOND = 16

    private var sprite = BooleanArray(0)

    /** Itens que viraram blocos: no inventário continuam como ícone plano. */
    fun spriteItem(b: Int): Boolean { shapeOf(b); return b > 0 && b < sprite.size && sprite[b] }

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
                    "item_lantern", "item_soul_lantern", "item_copper_lantern", "item_exposed_copper_lantern",
                    "item_weathered_copper_lantern", "item_oxidized_copper_lantern" -> LANTERN
                    "item_chain", "item_copper_chain", "item_exposed_copper_chain",
                    "item_weathered_copper_chain", "item_oxidized_copper_chain" -> CHAIN
                    "item_campfire", "item_soul_campfire" -> CAMPFIRE
                    "item_cauldron" -> CAULDRON
                    "item_hopper" -> HOPPER
                    "item_brewing_stand" -> BREWING
                    "item_lever" -> LEVER
                    "item_flower_pot" -> POT_EMPTY
                    "item_cake" -> CAKE
                    "item_repeater", "item_comparator" -> DIODE
                    else -> {
                        val k = Reg.key(i)
                        if (k.endsWith("_drz_stairs")) STAIRS
                        else if (k.endsWith("_drz_vslab")) VSLAB
                        else if (k.endsWith("_drz_pilar")) PILLAR
                        else if (k.endsWith("_drz_circulo")) CIRCLE
                        else if (k.endsWith("_drz_curva")) CURVE
                        else if (k.endsWith("_drz_curvah")) CURVEH
                        else if (k.endsWith("_drz_diagonal")) DIAG
                        else if (k.endsWith("_drz_ventila")) VENT
                        else if (k.endsWith("_drz_muro")) FWALL
                        else if (k.endsWith("_drz_quinae")) QCL
                        else if (k.endsWith("_drz_quinad")) QCR
                        else if (k.endsWith("_drz_slab")) SLAB
                        else if (k.startsWith("item_bed_")) BED
                        else if (k.startsWith("item_") && k.contains("sign")) SIGN
                        else if (k.startsWith("item_") && k.contains("door") && Reg.kind(i) == Reg.K_MODEL) DOOR
                        else NONE
                    }
                }
            }
            sprite = BooleanArray(Reg.count) { i -> i > 0 && Reg.key(i).startsWith("item_") && cache[i] != NONE }
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

    /** Parte de baixo da colisão (0..1) dentro da célula. */
    fun lo(b: Int, m: Int): Float = if (shapeOf(b) == SLAB && (m and M_UPPER) != 0 && (m and M_SECOND) == 0) 0.5f else 0f

    /** Topo da colisão (0..1,5) dentro da célula. */
    fun hi(b: Int, m: Int): Float = when (shapeOf(b)) {
        FENCE, GATE -> 1.5f
        SLAB -> if ((m and M_SECOND) != 0 || (m and M_UPPER) != 0) 1f else 0.5f
        FARMLAND, PATH -> 0.9375f
        POT, POT_AZALEA, POT_EMPTY -> 0.375f
        HEAVY_CORE, CAKE -> 0.5f
        BED -> 0.5625f
        CAMPFIRE -> 0.4375f
        DIODE -> 0.125f
        else -> 1f
    }

    fun solid(b: Int): Boolean = when (shapeOf(b)) {
        FENCE, GATE, CACTUS, SLAB, STAIRS, VSLAB, PILLAR, QCL, QCR, CIRCLE, CURVE, CURVEH, DIAG, VENT, FWALL, COMPOSTER, POT, POT_AZALEA, POT_EMPTY, FARMLAND, PATH, HEAVY_CORE, AZALEA,
        DOOR, BED, CAULDRON, HOPPER, CAKE, DIODE, CAMPFIRE -> true
        else -> false
    }

    /** Portas e portões abertos não bloqueiam. */
    fun solidAt(b: Int, m: Int): Boolean {
        val sh = shapeOf(b)
        if ((sh == GATE || sh == DOOR) && (m and M_OPEN) != 0) return false
        return true
    }

    /** Portas e portões abrem/fecham ao tocar. */
    fun interactive(b: Int): Boolean { val sh = shapeOf(b); return sh == GATE || sh == DOOR }

    fun isSlab(b: Int) = shapeOf(b) == SLAB
    fun isStairs(b: Int) = shapeOf(b) == STAIRS
    fun isVSlab(b: Int) = shapeOf(b) == VSLAB

    /** Formas cuja colisão segue as caixas do modelo (escadas, lajes verticais, pilares, quinas). */
    fun boxCollide(b: Int): Boolean { val s = shapeOf(b); return s == STAIRS || s == VSLAB || s == PILLAR || s == QCL || s == QCR ||
        s == CIRCLE || s == CURVE || s == CURVEH || s == DIAG || s == VENT || s == FWALL }

    /** Escadas, círculos e curvas podem ser colocados de cabeça para baixo (M_UPPER). */
    fun flippable(b: Int): Boolean { val s = shapeOf(b); return s == STAIRS || s == CIRCLE || s == CURVE }

    private fun stairFacing(w: World, x: Int, y: Int, z: Int, up: Int): Int {
        val n = w.get(x, y, z)
        if (shapeOf(n) != STAIRS) return -1
        val m = w.getMeta(x, y, z)
        return if ((m and M_UPPER) == up) (m and 3) else -1
    }

    /** A vizinha pode "tomar" o formato? (regra do Minecraft para cantos de escada) */
    private fun canTake(w: World, x: Int, y: Int, z: Int, f: Int, up: Int): Boolean {
        val n = w.get(x, y, z)
        if (shapeOf(n) != STAIRS) return true
        val m = w.getMeta(x, y, z)
        return (m and 3) != f || (m and M_UPPER) != up
    }

    /** 0 = reta, 1 = externa esq., 2 = externa dir., 3 = interna esq., 4 = interna dir. */
    private fun stairShape(w: World?, x: Int, y: Int, z: Int, m: Int): Int {
        if (w == null) return 0
        val f = m and 3
        val up = m and M_UPPER
        val dx = dirX(f); val dz = dirZ(f)
        val left = (f + 3) and 3
        val back = stairFacing(w, x + dx, y, z + dz, up)
        if (back >= 0 && ((back xor f) and 1) == 1) {
            val o = (back + 2) and 3
            if (canTake(w, x + dirX(o), y, z + dirZ(o), f, up)) return if (back == left) 1 else 2
        }
        val front = stairFacing(w, x - dx, y, z - dz, up)
        if (front >= 0 && ((front xor f) and 1) == 1) {
            if (canTake(w, x + dirX(front), y, z + dirZ(front), f, up)) return if (front == left) 3 else 4
        }
        return 0
    }
    fun isDoor(b: Int) = shapeOf(b) == DOOR
    fun isBed(b: Int) = shapeOf(b) == BED

    private val DX = intArrayOf(0, 1, 0, -1)
    private val DZ = intArrayOf(-1, 0, 1, 0)
    fun dirX(f: Int) = DX[f and 3]
    fun dirZ(f: Int) = DZ[f and 3]

    /** Rotaciona caixas (modelo feito olhando para -Z) conforme a direção f (0..3). */
    private fun rot(src: Array<Box>, f: Int): Array<Box> {
        if (f == 0) return src
        return Array(src.size) { i ->
            val q = src[i]
            var x0 = q.x0; var z0 = q.z0; var x1 = q.x1; var z1 = q.z1
            var mask = q.faces
            for (k in 0 until f) {
                val nx0 = 1f - z1; val nx1 = 1f - z0; val nz0 = x0; val nz1 = x1
                x0 = nx0; x1 = nx1; z0 = nz0; z1 = nz1
                var nm = mask and 3
                if ((mask and 32) != 0) nm = nm or 4   // -Z -> +X
                if ((mask and 4) != 0) nm = nm or 16   // +X -> +Z
                if ((mask and 16) != 0) nm = nm or 8   // +Z -> -X
                if ((mask and 8) != 0) nm = nm or 32   // -X -> -Z
                mask = nm
            }
            Box(x0, q.y0, z0, x1, q.y1, z1, q.top, q.bot, q.side, mask, q.vOff, q.vMul)
        }
    }

    private fun woodTile(key: String): Int {
        val n = when {
            key.contains("bamboo") -> "bamboo_planks"
            key.contains("cherry") -> "cherry_planks"
            key.contains("crimson") -> "red_nether_brick"
            key.contains("warped") -> "warped_wart_block"
            key.contains("mangrove") -> "mangrove_planks"
            key.contains("pale_oak") -> "pale_oak_planks"
            key.contains("acacia") -> "planks_acacia"
            key.contains("birch") -> "planks_birch"
            key.contains("dark_oak") || key.contains("darkoak") -> "planks_big_oak"
            key.contains("jungle") -> "planks_jungle"
            key.contains("spruce") -> "planks_spruce"
            key.contains("iron") -> "iron_block"
            key.contains("oxidized_copper") -> "oxidized_copper"
            key.contains("weathered_copper") -> "weathered_copper"
            key.contains("exposed_copper") -> "exposed_copper"
            key.contains("copper") -> "copper_block"
            else -> "planks"
        }
        return Reg.tile(Reg.id(n), 2)
    }

    private fun woolTile(key: String): Int {
        var c = key.removePrefix("item_bed_")
        c = when (c) { "light_blue" -> "blue"; "light_gray" -> "silver"; else -> c }
        return Reg.tile(Reg.id("wool_colored_$c"), 2)
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

    private fun gateBoxes(t: Int, open: Boolean, hit: Boolean): Array<Box> {
        val l = ArrayList<Box>()
        if (hit) {
            if (open) {
                l.add(bx(0f, 5f, 7f, 2f, 16f, 9f, t)); l.add(bx(14f, 5f, 7f, 16f, 16f, 9f, t))
            } else l.add(bx(0f, 5f, 7f, 16f, 16f, 9f, t))
        } else {
            l.add(bx(0f, 5f, 7f, 2f, 16f, 9f, t))
            l.add(bx(14f, 5f, 7f, 16f, 16f, 9f, t))
            if (open) {
                l.add(bx(0f, 6f, 9f, 2f, 9f, 15f, t)); l.add(bx(0f, 12f, 9f, 2f, 15f, 15f, t))
                l.add(bx(14f, 6f, 9f, 16f, 9f, 15f, t)); l.add(bx(14f, 12f, 9f, 16f, 15f, 15f, t))
                l.add(bx(0f, 9f, 13f, 2f, 12f, 15f, t)); l.add(bx(14f, 9f, 13f, 16f, 12f, 15f, t))
            } else {
                l.add(bx(2f, 6f, 7f, 14f, 9f, 9f, t))
                l.add(bx(2f, 12f, 7f, 14f, 15f, 9f, t))
                l.add(bx(2f, 9f, 7f, 6f, 12f, 9f, t))
                l.add(bx(10f, 9f, 7f, 14f, 12f, 9f, t))
                l.add(bx(6f, 9f, 7f, 10f, 12f, 9f, t))
            }
        }
        return l.toTypedArray()
    }

    /**
     * Modelo do bloco. Com [w] nulo (mão / ícone de inventário) mostra a cerca com os braços dos dois lados.
     * Para [hit] = true devolve as caixas de seleção (mira), mescladas.
     */
    fun model(w: World?, x: Int, y: Int, z: Int, b: Int, hit: Boolean = false): Model {
        val side = Reg.tile(b, 2)
        val top = Reg.tile(b, 0)
        val bot = Reg.tile(b, 1)
        val m = w?.getMeta(x, y, z) ?: 0
        val f = m and 3
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
                return Model(rot(gateBoxes(t, (m and M_OPEN) != 0, hit), f))
            }
            CACTUS -> return Model(arrayOf(bx(1f, 0f, 1f, 15f, 16f, 15f, top, bot, side)))
            SLAB -> {
                if ((m and M_SECOND) != 0) return Model(arrayOf(bx(0f, 0f, 0f, 16f, 16f, 16f, top, bot, side)))
                return if ((m and M_UPPER) != 0) Model(arrayOf(bx(0f, 8f, 0f, 16f, 16f, 16f, top, bot, side)))
                else Model(arrayOf(bx(0f, 0f, 0f, 16f, 8f, 16f, top, bot, side)))
            }
            STAIRS -> {
                // modelo olhando para -Z: degrau alto no fundo; M_UPPER = escada invertida; cantos automáticos
                val up = (m and M_UPPER) != 0
                val sh = stairShape(w, x, y, z, m)
                val lower = bx(0f, if (up) 8f else 0f, 0f, 16f, if (up) 16f else 8f, 16f, top, bot, side)
                val uy0 = if (up) 0f else 8f
                val uy1 = if (up) 8f else 16f
                val mk = if (up) 62 else 61
                val mk2 = mk and 32.inv()
                val ups: Array<Box> = when (sh) {
                    1 -> arrayOf(bx(0f, uy0, 0f, 8f, uy1, 8f, top, bot, side, mk))
                    2 -> arrayOf(bx(8f, uy0, 0f, 16f, uy1, 8f, top, bot, side, mk))
                    3 -> arrayOf(bx(0f, uy0, 0f, 16f, uy1, 8f, top, bot, side, mk), bx(0f, uy0, 8f, 8f, uy1, 16f, top, bot, side, mk2))
                    4 -> arrayOf(bx(0f, uy0, 0f, 16f, uy1, 8f, top, bot, side, mk), bx(8f, uy0, 8f, 16f, uy1, 16f, top, bot, side, mk2))
                    else -> arrayOf(bx(0f, uy0, 0f, 16f, uy1, 8f, top, bot, side, mk))
                }
                return Model(rot(arrayOf(lower) + ups, f))
            }
            VSLAB -> {
                if ((m and M_SECOND) != 0) return Model(arrayOf(bx(0f, 0f, 0f, 16f, 16f, 16f, top, bot, side)))
                return Model(rot(arrayOf(bx(0f, 0f, 0f, 16f, 16f, 8f, top, bot, side)), f))
            }
            PILLAR -> return Model(arrayOf(bx(4f, 0f, 4f, 12f, 16f, 12f, top, bot, side)))
            QCL -> return Model(rot(arrayOf(bx(0f, 0f, 0f, 8f, 16f, 8f, top, bot, side)), f))
            QCR -> return Model(rot(arrayOf(bx(8f, 0f, 0f, 16f, 16f, 8f, top, bot, side)), f))
            FWALL -> return Model(rot(arrayOf(bx(0f, 0f, 4f, 16f, 16f, 12f, top, bot, side)), f))
            CIRCLE, CURVE, CURVEH, DIAG, VENT -> {
                val up = (m and M_UPPER) != 0
                val n = when (shapeOf(b)) {
                    CIRCLE -> if (up) "circle_flip" else "circle"
                    CURVE -> if (up) "curve_flip" else "curve"
                    CURVEH -> "curveh"
                    DIAG -> "diag"
                    else -> "vent"
                }
                return Model(
                    if (hit) rot(Shapes3D.coll(n), f) else emptyArray(),
                    rc = Shapes3D.cubes(n), rf = f, rt = top, rb = bot, rs = side
                )
            }
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
            DOOR -> {
                val key = Reg.key(b)
                val wood = woodTile(key)
                val open = (m and M_OPEN) != 0
                val upper = (m and M_UPPER) != 0
                val vo = if (upper) 0f else 0.5f
                val closed = !open
                val frontMask = if (closed) 48 else 12
                val edgeMask = 63 and frontMask.inv()
                val x0 = if (closed) 0f else 13f
                val z0 = if (closed) 13f else 0f
                val x1 = 16f
                val z1 = 16f
                val boxes = arrayOf(
                    Box(x0 / 16f, 0f, z0 / 16f, x1 / 16f, 1f, z1 / 16f, side, side, side, frontMask, vo, 0.5f),
                    Box(x0 / 16f, 0f, z0 / 16f, x1 / 16f, 1f, z1 / 16f, wood, wood, wood, edgeMask)
                )
                return Model(rot(boxes, f))
            }
            BED -> {
                val key = Reg.key(b)
                val wool = woolTile(key)
                val white = Reg.tile(Reg.id("wool_colored_white"), 2)
                val plank = Reg.tile(Reg.id("planks"), 2)
                val head = (m and M_SECOND) != 0
                val l = ArrayList<Box>()
                l.add(bx(0f, 3f, 0f, 16f, 9f, 16f, wool))
                if (head) {
                    l.add(bx(1f, 9f, 1f, 15f, 11f, 8f, white))
                    l.add(bx(0f, 0f, 0f, 3f, 3f, 3f, plank)); l.add(bx(13f, 0f, 0f, 16f, 3f, 3f, plank))
                } else {
                    l.add(bx(0f, 0f, 13f, 3f, 3f, 16f, plank)); l.add(bx(13f, 0f, 13f, 16f, 3f, 16f, plank))
                }
                return Model(rot(l.toTypedArray(), f))
            }
            SIGN -> {
                val t = woodTile(Reg.key(b))
                return Model(rot(arrayOf(bx(7f, 0f, 7f, 9f, 8f, 9f, t), bx(0f, 8f, 7f, 16f, 15f, 9f, t)), f))
            }
            LANTERN -> return Model(arrayOf(bx(5f, 0f, 5f, 11f, 7f, 11f, side), bx(6f, 7f, 6f, 10f, 9f, 10f, side)))
            CHAIN -> {
                val t = Reg.tile(Reg.id("iron_block"), 2)
                return Model(arrayOf(bx(6.5f, 0f, 7.75f, 9.5f, 16f, 8.25f, t), bx(7.75f, 0f, 6.5f, 8.25f, 16f, 9.5f, t)))
            }
            CAMPFIRE -> {
                val logTop = Reg.tile(Reg.id("log"), 0)
                val logSide = Reg.tile(Reg.id("log"), 2)
                return Model(
                    arrayOf(
                        bx(0f, 0f, 0f, 16f, 3f, 4f, logTop, logTop, logSide),
                        bx(0f, 0f, 12f, 16f, 3f, 16f, logTop, logTop, logSide),
                        bx(0f, 3f, 0f, 4f, 6f, 16f, logSide, logSide, logSide),
                        bx(12f, 3f, 0f, 16f, 6f, 16f, logSide, logSide, logSide)
                    )
                )
            }
            CAULDRON -> {
                val t = Reg.tile(Reg.id("polished_blackstone"), 2)
                return Model(
                    arrayOf(
                        bx(0f, 3f, 0f, 16f, 4f, 16f, t),
                        bx(0f, 4f, 0f, 16f, 16f, 2f, t), bx(0f, 4f, 14f, 16f, 16f, 16f, t),
                        bx(0f, 4f, 2f, 2f, 16f, 14f, t), bx(14f, 4f, 2f, 16f, 16f, 14f, t),
                        bx(0f, 0f, 0f, 4f, 3f, 2f, t), bx(12f, 0f, 0f, 16f, 3f, 2f, t),
                        bx(0f, 0f, 14f, 4f, 3f, 16f, t), bx(12f, 0f, 14f, 16f, 3f, 16f, t)
                    )
                )
            }
            HOPPER -> {
                val t = Reg.tile(Reg.id("polished_blackstone"), 2)
                return Model(
                    arrayOf(
                        bx(0f, 10f, 0f, 16f, 16f, 2f, t), bx(0f, 10f, 14f, 16f, 16f, 16f, t),
                        bx(0f, 10f, 2f, 2f, 16f, 14f, t), bx(14f, 10f, 2f, 16f, 16f, 14f, t),
                        bx(2f, 10f, 2f, 14f, 11f, 14f, t),
                        bx(4f, 4f, 4f, 12f, 10f, 12f, t), bx(6f, 0f, 6f, 10f, 4f, 10f, t)
                    )
                )
            }
            BREWING -> {
                val stone = Reg.tile(Reg.id("stone_slab"), 2)
                val gold = Reg.tile(Reg.id("gold_block"), 2)
                return Model(arrayOf(bx(1f, 0f, 1f, 15f, 2f, 15f, stone), bx(7f, 2f, 7f, 9f, 14f, 9f, gold)))
            }
            LEVER -> {
                val cob = Reg.tile(Reg.id("cobble"), 2)
                val pl = Reg.tile(Reg.id("planks"), 2)
                return Model(arrayOf(bx(5f, 0f, 4f, 11f, 3f, 12f, cob), bx(7f, 3f, 7f, 9f, 10f, 9f, pl)))
            }
            POT_EMPTY -> {
                val clay = Reg.tile(Reg.id("hardened_clay"), 2)
                val dirt = Reg.tile(Reg.id("dirt"), 2)
                return Model(
                    arrayOf(
                        bx(5f, 0f, 5f, 11f, 1f, 11f, clay),
                        bx(5f, 1f, 5f, 11f, 6f, 6f, clay), bx(5f, 1f, 10f, 11f, 6f, 11f, clay),
                        bx(5f, 1f, 6f, 6f, 6f, 10f, clay), bx(10f, 1f, 6f, 11f, 6f, 10f, clay)
                    )
                )
            }
            CAKE -> {
                val white = Reg.tile(Reg.id("wool_colored_white"), 2)
                return Model(arrayOf(bx(1f, 0f, 1f, 15f, 8f, 15f, white, white, side)))
            }
            DIODE -> {
                val stone = Reg.tile(Reg.id("stone_slab"), 2)
                return Model(rot(arrayOf(bx(0f, 0f, 0f, 16f, 2f, 16f, side, stone, stone)), f))
            }
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
