package com.mineblocos.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animador do jogador (rig do Bedrock) — refeito do zero, 100% procedural, no pique do
 * "Actions And Stuff": poses exageradas, squash & stretch, overshoot e follow-through.
 *
 * Unidades iguais às do Bedrock: rotação em graus, posição em "pixels" do modelo (1 px = 1/16 de bloco
 * na escala do modelo), frente do modelo = +Z, braço direito em -X.
 * Convenção de sinais: rotação X+ = membro vai para trás / cabeça olha para baixo / tronco inclina para
 * frente; Y+ = gira para a direita do personagem; Z+ = braço/perna direita abre para fora
 * (inclinar o corpo para a direita = Z negativo no waist).
 * Nas matrizes de mão direita do OpenGL isso vira os ângulos (rx, -ry, -rz) — ver GameRenderer.drawPlayer.
 *
 * Ideias principais:
 *  - o ciclo de andar/correr é guiado pela DISTÂNCIA percorrida (pé não escorrega, passo acompanha a velocidade);
 *  - estados (chão, ar, voo, nado, sentado) se misturam por pesos suaves que sempre somam 1;
 *  - molas amortecidas dão elasticidade: squash/stretch, pouso, inclinação em curvas, cabeça.
 */

/** Mola amortecida (integração semi-implícita com sub-passos). */
internal class AnimSpring(private val k: Float, private val c: Float) {
    var x = 0f
    var v = 0f

    fun step(tgt: Float, dt: Float): Float {
        val n = (dt / 0.008f).toInt() + 1
        val h = dt / n
        for (i in 0 until n) {
            val a = k * (tgt - x) - c * v
            v += a * h
            x += v * h
        }
        return x
    }
}

class PlayerAnimator(@Suppress("UNUSED_PARAMETER") json: String?) {

    class Bone {
        /** graus (convenção Bedrock: x+ = para trás/para baixo, olhando de frente) */
        val rot = FloatArray(3)
        /** pixels do modelo */
        val pos = FloatArray(3)
        val scl = floatArrayOf(1f, 1f, 1f)
    }

    companion object {
        const val ROOT = 0
        const val WAIST = 1
        const val BODY = 2
        const val HEAD = 3
        const val R_ARM = 4
        const val L_ARM = 5
        const val R_LEG = 6
        const val L_LEG = 7
        val NAMES = arrayOf("root", "waist", "body", "head", "rightArm", "leftArm", "rightLeg", "leftLeg")

        /** velocidade de caminhada do jogo (blocos/s) */
        const val WALK_SPEED = 4.6f
        private const val PI_F = 3.1415927f
        private const val TAU = 6.2831855f
        private const val DEG = 57.29578f
    }

    val bones = Array(8) { Bone() }

    /** direção do corpo (rad, mesma convenção de GameState.yaw) */
    var bodyYaw = 0f
        private set

    val available: Boolean get() = true

    var loadError: String? = null
        private set

    // ---- estado
    private var inited = false
    private var life = 0f
    private var phase = 0f
    private var sp = 0f
    private var runW = 0f
    private var sneakW = 0f
    private var dirS = 1f
    private var fwdSmooth = 1f
    private var prevYaw = 0f
    private var yawRate = 0f
    private var pvx = 0f
    private var pvz = 0f
    private var accF = 0f
    private var accR = 0f
    private var wG = 1f
    private var wA = 0f
    private var wF = 0f
    private var wS = 0f
    private var wT = 0f
    private var airTime = 0f
    private var lastVy = 0f
    private var wasGround = true
    private var wasFlying = false
    private var footSign = 1f
    private var flySp = 0f
    private var swimSp = 0f
    private var swimPh = 0f
    private var swimLean = 0f
    private var swingP = -1f
    private var swingEnv = 0f
    private var gLean = 0f
    private var gRoll = 0f
    private var aLean = 0f
    private var aRoll = 0f

