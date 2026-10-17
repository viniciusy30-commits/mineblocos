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

    fun name(b: Int): String = Reg.name(b)

    fun opaque(b: Int): Boolean = Reg.opaque(b)

    /** kind: 0 = topo, 1 = base, 2 = lado */
    fun tile(b: Int, kind: Int): Int = Reg.tile(b, kind)
}
