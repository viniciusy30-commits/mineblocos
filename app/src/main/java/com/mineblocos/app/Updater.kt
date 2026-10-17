package com.mineblocos.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val REPO = "viniciusy30-commits/mineblocos"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    private class Info(val code: Long, val url: String?)

    fun installedCode(ctx: Context): Long {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(i)
    }

    /** Ao abrir o app: no máximo a cada 6 horas, avisa só se houver versão nova. */
    fun autoCheck(a: Activity) {
        val p = a.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) < 6L * 3600L * 1000L) return
        p.edit().putLong("last", now).apply()
        check(a, false, null)
    }

    fun check(a: Activity, manual: Boolean, status: ((String) -> Unit)?) {
        status?.invoke("Procurando atualização...")
        Thread {
            var info: Info? = null
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "MineBlocos")
                if (c.responseCode == 200) {
                    val txt = c.inputStream.bufferedReader().use { it.readText() }
                    val j = JSONObject(txt)
                    val code = j.getString("tag_name").removePrefix("v").trim().toLongOrNull() ?: 0L
                    val assets = j.getJSONArray("assets")
                    var url: String? = null
                    for (i in 0 until assets.length()) {
                        val o = assets.getJSONObject(i)
                        if (o.getString("name").endsWith(".apk")) {
                            url = o.getString("browser_download_url")
                            break
                        }
                    }
                    info = Info(code, url)
                }
                c.disconnect()
            } catch (e: Exception) {
                info = null
            }
            val result = info
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                val cur = installedCode(a)
                if (result == null) {
                    status?.invoke("Sem internet ou não foi possível verificar.")
                } else if (result.code > cur && result.url != null) {
                    status?.invoke("Nova versão encontrada: v${result.code}")
                    showUpdateDialog(a, result, cur)
                } else {
                    status?.invoke("Você já está na versão mais nova (v$cur).")
                }
            }
        }.start()
    }

    private fun showUpdateDialog(a: Activity, info: Info, cur: Long) {
        AlertDialog.Builder(a)
            .setTitle("Nova versão disponível")
            .setMessage("A versão v${info.code} está pronta. Você está na v$cur.")
            .setPositiveButton("Atualizar") { _, _ -> startDownload(a, info.url!!) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun startDownload(a: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Permissão necessária")
                .setMessage("Para instalar a atualização, permita \"Instalar apps desconhecidos\" para o MineBlocos na próxima tela. Depois volte e toque em Atualizar de novo.")
                .setPositiveButton("Abrir configurações") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                    a.startActivity(i)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad / 2)
        val label = TextView(a)
        label.text = "Baixando... 0%"
        label.gravity = Gravity.CENTER_HORIZONTAL
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(label)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var cancelled = false
        val dialog = AlertDialog.Builder(a)
            .setTitle("Atualizando")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> cancelled = true }
            .show()

        val app = a.applicationContext
        Thread {
            var file: File? = null
            try {
                val dir = File(app.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "MineBlocos.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "MineBlocos")
                val total = c.contentLengthLong
                var done = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(16384)
                        while (true) {
                            if (cancelled) break
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post {
                                        bar.progress = pct
                                        label.text = "Baixando... $pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!cancelled && out.length() > 0) file = out
            } catch (e: Exception) {
                file = null
            }
            val result = file
            main.post {
                try { dialog.dismiss() } catch (e: Exception) { }
                if (result != null) {
                    install(app, result)
                } else if (!cancelled && !a.isFinishing) {
                    AlertDialog.Builder(a)
                        .setTitle("Falha no download")
                        .setMessage("Não foi possível baixar a atualização. Verifique a internet e tente de novo.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
