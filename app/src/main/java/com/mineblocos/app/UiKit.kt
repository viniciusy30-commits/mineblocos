package com.mineblocos.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button

object UiKit {
    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    fun mcButton(c: Context, label: String, click: () -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.setShadowLayer(1f, 2f, 2f, 0xFF3F3F3F.toInt())
        fun box(fill: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(fill)
            g.setStroke(dp(c, 3f), 0xFF000000.toInt())
            return g
        }
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), box(0xFF5B7FB5.toInt()))
        sl.addState(intArrayOf(), box(0xFF717171.toInt()))
        b.background = sl
        b.stateListAnimator = null
        b.minimumHeight = dp(c, 46f)
        b.setOnClickListener { click() }
        return b
    }
}
