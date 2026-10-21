package com.mineblocos.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var btnPlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        val dp = { v: Float -> UiKit.dp(this, v) }

        val root = FrameLayout(this)
        root.addView(MenuBackgroundView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))

        val titleBox = FrameLayout(this)
        val title = TextView(this)
        title.text = "MINEBLOCOS"
        title.textSize = 44f
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.letterSpacing = 0.08f
        title.setShadowLayer(1f, 7f, 7f, 0xFF2A2A2A.toInt())
        title.gravity = Gravity.CENTER
        titleBox.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val splash = TextView(this)
        splash.text = "Feito no celular!"
        splash.textSize = 15f
        splash.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        splash.setTextColor(0xFFFFE94A.toInt())
        splash.setShadowLayer(1f, 3f, 3f, 0xFF3F3A00.toInt())
        splash.rotation = -14f
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM)
        lp.setMargins(0, 0, -dp(30f), -dp(8f))
        titleBox.addView(splash, lp)
        col.addView(titleBox, LinearLayout.LayoutParams(-2, -2))

        ObjectAnimator.ofFloat(splash, "scaleX", 1f, 1.14f).apply {
            duration = 650; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }
        ObjectAnimator.ofFloat(splash, "scaleY", 1f, 1.14f).apply {
            duration = 650; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }
        ObjectAnimator.ofFloat(title, "translationY", -dp(3f).toFloat(), dp(3f).toFloat()).apply {
            duration = 1800; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.VERTICAL
        fun add(b: Button) {
            val p = LinearLayout.LayoutParams(dp(300f), ViewGroup.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(8f)
            btns.addView(b, p)
        }
        btnPlay = UiKit.mcButton(this, "Jogar") { startGame(false) }
        add(btnPlay)
        add(UiKit.mcButton(this, "Novo mundo") { confirmNew() })
        add(UiKit.mcButton(this, "Addons") { startActivity(Intent(this, AddonsActivity::class.java)) })
        add(UiKit.mcButton(this, "Configurações") { startActivity(Intent(this, SettingsActivity::class.java)) })
        add(UiKit.mcButton(this, "O que mudou") { Novidades.showAll(this) })

        val lpBtns = LinearLayout.LayoutParams(-2, -2)
        lpBtns.topMargin = dp(10f)
        col.addView(btns, lpBtns)

        val ver = TextView(this)
        ver.text = "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        ver.setTextColor(0xAAFFFFFF.toInt())
        ver.textSize = 12f
        ver.typeface = Typeface.MONOSPACE
        val lpv = LinearLayout.LayoutParams(-2, -2)
        lpv.topMargin = dp(12f)
        col.addView(ver, lpv)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        col.alpha = 0f
        col.translationY = dp(24f).toFloat()
        col.animate().alpha(1f).translationY(0f).setDuration(600).start()

        Novidades.showIfNew(this)
        Updater.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(window)
        btnPlay.text = if (WorldStore.exists(this)) "Continuar mundo" else "Jogar"
    }

    private fun startGame(novo: Boolean) {
        val i = Intent(this, GameActivity::class.java)
        i.putExtra("novo", novo)
        startActivity(i)
    }

    private fun confirmNew() {
        if (!WorldStore.exists(this)) {
            startGame(true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Novo mundo")
            .setMessage("Isso apaga o mundo salvo e cria um novo. Continuar?")
            .setPositiveButton("Criar novo") { _, _ ->
                WorldStore.delete(this)
                startGame(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
