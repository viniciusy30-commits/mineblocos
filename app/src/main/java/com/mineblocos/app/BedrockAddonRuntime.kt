package com.mineblocos.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runtime compatibility layer for user-imported Bedrock behavior/resource packs.
 * It translates static Bedrock block geometry into the XModel format used by MineBlocos.
 * Molang-driven visibility, scripts, animations and multi-block state permutations are not
 * executed; each block is loaded with its base geometry and base material.
 */
object BedrockAddonRuntime {
    private const val TAG = "MineblocosAddon"
    private const val MAX_BLOCK_DEFS = 20000
    private const val MAX_GEOMETRIES = 5000

    private data class Geo(val cubes: Array<XCube>, val texW: Int, val texH: Int)

    @Volatile private var lastSummary = ""
    @Volatile private var lastFingerprint = ""

    @Synchronized
    fun load(context: Context): String {
        val packs = AddonManager.list(context)
        val behavior = packs.filter { it.type.equals("data", true) }.map { it.directory }.distinctBy { it.absolutePath }
        val resources = packs.filter { it.type.equals("resources", true) }.map { it.directory }.distinctBy { it.absolutePath }
        if (behavior.isEmpty() || resources.isEmpty()) return "Importe o pacote de comportamento e o pacote de recursos do addon."
        val fingerprint = (behavior + resources).sortedBy { it.absolutePath }
            .joinToString("|") { it.absolutePath + ":" + it.lastModified() + ":" + (it.walkTopDown().count { f -> f.isFile }) }
        if (fingerprint == lastFingerprint && lastSummary.isNotBlank()) return lastSummary

        val names = HashMap<String, String>()
        for (rp in resources) {
            rp.walkTopDown().filter { it.isFile && it.extension.equals("lang", true) }.take(20).forEach { file ->
                try {
                    file.forEachLine(Charsets.UTF_8) { line ->
                        val ix = line.indexOf('=')
                        if (ix <= 0) return@forEachLine
                        val key = line.substring(0, ix).trim()
                        if (key.startsWith("tile.") && key.endsWith(".name")) {
                            val identifier = key.removePrefix("tile.").removeSuffix(".name")
                            names.putIfAbsent(identifier, cleanName(line.substring(ix + 1)))
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Não foi possível ler nomes em ${file.name}", e) }
            }
        }

        val texturePathByKey = HashMap<String, File>()
        for (rp in resources) {
            val terrain = File(rp, "textures/terrain_texture.json")
            if (!terrain.isFile) continue
            try {
                val data = JSONObject(terrain.readText(Charsets.UTF_8)).optJSONObject("texture_data") ?: continue
                val it = data.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val entry = data.optJSONObject(key) ?: continue
                    val raw = entry.opt("textures")
                    val path = when (raw) {
                        is String -> raw
                        is JSONArray -> raw.optString(0, "")
                        is JSONObject -> raw.optString("path", "")
                        else -> ""
                    }.trim()
                    if (path.isNotBlank()) resolveTexture(rp, path)?.let { texturePathByKey.putIfAbsent(key, it) }
                }
            } catch (e: Exception) { Log.w(TAG, "terrain_texture.json inválido em ${rp.name}", e) }
        }

        val geometryById = HashMap<String, Geo>()
        var geometryCount = 0
        for (rp in resources) {
            val dir = File(rp, "models/blocks")
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".geo.json", true) }.forEach { file ->
                if (geometryCount >= MAX_GEOMETRIES) return@forEach
                try {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    val array = root.optJSONArray("minecraft:geometry") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val g = array.optJSONObject(i) ?: continue
                        val desc = g.optJSONObject("description") ?: JSONObject()
                        val id = desc.optString("identifier", "")
                        if (id.isBlank() || geometryById.containsKey(id)) continue
                        val texW = desc.optInt("texture_width", 64).coerceAtLeast(1)
                        val texH = desc.optInt("texture_height", 64).coerceAtLeast(1)
                        geometryById[id] = Geo(parseGeometry(g, texW, texH), texW, texH)
                        geometryCount++
                    }
                } catch (e: Exception) { Log.w(TAG, "Geometria ignorada: ${file.name}", e) }
            }
        }

