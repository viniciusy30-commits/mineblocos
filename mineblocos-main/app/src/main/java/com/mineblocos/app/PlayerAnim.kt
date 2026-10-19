package com.mineblocos.app

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animador do jogador (rig do Bedrock) — 100% procedural, no pique do "Actions And Stuff":
 * poses exageradas, squash & stretch, overshoot e follow-through.
 *
 * Unidades iguais às do Bedrock: rotação em graus, posição em "pixels" do modelo (1 px = 1/16 de bloco
 * na escala do modelo), frente do modelo = +Z, braço direito em -X.
 * Convenção de sinais: rotação X+ = membro vai para trás / cabeça olha para baixo / tronco inclina para
 * frente; Y+ = gira para a direita do personagem; Z+ = braço/perna direita abre para fora
 * (inclinar o corpo para a direita = Z negativo no waist). Para AS DUAS pernas/braços, Z+ move a ponta
 * para a DIREITA do personagem.
 * Nas matrizes de mão direita do OpenGL isso vira os ângulos (rx, -ry, -rz) — ver GameRenderer.drawPlayer.
 *
 * Ideias principais:
 *  - a locomoção é OMNIDIRECIONAL: o ângulo de deslocamento em relação ao corpo (0 = frente, 90° = direita,
 *    180° = trás) é suavizado e define para onde cada pé vai. Frente/trás balançam a perna em X, de lado
 *    abre/fecha em Z (passo lateral), diagonais misturam os dois;
 *  - o ciclo de andar/correr é guiado pela DISTÂNCIA percorrida e o comprimento do passo é calculado a partir
 *    do tamanho real da varredura do pé (o pé de apoio quase não escorrega);
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
        private const val HALF_PI = 1.5707964f
        private const val TAU = 6.2831855f
        private const val DEG = 57.29578f
        private const val RAD = 0.017453292f
        /** tamanho de 1 px do modelo em blocos (32 px = 1,8 bloco) */
        private const val PX = 1.8f / 32f
        /** comprimento da perna (px) */
        private const val LEG = 12f
    }

    val bones = Array(8) { Bone() }

    /** direção do corpo (rad, mesma convenção de GameState.yaw) */
    var bodyYaw = 0f
        private set

    /** 0..1: pálpebra fechada (o renderizador desenha uma "pálpebra" sobre os olhos) */
    var blink = 0f
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
    private var dirAng = 0f          // ângulo de deslocamento em relação ao corpo (rad): 0 frente, +90° direita
    private var dF = 1f              // cos(dirAng)
    private var dR = 0f              // sin(dirAng)
    private var smVF = 0f
    private var smVR = 0f
    private var prevYaw = 0f
    private var yawRate = 0f
    private var turnW = 0f
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
    private var flF = 0f
    private var flSup = 0f
    private var flR = 0f
    private var flU = 0f
    private var swF = 0f
    private var swR = 0f
    private var swU = 0f
    private var swimSp = 0f
    private var swimPh = 0f
    private var swimLean = 0f
    private var swimRoll = 0f
    private var swingP = -1f
    private var swingEnv = 0f
    private var gLean = 0f
    private var gRoll = 0f
    private var aLean = 0f
    private var aRoll = 0f
    private var blinkIn = 2.2f
    private var blinkP = -1f
    private var stride = 2.2f
    private var gBeta = 0.58f
    private var gNorm = 1.05f
    private var liftR = 0f
    private var liftL = 0f
    private var axA = 0f             // amplitude do passo em X (graus)
    private var azA = 0f             // amplitude do passo lateral em Z (graus)

    private val sq = AnimSpring(420f, 17f)
    private val crouch = AnimSpring(260f, 15f)
    private val leanSp = AnimSpring(150f, 15f)
    private val rollSp = AnimSpring(140f, 13f)
    private val headY = AnimSpring(230f, 19f)
    private val headX = AnimSpring(230f, 19f)
    private val flyLean = AnimSpring(110f, 13f)
    private val flyBank = AnimSpring(140f, 13f)
    private val swimBank = AnimSpring(110f, 12f)

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

    private fun wrapDeg(a: Float): Float {
        var x = a
        while (x > 180f) x -= 360f
        while (x < -180f) x += 360f
        return x
    }

    private fun hyp(a: Float, b: Float): Float = sqrt(a * a + b * b)

    /**
     * Posição do pé (-1..1, + = para a frente) ao longo do ciclo, t em 0..1 e duty factor [beta] (fração do ciclo com o pé
     * no chão). APOIO: reta (velocidade constante = o pé "agarra" o chão). BALANÇO: spline de Hermite cujas tangentes nas
     * pontas são IGUAIS à do apoio, então o pé toca o chão já recuando na velocidade do chão (nada de derrapada no
     * contato) e o joelho "recolhe" a perna antes de pisar, como numa marcha de verdade.
     */
    private fun footX(t0: Float, beta: Float): Float {
        val t = t0 - floor(t0)
        if (t < beta) return 1f - 2f * t / beta
        val ls = 1f - beta
        val u = (t - beta) / ls
        val m = -2f / beta * ls
        val u2 = u * u
        val u3 = u2 * u
        return -(2f * u3 - 3f * u2 + 1f) + (u3 - 2f * u2 + u) * m + (-2f * u3 + 3f * u2) + (u3 - u2) * m
    }

    /** 0..1: arco de elevação do pé durante o balanço (0 no apoio) */
    private fun swingArc(t0: Float, beta: Float): Float {
        val t = t0 - floor(t0)
        if (t < beta) return 0f
        val u = (t - beta) / (1f - beta)
        return sin(u * PI_F).pow(0.9f)
    }

    /** maior |footX| (o balanço passa um pouco do alvo): usado para normalizar a amplitude */
    private fun footNorm(beta: Float): Float {
        var mx = 1f
        for (i in 0 until 24) mx = max(mx, abs(footX(beta + (1f - beta) * (i + 0.5f) / 24f, beta)))
        return mx
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

        // orientação do corpo (cabeça livre até 50°, o corpo acompanha ao andar)
        val prevBody = bodyYaw
        if (gs > 0.3f || noAir) {
            bodyYaw += wrapPi(yaw - bodyYaw) * min(1f, d * 14f)
        } else {
            val dd = wrapPi(yaw - bodyYaw)
            val lim = 0.87266463f
            if (abs(dd) > lim) bodyYaw += dd - (if (dd > 0f) lim else -lim)
        }
        val bodyDelta = wrapPi(bodyYaw - prevBody)

        // velocidade no referencial DO CORPO (é isso que decide para onde os pés vão)
        val sb = sin(bodyYaw)
        val cb = cos(bodyYaw)
        val bvF = vx * sb - vz * cb
        val bvR = vx * cb + vz * sb
        smVF = approach(smVF, bvF, 9f, d)
        smVR = approach(smVR, bvR, 9f, d)
        if (gs > 0.45f) {
            // o ângulo é suavizado (e não o vetor): inverter o sentido passa pelo lado, sem "zerar" o passo
            dirAng += wrapPi(atan2(bvR, bvF) - dirAng) * min(1f, d * 13f)
            dirAng = wrapPi(dirAng)
        } else {
            // parado: volta devagar para "frente" (o próximo passo começa de frente)
            dirAng += wrapPi(0f - dirAng) * min(1f, d * 2f)
        }
        dF = cos(dirAng)
        dR = sin(dirAng)

        // passos no lugar ao girar o corpo parado
        val turnRate = abs(bodyDelta) / d
        val turnT = if (grounded && gs < 0.6f) smooth(turnRate, 0.35f, 2.6f) else 0f
        turnW = approach(turnW, turnT, 12f, d)

        // ---- pesos de estado
        val tSit = if (sitting) 1f else 0f
        val tSwim = if (swimming && !sitting) 1f else 0f
        val tFly = if (flying && !swimming && !sitting) 1f else 0f
        val tAir = if (!grounded && !noAir && !sitting) 1f else 0f
        val tG = if (tSit + tSwim + tFly + tAir < 0.5f) 1f else 0f
        wG = approach(wG, tG, 9f, d)
        wA = approach(wA, tAir, 12f, d)
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
                // quem pousa correndo se joga um pouco para a frente (e de lado) ao absorver o impacto
                leanSp.v += (smVF * 4f).coerceIn(-14f, 22f)
                rollSp.v += (-smVR * 3f).coerceIn(-12f, 12f)
            }
        }
        if (!grounded && wasGround && !noAir && vy > 1f) {
            footSign = -footSign
            sq.v += 2.6f
            // impulso: agacha de leve e "explode" para cima (a mola passa do zero = pernas esticam)
            crouch.x = 0.26f
            crouch.v = -2.2f
        }
        if (flying && !wasFlying) sq.v += 2.2f
        if (!grounded && !noAir) airTime += d else airTime = 0f

        // ---- parâmetros de locomoção
        val sneakG = sneaking && !noAir
        sneakW = approach(sneakW, if (sneakG) 1f else 0f, 12f, d)
        val runT = if (sprinting && grounded && gs > 2f) 1f else 0f
        runW = approach(runW, runT, 8f, d)
        var spT = if (grounded) (gs / WALK_SPEED).coerceIn(0f, 1.45f) else 0f
        if (turnT > 0f) spT = max(spT, 0.55f * turnT)     // girando parado: passinhos
        sp = approach(sp, spT, 12f, d)

        // amplitude do passo e comprimento do passo (derivado do tamanho real da varredura do pé)
        val spn0 = sp.coerceIn(0f, 1.3f)
        val ampK = 0.45f + 0.55f * min(spn0, 1.1f)
        val backK = if (dF < 0f) 1f + 0.18f * dF else 1f
        axA = lerp(lerp(46f, 60f, runW), 28f, sneakW) * ampK * backK
        azA = lerp(20f, 13f, sneakW) * ampK
        gBeta = lerp(lerp(0.58f, 0.40f, runW), 0.62f, sneakW)
        gNorm = footNorm(gBeta)
        val mag = hyp(LEG * sin(axA * RAD) * dF, LEG * sin(azA * RAD) * dR)
        val kPlant = lerp(lerp(0.76f, 0.80f, runW), 0.85f, sneakW)
        // comprimento do passo = varredura REAL do pé no apoio / fração do ciclo no chão / quanto o pé acompanha o chão
        stride = max(2f * mag / gNorm * PX / (gBeta * kPlant), 0.7f)

        if (grounded) {
            if (gs < 0.3f && turnW < 0.1f) {
                // parou: o ciclo termina com os pés lado a lado (nunca congela no meio do passo)
                val tgt = floor((phase - 0.25f) * 2f + 0.5f) / 2f + 0.25f   // pés lado a lado
                phase += (tgt - phase) * (1f - exp(-7f * d))
            } else {
                var adv = gs / stride * d
                if (gs < 0.9f) adv += turnRate * 0.28f * d * (1f - smooth(gs, 0.4f, 0.9f))
                phase += adv
            }
            phase -= floor(phase)
        }

        // ---- zera
        liftR = 0f; liftL = 0f
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
        if (nF > 0.001f) fly(nF, vy, bvF, bvR, d)
        if (nS > 0.001f) swim(nS, vy, bvF, bvR, d)
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

        // ---- amortecimento do pouso / impulso (por cima do chão e do ar)
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

        // pé sempre no chão: se o quadril desceu (agachar, pouso), a perna "dobra o joelho" (encurta)
        if (nG > 0.01f) {
            for (leg in R_LEG..L_LEG) {
                val bl = bones[leg]
                val cc = (cos(bl.rot[0] * RAD) * cos(bl.rot[2] * RAD)).coerceAtLeast(0.35f)
                val up = LEG + bones[ROOT].pos[1] + bl.pos[1] - (if (leg == R_LEG) liftR else liftL)
                if (up < LEG * cc) {
                    val sc = (up / (LEG * cc)).coerceIn(0.62f, 1f)
                    bl.scl[1] = 1f - (1f - sc) * nG
                }
            }
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
        if (swingP >= 0f) swing(swingP, pitch, nS, nF)

        // ---- olhar: a cabeça segue a câmera com elasticidade
        val ty = wrapPi(yaw - bodyYaw) * DEG
        val tx = -pitch * DEG
        val hy = headY.step(ty, d)
        val hx = headX.step(tx, d)
        r(HEAD, 1, hy.coerceIn(-80f, 80f))
        r(HEAD, 0, hx.coerceIn(-85f, 80f))

        // ---- piscar
        blinkIn -= d
        if (blinkP < 0f && blinkIn <= 0f) blinkP = 0f
        if (blinkP >= 0f) {
            blinkP += d / 0.17f
            if (blinkP >= 1f) {
                blinkP = -1f
                // intervalo "aleatório" (determinístico) entre 1,8 e 5,5 s; às vezes pisca duas vezes seguidas
                val rnd = (life * 7.31f + sin(life * 3.7f) * 5f) % 1f
                blinkIn = if (rnd < 0.18f) 0.12f else 1.8f + abs(rnd) * 3.7f
            }
        }
        blink = if (blinkP >= 0f) sin(blinkP.coerceIn(0f, 1f) * PI_F).coerceIn(0f, 1f) else 0f

        wasGround = grounded
        wasFlying = flying
        lastVy = vy
    }

    // ------------------------------------------------------------------------------------ chão
    private fun ground(w: Float, gs: Float, strV: Float) {
        val phi = phase * TAU
        val beta = gBeta
        val xR = footX(phase, beta) / gNorm               // pé direito ao longo da direção de deslocamento (+ = na direção)
        val xL = footX(phase + 0.5f, beta) / gNorm
        val hs = -cos(phi - PI_F * beta)                    // -1 quando o pé direito está no meio do apoio (peso sobre ele)
        val mv = smooth(sp, 0.04f, 0.5f)             // peso de andar
        val idle = 1f - mv
        val spn = sp.coerceIn(0f, 1.3f)
        val k = w * mv
        val absR = abs(dR)

        // ---- pernas: o pé vai/volta ao longo da direção de deslocamento.
        // X = frente/trás, Z = lateral (as duas pernas SEMPRE se afastam um pouco, para não se cruzarem).
        val legRx = -axA * xR * dF                   // X negativo = perna para a frente
        val legLx = -axA * xL * dF
        val latR = azA * (xR * dR + absR)            // 0 .. 2*az (abre)
        val latL = azA * (-xL * dR + absR)
        // levantada do pé de balanço: só no balanço, arco suave (andando de costas o calcanhar sobe mais)
        val kick = 1f + 0.55f * max(0f, -dF)
        val lift = lerp(2.0f, 4.8f, runW) * (0.4f + 0.6f * min(spn, 1.2f)) * kick
        liftR = lift * swingArc(phase, beta) * k
        liftL = lift * swingArc(phase + 0.5f, beta) * k
        r(R_LEG, 0, legRx * k)
        r(L_LEG, 0, legLx * k)
        r(R_LEG, 2, latR * k)
        r(L_LEG, 2, -latL * k)
        p(R_LEG, 1, liftR)
        p(L_LEG, 1, liftL)
        // a perna que levanta também recua um pouco (joelho dobrado)
        p(R_LEG, 2, -liftR * 0.55f)
        p(L_LEG, 2, -liftL * 0.55f)
        // o corpo desce quando a perna se abre (e sobe quando junta): a altura do quadril acompanha o pé mais baixo
        val thR = hyp(legRx, latR) * RAD
        val thL = hyp(legLx, latL) * RAD
        val drop = LEG * (1f - cos(max(thR, thL))) * 0.85f
        p(ROOT, 1, -drop * k)

        // ---- braços contrabalançam, com atraso (follow-through)
        val sa = cos(phi - 0.35f)                   // braço direito vai para trás quando a perna direita vai para frente
        val armA = lerp(26f, 40f, runW) * (0.3f + 0.7f * spn) * lerp(1f, 0.45f, sneakW)
        val pump = -34f * runW
        val armF = armA * sa * dF
        r(R_ARM, 0, (pump + armF) * k)
        r(L_ARM, 0, (pump - armF) * k)
        val flare = 4f - 6f * runW
        val fl2 = 2f * cos(2f * phi)
        val armZ = 13f * min(spn, 1f) * (1f - 0.5f * sneakW)       // balanço lateral dos braços (passo de lado)
        val arR = flare + fl2 + armZ * (absR - sa * dR)
        r(R_ARM, 2, arR * k)
        r(L_ARM, 2, -arR * k)

        // ---- torção do tronco e balanço lateral
        val tw = (5f + 5f * runW) * spn
        r(WAIST, 1, -tw * xR * dF * k)
        r(HEAD, 1, tw * xR * dF * 0.8f * k)
        // o tronco "olha" um pouco para o lado em que está indo (a cabeça mantém o olhar)
        val fold = asin(sin(dirAng))                 // 0 frente/trás, ±90° de lado (contínuo)
        val twDir = (fold * DEG * 0.2f).coerceIn(-16f, 16f) * min(spn, 1f)
        r(WAIST, 1, twDir * k)
        r(HEAD, 1, -twDir * 0.9f * k)
        r(WAIST, 2, (3f + 2f * runW) * spn * hs * k)
        r(HEAD, 2, -(3f + 2f * runW) * spn * hs * 0.6f * k)
        // o quadril balança para o lado do pé de apoio
        p(ROOT, 0, 0.5f * hs * min(spn, 1.2f) * (1f + 0.5f * runW) * k)
        // quique extra ao correr
        p(ROOT, 1, 0.9f * runW * abs(hs) * spn * k)

        // inclinação (aceleração + velocidade): para frente corre inclinado, de costas inclina o corpo para trás
        gLean = (accF * 0.09f).coerceIn(-9f, 11f) +
            (3f * spn + 7f * runW * spn) * (if (dF > 0f) dF else dF * 0.55f)
        gRoll = -(accR * 0.10f + strV * 0.9f).coerceIn(-9f, 9f) - (yawRate * gs * 0.45f).coerceIn(-10f, 10f)

        // ---- respiração / ociosidade (com troca de apoio)
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
        // troca de apoio: a perna descarregada relaxa (joelho para a frente) e o quadril desloca
        val ws = sin(life * 0.55f)
        val relR = max(0f, ws)
        val relL = max(0f, -ws)
        r(R_LEG, 0, -5f * relR * ki)
        r(L_LEG, 0, -5f * relL * ki)
        p(R_LEG, 1, 0.35f * relR * ki)
        p(L_LEG, 1, 0.35f * relL * ki)
        p(ROOT, 0, ws * 0.55f * ki)
        r(WAIST, 2, ws * 1.4f * ki)
        r(HEAD, 2, -ws * 1.0f * ki)

        // ---- agachar
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
        // direção do salto no referencial do corpo: sF > 0 = para a frente, < 0 = para trás; sR > 0 = para a direita
        val sF = (smVF / 5f).coerceIn(-1f, 1f)
        val sR = (smVR / 5f).coerceIn(-1f, 1f)
        val mF = abs(sF)
        val mR = abs(sR)
        val sgnR = if (sR >= 0f) 1f else -1f
        val panic = smooth(airTime, 0.5f, 2.2f)
        // SUBIDA: braços jogados para cima/fora, perna da frente dobrada, a de trás esticada
        val rr = rise * w
        r(R_ARM, 0, (-72f + 50f * sF * fs) * rr)
        r(L_ARM, 0, (-72f - 50f * sF * fs) * rr)
        r(R_ARM, 2, 30f * rr)
        r(L_ARM, 2, -30f * rr)
        r(R_LEG, 0, (10f - 48f * sF * fs) * rr)
        r(L_LEG, 0, (10f + 48f * sF * fs) * rr)
        p(R_LEG, 1, (1.5f + 2.5f * mF * max(0f, fs * sF)) * rr)
        p(L_LEG, 1, (1.5f + 2.5f * mF * max(0f, -fs * sF)) * rr)
        // pulo de lado: as pernas abrem para o lado do salto (a da frente mais)
        r(R_LEG, 2, (3f + 14f * mR * (1f + 0.6f * sgnR * fs)) * rr)
        r(L_LEG, 2, -(3f + 14f * mR * (1f - 0.6f * sgnR * fs)) * rr)
        r(WAIST, 0, (-2f + 7f * sF) * rr)
        r(HEAD, 0, -(10f + 6f * mF) * rr)
        // TOPO: flutua com os braços abertos e joelhos recolhidos
        val aa = apex * w
        r(R_ARM, 0, -22f * aa)
        r(L_ARM, 0, -22f * aa)
        r(R_ARM, 2, 44f * aa)
        r(L_ARM, 2, -44f * aa)
        r(R_LEG, 0, (-20f - 14f * sF * fs) * aa)
        r(L_LEG, 0, (-20f + 14f * sF * fs) * aa)
        r(R_LEG, 2, (6f + 8f * mR * (1f + 0.6f * sgnR * fs)) * aa)
        r(L_LEG, 2, -(6f + 8f * mR * (1f - 0.6f * sgnR * fs)) * aa)
        p(R_LEG, 1, 3f * aa)
        p(L_LEG, 1, 3f * aa)
        p(ROOT, 1, 0.8f * aa)
        r(WAIST, 0, 5f * aa)
        // QUEDA: braços para cima batendo (mais desesperados quanto mais longa a queda), pernas pedalando
        val ff = fall * w
        val fl = sin(t * 15f) * 0.7f + sin(t * 9.3f + 1.1f) * 0.5f
        val fl2 = sin(t * 12f + 0.8f)
        val flA = 10f + 12f * panic
        r(R_ARM, 0, (-42f - 14f * panic + flA * fl) * ff)
        r(L_ARM, 0, (-42f - 14f * panic - flA * fl2) * ff)
        r(R_ARM, 2, (50f + 10f * panic + 8f * fl) * ff)
        r(L_ARM, 2, -(50f + 10f * panic - 8f * fl2) * ff)
        val pd = sin(t * 11f)
        r(R_LEG, 0, (-16f + 14f * pd - 10f * sF * fs) * ff)
        r(L_LEG, 0, (-16f - 14f * pd + 10f * sF * fs) * ff)
        r(R_LEG, 2, (9f + 6f * mR * (1f + 0.6f * sgnR * fs)) * ff)
        r(L_LEG, 2, -(9f + 6f * mR * (1f - 0.6f * sgnR * fs)) * ff)
        p(R_LEG, 1, (1.5f + 1.5f * max(0f, pd)) * ff)
        p(L_LEG, 1, (1.5f + 1.5f * max(0f, -pd)) * ff)
        r(WAIST, 0, (3f + 3f * sF) * ff)
        r(HEAD, 0, 6f * ff)
        r(WAIST, 2, 2f * fl * panic * ff)
        // inclinação com a velocidade horizontal (para trás inclina para trás) e curvas
        aLean = 5.5f * sF * mv + (accF * 0.05f).coerceIn(-5f, 5f)
        aRoll = -(accR * 0.08f + smVR * 0.6f).coerceIn(-8f, 8f) - (yawRate * gs * 0.3f).coerceIn(-8f, 8f)
    }

    // ------------------------------------------------------------------------------------ voo
    private fun fly(w: Float, vy: Float, bvF: Float, bvR: Float, d: Float) {
        flF = approach(flF, bvF, 5f, d)
        flR = approach(flR, bvR, 5f, d)
        flU = approach(flU, vy, 5f, d)
        val spd = hyp(flF, flR)
        val t = life
        val n = smooth(spd, 0.3f, 12f)
        val nFw = smooth(max(0f, flF), 0.3f, 12f)       // indo para a frente
        val nBk = smooth(max(0f, -flF), 0.3f, 8f)       // indo para trás
        val nSd = smooth(abs(flR), 0.3f, 8f)            // indo de lado
        val sideDir = (flR / 9f).coerceIn(-1f, 1f)      // + = direita
        val asc = (flU / 6.5f).coerceIn(-1f, 1f)
        // tronco: Superman para a frente, ereto/inclinado para trás ao recuar, deitado de lado ao ir de lado
        var leanT = 80f * nFw.pow(0.85f) - 22f * nBk + 18f * nSd * (1f - nFw)
        leanT += -asc * 14f * (1f - 0.6f * n) + max(0f, -asc) * 22f * n * (0.3f + 0.7f * nFw)
        leanT = leanT.coerceIn(-26f, 100f)
        val l = flyLean.step(leanT, d)
        val hover = 1f - smooth(spd, 0.4f, 3f)
        flSup = approach(flSup, smooth(max(0f, flF), 5f, 13f), 7f, d)
        val sup = flSup
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
        // pernas arrastam para o lado contrário ao deslocamento
        r(R_LEG, 2, -sideDir * 10f * nSd * w)
        r(L_LEG, 2, -sideDir * 10f * nSd * w)
        // braços: abertos em hover, rente ao corpo em velocidade média, Superman em velocidade alta
        val armSuper = -(88f + l) * sup
        val armTrail = 6f * (1f - sup)
        val armHover = -10f * hover + 4f * hb * hover
        val flut = sin(t * 9f) * 2.5f * n
        // subindo: braços para o alto (lançamento); recuando: braços à frente "freando"
        val armUp = -45f * max(0f, asc) * (1f - 0.6f * sup)
        val armBrake = -26f * nBk
        r(R_ARM, 0, (armSuper + armTrail + armHover + armUp + armBrake + flut) * w)
        r(L_ARM, 0, (armSuper + armTrail + armHover + armUp + armBrake - flut) * w)
        val zOut = lerp(7f, 3f, sup) + hover * (28f + 5f * sin(t * 2.1f + 0.5f)) +
            4f * n * (1f - sup) * sin(t * 7f) + 18f * nBk + 14f * max(0f, asc) * (1f - sup)
        r(R_ARM, 2, zOut * w)
        r(L_ARM, 2, -zOut * w)
        // indo de lado: o braço do lado do movimento se estica, o outro encolhe
        r(R_ARM, 2, (22f * max(0f, sideDir) - 6f * max(0f, -sideDir)) * nSd * w)
        r(L_ARM, 2, -(22f * max(0f, -sideDir) - 6f * max(0f, sideDir)) * nSd * w)
        // flutuar parado
        p(ROOT, 1, hb * 0.9f * hover * w)
        // curva / lateral: o corpo todo inclina (roll). Direita = Z negativo
        val bankT = (-(yawRate * 9f).coerceIn(-38f, 38f) * smooth(spd, 0.5f, 5f) -
            sideDir * 40f * nSd).coerceIn(-55f, 55f)
        val bank = flyBank.step(bankT, d)
        r(WAIST, 2, bank * w)
        r(R_LEG, 2, bank * w)
        r(L_LEG, 2, bank * w)
        r(HEAD, 2, -bank * 0.6f * w)
    }

    // ------------------------------------------------------------------------------------ nado
    /** braçada do crawl: ângulo do braço em X (graus). u = 0..1. Puxa por baixo (devagar), recupera por cima (rápido). */
    private fun crawlArm(u0: Float): Float {
        val u = u0 - floor(u0)
        val pull = 0.55f
        return if (u < pull) {
            val t = u / pull
            -180f + 180f * lerp(t, t * t * (3f - 2f * t), 0.35f)
        } else {
            val t = (u - pull) / (1f - pull)
            180f * lerp(t, t * t * (3f - 2f * t), 0.25f)
        }
    }

    private fun swim(w: Float, vy: Float, bvF: Float, bvR: Float, d: Float) {
        swF = approach(swF, bvF, 5f, d)
        swR = approach(swR, bvR, 5f, d)
        swU = approach(swU, vy, 5f, d)
        val hs = hyp(swF, swR)
        val spd = hyp(hs, swU)
        swimSp = approach(swimSp, spd, 5f, d)
        val mvv = smooth(swimSp, 0.3f, 2f)
        swimPh += d * lerp(0.55f, 1.35f, (swimSp / 4f).coerceIn(0f, 1f))
        val u = swimPh - floor(swimPh)
        val ph = u * TAU
        val elev = atan2(swU, max(hs, 0.2f)) * DEG
        // pesos do estilo: crawl (frente/vertical), costas (recuo), nado de lado
        val aF = max(0f, swF) + abs(swU) * 0.6f
        val aB = max(0f, -swF)
        val aS = abs(swR)
        var sum = aF + aB + aS
        if (sum < 0.001f) sum = 1f
        var cF = aF / sum
        val cB = aB / sum
        val cS = aS / sum
        if (aF + aB + aS < 0.001f) cF = 1f
        val lf = (82f - elev * 0.9f).coerceIn(-4f, 125f)
        val lt = lerp(18f, cF * lf + cB * -22f + cS * 62f, mvv)
        swimLean = approach(swimLean, lt, 5f, d)
        val l = swimLean
        val t = life
        // lateral: deita de lado (direita = Z negativo)
        val sideDir = (swR / 3f).coerceIn(-1f, 1f)
        val rollT = -sideDir * 48f * cS * mvv
        swimRoll = swimBank.step(rollT, d)
        r(WAIST, 0, l * w)
        r(HEAD, 0, -l * 0.7f * w)
        r(WAIST, 2, swimRoll * w)
        r(R_LEG, 2, swimRoll * w)
        r(L_LEG, 2, swimRoll * w)
        r(HEAD, 2, -swimRoll * 0.55f * w)

        // pesos finais (somam 1): crawl, remada parada/de costas, nado de lado
        // o crawl é um círculo completo (-180..180): misturado pela metade "pula" a cada volta, então o peso é afiado
        val wc = smooth(cF * mvv, 0.5f, 0.8f) * (cF * mvv)
        val restW = 1f - wc
        val restSum = max(cB * mvv + (1f - mvv) + cS * mvv, 0.0001f)
        val wsc = (cB * mvv + (1f - mvv)) / restSum * restW
        val wsd = cS * mvv / restSum * restW

        // -- braços
        // crawl: braçada alternada
        val crR = crawlArm(u)
        val crL = crawlArm(u + 0.5f)
        val crZR = 5f + 16f * sin(PI_F * ((u - 0.55f) / 0.45f).coerceIn(0f, 1f))
        val crZL = 5f + 16f * sin(PI_F * (((u + 0.5f) % 1f - 0.55f) / 0.45f).coerceIn(0f, 1f))
        // remada parada (sculling): braços abertos fazendo "oitos"
        val scull = sin(ph * 2f)
        val scR = -42f + 12f * scull + 4f * sin(t * 3f)
        val scL = -42f - 12f * scull - 4f * sin(t * 3f)
        val scZ = 40f + 9f * cos(ph * 2f)
        // de lado: braçada de peito (braços abrem e fecham juntos)
        val sdSweep = sin(ph)
        val sdX = -78f + 22f * sdSweep
        val sdZ = 30f + 34f * (0.5f + 0.5f * sdSweep)
        val rest = max(wsc + wsd, 0.0001f)
        val restR = (scR * wsc + sdX * wsd) / rest
        val restL = (scL * wsc + sdX * wsd) / rest
        // mistura pelo caminho mais curto (o crawl dá a volta inteira no ombro)
        r(R_ARM, 0, (restR + wrapDeg(crR - restR) * wc) * w)
        r(L_ARM, 0, (restL + wrapDeg(crL - restL) * wc) * w)
        r(R_ARM, 2, (crZR * wc + scZ * wsc + sdZ * wsd) * w)
        r(L_ARM, 2, -(crZL * wc + scZ * wsc + sdZ * wsd) * w)

        // -- pernas
        val kk = sin(ph * 2f)
        val tr = l * 0.9f
        // crawl: batida de perna rápida. parado/costas: pedalada alternada. lado: tesoura
        val cwL = 16f * kk
        val scLg = -24f + 12f * sin(t * 3f)
        val kR = tr + cwL * wc + scLg * wsc + (-6f + 10f * sin(ph)) * wsd
        val kL = tr - cwL * wc + (-24f - 12f * sin(t * 3f)) * wsc + (-6f - 10f * sin(ph)) * wsd
        r(R_LEG, 0, kR * w)
        r(L_LEG, 0, kL * w)
        val scissor = 14f * max(0f, sin(ph)) * wsd
        val scissorL = 14f * max(0f, -sin(ph)) * wsd
        r(R_LEG, 2, (3f + scissor) * w)
        r(L_LEG, 2, -(3f + scissorL) * w)

        // rolagem do corpo com a braçada
        val rl = sin(ph) * 14f * wc
        r(WAIST, 2, rl * w)
        r(R_LEG, 2, rl * 0.6f * w)
        r(L_LEG, 2, rl * 0.6f * w)
        r(HEAD, 2, -rl * 0.5f * w)
        // balanço de quem boia
        p(ROOT, 1, sin(t * 2f) * 0.6f * (1f - mvv) * w)
    }

    // ------------------------------------------------------------------------------------ sentar
    private fun sit(w: Float) {
        val t = life
        val b = sin(t * 2f)
        p(ROOT, 1, -11f * w)
        r(R_LEG, 0, -84f * w)
        r(L_LEG, 0, -84f * w)
        r(R_LEG, 2, (5f + 1.2f * sin(t * 0.4f)) * w)
        r(L_LEG, 2, -(5f + 1.2f * sin(t * 0.4f + 1f)) * w)
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
    private fun swing(p: Float, pitch: Float, wSwim: Float, wFly: Float) {
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
        // o corpo acompanha: passo curto para a frente, pé de trás empurra, ombro esquerdo recua
        val lg = (1f - wFly) * (1f - wSwim)
        p(ROOT, 2, 0.7f * f1 * lg)
        r(R_LEG, 0, -5f * f1 * lg)
        r(L_LEG, 0, 4f * f1 * lg)
    }
}
