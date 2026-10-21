package com.mineblocos.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Indexa definições Bedrock dos add-ons importados sem modificar o pacote.
 * Catalogar um bloco não significa que sua geometria/UV já foi convertida
 * para o renderer do MineBlocos.
 */
object AddonCatalog {
    data class BlockDef(
        val identifier: String,
        val displayName: String,
        val geometryId: String,
        val textureId: String,
        val definitionFile: File,
        val geometryFile: File?
    )

    data class Summary(val blocks: Int, val models: Int, val textures: Int, val namedBlocks: Int) {
        fun describe(): String {
            val suffix = if (namedBlocks > 0) " (" + namedBlocks + " com nome traduzido)." else "."
            return blocks.toString() + " definições de blocos, " + models +
                " geometrias e " + textures + " texturas detectadas" + suffix
        }
    }

    private const val MAX_JSON_BYTES = 8L * 1024L * 1024L
    @Volatile private var cached: List<BlockDef>? = null
    @Volatile private var cachedSummary: Summary? = null

    @Synchronized
    fun scan(context: Context, force: Boolean = false): List<BlockDef> {
        if (!force) cached?.let { return it }
        val packs = AddonManager.list(context)
        val resourceRoots = packs.filter {
            it.type == "resources" || it.directory.absolutePath.contains("_RP", true)
        }.map { it.directory }
        val behaviorRoots = packs.filter {
            it.type == "data" || it.directory.absolutePath.contains("_BP", true)
        }.map { it.directory }

        val language = HashMap<String, String>()
        for (root in resourceRoots) {
            root.walkTopDown().filter {
                it.isFile && it.extension.equals("lang", true) && it.length() <= 16L * 1024L * 1024L
            }.forEach { file ->
                try {
                    file.forEachLine(Charsets.UTF_8) { line ->
                        val s = line.trim()
                        if (s.isEmpty() || s.startsWith("#")) return@forEachLine
                        val ix = s.indexOf('=')
                        if (ix <= 0) return@forEachLine
                        val key = s.substring(0, ix).trim()
                        val value = s.substring(ix + 1).trim()
                        if (value.isNotEmpty()) language.putIfAbsent(key, value)
                    }
                } catch (e: Exception) {
                    Log.w("Mineblocos", "Falha lendo tradução do addon: " + file.name, e)
                }
            }
        }

        val geometryFiles = HashMap<String, File>()
        var textureCount = 0
        for (root in resourceRoots) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith("models/") && file.name.endsWith(".geo.json", true) &&
                    file.length() <= MAX_JSON_BYTES) {
                    try {
                        val json = JSONObject(file.readText(Charsets.UTF_8))
                        val geos = json.optJSONArray("minecraft:geometry")
                        if (geos != null) for (i in 0 until geos.length()) {
                            val id = geos.optJSONObject(i)?.optJSONObject("description")
                                ?.optString("identifier").orEmpty()
                            if (id.isNotBlank()) geometryFiles.putIfAbsent(id, file)
                        }
                    } catch (e: Exception) {
                        Log.w("Mineblocos", "Geometria Bedrock inválida: " + file.name, e)
                    }
                }
                if (rel.startsWith("textures/") &&
                    file.extension.lowercase() in setOf("png", "tga")) textureCount++
            }
        }

        val out = LinkedHashMap<String, BlockDef>()
        for (root in behaviorRoots) {
            root.walkTopDown().filter {
                it.isFile && it.extension.equals("json", true) &&
                    it.path.replace('\\', '/').contains("/blocks/")
            }.forEach { file ->
                if (file.length() > MAX_JSON_BYTES) return@forEach
                try {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val block = json.optJSONObject("minecraft:block") ?: return@forEach
                    val description = block.optJSONObject("description") ?: JSONObject()
                    val id = description.optString("identifier").trim()
                    if (id.isBlank()) return@forEach
                    val components = block.optJSONObject("components") ?: JSONObject()
                    val geometryRaw = components.opt("minecraft:geometry")
                    val geometryId = when (geometryRaw) {
                        is String -> geometryRaw
                        is JSONObject -> geometryRaw.optString("identifier").orEmpty()
                        else -> ""
                    }
                    val materials = components.optJSONObject("minecraft:material_instances")
                    var texture = ""
                    if (materials != null) {
                        val keys = materials.keys()
                        while (keys.hasNext()) {
                            val material = materials.optJSONObject(keys.next()) ?: continue
                            val value = material.optString("texture")
                            if (value.isNotBlank()) { texture = value; break }
                        }
                    }
                    val bareId = id.substringAfter(':')
                    val name = language["tile." + id + ".name"]
                        ?: language["tile." + bareId + ".name"]
                        ?: bareId.replace('_', ' ').replace(Regex(" +"), " ")
                            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    out[id] = BlockDef(id, name, geometryId, texture, file, geometryFiles[geometryId])
                } catch (e: Exception) {
                    Log.w("Mineblocos", "Definição Bedrock inválida: " + file.name, e)
                }
            }
        }
        val result = out.values.sortedBy { it.displayName.lowercase() }
        cached = result
        cachedSummary = Summary(
            result.size, geometryFiles.size, textureCount,
            result.count { it.displayName != it.identifier.substringAfter(':') }
        )
        return result
    }

    fun summary(context: Context, force: Boolean = false): Summary {
        if (force || cached == null) scan(context, force)
        return cachedSummary ?: Summary(0, 0, 0, 0)
    }

    fun invalidate() {
        cached = null
        cachedSummary = null
    }
}
