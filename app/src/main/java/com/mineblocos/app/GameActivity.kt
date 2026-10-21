package com.mineblocos.app

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Random

class GameActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var glView: GLSurfaceView? = null
    private var state: GameState? = null
    private var world: World? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFF101418.toInt())
        val loading = TextView(this)
        loading.text = "Gerando mundo..."
        loading.setTextColor(0xFFFFFFFF.toInt())
        loading.textSize = 18f
        loading.gravity = Gravity.CENTER
        root.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showPause()
            }
        })

        val novo = intent.getBooleanExtra("novo", false)
        val app = applicationContext
        Thread {
            // Load imported Bedrock furniture before restoring/generating a world so block IDs
            // and the runtime texture atlas are ready before the renderer is created.
            try {
                val addonResult = Reg.loadImportedAddons(app)
                android.util.Log.i("Mineblocos", addonResult)
            } catch (e: Throwable) {
                android.util.Log.e("Mineblocos", "Falha ao converter addon Bedrock", e)
            }
            val loaded = if (novo) null else WorldStore.load(app)
            val w: World
            val st = GameState()
            if (loaded != null) {
                w = loaded.world
                st.px = loaded.px; st.py = loaded.py; st.pz = loaded.pz
                st.yaw = loaded.yaw; st.pitch = loaded.pitch; st.time = loaded.time
            } else {
                w = World(Random().nextInt())
                w.generate { }
                st.px = World.W / 2 + 0.5f
                st.pz = World.D / 2 + 0.5f
                st.py = (w.surfaceY(World.W / 2, World.D / 2) + 1).toFloat()
            }
            Atlas.bitmap
            runOnUiThread { start(w, st) }
        }.start()
    }

    private fun start(w: World, st: GameState) {
        if (isFinishing || isDestroyed) return
        world = w
        state = st
        st.sens = Prefs.sensRad(this)
        st.showFps = Prefs.showFps(this)

        val animJson = try {
            assets.open("player_anims.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
        val renderer = GameRenderer(w, st, Prefs.dist(this), animJson)
        val gv = GLSurfaceView(this)
        gv.setEGLContextClientVersion(2)
        gv.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        gv.setPreserveEGLContextOnPause(true)
        gv.setRenderer(renderer)
        glView = gv

        root.removeAllViews()
        root.addView(gv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            HudView(this, st, { showPause() }, { showCategories() }),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // mundo novo: já pergunta quais categorias montar
        if (intent.getBooleanExtra("novo", false)) gv.post { showCategories() }
    }

    /** Escolha das categorias da vitrine: a vitrine é refeita só com as marcadas. */
    private fun showCategories() {
        val w = world ?: return
        val names = Showcase.categoryNames().toTypedArray()
        val current = w.areas.map { it.name }.toHashSet()
        val checked = BooleanArray(names.size) { names[it] in current }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Categorias da vitrine")
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Aplicar") { _, _ ->
                val sel = HashSet<String>()
                for (i in names.indices) if (checked[i]) sel.add(names[i])
                applyCategories(sel)
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Todas", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val v = !checked.all { it }
            for (i in names.indices) { checked[i] = v; dlg.listView.setItemChecked(i, v) }
        }
    }

    private fun applyCategories(sel: Set<String>) {
        val w = world ?: return
        val st = state ?: return
        val gv = glView ?: return
        val msg = AlertDialog.Builder(this).setMessage("Montando vitrine...").setCancelable(false).show()
        gv.queueEvent {
            try {
                w.rebuildShowcase(sel)
                // volta para o ponto de partida (livre), para não ficar preso dentro de um bloco novo
                st.vx = 0f; st.vy = 0f; st.vz = 0f
                st.px = World.W / 2 + 0.5f
                st.pz = World.D / 2 + 0.5f
                st.py = (w.surfaceY(World.W / 2, World.D / 2) + 1).toFloat()
            } catch (e: Throwable) {
                android.util.Log.e("Mineblocos", "falha ao montar vitrine", e)
            }
            runOnUiThread {
                msg.dismiss()
                saveWorld()
            }
        }
    }

    private fun showPause() {
        if (state == null) {
            finish()
            return
        }
        saveWorld()
        AlertDialog.Builder(this)
            .setTitle("Jogo pausado")
            .setItems(arrayOf("Continuar", "Categorias da vitrine", "Salvar e sair")) { _, which ->
                if (which == 1) {
                    showCategories()
                } else if (which == 2) {
                    saveWorld()
                    finish()
                }
            }
            .show()
    }

    private fun saveWorld() {
        val w = world ?: return
        val s = state ?: return
        val copy = w.blocks.clone()
        val metaCopy = w.meta.clone()
        val seed = w.seed
        val showcase = w.showcase.clone() as java.util.BitSet
        val areas = ArrayList(w.areas)
        val px = s.px; val py = s.py; val pz = s.pz
        val yaw = s.yaw; val pitch = s.pitch; val time = s.time
        val app = applicationContext
        Thread { WorldStore.save(app, copy, metaCopy, seed, showcase, areas, px, py, pz, yaw, pitch, time) }.start()
    }

    override fun onPause() {
        super.onPause()
        state?.let {
            it.breaking = false; it.jump = false; it.down = false
            it.moveX = 0f; it.moveZ = 0f
        }
        glView?.onPause()
        saveWorld()
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(window)
        glView?.onResume()
    }
}
