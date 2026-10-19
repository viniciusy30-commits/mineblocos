package com.mineblocos.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Atlas de texturas (tiles de 16px, 64 colunas) vindo do pack Actions & Stuff. */
object Atlas {
    const val TILE = 16
    const val COLS = 64

    @Volatile private var appCtx: Context? = null

    fun init(c: Context) { appCtx = c.applicationContext }

    fun context(): Context? = appCtx

    val bitmap: Bitmap by lazy { load() }

    private fun load(): Bitmap {
        try {
            val o = BitmapFactory.Options()
            o.inScaled = false
            o.inPreferredConfig = Bitmap.Config.ARGB_8888
            val c = appCtx ?: throw IllegalStateException("Atlas.init(context) não foi chamado")
            c.assets.open("atlas.png").use { s ->
                return BitmapFactory.decodeStream(s, null, o)!!
            }
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "atlas.png ausente; usando textura provisória", e)
            return placeholder()
        }
    }

    private val FALLBACK_COLORS = intArrayOf(
        0x5DA03A, 0x6E8F3C, 0x866043, 0x7D7D7D, 0x6A6A6A, 0x6B5230,
        0x9C7F4E, 0xB8935A, 0x3F7A2A, 0xDBD3A0, 0xC8E6F0, 0x9A4B3C
    )

    /** Textura provisória (cores lisas com ruído leve) para o app abrir sem o atlas.png. */
    private fun placeholder(): Bitmap {
        val w = COLS * TILE
        val h = 8 * TILE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = (y / TILE) * COLS + (x / TILE)
            val base = if (t < FALLBACK_COLORS.size) FALLBACK_COLORS[t] else 0x808080
            val n = (((x % TILE) * 7 + (y % TILE) * 13 + t * 5) % 5 - 2) * 4
            val r = (((base shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((base shr 8) and 0xFF) + n).coerceIn(0, 255)
            val bl = ((base and 0xFF) + n).coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    fun tx(tile: Int) = (tile % COLS) * TILE
    fun ty(tile: Int) = (tile / COLS) * TILE
}

/** Registro de todos os blocos e itens (assets/registry.tsv). O ID 0 é o ar. */
object Reg {
    const val K_CUBE = 1
    const val K_CROSS = 2
    const val K_FLAT = 3
    const val K_SLAB = 4
    const val K_ITEM = 5
    const val K_MODEL = 6
    const val K_HIDDEN = 7

    var count = 1
        private set
    private var ids = arrayOf("air")
    private var names = arrayOf("Ar")
    private var kinds = IntArray(1)
    private var top = IntArray(1)
    private var bot = IntArray(1)
    private var side = IntArray(1)
    private var flags = IntArray(1)
    private var avgs = IntArray(1) { 0xFF808080.toInt() }
    private val index = HashMap<String, Int>()
    private var ready = false
    private var waterId = -1

    /** Id do bloco de água (0 se o registro não tiver). */
    val water: Int get() = if (waterId > 0) waterId else 0

    // id, nome, kind, topo, base, lado, flags, cor média (mesma ordem de Blocks.kt)
    private val FALLBACK_REGISTRY = listOf(
        "grass\tGrama\t1\t0\t2\t1\t0\t5DA03A",
        "dirt\tTerra\t1\t2\t2\t2\t0\t866043",
        "stone\tPedra\t1\t3\t3\t3\t0\t7D7D7D",
        "cobble\tPedregulho\t1\t4\t4\t4\t0\t6A6A6A",
        "log\tTronco\t1\t6\t6\t5\t0\t6B5230",
        "planks\tTábuas\t1\t7\t7\t7\t0\tB8935A",
        "leaves\tFolhas\t1\t8\t8\t8\t1\t3F7A2A",
        "sand\tAreia\t1\t9\t9\t9\t0\tDBD3A0",
        "glass\tVidro\t1\t10\t10\t10\t3\tC8E6F0",
        "brick\tTijolos\t1\t11\t11\t11\t0\t9A4B3C"
    )

    @Synchronized
    fun init(c: Context) {
        if (ready) return
        val lines = try {
            c.assets.open("registry.tsv").bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "registry.tsv ausente; usando blocos básicos", e)
            FALLBACK_REGISTRY
        }
        val n = lines.size + 1
        ids = Array(n) { "air" }
        names = Array(n) { "Ar" }
        kinds = IntArray(n); top = IntArray(n); bot = IntArray(n); side = IntArray(n)
        flags = IntArray(n); avgs = IntArray(n) { 0xFF808080.toInt() }
        for ((i, l) in lines.withIndex()) {
            val p = l.split('\t')
            val k = i + 1
            ids[k] = p[0]; names[k] = p[1]
            kinds[k] = p[2].toInt()
            top[k] = p[3].toInt(); bot[k] = p[4].toInt(); side[k] = p[5].toInt()
            flags[k] = p[6].toInt()
            avgs[k] = (0xFF shl 24) or p[7].toInt(16)
            index[p[0]] = k
        }
        count = n
        waterId = index["water"] ?: -1
        ready = true
        Shapes3D.load(c)
        Models3D.load(c)
        Anim3D.load(c)
        emitArr = ByteArray(n)
        val vanilla = mapOf(
            "glowstone" to 15, "sea_lantern" to 15, "shroomlight" to 15, "jack_o_lantern" to 15,
            "ochre_froglight" to 15, "pearlescent_froglight" to 15, "verdant_froglight" to 15,
            "torch" to 14, "lantern" to 15, "redstone_lamp" to 15
        )
        for (i in 1 until n) {
            val k = ids[i]
            val m = Models3D.get(k)
            if (m != null && m.emit > 0) emitArr[i] = m.emit.toByte()
            val v = vanilla[k]
            if (v != null) emitArr[i] = v.toByte()
        }
    }

    private fun ok(b: Int) = b > 0 && b < count

    fun id(name: String): Int = index[name] ?: 0
    fun key(b: Int): String = if (ok(b)) ids[b] else "air"
    fun name(b: Int): String = if (ok(b)) names[b] else "?"
    fun kind(b: Int): Int = if (ok(b)) kinds[b] else 0

    /** kind: 0 = topo, 1 = base, 2 = lado */
    fun tile(b: Int, kind: Int): Int {
        if (!ok(b)) return 0
        return when (kind) { 0 -> top[b]; 1 -> bot[b]; else -> side[b] }
    }

    fun avg(b: Int): Int = if (ok(b)) avgs[b] else 0xFF808080.toInt()

    /** Cubo cheio e sem pixels transparentes. */
    private var emitArr = ByteArray(0)
    fun emission(b: Int): Int = if (b > 0 && b < emitArr.size) emitArr[b].toInt() else 0

    fun opaque(b: Int): Boolean = ok(b) && kinds[b] == K_CUBE && (flags[b] and 1) == 0

    /** Vidro, gelo, água: faces entre blocos iguais não são desenhadas. */
    fun glassy(b: Int): Boolean = ok(b) && (flags[b] and 2) != 0

    fun solid(b: Int): Boolean = ok(b) && b != waterId &&
        (kinds[b] == K_CUBE || kinds[b] == K_SLAB || (kinds[b] == K_MODEL && Shapes.solid(b)))

    /** IDs de todos os blocos cúbicos (para o inventário). */
    fun cubes(): IntArray = list(K_CUBE)

    /** IDs de todos os registros de um dos tipos dados. */
    fun list(vararg k: Int): IntArray = (1 until count).filter { kinds[it] in k }.toIntArray()

    /** Chaves de blocos que não são de construção (estações, colmeias, decoração, minérios, texturas auxiliares). */
    private val NOT_BUILDING = setOf(
        "bedrock", "bee_nest", "bee_nest_front_honey", "beehive", "beehive_front_honey",
        "blast_furnace", "furnace", "smoker", "crafter", "crafting_table", "fletcher_table",
        "honey", "honeycomb", "slime", "sponge", "sponge_wet", "hay_block", "pumpkin", "pumpkin_white", "melon",
        "ochre_froglight", "pearlescent_froglight", "verdant_froglight", "shroomlight", "sculk",
        "end_gateway", "budding_amethyst", "powder_snow", "magma", "bone_block",
        "mushroom_block_inside", "mushroom_block_skin_brown", "mushroom_block_skin_red",
        "grass_side_snowed", "log_top_normal_weak", "planks_mer_weak", "planks_normal_weak",
        "dirt_with_roots", "muddy_mangrove_roots", "mangrove_roots", "ancient_debris", "gilded_blackstone", "water"
    )

    /** Bloco de construção: cubo cheio (ou laje de pedra) que não seja minério, estação ou decoração. */
    fun building(b: Int): Boolean {
        if (!ok(b)) return false
        val k = ids[b]
        if (k == "stone_slab") return true
        if (kinds[b] == K_MODEL && Models3D.has(k)) return true
        if (kinds[b] == K_MODEL && (k.endsWith("_drz_stairs") || k.endsWith("_drz_slab") ||
            k.endsWith("_drz_vslab") || k.endsWith("_drz_pilar") || k.endsWith("_drz_quinae") || k.endsWith("_drz_quinad") ||
            k.endsWith("_drz_circulo") || k.endsWith("_drz_curva") || k.endsWith("_drz_curvah") ||
            k.endsWith("_drz_diagonal") || k.endsWith("_drz_ventila") || k.endsWith("_drz_muro") ||
            k.endsWith("_drz_vescl") || k.endsWith("_drz_vescr"))) return true
        if (kinds[b] != K_CUBE) return false
        if (k.endsWith("_ore") || k in NOT_BUILDING) return false
        return true
    }

    /** IDs dos blocos de construção (para o inventário). */
    fun buildingList(): IntArray = (1 until count).filter { building(it) }.toIntArray()

    /** Aba do inventário: 0 = blocos, 1 = escadas e lajes, 2 = formas especiais. */
    private fun category(b: Int): Int {
        val k = ids[b]
        if (k == "stone_slab") return 1
        if (Models3D.has(k)) return 3
        return when {
            k.endsWith("_drz_stairs") || k.endsWith("_drz_slab") || k.endsWith("_drz_vslab") ||
                k.endsWith("_drz_pilar") || k.endsWith("_drz_quinae") || k.endsWith("_drz_quinad") ||
                k.endsWith("_drz_vescl") || k.endsWith("_drz_vescr") -> 1
            k.endsWith("_drz_circulo") || k.endsWith("_drz_curva") || k.endsWith("_drz_curvah") ||
                k.endsWith("_drz_diagonal") || k.endsWith("_drz_ventila") || k.endsWith("_drz_muro") -> 2
            else -> 0
        }
    }

    fun buildingList(tab: Int): IntArray {
        val l = (1 until count).filter { building(it) && category(it) == tab }
        // móveis e objetos: ordem alfabética (a aba tem índice por letra)
        return if (tab == 3) l.sortedBy { names[it].lowercase() }.toIntArray() else l.toIntArray()
    }

    /** Letra inicial do nome (A..Z) ou '#'. */
    fun initial(b: Int): Char {
        val c = name(b).trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    /** Pode ir para a hotbar e ser colocado (inclui minérios, plantas e demais itens da vitrine). */
    fun placeable(b: Int): Boolean = ok(b) && kinds[b] != K_ITEM && kinds[b] != K_HIDDEN && b != waterId

    fun isPlant(b: Int): Boolean = ok(b) && (kinds[b] == K_CROSS || kinds[b] == K_FLAT)
}
