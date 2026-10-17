package com.mineblocos.app

object Blocks {
    const val AIR = 0
    const val GRASS = 1
    const val DIRT = 2
    const val STONE = 3
    const val COBBLE = 4
    const val LOG = 5
    const val PLANKS = 6
    const val LEAVES = 7
    const val SAND = 8
    const val GLASS = 9
    const val BRICK = 10

    val hotbar = intArrayOf(GRASS, DIRT, STONE, COBBLE, LOG, PLANKS, LEAVES, SAND, GLASS, BRICK)

    private val names = arrayOf(
        "Ar", "Grama", "Terra", "Pedra", "Pedregulho", "Tronco",
        "Tábuas", "Folhas", "Areia", "Vidro", "Tijolos"
    )

    fun name(b: Int): String = if (b in names.indices) names[b] else "?"

    fun opaque(b: Int): Boolean = b != AIR && b != LEAVES && b != GLASS

    /** kind: 0 = topo, 1 = base, 2 = lado */
    fun tile(b: Int, kind: Int): Int = when (b) {
        GRASS -> when (kind) { 0 -> 0; 1 -> 2; else -> 1 }
        DIRT -> 2
        STONE -> 3
        COBBLE -> 4
        LOG -> if (kind == 2) 5 else 6
        PLANKS -> 7
        LEAVES -> 8
        SAND -> 9
        GLASS -> 10
        BRICK -> 11
        else -> 3
    }
}
