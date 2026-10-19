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
    private val onPauseClick: () -> Unit,
    private val onCategoriesClick: () -> Unit = {}
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
    private val invIcons = HashMap<Int, Bitmap>()

    // inventário
    private var invOpen = false
    private var invScroll = 0f
    private var invMaxScroll = 0f
    private var invId = -1
    private var invDownX = 0f; private var invDownY = 0f; private var invLastY = 0f
    private var invMoved = false
    private var invX = 0f; private var invY = 0f; private var invR = 0f
    private var cubes: IntArray = IntArray(0)
    private var invTab = 0
    private val tabNames = arrayOf("Blocos", "Escadas", "Formas", "Móveis")

    init {
        Prefs.hotbar(ctx)?.let { v ->
            if (v.all { Reg.placeable(it) }) for (i in 0 until 10) s.hotbar[i] = v[i]
        }
    }

    private val letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val letterIdx = IntArray(27)

    private fun loadTab() {
        cubes = Reg.buildingList(invTab)
        invScroll = 0f
        if (invTab == 3) {
            // primeiro item de cada letra (ou o próximo que existir)
            var p = 0
            for (li in letters.indices) {
                while (p < cubes.size && letters.indexOf(Reg.initial(cubes[p])) < li) p++
                letterIdx[li] = min(p, max(0, cubes.size - 1))
            }
        }
    }

    private fun gridTopY(): Float = pt + dp(30f) + (if (invTab == 3) dp(48f) else 0f)
    private val pl get() = dp(24f)
    private val pr get() = width - dp(24f)
    private val pt get() = dp(76f)
    private val pb get() = hbTop - dp(10f)
    private val cell get() = dp(50f)
    private val cols get() = max(1, ((pr - pl - dp(16f)) / cell).toInt())

    // geometria
    private var slot = 0f
    private var hbLeft = 0f
    private var hbTop = 0f
    private var jumpX = 0f; private var jumpY = 0f; private var jumpR = 0f
    private var flyX = 0f; private var flyY = 0f; private var flyR = 0f
    private var camX = 0f; private var camY = 0f; private var camR = 0f
    private var dnX = 0f; private var dnY = 0f; private var dnR = 0f
    private var pauseX = 0f; private var pauseY = 0f; private var pauseR = 0f
    private var cfgX = 0f; private var cfgY = 0f; private var cfgR = 0f
    private var sunX = 0f; private var sunY = 0f; private var sunR = 0f
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

    // botão COPIAR (aparece ao mirar num bloco da vitrine)
    private var copyId = -1
    private var copyBlk = 0
    private val copyRect = RectF()
    private var copyFlash = 0L

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
        camR = dp(25f); camX = w - dp(152f); camY = h - dp(170f)
        dnR = dp(30f); dnX = w - dp(152f); dnY = h - dp(80f)
        pauseR = dp(22f); pauseX = w - dp(36f); pauseY = dp(36f)
        invR = dp(22f); invX = w - dp(92f); invY = dp(36f)
        cfgR = dp(22f); cfgX = w - dp(148f); cfgY = dp(36f)
        sunR = dp(22f); sunX = w - dp(204f); sunY = dp(36f)
        joyR = dp(56f); joyDefX = dp(108f); joyDefY = h - dp(116f)
        icons.clear()
        invIcons.clear()
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
                copyId = -1
                touchUp(joyId); touchUp(lookId); touchUp(jumpId); touchUp(downId)
            }
        }
        return true
    }

    private fun resetControls() {
        removeCallbacks(longPress)
        joyId = -1; lookId = -1; jumpId = -1; downId = -1; copyId = -1
        s.moveX = 0f; s.moveZ = 0f; s.jump = false; s.down = false; s.breaking = false
        longFired = false
    }

    private fun invContentH(): Float = ((cubes.size + cols - 1) / cols) * cell

    private fun invTouchDown(id: Int, x: Float, y: Float) {
        if (invTab == 3 && y in (pt + dp(30f))..(pt + dp(78f)) && x in pl..pr) {
            val lw = (pr - pl - dp(16f)) / 14f
            val row = if (y < pt + dp(54f)) 0 else 1
            val col = ((x - (pl + dp(8f))) / lw).toInt().coerceIn(0, 13)
            val li = row * 14 + col
            if (li in 0..26) invScroll = ((letterIdx[li] / cols) * cell).coerceIn(0f, invMaxScroll)
            return
        }
        if (y in pt..(pt + dp(30f)) && x in pl..pr) {
            val tw = dp(58f)
            val i = ((x - (pr - tw * 4f - dp(6f))) / tw).toInt()
            if (x >= pr - tw * 4f - dp(6f) && i in 0..3 && i != invTab) { invTab = i; loadTab() }
            return
        }
        if (y >= hbTop - dp(4f) && x >= hbLeft && x <= hbLeft + slot * 10f) {
            s.selected = ((x - hbLeft) / slot).toInt().coerceIn(0, 9)
            return
        }
        if (invId == -1 && x in pl..pr && y in pt..pb) {
            invId = id; invDownX = x; invDownY = y; invLastY = y; invMoved = false
        }
    }

    private fun invTouchMove(id: Int, x: Float, y: Float) {
        if (id != invId) return
        if (!invMoved && hypot(x - invDownX, y - invDownY) > dp(10f)) invMoved = true
        if (invMoved) {
            invScroll = (invScroll - (y - invLastY)).coerceIn(0f, invMaxScroll)
            invLastY = y
        }
    }

    private fun invTouchUp(id: Int) {
        if (id != invId) return
        invId = -1
        if (invMoved) return
        val gridTop = gridTopY()
        val gx = invDownX - (pl + dp(8f))
        val gy = invDownY - gridTop + invScroll
        if (gx < 0f || gy < 0f || invDownY < gridTop) return
        val col = (gx / cell).toInt()
        val row = (gy / cell).toInt()
        if (col >= cols) return
        val idx = row * cols + col
        if (idx in cubes.indices) {
            s.hotbar[s.selected] = cubes[idx]
            Prefs.setHotbar(context, s.hotbar)
        }
    }

    private fun touchDown(id: Int, x: Float, y: Float) {
        if (inCircle(x, y, pauseX, pauseY, pauseR)) { onPauseClick(); return }
        if (inCircle(x, y, cfgX, cfgY, cfgR)) { onCategoriesClick(); return }
        if (inCircle(x, y, sunX, sunY, sunR)) {
            // alterna dia/noite (meio-dia = 0.25, meia-noite = 0.75)
            s.time = if (sin(2.0 * Math.PI * s.time) > 0.0) 0.75f else 0.25f
            return
        }
        if (inCircle(x, y, invX, invY, invR)) {
            invOpen = !invOpen
            if (invOpen) {
                resetControls()
                loadTab()
                invId = -1
            }
            return
        }
        if (invOpen) { invTouchDown(id, x, y); return }
        if (y >= hbTop - dp(4f) && x >= hbLeft && x <= hbLeft + slot * 10f) {
            s.selected = ((x - hbLeft) / slot).toInt().coerceIn(0, 9)
            return
        }
        if (s.targetShow != 0 && copyId == -1 && copyRect.contains(x, y)) {
            copyId = id; copyBlk = s.targetShow
            return
        }
        if (inCircle(x, y, jumpX, jumpY, jumpR)) { jumpId = id; s.jump = true; return }
        if (inCircle(x, y, flyX, flyY, flyR)) { s.flying = !s.flying; if (!s.flying) s.down = false; return }
        if (inCircle(x, y, camX, camY, camR)) { s.camMode = (s.camMode + 1) % 3; return }
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
        if (invOpen) { invTouchMove(id, x, y); return }
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
        if (id == copyId) {
            copyId = -1
            if (copyBlk != 0 && Reg.placeable(copyBlk)) {
                s.hotbar[s.selected] = copyBlk
                Prefs.setHotbar(context, s.hotbar)
                copyFlash = SystemClock.uptimeMillis()
            }
            return
        }
        if (invOpen || id == invId) { invTouchUp(id); return }
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
        drawRound(c, camX, camY, camR, false, false)
        c.drawText("CÂM", camX, camY + dp(3.5f), text)
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

        // configurações (categorias da vitrine): engrenagem
        drawRound(c, cfgX, cfgY, cfgR, false, false)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.WHITE
        c.drawCircle(cfgX, cfgY, dp(6f), paint)
        paint.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 8) {
            val a = k * Math.PI / 4
            val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            c.drawLine(cfgX + ca * dp(9f), cfgY + sa * dp(9f), cfgX + ca * dp(12f), cfgY + sa * dp(12f), paint)
        }
        paint.strokeCap = Paint.Cap.BUTT

        // dia/noite: mostra o sol de dia e a lua de noite
        drawRound(c, sunX, sunY, sunR, false, false)
        if (sin(2.0 * Math.PI * s.time) > 0.0) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFD83A.toInt()
            c.drawCircle(sunX, sunY, dp(6.5f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                c.drawLine(sunX + ca * dp(9.5f), sunY + sa * dp(9.5f), sunX + ca * dp(12.5f), sunY + sa * dp(12.5f), paint)
            }
            paint.strokeCap = Paint.Cap.BUTT
        } else {
            val moon = Path()
            moon.addCircle(sunX, sunY, dp(10f), Path.Direction.CW)
            val cut = Path()
            cut.addCircle(sunX + dp(5f), sunY - dp(3.5f), dp(8.5f), Path.Direction.CW)
            moon.op(cut, Path.Op.DIFFERENCE)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFE9EDF7.toInt()
            c.drawPath(moon, paint)
        }

        // botão do inventário
        drawRound(c, invX, invY, invR, false, invOpen)
        text.textSize = dp(10f)
        text.textAlign = Paint.Align.CENTER
        c.drawText(if (invOpen) "FECHAR" else "INV", invX, invY + dp(3.5f), text)

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
            val ic = icon(s.hotbar[i])
            val lift = if (i == s.selected) dp(3f) else 0f
            c.drawBitmap(ic, l + (slot - ic.width) / 2f, hbTop + (slot - ic.height) / 2f - lift, null)
        }
        val sl = hbLeft + selPos * slot
        rect.set(sl + 1f, hbTop - dp(2f), sl + slot - 1f, hbTop + slot + dp(2f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.WHITE
        c.drawRoundRect(rect, dp(5f), dp(5f), paint)

        if (invOpen) drawInventory(c)

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
        val an = s.areaName
        if (an != null) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(13f)
            text.alpha = 225
            c.drawText("Vitrine  •  $an", w / 2f, dp(24f), text)
            text.alpha = 255
        }
        if (s.targetShow != 0 && !invOpen) {
            val bw = dp(104f); val bh = dp(34f)
            copyRect.set(cx - bw / 2f, cy + dp(50f), cx + bw / 2f, cy + dp(50f) + bh)
            val done = s.hotbar[s.selected] == s.targetShow
            paint.style = Paint.Style.FILL
            paint.color = if (copyId != -1) 0xFF3E9A36.toInt() else if (done) 0xCC2F7D2A.toInt() else 0xCC57C84D.toInt()
            c.drawRoundRect(copyRect, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(copyRect, dp(8f), dp(8f), paint)
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(12f)
            c.drawText(if (done) "COPIADO" else "COPIAR", cx, copyRect.centerY() + dp(4.5f), text)
            text.textSize = dp(10f)
            text.alpha = 200
            c.drawText("indestrutível", cx, copyRect.bottom + dp(14f), text)
            text.alpha = 255
        } else {
            copyRect.setEmpty()
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
            c.drawText("Toque: colocar  •  Segure: quebrar  •  Arraste: olhar", w / 2f, dp(52f), text)
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

    private fun drawInventory(c: Canvas) {
        val rect = RectF(pl, pt, pr, pb)
        paint.style = Paint.Style.FILL
        paint.color = 0xE0101418.toInt()
        c.drawRoundRect(rect, dp(8f), dp(8f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = 0xFF5A5F66.toInt()
        c.drawRoundRect(rect, dp(8f), dp(8f), paint)

        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(13f)
        c.drawText("Inventário", pl + dp(10f), pt + dp(20f), text)
        val tw = dp(58f)
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(11f)
        for (i in 0 until 4) {
            val tl = pr - tw * 4f - dp(6f) + i * tw
            val tr = RectF(tl + 2f, pt + dp(5f), tl + tw - 2f, pt + dp(27f))
            paint.style = Paint.Style.FILL
            paint.color = if (i == invTab) 0xAA57C84D.toInt() else 0x55FFFFFF
            c.drawRoundRect(tr, dp(4f), dp(4f), paint)
            c.drawText(tabNames[i], (tl + tw / 2f), pt + dp(20f), text)
        }
        text.textAlign = Paint.Align.LEFT

        if (invTab == 3) {
            // índice por letra (2 linhas)
            val lw = (pr - pl - dp(16f)) / 14f
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(12f)
            for (li in 0 until 27) {
                val row = li / 14
                val col = li % 14
                val lx = pl + dp(8f) + col * lw
                val ly = pt + dp(32f) + row * dp(22f)
                paint.style = Paint.Style.FILL
                paint.color = 0x44FFFFFF
                c.drawRoundRect(RectF(lx + 1f, ly, lx + lw - 1f, ly + dp(20f)), dp(3f), dp(3f), paint)
                c.drawText(letters[li].toString(), lx + lw / 2f, ly + dp(14f), text)
            }
            text.textAlign = Paint.Align.LEFT
        }

        val gridTop = gridTopY()
        val rows = (cubes.size + cols - 1) / cols
        invMaxScroll = max(0f, rows * cell - (pb - gridTop - dp(6f)))
        invScroll = invScroll.coerceIn(0f, invMaxScroll)

        c.save()
        c.clipRect(pl + dp(2f), gridTop, pr - dp(2f), pb - dp(4f))
        val first = max(0, (invScroll / cell).toInt())
        val last = min(rows - 1, ((invScroll + (pb - gridTop)) / cell).toInt())
        val r = RectF()
        val isz = max(8, (cell * 0.8f).toInt())
        for (row in first..last) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                if (idx >= cubes.size) break
                val b = cubes[idx]
                val x = pl + dp(8f) + col * cell
                val y = gridTop + row * cell - invScroll
                r.set(x + 2f, y + 2f, x + cell - 2f, y + cell - 2f)
                paint.style = Paint.Style.FILL
                paint.color = 0x66303840
                c.drawRoundRect(r, dp(4f), dp(4f), paint)
                val ic = invIcons.getOrPut(b) { BlockIcons.make(b, isz) }
                c.drawBitmap(ic, x + (cell - ic.width) / 2f, y + (cell - ic.height) / 2f, null)
                if (b == s.block) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2f)
                    paint.color = Color.WHITE
                    c.drawRoundRect(r, dp(4f), dp(4f), paint)
                }
            }
        }
        c.restore()
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
