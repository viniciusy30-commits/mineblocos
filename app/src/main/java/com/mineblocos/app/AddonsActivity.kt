package com.mineblocos.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import java.util.concurrent.Executors
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** UI for importing and inspecting Bedrock add-on archives. */
class AddonsActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout
    private val addonExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(0xFF252525.toInt())
        }
        val title = TextView(this).apply {
            text = "ADDONS"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val info = TextView(this).apply {
            text = "Importe arquivos .mcaddon, .mcpack ou .zip. Ao iniciar o mundo, o MineBlocos tenta converter os blocos e modelos estáticos do addon para o inventário e para o mundo. Scripts, animações e estados avançados próprios do Bedrock não são executados."
            textSize = 14f
            setTextColor(0xFFD7D7D7.toInt())
            setPadding(4, 12, 4, 12)
        }
        root.addView(info)
        val officialButton = UiKit.mcButton(this, "Abrir monoDeco oficial para baixar") {
            val page = Uri.parse("https://www.curseforge.com/minecraft-bedrock/addons/monodeco-gen-7/files/all")
            startActivity(Intent(Intent.ACTION_VIEW, page))
        }
        root.addView(officialButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        val importButton = UiKit.mcButton(this, "Importar arquivo baixado") { chooseArchive() }
        root.addView(importButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        val urlInput = EditText(this).apply {
            hint = "Link direto HTTPS do .mcaddon/.mcpack"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFBBBBBB.toInt())
            setPadding(12, 8, 12, 8)
        }
        root.addView(urlInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        lateinit var urlButton: Button
        urlButton = UiKit.mcButton(this, "Baixar addon pelo link") {
            val url = urlInput.text.toString().trim()
            if (!url.startsWith("https://", ignoreCase = true)) {
                AlertDialog.Builder(this).setTitle("Link inválido")
                    .setMessage("Cole um link HTTPS direto para o arquivo, não a página do site.")
                    .setPositiveButton("OK", null).show()
            } else {
                urlButton.isEnabled = false
                addonExecutor.execute {
                    val result = AddonManager.importFromUrl(this, url)
                    runOnUiThread {
                        urlButton.isEnabled = true
                        AlertDialog.Builder(this).setTitle("Download do addon")
                            .setMessage(result).setPositiveButton("OK", null).show()
                        refreshList()
                    }
                }
            }
        }
        root.addView(urlButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        val refreshButton = UiKit.mcButton(this, "Atualizar lista") { refreshList() }
        root.addView(refreshButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(UiKit.mcButton(this, "Voltar") { finish() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        setContentView(root)
        refreshList()
    }

    private fun chooseArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip", "application/x-zip-compressed",
                "application/octet-stream", "application/x-mcaddon", "application/x-mcpack"
            ))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_ARCHIVE)
    }

    @Deprecated("Legacy result callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ARCHIVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val result = AddonManager.import(this, uri)
        AlertDialog.Builder(this).setTitle("Importação de addon")
            .setMessage(result).setPositiveButton("OK", null).show()
        refreshList()
    }

    private fun refreshList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val packs = AddonManager.list(this)
        if (packs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Nenhum pacote importado ainda."
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(8, 18, 8, 18)
            })
            return
        }
        for (pack in packs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(0xFF383838.toInt())
            }
            card.addView(TextView(this).apply {
                text = pack.name
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
            })
            card.addView(TextView(this).apply {
                text = "Tipo: ${pack.type}  •  Versão: ${pack.version}" +
                    if (pack.description.isBlank()) "" else "\n${pack.description}"
                textSize = 12f
                setTextColor(0xFFDDDDDD.toInt())
                setPadding(0, 4, 0, 6)
            })
            val remove = UiKit.mcButton(this, "Remover pacote") {
                AlertDialog.Builder(this).setTitle("Remover addon")
                    .setMessage("Remover \"${pack.name}\" do armazenamento do MineBlocos?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Remover") { _, _ ->
                        AddonManager.remove(this, pack)
                        refreshList()
                    }.show()
            }
            card.addView(remove, ViewGroup.LayoutParams(-1, -2))
            listBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
    }

    override fun onDestroy() {
        addonExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_ARCHIVE = 7304 }
}