        val definitions = ArrayList<Pair<File, JSONObject>>()
        for (bp in behavior) {
            val dir = File(bp, "blocks")
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                if (definitions.size >= MAX_BLOCK_DEFS) return@forEach
                try { definitions.add(file to JSONObject(file.readText(Charsets.UTF_8))) }
                catch (e: Exception) { Log.w(TAG, "Definição inválida: ${file.name}", e) }
            }
        }
        definitions.sortBy { it.second.optJSONObject("minecraft:block")?.optJSONObject("description")?.optString("identifier", "") ?: "" }

        val neededTextures = LinkedHashMap<String, File>()
        val blockRows = ArrayList<JSONObject>()
        for ((file, root) in definitions) {
            val block = root.optJSONObject("minecraft:block") ?: continue
            val desc = block.optJSONObject("description") ?: continue
            val id = desc.optString("identifier", "").trim()
            if (id.isBlank() || Reg.id(id) != 0) continue
            val components = block.optJSONObject("components") ?: JSONObject()
            val materials = components.optJSONObject("minecraft:material_instances") ?: JSONObject()
            var textureKey = materials.optJSONObject("*")?.optString("texture", "")?.trim().orEmpty()
            if (textureKey.isBlank()) {
                val keys = materials.keys()
                while (keys.hasNext() && textureKey.isBlank()) textureKey = materials.optJSONObject(keys.next())?.optString("texture", "")?.trim().orEmpty()
            }
            if (textureKey.isBlank()) textureKey = id.substringAfter(':')
            val materialKeys = materials.keys()
            while (materialKeys.hasNext()) {
                val materialKey = materialKeys.next()
                val materialTexture = materials.optJSONObject(materialKey)?.optString("texture", "")?.trim().orEmpty()
                if (materialTexture.isBlank()) continue
                val materialFile = texturePathByKey[materialTexture]
                    ?: resources.firstNotNullOfOrNull { resolveTexture(it, "textures/blocks/$materialTexture") }
                if (materialFile != null) neededTextures[materialTexture] = materialFile
            }
            val textureFile = texturePathByKey[textureKey]
                ?: resources.firstNotNullOfOrNull { resolveTexture(it, "textures/blocks/$textureKey") }
            if (textureFile != null) neededTextures[textureKey] = textureFile
            blockRows.add(JSONObject().put("id", id).put("name", names[id] ?: names[id.substringAfter(':')] ?: cleanName(id.substringAfter(':')))
                .put("texture", textureKey).put("materials", materials)
                .put("geometry", components.optJSONObject("minecraft:geometry")?.optString("identifier", "").orEmpty())
                .put("components", components).put("file", file.absolutePath))
        }

        val textureRects = Atlas.appendAddonTextures(neededTextures)
        var added = 0
        var missingGeometry = 0
        var missingTexture = 0
        for (row in blockRows) {
            val id = row.optString("id")
            if (Reg.id(id) != 0) continue
            val textureKey = row.optString("texture")
            val rect = textureRects[textureKey]
            if (rect == null) missingTexture++
            val geoId = row.optString("geometry")
            val geo = geometryById[geoId]
            if (geo == null) missingGeometry++
            val cubes = geo?.cubes?.takeIf { it.isNotEmpty() } ?: fallbackCube()
            val rectMap = HashMap<String, IntArray>()
            if (rect != null) rectMap["*"] = rect else rectMap["*"] = intArrayOf(0, 0, 16, 16)
            val materials = row.optJSONObject("materials") ?: JSONObject()
            val materialKeys = materials.keys()
            while (materialKeys.hasNext()) {
                val materialKey = materialKeys.next()
                val materialTexture = materials.optJSONObject(materialKey)?.optString("texture", "")?.trim().orEmpty()
                textureRects[materialTexture]?.let { rectMap[materialKey] = it }
            }
            val components = row.optJSONObject("components") ?: JSONObject()
            val coll = parseBlockBoxes(components.opt("minecraft:collision_box"))
            val sel = parseBlockBoxes(components.opt("minecraft:selection_box"))
            val variant = XVar(cubes, 0, rectMap, coll, if (sel.isEmpty()) arrayOf(Box(0f, 0f, 0f, 1f, 1f, 1f, 0, 0, 0)) else sel, 0f, 0f, 0f)
            val light = components.optInt("minecraft:light_emission", 0).coerceIn(0, 15)
            val model = XModel(id, "", 1, intArrayOf(), intArrayOf(), arrayOf(variant), shortArrayOf(0), emptyArray(), false, light)
            Reg.registerAddonModel(id, row.optString("name", id.substringAfter(':')), model, 0xFF8A8A8A.toInt(), light)
            added++
        }
        val summary = "monoDeco convertido: $added blocos/modelos registrados; $geometryCount geometrias lidas; $missingGeometry usando modelo de reserva; $missingTexture sem textura compatível."
        lastFingerprint = fingerprint
        lastSummary = summary
        Log.i(TAG, summary)
        return summary
    }

    private fun cleanName(raw: String): String {
        val noCodes = raw.replace(Regex("§[0-9a-fk-or]"), "").trim()
        val main = noCodes.substringBefore(" - ").trim()
        return main.ifBlank { noCodes }
    }

    private fun resolveTexture(root: File, rawPath: String): File? {
        var rel = rawPath.replace('\\', '/').removePrefix("/")
        if (!rel.endsWith(".png", true)) rel += ".png"
        val direct = File(root, rel)
        if (direct.isFile) return direct
        val textureRoot = File(root, "textures")
        val without = rel.removePrefix("textures/")
        val alt = File(textureRoot, without)
        if (alt.isFile) return alt
        val baseName = File(rel).name
        return textureRoot.walkTopDown().firstOrNull { it.isFile && it.name.equals(baseName, true) }
    }

    private fun numArray(a: JSONArray?, size: Int, defaults: FloatArray): FloatArray {
        if (a == null) return defaults.copyOf()
        return FloatArray(size) { i -> if (i < a.length()) a.optDouble(i, defaults[i].toDouble()).toFloat() else defaults[i] }
    }

    private fun parseGeometry(g: JSONObject, texW: Int, texH: Int): Array<XCube> {
        val out = ArrayList<XCube>()
        val bones = g.optJSONArray("bones") ?: return emptyArray()
        for (bi in 0 until bones.length()) {
            val bone = bones.optJSONObject(bi) ?: continue
            val boneName = bone.optString("name", "").lowercase(Locale.ROOT)
            val boneMaterial = when {
                boneName.contains("light") || boneName.contains("emissive") -> "light"
                boneName == "1" || boneName == "2" -> boneName
                boneName.isNotBlank() && boneName !in setOf("main", "body", "root") -> boneName
                else -> "*"
            }
            val pivot = numArray(bone.optJSONArray("pivot"), 3, floatArrayOf(0f, 0f, 0f))
            val rotation = numArray(bone.optJSONArray("rotation"), 3, floatArrayOf(0f, 0f, 0f))
            val cubes = bone.optJSONArray("cubes") ?: continue
            for (ci in 0 until cubes.length()) {
                val cube = cubes.optJSONObject(ci) ?: continue
                val origin = numArray(cube.optJSONArray("origin"), 3, floatArrayOf(0f, 0f, 0f))
                val size = numArray(cube.optJSONArray("size"), 3, floatArrayOf(1f, 1f, 1f))
                val inflate = cube.optDouble("inflate", 0.0).toFloat()
                for (a in 0..2) { origin[a] -= inflate; size[a] += inflate * 2f }
                val cp = numArray(cube.optJSONArray("pivot"), 3, pivot)
                val cr = numArray(cube.optJSONArray("rotation"), 3, floatArrayOf(0f, 0f, 0f))
                val wor = FloatArray(24)
                for (i in 0 until 8) {
                    var x = origin[0] + if ((i and 1) != 0) size[0] else 0f
                    var y = origin[1] + if ((i and 2) != 0) size[1] else 0f
                    var z = origin[2] + if ((i and 4) != 0) size[2] else 0f
                    val rotatedCube = rotate(x, y, z, cp, cr)
                    val rotatedBone = rotate(rotatedCube[0], rotatedCube[1], rotatedCube[2], pivot, rotation)
                    wor[i * 3] = (rotatedBone[0] + 8f) / 16f
                    wor[i * 3 + 1] = rotatedBone[1] / 16f
                    wor[i * 3 + 2] = (rotatedBone[2] + 8f) / 16f
                }
                val faces = ArrayList<XFace>()
                val uv = cube.optJSONObject("uv")
                if (uv != null) {
                    val faceIds = arrayOf("up" to 0, "down" to 1, "east" to 2, "west" to 3, "south" to 4, "north" to 5)
                    for ((faceName, faceId) in faceIds) {
                        val face = uv.optJSONObject(faceName) ?: continue
                        val pos = face.optJSONArray("uv") ?: continue
                        val sz = face.optJSONArray("uv_size") ?: JSONArray().put(0).put(0)
                        val u0 = pos.optDouble(0, 0.0).toFloat() / texW
                        val v0 = pos.optDouble(1, 0.0).toFloat() / texH
                        val u1 = (pos.optDouble(0, 0.0) + sz.optDouble(0, 0.0)).toFloat() / texW
                        val v1 = (pos.optDouble(1, 0.0) + sz.optDouble(1, 0.0)).toFloat() / texH
                        val faceMaterial = if (boneMaterial == "light" || boneMaterial == "1" || boneMaterial == "2" ||
                            (boneMaterial != "*" && boneMaterial == faceName)) boneMaterial else faceName
                        faces.add(XFace(faceId, faceMaterial, u0, v0, u1, v1))
                    }
                } else {
                    // Legacy Bedrock box UVs: use the declared texture window on each face.
                    val uv0 = cube.optJSONArray("uv")
                    val u = if (uv0 != null) uv0.optDouble(0, 0.0).toFloat() else 0f
                    val v = if (uv0 != null) uv0.optDouble(1, 0.0).toFloat() else 0f
                    val faceNames = arrayOf("up", "down", "east", "west", "south", "north")
                    for (faceId in 0..5) {
                        val faceName = faceNames[faceId]
                        val faceMaterial = if (boneMaterial == "light" || boneMaterial == "1" || boneMaterial == "2" ||
                            (boneMaterial != "*" && boneMaterial == faceName)) boneMaterial else faceName
                        faces.add(XFace(faceId, faceMaterial, u / texW, v / texH,
                            (u + size[0]) / texW, (v + size[1]) / texH))
                    }
                }
                if (faces.isNotEmpty()) out.add(XCube(wor, faces.toTypedArray()))
            }
        }
        return out.toTypedArray()
    }

    private fun rotate(x0: Float, y0: Float, z0: Float, pivot: FloatArray, rotation: FloatArray): FloatArray {
        var x = x0 - pivot[0]; var y = y0 - pivot[1]; var z = z0 - pivot[2]
        val rx = Math.toRadians(rotation[0].toDouble())
        val ry = Math.toRadians(rotation[1].toDouble())
        val rz = Math.toRadians(rotation[2].toDouble())
        var ny = y * cos(rx).toFloat() - z * sin(rx).toFloat()
        var nz = y * sin(rx).toFloat() + z * cos(rx).toFloat()
        y = ny; z = nz
        var nx = x * cos(ry).toFloat() + z * sin(ry).toFloat()
        nz = -x * sin(ry).toFloat() + z * cos(ry).toFloat()
        x = nx; z = nz
        nx = x * cos(rz).toFloat() - y * sin(rz).toFloat()
        ny = x * sin(rz).toFloat() + y * cos(rz).toFloat()
        return floatArrayOf(nx + pivot[0], ny + pivot[1], z + pivot[2])
    }

    private fun parseBlockBoxes(raw: Any?): Array<Box> {
        if (raw == null || raw == JSONObject.NULL || raw == false) return emptyArray()
        if (raw == true) return arrayOf(Box(0f, 0f, 0f, 1f, 1f, 1f, 0, 0, 0))
        val obj = raw as? JSONObject ?: return emptyArray()
        val origin = numArray(obj.optJSONArray("origin"), 3, floatArrayOf(-8f, 0f, -8f))
        val size = numArray(obj.optJSONArray("size"), 3, floatArrayOf(16f, 16f, 16f))
        if (size.any { it <= 0f }) return emptyArray()
        val x0 = (origin[0] + 8f) / 16f
        val y0 = origin[1] / 16f
        val z0 = (origin[2] + 8f) / 16f
        return arrayOf(Box(x0, y0, z0, x0 + size[0] / 16f, y0 + size[1] / 16f, z0 + size[2] / 16f, 0, 0, 0))
    }

    private fun fallbackCube(): Array<XCube> {
        val wor = FloatArray(24)
        for (i in 0 until 8) {
            wor[i * 3] = if ((i and 1) != 0) 1f else 0f
            wor[i * 3 + 1] = if ((i and 2) != 0) 1f else 0f
            wor[i * 3 + 2] = if ((i and 4) != 0) 1f else 0f
        }
        val faces = Array(6) { f -> XFace(f, "*", 0f, 0f, 1f, 1f) }
        return arrayOf(XCube(wor, faces))
    }
}
