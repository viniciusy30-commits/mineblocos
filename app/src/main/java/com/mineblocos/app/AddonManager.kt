package com.mineblocos.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * First-stage Bedrock add-on package manager.
 *
 * Imports .mcaddon/.mcpack/.zip files into app-private storage and indexes pack
 * manifests. This deliberately does not claim full Bedrock runtime compatibility:
 * block/entity JSON, Molang, scripts and geometry must be translated separately.
 */
object AddonManager {
    data class Pack(
        val id: String,
        val name: String,
        val description: String,
        val type: String,
        val version: String,
        val directory: File
    )

    private const val MAX_ENTRIES = 100000
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    private val packRootName = "bedrock_addons"

    private fun root(context: Context): File =
        File(context.filesDir, packRootName).apply { if (!exists()) mkdirs() }

    /** Import an archive selected by the user. Returns a human-readable result. */
    @Synchronized
    fun import(context: Context, uri: Uri): String {
        val staging = File(root(context), ".import-${UUID.randomUUID()}")
        if (!staging.mkdirs()) return "Não foi possível preparar a importação."
        var totalBytes = 0L
        var entries = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        if (entries > MAX_ENTRIES) error("O pacote tem arquivos demais.")
                        val relative = entry.name.replace('\\', '/')
                        val parts = relative.split('/')
                        if (relative.startsWith("/") || parts.any { it == ".." } ||
                            relative.contains(":") || relative.isBlank()) {
                            zip.closeEntry()
                            continue
                        }
                        val out = File(staging, relative).canonicalFile
                        if (out.canonicalPath != staging.canonicalPath && !out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    totalBytes += n
                                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                        error("O pacote excede o limite de 512 MB descompactados.")
                                    }
                                    stream.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Não foi possível abrir o arquivo selecionado.")

            expandNestedPacks(staging)

            val manifests = staging.walkTopDown().filter {
                it.isFile && it.name.equals("manifest.json", ignoreCase = true)
            }.toList()
            if (manifests.isEmpty()) error("Nenhum manifest.json foi encontrado. Selecione um .mcaddon, .mcpack ou .zip válido.")

            // Validate manifests and give each imported archive its own stable folder.
            var valid = 0
            for (manifest in manifests) {
                val json = JSONObject(manifest.readText(Charsets.UTF_8))
                val header = json.optJSONObject("header") ?: continue
                if (header.optString("name").isBlank()) continue
                valid++
            }
            if (valid == 0) error("Os manifestos encontrados não são válidos para um pacote Bedrock.")

            val safeName = "pack-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            val destination = File(root(context), safeName)
            if (!staging.renameTo(destination)) {
                staging.copyRecursively(destination, overwrite = false)
                staging.deleteRecursively()
            }
            val packs = list(context)
            val names = packs.filter { it.directory.absolutePath.startsWith(destination.absolutePath) }
                .joinToString(", ") { it.name }
            AddonCatalog.invalidate()
            val catalog = AddonCatalog.summary(context, force = true)
            return "Pacote importado: ${if (names.isBlank()) "$valid pacote(s)" else names}. Catálogo analisado: ${catalog.describe()} A conversão das geometrias e texturas Bedrock para o renderer ainda não está concluída."
        } catch (e: Exception) {
            staging.deleteRecursively()
            return "Falha na importação: ${e.message ?: "arquivo inválido"}"
        }
    }


    /**
     * Downloads a public HTTPS archive and imports it through the same validation path.
     * Only direct archive URLs are supported; redirects must remain HTTPS.
     */
    @Synchronized
    fun importFromUrl(context: Context, rawUrl: String): String {
        val downloadDir = File(context.cacheDir, "addons").apply { mkdirs() }
        val download = File(downloadDir, "download-${UUID.randomUUID()}.zip")
        try {
            var current = URL(rawUrl.trim())
            var redirects = 0
            while (true) {
                if (!current.protocol.equals("https", true))
                    error("Use um link HTTPS direto para o arquivo.")
                val connection = current.openConnection() as? HttpURLConnection
                    ?: error("Tipo de conexão não suportado.")
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "MineBlocos-AddonImporter/1.0")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) error("Redirecionamento sem destino.")
                    if (++redirects > 5) error("O link redirecionou muitas vezes.")
                    current = URL(current, location)
                    continue
                }
                if (status !in 200..299) {
                    connection.disconnect()
                    error("O servidor respondeu HTTP $status.")
                }
                if (connection.contentLengthLong > 100L * 1024L * 1024L) {
                    connection.disconnect()
                    error("O arquivo excede o limite de 100 MB.")
                }
                connection.inputStream.use { input ->
                    download.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > 100L * 1024L * 1024L)
                                error("O arquivo excede o limite de 100 MB.")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                connection.disconnect()
                break
            }
            val uri = FileProvider.getUriForFile(context,
                context.packageName + ".fileprovider", download)
            return import(context, uri)
        } catch (e: Exception) {
            return "Não foi possível baixar/importar: ${e.message ?: "link inválido"}"
        } finally {
            download.delete()
        }
    }

    /**
     * A .mcaddon commonly contains .mcpack ZIPs rather than loose manifests.
     * Expand those nested packs into sibling folders so their manifests/assets
     * can be indexed without altering the original imported files.
     */
    private fun expandNestedPacks(staging: File) {
        val archives = staging.walkTopDown().filter {
            it.isFile && (it.extension.equals("mcpack", true) || it.extension.equals("mcaddon", true))
        }.toList()
        for (archive in archives) {
            val destination = File(archive.parentFile, archive.nameWithoutExtension + "_contents")
            if (!destination.mkdirs()) continue
            var total = 0L
            var count = 0
            try {
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        if (count > MAX_ENTRIES) error("O pacote interno excede o limite de 100.000 arquivos.")
                        val name = entry.name.replace('\\', '/')
                        val parts = name.split('/')
                        if (name.startsWith("/") || parts.any { it == ".." } ||
                            name.contains(":") || name.isBlank()) {
                            zip.closeEntry()
                            continue
                        }
                        val out = File(destination, name).canonicalFile
                        if (out.canonicalPath != destination.canonicalPath &&
                            !out.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_UNCOMPRESSED_BYTES) error("O pacote interno excede o limite de 512 MB descompactados.")
                                    stream.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                destination.deleteRecursively()
                android.util.Log.w("Mineblocos", "Não foi possível expandir pacote aninhado", e)
            }
        }
    }

    /** List manifests found in installed packs; resource and behavior packs are kept distinct. */
    fun list(context: Context): List<Pack> {
        val base = root(context)
        if (!base.exists()) return emptyList()
        val result = ArrayList<Pack>()
        base.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".import-") }?.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.equals("manifest.json", true) }.forEach { file ->
                try {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val header = json.optJSONObject("header") ?: return@forEach
                    val modules = json.optJSONArray("modules")
                    val type = if (modules != null && modules.length() > 0)
                        modules.optJSONObject(0)?.optString("type", "unknown") ?: "unknown"
                    else "unknown"
                    val versionArray = header.optJSONArray("version")
                    val version = if (versionArray != null && versionArray.length() >= 3)
                        "${versionArray.optInt(0)}.${versionArray.optInt(1)}.${versionArray.optInt(2)}"
                    else "desconhecida"
                    val uuid = header.optString("uuid", file.absolutePath)
                    result.add(Pack(
                        id = uuid,
                        name = header.optString("name", dir.name),
                        description = header.optString("description", ""),
                        type = type,
                        version = version,
                        directory = file.parentFile ?: dir
                    ))
                } catch (_: Exception) { }
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    fun remove(context: Context, pack: Pack): Boolean {
        val root = root(context).canonicalFile
        val packDir = pack.directory.canonicalFile
        // Delete only the enclosing imported archive folder, never an arbitrary path.
        val owner = generateSequence(packDir) { it.parentFile }
            .firstOrNull { it.parentFile?.canonicalFile == root }
        return owner != null && owner.isDirectory && owner.name.startsWith("pack-") &&
            owner.deleteRecursively()
    }
}
