package com.mineblocos.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Camada de interface por cima do jogo: controles de toque, hotbar, mira. */
class HudView(
    ctx: Context,
    private val s: GameState,
    private val onPauseClick: () -> Unit
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
        setShadowLayer(dp(1.5f), dp(1.5f), dp(1.5f), 0xFF202020.toInt())
    }
    private val icons = HashMap<Int, Bitmap>()

    // geometria
    private var slot = 0f
    private var hbLeft = 0f
    private var hbTop = 0f
    private var jumpX = 0f; private var jumpY = 0f; private var jumpR = 0f
    private var flyX = 0f; private var flyY = 0f; private var flyR = 0f
    private var dnX = 0f; private var dnY = 0f; private var dnR = 0f
    private var pauseX = 0f; private var pauseY = 0f; private var pauseR = 0f
    private var joyDefX = 0f; private var joyDefY = 0f; private var joyR = 0f

    // toques
    private var joyId = -1
    private var lookId = -1
    private var jumpId = -1
    private var downId = -1
    private var joyOx = 0f; private var joyOy = 0f; private var joyX = 0f; private var joyY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var longFired = false

    // animação
    private var selPos = 0f
    private var nameAlpha = 1f
    private var lastBlock = -1
    private var overlay = 1f
    private val startT = SystemClock.uptimeMillis()

    private val longPress = Runnable {
        if (lookId != -1 && !moved) {
            longFired = true
            s.breaking = true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        slot = min(dp(46f), max(dp(34f), (w - dp(300f)) / 10f))
        hbLeft = (w - slot * 10f) / 2f
        hbTop = h - slot - dp(8f)
        jumpR = dp(38f); jumpX = w - dp(72f); jumpY = h - dp(112f)
        flyR = dp(25f); flyX = w - dp(72f); flyY = h - dp(196f)
        dnR = dp(30f); dnX = w - dp(152f); dnY = h - dp(80f)
        pauseR = dp(22f); pauseX = w - dp(36f); pauseY = dp(36f)
        joyR = dp(56f); joyDefX = dp(108f); joyDefY = h - dp(116f)
        icons.clear()
    }

    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r * 1.25f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                touchDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) touchMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                touchUp(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                touchUp(joyId); touchUp(lookId); touchUp(jumpId); touchUp(downId)
            }
        }
        return true
    }

    private fun touchDown(id: Int, x: Float, y: Float) {
        if (inCircle(x, y, pauseX, pauseY, pauseR)) { onPauseClick(); return }
        if (y >= hbTop - dp(4f) && x >= hbLeft && x <= hbLeft + slot * 10f) {
            s.selected = ((x - hbLeft) / slot).toInt().coerceIn(0, 9)
            return
        }
        if (inCircle(x, y, jumpX, jumpY, jumpR)) { jumpId = id; s.jump = true; return }
        if (inCircle(x, y, flyX, flyY, flyR)) { s.flying = !s.flying; if (!s.flying) s.down = false; return }
        if (s.flying && inCircle(x, y, dnX, dnY, dnR)) { downId = id; s.down = true; return }
        if (x < width * 0.38f && joyId == -1) {
            joyId = id; joyOx = x; joyOy = y; joyX = x; joyY = y
            return
        }
        if (lookId == -1) {
            lookId = id
            lastX = x; lastY = y; downX = x; downY = y
            downT = SystemClock.uptimeMillis()
            moved = false
            longFired = false
            postDelayed(longPress, 260L)
        }
    }

    private fun touchMove(id: Int, x: Float, y: Float) {
        if (id == joyId) {
            var vx = x - joyOx
            var vy = y - joyOy
            val len = hypot(vx, vy)
            if (len > joyR) { vx *= joyR / len; vy *= joyR / len }
            joyX = joyOx + vx
            joyY = joyOy + vy
            val nx = vx / joyR
            val ny = vy / joyR
            if (hypot(nx, ny) < 0.12f) { s.moveX = 0f; s.moveZ = 0f } else { s.moveX = nx; s.moveZ = -ny }
        } else if (id == lookId) {
            s.addLook(x - lastX, y - lastY)
            lastX = x; lastY = y
            if (!moved && hypot(x - downX, y - downY) > dp(14f)) {
                moved = true
                if (!longFired) removeCallbacks(longPress)
            }
        }
    }

    private fun touchUp(id: Int) {
        if (id < 0) return
        if (id == joyId) {
            joyId = -1; s.moveX = 0f; s.moveZ = 0f
        }
        if (id == jumpId) { jumpId = -1; s.jump = false }
        if (id == downId) { downId = -1; s.down = false }
        if (id == lookId) {
            removeCallbacks(longPress)
            if (longFired) {
                s.breaking = false
            } else if (!moved && SystemClock.uptimeMillis() - downT < 260L) {
                s.placeRequest = true
            }
            lookId = -1
            longFired = false
        }
    }

    // ---------------------------------------------------------------- desenho

    private fun icon(b: Int): Bitmap {
        return icons.getOrPut(b) { BlockIcons.make(b, max(8, (slot * 0.82f).toInt())) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()

        if (s.ready) overlay = max(0f, overlay - 0.04f)
        if (s.block != lastBlock) { lastBlock = s.block; nameAlpha = 1.6f }
        nameAlpha = max(0f, nameAlpha - 0.012f)
        selPos += (s.selected - selPos) * 0.35f

        // mira
        paint.style = Paint.Style.FILL
        paint.color = 0xCCFFFFFF.toInt()
        val cx = w / 2f
        val cy = h / 2f
        val arm = dp(9f)
        val th = dp(1.6f)
        c.drawRect(cx - arm, cy - th, cx + arm, cy + th, paint)
        c.drawRect(cx - th, cy - arm, cx + th, cy + arm, paint)

        // joystick
        val jx = if (joyId != -1) joyOx else joyDefX
        val jy = if (joyId != -1) joyOy else joyDefY
        val kx = if (joyId != -1) joyX else joyDefX
        val ky = if (joyId != -1) joyY else joyDefY
        val ja = if (joyId != -1) 1f else 0.55f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((60 * ja).toInt(), 255, 255, 255)
        c.drawCircle(jx, jy, joyR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.argb((150 * ja).toInt(), 255, 255, 255)
        c.drawCircle(jx, jy, joyR, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((130 * ja + 30).toInt(), 255, 255, 255)
        c.drawCircle(kx, ky, joyR * 0.42f, paint)

        // botões
        drawRound(c, jumpX, jumpY, jumpR, jumpId != -1, false)
        drawChevron(c, jumpX, jumpY, jumpR * 0.45f, true)
        drawRound(c, flyX, flyY, flyR, false, s.flying)
        text.textSize = dp(10f)
        text.textAlign = Paint.Align.CENTER
        c.drawText("VOAR", flyX, flyY + dp(3.5f), text)
        if (s.flying) {
            drawRound(c, dnX, dnY, dnR, downId != -1, false)
            drawChevron(c, dnX, dnY, dnR * 0.45f, false)
        }
        // pausa
        drawRound(c, pauseX, pauseY, pauseR, false, false)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawRect(pauseX - dp(6f), pauseY - dp(7f), pauseX - dp(2.5f), pauseY + dp(7f), paint)
        c.drawRect(pauseX + dp(2.5f), pauseY - dp(7f), pauseX + dp(6f), pauseY + dp(7f), paint)

        // hotbar
        val rect = RectF()
        for (i in 0 until 10) {
            val l = hbLeft + i * slot
            rect.set(l + 2f, hbTop, l + slot - 2f, hbTop + slot)
            paint.style = Paint.Style.FILL
            paint.color = 0x99101418.toInt()
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = 0xFF5A5F66.toInt()
            c.drawRoundRect(rect, dp(4f), dp(4f), paint)
            val ic = icon(Blocks.hotbar[i])
            val lift = if (i == s.selected) dp(3f) else 0f
            c.drawBitmap(ic, l + (slot - ic.width) / 2f, hbTop + (slot - ic.height) / 2f - lift, null)
        }
        val sl = hbLeft + selPos * slot
        rect.set(sl + 1f, hbTop - dp(2f), sl + slot - 1f, hbTop + slot + dp(2f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.WHITE
        c.drawRoundRect(rect, dp(5f), dp(5f), paint)

        // nome do bloco
        if (nameAlpha > 0f) {
            text.textSize = dp(15f)
            text.alpha = (min(1f, nameAlpha) * 255).toInt()
            c.drawText(Blocks.name(s.block), w / 2f, hbTop - dp(10f), text)
            text.alpha = 255
        }

        // textos
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(12f)
        if (s.showFps) {
            c.drawText("FPS: ${s.fps}", dp(12f), dp(22f), text)
            c.drawText(
                "XYZ: ${"%.1f".format(s.px)} ${"%.1f".format(s.py)} ${"%.1f".format(s.pz)}",
                dp(12f), dp(40f), text
            )
        }
        val tn = s.targetName
        if (tn != null) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(11f)
            text.alpha = 190
            c.drawText(tn, w / 2f, cy + dp(34f), text)
            text.alpha = 255
        }
        val since = now - startT
        if (since < 12000L && s.ready) {
            val a = if (since > 9500L) (12000L - since) / 2500f else 1f
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(12f)
            text.alpha = (a * 255).toInt()
            c.drawText("Toque: colocar  •  Segure: quebrar  •  Arraste: olhar", w / 2f, dp(26f), text)
            text.alpha = 255
        }

        // carregando
        if (overlay > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((overlay * 255).toInt(), 16, 20, 24)
            c.drawRect(0f, 0f, w, h, paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(18f)
            text.alpha = (overlay * 255).toInt()
            val dots = ".".repeat(1 + ((now / 350L) % 3L).toInt())
            c.drawText("Construindo o mundo$dots", w / 2f, h / 2f, text)
            text.alpha = 255
        }
        postInvalidateOnAnimation()
    }

    private fun drawRound(c: Canvas, x: Float, y: Float, r: Float, pressed: Boolean, active: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (active) 0xAA57C84D.toInt() else if (pressed) 0x88FFFFFF.toInt() else 0x55FFFFFF.toInt()
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = 0xBBFFFFFF.toInt()
        c.drawCircle(x, y, r, paint)
    }

    private fun drawChevron(c: Canvas, x: Float, y: Float, r: Float, up: Boolean) {
        val p = Path()
        if (up) {
            p.moveTo(x - r, y + r * 0.5f)
            p.lineTo(x, y - r * 0.5f)
            p.lineTo(x + r, y + r * 0.5f)
        } else {
            p.moveTo(x - r, y - r * 0.5f)
            p.lineTo(x, y + r * 0.5f)
            p.lineTo(x + r, y - r * 0.5f)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        c.drawPath(p, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }
}
