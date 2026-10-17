package com.mineblocos.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        val dp = { v: Float -> UiKit.dp(this, v) }

        val root = FrameLayout(this)
        root.addView(MenuBackgroundView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24f), dp(16f), dp(24f), dp(16f))

        fun label(t: String, size: Float = 15f): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setTextColor(Color.WHITE)
            v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            v.setShadowLayer(1f, 2f, 2f, 0xFF202020.toInt())
            return v
        }

        val head = label("Configurações", 24f)
        col.addView(head, LinearLayout.LayoutParams(-2, -2))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val bw = dp(420f)

        fun slider(titulo: String, min: Int, max: Int, atual: Int, fmt: (Int) -> String, onSet: (Int) -> Unit) {
            val t = label("$titulo: ${fmt(atual)}")
            val sb = SeekBar(this)
            sb.max = max - min
            sb.progress = atual - min
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    t.text = "$titulo: ${fmt(p + min)}"
                    if (fromUser) onSet(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            val lp = LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14f)
            box.addView(t, lp)
            box.addView(sb, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        slider("Sensibilidade do olhar", 1, 10, Prefs.sens(this), { it.toString() }) { Prefs.setSens(this, it) }
        slider("Distância de visão", 3, 8, Prefs.dist(this), { "$it chunks" }) { Prefs.setDist(this, it) }

        val cb = CheckBox(this)
        cb.text = "Mostrar FPS e coordenadas"
        cb.setTextColor(Color.WHITE)
        cb.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        cb.isChecked = Prefs.showFps(this)
        cb.setOnCheckedChangeListener { _, v -> Prefs.setShowFps(this, v) }
        val lpc = LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpc.topMargin = dp(14f)
        box.addView(cb, lpc)

        val status = label("", 13f)
        status.text = "Versão instalada: v" + Updater.installedCode(this)
        val btnUpd = UiKit.mcButton(this, "Verificar atualização") {
            Updater.check(this, true) { msg -> status.text = msg }
        }
        val lpb = LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpb.topMargin = dp(18f)
        box.addView(btnUpd, lpb)
        val lps = LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT)
        lps.topMargin = dp(8f)
        box.addView(status, lps)

        val back = UiKit.mcButton(this, "Voltar") { finish() }
        val lpk = LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpk.topMargin = dp(16f)
        box.addView(back, lpk)

        col.addView(box, LinearLayout.LayoutParams(-2, -2))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(window)
    }
}