    private val sq = AnimSpring(420f, 17f)
    private val crouch = AnimSpring(260f, 15f)
    private val leanSp = AnimSpring(150f, 15f)
    private val rollSp = AnimSpring(140f, 13f)
    private val headY = AnimSpring(230f, 19f)
    private val headX = AnimSpring(230f, 19f)
    private val flyLean = AnimSpring(110f, 13f)
    private val flyBank = AnimSpring(140f, 13f)

    // ---- utilidades
    private fun smooth(x: Float, a: Float, b: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun approach(cur: Float, tgt: Float, rate: Float, dt: Float): Float =
        cur + (tgt - cur) * (1f - exp(-rate * dt))

    private fun wrapPi(a: Float): Float {
        var x = a
        while (x > PI_F) x -= TAU
        while (x < -PI_F) x += TAU
        return x
    }

    private fun r(b: Int, c: Int, v: Float) { bones[b].rot[c] += v }
    private fun p(b: Int, c: Int, v: Float) { bones[b].pos[c] += v }

    /**
     * @param swingStart  um golpe/colocação de bloco acabou de acontecer
     * @param swingHold   está quebrando bloco (golpes repetidos)
     */
    fun update(
        dt: Float, onGround: Boolean, flying: Boolean, sneaking: Boolean, sprinting: Boolean,
        vx: Float, vy: Float, vz: Float, yaw: Float, pitch: Float,
        swingStart: Boolean, swingHold: Boolean,
        sitting: Boolean = false, swimming: Boolean = false
    ) {
        var d = if (dt > 0.1f) 0.1f else dt
        if (d < 0.0001f) d = 0.0001f
        if (!inited) {
            bodyYaw = yaw; prevYaw = yaw; pvx = vx; pvz = vz
            inited = true
        }
        life += d
        val gs = sqrt(vx * vx + vz * vz)
        val noAir = flying || swimming
        val grounded = onGround && !noAir
        val sy = sin(yaw)
        val cy = cos(yaw)
        val fwdV = vx * sy - vz * cy
        val strV = vx * cy + vz * sy

        // giro e aceleração (suavizados)
        val yr = wrapPi(yaw - prevYaw) / d
        prevYaw = yaw
        yawRate = approach(yawRate, yr.coerceIn(-8f, 8f), 10f, d)
        val ax = (vx - pvx) / d
        val az = (vz - pvz) / d
        pvx = vx; pvz = vz
        accF = approach(accF, (ax * sy - az * cy).coerceIn(-90f, 90f), 9f, d)
        accR = approach(accR, (ax * cy + az * sy).coerceIn(-90f, 90f), 9f, d)

        if (gs > 0.4f) {
            val f = fwdV / gs
            fwdSmooth += (f - fwdSmooth) * min(1f, d * 10f)
        }
        val backward = fwdSmooth < -0.3f
        dirS = approach(dirS, if (backward) -1f else 1f, 14f, d)

        // orientação do corpo (cabeça livre até 50°, o corpo acompanha ao andar)
        if (gs > 0.3f || noAir) {
            bodyYaw += wrapPi(yaw - bodyYaw) * min(1f, d * 14f)
        } else {
            val dd = wrapPi(yaw - bodyYaw)
            val lim = 0.87266463f
            if (abs(dd) > lim) bodyYaw += dd - (if (dd > 0f) lim else -lim)
        }

        // ---- pesos de estado
        val tSit = if (sitting) 1f else 0f
        val tSwim = if (swimming && !sitting) 1f else 0f
        val tFly = if (flying && !swimming && !sitting) 1f else 0f
        val tAir = if (!grounded && !noAir && !sitting) 1f else 0f
        val tG = if (tSit + tSwim + tFly + tAir < 0.5f) 1f else 0f
        wG = approach(wG, tG, 9f, d)
        wA = approach(wA, tAir, 14f, d)
        wF = approach(wF, tFly, 6f, d)
        wS = approach(wS, tSwim, 6f, d)
        wT = approach(wT, tSit, 8f, d)
        var tot = wG + wA + wF + wS + wT
        if (tot < 0.001f) tot = 1f
        val nG = wG / tot
        val nA = wA / tot
        val nF = wF / tot
        val nS = wS / tot
        val nT = wT / tot

        // ---- eventos de pouso / decolagem
        if (grounded && !wasGround) {
            val impact = max(0f, -lastVy)
            if (airTime > 0.12f && impact > 3f) {
                crouch.x = (impact / 17f).coerceIn(0.2f, 1f)
                crouch.v = 0f
                sq.v -= (impact * 0.2f).coerceIn(0.8f, 5f)
            }
        }
        if (!grounded && wasGround && !noAir && vy > 1f) {
            footSign = -footSign
            sq.v += 2.6f
        }
        if (flying && !wasFlying) sq.v += 2.2f
        if (!grounded && !noAir) airTime += d else airTime = 0f

        // ---- parâmetros de locomoção
        val sneakG = sneaking && !noAir
        sneakW = approach(sneakW, if (sneakG) 1f else 0f, 12f, d)
        val runT = if (sprinting && grounded && gs > 2f) 1f else 0f
        runW = approach(runW, runT, 8f, d)
        val spT = if (grounded) (gs / WALK_SPEED).coerceIn(0f, 1.45f) else 0f
        sp = approach(sp, spT, 12f, d)
        val stride = lerp(lerp(2.4f, 3.0f, runW), 1.9f, sneakW)
        if (grounded) phase = (phase + gs / stride * d) % 1f

        // ---- zera
        for (b in bones) {
            b.rot.fill(0f); b.pos.fill(0f)
            b.scl[0] = 1f; b.scl[1] = 1f; b.scl[2] = 1f
        }

        // ---- squash & stretch (mola)
        var sqT = 0f
        if (nA > 0.01f && vy < 0f) {
            sqT = 0.09f * smooth(-vy, 2f, 16f) * nA
        } else if (nA > 0.01f) {
            sqT = 0.05f * smooth(vy, 1f, 8f) * nA
        }
        val sqv = sq.step(sqT, d)
        val cr = max(crouch.step(0f, d), -0.35f)

        // ---- camadas
        if (nG > 0.001f) ground(nG, gs, strV)
        if (nA > 0.001f) air(nA, vy, gs)
        if (nF > 0.001f) fly(nF, gs, vy, strV, d)
        if (nS > 0.001f) swim(nS, gs, vy, d)
        if (nT > 0.001f) sit(nT)

        // ---- inclinação por aceleração / curva (chão e ar)
        val lw = nG + nA
        val lean = leanSp.step(nG * gLean + nA * aLean, d)
        val roll = rollSp.step(nG * gRoll + nA * aRoll, d)
        r(WAIST, 0, lean * lw)
        r(HEAD, 0, -lean * 0.7f * lw)
        r(WAIST, 2, roll * lw)
        r(HEAD, 2, -roll * 0.5f * lw)
        r(R_LEG, 2, roll * 0.35f * lw)
        r(L_LEG, 2, roll * 0.35f * lw)

        // ---- amortecimento do pouso (por cima do chão e do ar)
        val kc = (1f - nF) * (1f - nS) * (1f - nT)
        if (abs(cr) > 0.001f) {
            val c = cr * kc
            p(ROOT, 1, -5.2f * c)
            r(WAIST, 0, 22f * c)
            r(HEAD, 0, -14f * c)
            r(R_LEG, 0, -32f * c)
            r(L_LEG, 0, -32f * c)
            p(R_LEG, 2, -6f * c)
            p(L_LEG, 2, -6f * c)
            r(R_ARM, 0, -14f * c)
            r(L_ARM, 0, -14f * c)
            r(R_ARM, 2, 16f * c)
            r(L_ARM, 2, -16f * c)
        }

        // squash & stretch no root
        val sxz = 1f - sqv * 0.55f
        bones[ROOT].scl[0] = sxz
        bones[ROOT].scl[1] = 1f + sqv
        bones[ROOT].scl[2] = sxz

        // ---- golpe de braço
        if (swingStart) swingP = 0f
        if (swingHold && swingP < 0f) swingP = 0f
        if (swingP >= 0f) {
            swingP += d / 0.30f
            if (swingP >= 1f) swingP = if (swingHold) 0f else -1f
        }
        val envT = if (swingP >= 0f) sin(swingP.coerceIn(0f, 1f) * PI_F) else 0f
        swingEnv = approach(swingEnv, envT, 30f, d)
        if (swingP >= 0f) swing(swingP, pitch, nS)

        // ---- olhar: a cabeça segue a câmera com elasticidade
        val ty = wrapPi(yaw - bodyYaw) * DEG
        val tx = -pitch * DEG
        val hy = headY.step(ty, d)
        val hx = headX.step(tx, d)
        r(HEAD, 1, hy.coerceIn(-80f, 80f))
        r(HEAD, 0, hx.coerceIn(-85f, 80f))

        wasGround = grounded
        wasFlying = flying
        lastVy = vy
    }

    // ------------------------------------------------------------------------------------ chão
    private fun ground(w: Float, gs: Float, strV: Float) {
        val phi = phase * TAU
        val s = sin(phi)
        val c = cos(phi)
        val mv = smooth(sp, 0.04f, 0.5f)          // peso de andar
        val idle = 1f - mv
        val spn = sp.coerceIn(0f, 1.3f)
        val amp = lerp(lerp(32f, 42f, runW), 22f, sneakW) * (0.35f + 0.65f * min(spn, 1.1f))
        val fR = amp * s * dirS                     // perna direita: + = para frente
        val fL = -fR
        // para trás levanta mais (chute do calcanhar)
        val backK = 1.3f + 0.05f * runW
        val legR = if (fR > 0f) -fR else -fR * backK
        val legL = if (fL > 0f) -fL else -fL * backK
        val lift = lerp(1.8f, 4.2f, runW) * (0.4f + 0.6f * spn)
        val liftR = lift * max(0f, c * dirS)
        val liftL = lift * max(0f, -c * dirS)
        val th = max(abs(legR), abs(legL)) / DEG
        val drop = 12f * (1f - cos(th)) * 0.85f
        val k = w * mv
        r(R_LEG, 0, legR * k)
        r(L_LEG, 0, legL * k)
        p(R_LEG, 1, liftR * k)
        p(L_LEG, 1, liftL * k)
        // a perna que levanta também recua um pouco (joelho dobrado)
        p(R_LEG, 2, -liftR * 0.55f * k)
        p(L_LEG, 2, -liftL * 0.55f * k)
        p(ROOT, 1, -drop * k)
        // braços contrabalançam, com atraso (follow-through)
        val sa = sin(phi - 0.35f)
        val armA = lerp(26f, 40f, runW) * (0.3f + 0.7f * spn) * lerp(1f, 0.45f, sneakW)
        val pump = -34f * runW
        r(R_ARM, 0, (pump + armA * sa * dirS) * k)
        r(L_ARM, 0, (pump - armA * sa * dirS) * k)
        val flare = 4f - 6f * runW
        val fl2 = 2f * cos(2f * phi)
        r(R_ARM, 2, (flare + fl2) * k)
        r(L_ARM, 2, -(flare + fl2) * k)
        // torção do tronco e balanço lateral
        val tw = (5f + 5f * runW) * spn
        r(WAIST, 1, -tw * s * dirS * k)
        r(HEAD, 1, tw * s * dirS * 0.8f * k)
        r(WAIST, 2, (3f + 2f * runW) * spn * c * k)
        r(HEAD, 2, -(3f + 2f * runW) * spn * c * 0.6f * k)
        // quique extra ao correr
        p(ROOT, 1, 0.9f * runW * abs(c) * spn * k)

        // inclinação (aceleração + velocidade) e inclinação em curvas
        gLean = (accF * 0.09f).coerceIn(-9f, 11f) +
            (3f * spn + 7f * runW * spn) * (if (dirS > 0f) 1f else -0.5f)
        gRoll = -(accR * 0.10f + strV * 0.9f).coerceIn(-9f, 9f) - (yawRate * gs * 0.45f).coerceIn(-10f, 10f)

        // respiração / ociosidade
        val b = sin(life * 2.1f)
        val sway = sin(life * 0.7f)
        val ki = w * idle
        p(ROOT, 1, b * 0.16f * ki)
        r(WAIST, 2, sway * 1.2f * ki)
        r(WAIST, 1, sin(life * 0.45f) * 1.5f * ki)
        r(R_ARM, 2, (2.5f + b * 1.4f) * ki)
        r(L_ARM, 2, -(2.5f + b * 1.4f) * ki)
        val ab = -1.5f + sin(life * 2.1f + 0.6f) * 1.2f
        r(R_ARM, 0, ab * ki)
        r(L_ARM, 0, ab * ki)
        r(HEAD, 0, b * 0.7f * ki)
        r(R_LEG, 2, 1.2f * ki)
        r(L_LEG, 2, -1.2f * ki)

        // agachar
        val sw = w * sneakW
        p(ROOT, 1, -2.3f * sw)
        r(WAIST, 0, 24f * sw)
        r(HEAD, 0, -17f * sw)
        r(R_LEG, 0, -13f * sw)
        r(L_LEG, 0, -13f * sw)
        p(R_LEG, 2, -2.6f * sw)
        p(L_LEG, 2, -2.6f * sw)
        r(R_ARM, 0, -12f * sw)
        r(L_ARM, 0, -12f * sw)
    }

    // ------------------------------------------------------------------------------------ ar
    private fun air(w: Float, vy: Float, gs: Float) {
        var rise = smooth(vy, 0.5f, 6.5f)
        var fall = smooth(-vy, 0.3f, 13f)
        var apex = max(0f, 1f - rise - fall)
        var tot = rise + fall + apex
        if (tot < 0.001f) tot = 1f
        rise /= tot; fall /= tot; apex /= tot
        val mv = smooth(gs, 1.2f, 5f)
        val fs = footSign
        val t = life
        // SUBIDA: braços jogados para cima/fora, perna da frente dobrada, a de trás esticada
        val rr = rise * w
        r(R_ARM, 0, (-72f + 50f * mv * fs) * rr)
        r(L_ARM, 0, (-72f - 50f * mv * fs) * rr)
        r(R_ARM, 2, 30f * rr)
        r(L_ARM, 2, -30f * rr)
        r(R_LEG, 0, (10f - 48f * mv * fs) * rr)
        r(L_LEG, 0, (10f + 48f * mv * fs) * rr)
        p(R_LEG, 1, (1.5f + 2.5f * mv * max(0f, fs)) * rr)
        p(L_LEG, 1, (1.5f + 2.5f * mv * max(0f, -fs)) * rr)
        r(R_LEG, 2, 3f * rr)
        r(L_LEG, 2, -3f * rr)
        r(WAIST, 0, (-2f + 7f * mv) * rr)
        r(HEAD, 0, -(10f + 6f * mv) * rr)
        // TOPO: flutua com os braços abertos e joelhos recolhidos
        val aa = apex * w
        r(R_ARM, 0, -22f * aa)
        r(L_ARM, 0, -22f * aa)
        r(R_ARM, 2, 44f * aa)
        r(L_ARM, 2, -44f * aa)
        r(R_LEG, 0, (-20f - 14f * mv * fs) * aa)
        r(L_LEG, 0, (-20f + 14f * mv * fs) * aa)
        r(R_LEG, 2, 6f * aa)
        r(L_LEG, 2, -6f * aa)
        p(R_LEG, 1, 3f * aa)
        p(L_LEG, 1, 3f * aa)
        p(ROOT, 1, 0.8f * aa)
        r(WAIST, 0, 5f * aa)
        // QUEDA: braços para cima batendo, pernas pedalando
        val ff = fall * w
        val fl = sin(t * 15f)
        r(R_ARM, 0, (-42f + 10f * fl) * ff)
        r(L_ARM, 0, (-42f - 10f * fl) * ff)
        r(R_ARM, 2, (50f + 8f * fl) * ff)
        r(L_ARM, 2, -(50f - 8f * fl) * ff)
        val pd = sin(t * 11f)
        r(R_LEG, 0, (-16f + 14f * pd) * ff)
        r(L_LEG, 0, (-16f - 14f * pd) * ff)
        r(R_LEG, 2, 9f * ff)
        r(L_LEG, 2, -9f * ff)
        p(R_LEG, 1, (1.5f + 1.5f * max(0f, pd)) * ff)
        p(L_LEG, 1, (1.5f + 1.5f * max(0f, -pd)) * ff)
        r(WAIST, 0, 3f * ff)
        r(HEAD, 0, 6f * ff)
        // inclinação com a velocidade horizontal e curvas
        aLean = 5f * mv + (accF * 0.05f).coerceIn(-5f, 5f)
        aRoll = -(accR * 0.08f).coerceIn(-8f, 8f) - (yawRate * gs * 0.3f).coerceIn(-8f, 8f)
    }

    // ------------------------------------------------------------------------------------ voo
    private fun fly(w: Float, gs: Float, vy: Float, strV: Float, d: Float) {
        flySp = approach(flySp, gs, 5f, d)
        val spd = flySp
        val t = life
        val n = smooth(spd, 0.3f, 12f)
        val asc = (vy / 6.5f).coerceIn(-1f, 1f)
        var leanT = 80f * n.pow(0.85f)
        leanT += -asc * 14f * (1f - 0.6f * n) + max(0f, -asc) * 22f * n
        leanT = leanT.coerceIn(-6f, 100f)
        val l = flyLean.step(leanT, d)
        val hover = 1f - smooth(spd, 0.4f, 3f)
        val sup = smooth(spd, 5f, 13f)
        val hb = sin(t * 2.1f)
        // tronco inclina; a cabeça mantém o olhar à frente
        r(WAIST, 0, l * w)
        r(HEAD, 0, -l * 0.82f * w)
        // pernas acompanham o corpo
        val kick = sin(t * 13f) * (3f + 5f * n)
        r(R_LEG, 0, (l * 0.95f + kick + hover * (8f + 5f * hb)) * w)
        r(L_LEG, 0, (l * 0.95f - kick + hover * (8f - 5f * hb)) * w)
        r(R_LEG, 2, (2.5f + hover * 3f) * w)
        r(L_LEG, 2, -(2.5f + hover * 3f) * w)
        // braços: abertos em hover, rente ao corpo em velocidade média, Superman em velocidade alta
        val armSuper = -(88f + l) * sup
        val armTrail = 6f * (1f - sup)
        val armHover = -10f * hover + 4f * hb * hover
        val flut = sin(t * 9f) * 2.5f * n
        r(R_ARM, 0, (armSuper + armTrail + armHover + flut) * w)
        r(L_ARM, 0, (armSuper + armTrail + armHover - flut) * w)
        val zOut = lerp(7f, 3f, sup) + hover * (28f + 5f * sin(t * 2.1f + 0.5f)) +
            4f * n * (1f - sup) * sin(t * 7f)
        r(R_ARM, 2, zOut * w)
        r(L_ARM, 2, -zOut * w)
        // flutuar parado
        p(ROOT, 1, hb * 0.9f * hover * w)
        // curva: o corpo todo inclina (roll)
        val bankT = -(yawRate * 9f + strV * 1.3f).coerceIn(-38f, 38f) * smooth(spd, 0.5f, 5f)
        val bank = flyBank.step(bankT, d)
        r(WAIST, 2, bank * w)
        r(R_LEG, 2, bank * w)
        r(L_LEG, 2, bank * w)
    }

    // ------------------------------------------------------------------------------------ nado
    private fun swim(w: Float, gs: Float, vy: Float, d: Float) {
        val spd = sqrt(gs * gs + vy * vy)
        swimSp = approach(swimSp, spd, 5f, d)
        val mvv = smooth(swimSp, 0.3f, 2f)
        swimPh += d * lerp(0.55f, 1.35f, (swimSp / 4f).coerceIn(0f, 1f))
        val ph = swimPh * TAU
        val elev = atan2(vy, max(gs, 0.2f)) * DEG
        val lt = lerp(18f, (82f - elev * 0.9f).coerceIn(-4f, 125f), mvv)
        swimLean = approach(swimLean, lt, 5f, d)
        val l = swimLean
        val t = life
        r(WAIST, 0, l * w)
        r(HEAD, 0, -l * 0.7f * w)
        // braçada: crawl alternado
        val a = ph + 0.3f * sin(ph)
        val armR = -((a * DEG) % 360f)
        val armL = -(((a + PI_F) * DEG) % 360f)
        val scull = sin(t * 3f)
        r(R_ARM, 0, (armR * mvv + (-42f + 10f * scull) * (1f - mvv)) * w)
        r(L_ARM, 0, (armL * mvv + (-42f - 10f * scull) * (1f - mvv)) * w)
        r(R_ARM, 2, (6f * mvv + 38f * (1f - mvv)) * w)
        r(L_ARM, 2, -(6f * mvv + 38f * (1f - mvv)) * w)
        // pernas: pernada rápida
        val kick = sin(ph * 2f)
        val tr = l * 0.9f
        r(R_LEG, 0, (tr + 16f * kick * mvv + (-24f + 12f * scull) * (1f - mvv)) * w)
        r(L_LEG, 0, (tr - 16f * kick * mvv + (-24f - 12f * scull) * (1f - mvv)) * w)
        r(R_LEG, 2, 3f * w)
        r(L_LEG, 2, -3f * w)
        // rolagem do corpo com a braçada
        val rl = sin(ph) * 14f * mvv
        r(WAIST, 2, rl * w)
        r(R_LEG, 2, rl * 0.6f * w)
        r(L_LEG, 2, rl * 0.6f * w)
        r(HEAD, 2, -rl * 0.5f * w)
        p(ROOT, 1, sin(t * 2f) * 0.6f * (1f - mvv) * w)
    }

    // ------------------------------------------------------------------------------------ sentar
    private fun sit(w: Float) {
        val t = life
        val b = sin(t * 2f)
        p(ROOT, 1, -11f * w)
        r(R_LEG, 0, -84f * w)
        r(L_LEG, 0, -84f * w)
        r(R_LEG, 2, 5f * w)
        r(L_LEG, 2, -5f * w)
        r(R_ARM, 0, (-34f + b) * w)
        r(L_ARM, 0, (-34f + b) * w)
        r(R_ARM, 2, 7f * w)
        r(L_ARM, 2, -7f * w)
        r(WAIST, 0, (2f + b * 0.6f) * w)
        r(WAIST, 2, sin(t * 0.6f) * w)
        r(HEAD, 0, b * 0.6f * w)
    }

    // ------------------------------------------------------------------------------------ golpe
    /** Golpe de braço (base: HumanoidModel.setupAttackAnimation) + antecipação, lunge e follow-through. */
    private fun swing(p: Float, pitch: Float, wSwim: Float) {
        if (wSwim > 0.5f) return
        val f1 = sin((1f - (1f - p).pow(4)) * PI_F)
        val f2 = sin(p * PI_F) * (pitch + 0.7f) * 0.75f
        val twist = sin(sqrt(p) * 2f * PI_F) * 0.2f * DEG
        val wind = if (p < 0.22f) sin((p / 0.22f).coerceIn(0f, 1f) * PI_F) else 0f
        val env = swingEnv
        r(BODY, 1, -twist)
        r(HEAD, 1, twist)
        r(WAIST, 0, 9f * f1)
        r(HEAD, 0, -4f * f1)
        // desfaz o balanço de caminhada do braço direito durante o golpe
        bones[R_ARM].rot[0] *= (1f - 0.85f * env)
        r(R_ARM, 0, -(f1 * 1.2f + f2) * DEG + 18f * wind)
        r(R_ARM, 1, -twist * 2f)
        r(R_ARM, 2, 6f * f1)
        r(L_ARM, 1, -twist)
        r(L_ARM, 0, 6f * f1)
    }
}
