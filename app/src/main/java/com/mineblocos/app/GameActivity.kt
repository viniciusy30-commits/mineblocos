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

        val renderer = GameRenderer(w, st, Prefs.dist(this))
        val gv = GLSurfaceView(this)
        gv.setEGLContextClientVersion(2)
        gv.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        gv.setPreserveEGLContextOnPause(true)
        gv.setRenderer(renderer)
        glView = gv

        root.removeAllViews()
        root.addView(gv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            HudView(this, st) { showPause() },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun showPause() {
        if (state == null) {
            finish()
            return
        }
        saveWorld()
        AlertDialog.Builder(this)
            .setTitle("Jogo pausado")
            .setItems(arrayOf("Continuar", "Salvar e sair")) { _, which ->
                if (which == 1) {
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
        val seed = w.seed
        val px = s.px; val py = s.py; val pz = s.pz
        val yaw = s.yaw; val pitch = s.pitch; val time = s.time
        val app = applicationContext
        Thread { WorldStore.save(app, copy, seed, px, py, pz, yaw, pitch, time) }.start()
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
