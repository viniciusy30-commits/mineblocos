package com.mineblocos.app

import android.content.Context

/** Caixa girada (modelo do addon): cantos locais (sem rotação) e cantos no mundo, em fração do bloco. */
class RCube(val loc: FloatArray, val wor: FloatArray)

/** Formas com caixas giradas (círculo, curva, diagonal, ventilação), lidas de assets/shapes3d.txt. */
object Shapes3D {
    private val cubeMap = HashMap<String, Array<RCube>>()
    private val collMap = HashMap<String, Array<Box>>()

    @Synchronized
    fun load(c: Context) {
        if (cubeMap.isNotEmpty()) return
        try {
            val cb = HashMap<String, ArrayList<RCube>>()
            val bb = HashMap<String, ArrayList<Box>>()
            var cur = ""
            c.assets.open("shapes3d.txt").bufferedReader(Charsets.UTF_8).forEachLine { l ->
                if (l.isBlank()) return@forEachLine
                if (l.startsWith("#")) { cur = l.substring(1).trim(); return@forEachLine }
                val p = l.trim().split(' ')
                if (p[0] == "c") {
                    val f = FloatArray(48) { p[it + 1].toFloat() / 16f }
                    cb.getOrPut(cur) { ArrayList() }.add(RCube(f.copyOfRange(0, 24), f.copyOfRange(24, 48)))
                } else if (p[0] == "b") {
                    val f = FloatArray(6) { p[it + 1].toFloat() / 16f }
                    bb.getOrPut(cur) { ArrayList() }.add(Box(f[0], f[1], f[2], f[3], f[4], f[5], 0, 0, 0))
                }
            }
            for ((k, v) in cb) cubeMap[k] = v.toTypedArray()
            for ((k, v) in bb) collMap[k] = v.toTypedArray()
        } catch (e: Throwable) {
            android.util.Log.e("Mineblocos", "shapes3d.txt ausente", e)
        }
    }

    fun cubes(n: String): Array<RCube> = cubeMap[n] ?: emptyArray()
    fun coll(n: String): Array<Box> = collMap[n] ?: emptyArray()
}
