package com.mineblocos.app

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animação de ANDAR do personagem, portada do addon "Actions & Stuff" (Oreville Studios).
 *
 * Origem: animation.oreville_ans.vayhkdd.move, que é o estado "andar" do controlador
 * de movimento do jogador no addon (corrida e andar agachado são animações separadas
 * e entram nos próximos passos).
 *
 * Esqueleto (igual ao do Minecraft): cintura -> corpo -> cabeça e braços; as pernas
 * ficam presas na raiz. Rotações em graus, posições em "pixels" do modelo (1 px = 1/32 da altura).
 *
 * As tabelas das pernas são os keyframes originais; braços, corpo, cabeça e cintura usam
 * as mesmas fórmulas do addon (senos/cossenos do tempo da animação).
 */
class PlayerAnim {

    companion object {
        const val WAIST = 0
        const val BODY = 1
        const val HEAD = 2
        const val R_ARM = 3
        const val L_ARM = 4
        const val R_LEG = 5
        const val L_LEG = 6
        const val COUNT = 7

        /** Velocidade (blocos/s) em que o andar tem 100% de intensidade. */
        private const val WALK_SPEED = 4.6f
        /** Ciclos completos por segundo (o addon usa q.delta_time * 1.5). */
        private const val CYCLES_PER_SEC = 1.5f
        private val RAD = (Math.PI / 180.0).toFloat()

        // ---- keyframes das pernas (animation.oreville_ans.vayhkdd.move) ----
        private val RLEG_ROT_T = floatArrayOf(0f, 0.25f, 0.2813f, 0.3125f, 0.3438f, 0.375f, 0.4063f, 0.4375f, 0.4688f, 0.625f, 0.6875f, 0.75f, 0.8125f, 0.8438f, 0.875f, 0.9063f, 0.9375f, 0.9688f, 1f)
        private val RLEG_ROT_X = floatArrayOf(-9.6f, 40.1f, 48.1f, 56.9f, 64.61f, 70.325f, 71.05f, 68.525f, 58.97f, -5.03f, -26.62f, -38.8f, -46.45f, -47.03f, -45.8f, -41.15f, -33.3f, -20.15f, -9.6f)
        private val RLEG_POS_T = floatArrayOf(0f, 0.0625f, 0.125f, 0.1563f, 0.1875f, 0.2188f, 0.25f, 0.2813f, 0.3125f, 0.3438f, 0.375f, 0.4063f, 0.4375f, 0.4688f, 0.5f, 0.5313f, 0.5625f, 0.5938f, 0.625f, 0.6875f, 0.75f, 0.8125f, 0.875f, 0.9063f, 0.9375f, 0.9688f, 1f)
        private val RLEG_POS_Y = floatArrayOf(-0.15f, 0f, -0.2f, -0.25f, -0.15f, 0.1f, 0.15f, 0.13f, -0.225f, -0.9f, -1.3f, -1.5f, -1.5f, -1.1f, -0.3f, 1.33f, 2.75f, 3.58f, 4f, 3.97f, 3.33f, 1.27f, -2f, -2f, -1.2f, -0.42f, -0.15f)
        private val RLEG_POS_Z = floatArrayOf(-0.5f, -0.03f, 0.4f, 0.65f, 0.9f, 1.1f, 1.15f, 1.1f, 0.65f, -0.1f, -1.3f, -2.22f, -2.95f, -3.22f, -3.5f, -3.61f, -3.72f, -3.49f, -3.25f, -2.44f, -1.955f, -1.845f, -2.5f, -2f, -1.35f, -0.85f, -0.5f)
        private val LLEG_ROT_T = floatArrayOf(0f, 0.125f, 0.1875f, 0.25f, 0.3125f, 0.3438f, 0.375f, 0.4063f, 0.4375f, 0.4688f, 0.5f, 0.75f, 0.7813f, 0.8125f, 0.8438f, 0.875f, 0.9063f, 0.9375f, 0.9688f, 1f)
        private val LLEG_ROT_X = floatArrayOf(46.17f, -5.03f, -26.62f, -38.8f, -46.45f, -47.03f, -45.8f, -41.15f, -33.3f, -20.15f, -9.6f, 40.1f, 48.1f, 56.9f, 64.61f, 70.325f, 71.05f, 68.525f, 58.97f, 46.17f)
        private val LLEG_POS_T = floatArrayOf(0f, 0.0313f, 0.0625f, 0.0938f, 0.125f, 0.1875f, 0.25f, 0.3125f, 0.375f, 0.4063f, 0.4375f, 0.4688f, 0.5f, 0.5625f, 0.625f, 0.6563f, 0.6875f, 0.7188f, 0.75f, 0.7813f, 0.8125f, 0.8438f, 0.875f, 0.9063f, 0.9375f, 0.9688f, 1f)
        private val LLEG_POS_Y = floatArrayOf(-0.3f, 1.33f, 2.75f, 3.58f, 4f, 3.97f, 3.33f, 1.27f, -2f, -2f, -1.2f, -0.42f, -0.15f, 0f, -0.2f, -0.25f, -0.15f, 0.1f, 0.15f, 0.13f, -0.225f, -0.9f, -1.3f, -1.5f, -1.5f, -1.1f, -0.3f)
        private val LLEG_POS_Z = floatArrayOf(-3.5f, -3.61f, -3.72f, -3.49f, -3.25f, -2.44f, -1.955f, -1.845f, -2.5f, -2f, -1.35f, -0.85f, -0.5f, -0.03f, 0.4f, 0.65f, 0.9f, 1.1f, 1.15f, 1.1f, 0.65f, -0.1f, -1.3f, -2.22f, -2.95f, -3.22f, -3.5f)

        private fun sample(t: FloatArray, v: FloatArray, x: Float): Float {
            val n = t.size
            if (x <= t[0]) return v[0]
            if (x >= t[n - 1]) return v[n - 1]
            var i = 1
            while (t[i] < x) i++
            val f = (x - t[i - 1]) / (t[i] - t[i - 1])
            return v[i - 1] + (v[i] - v[i - 1]) * f
        }

        // seno/cosseno em graus, como no Molang do Minecraft
        private fun sd(deg: Float) = sin(deg * RAD)
        private fun cd(deg: Float) = cos(deg * RAD)
    }

    /** Rotação final de cada osso (x, y, z) em graus, já na convenção do jogo. */
    val rot = FloatArray(COUNT * 3)
    /** Deslocamento final de cada osso (x, y, z) em pixels do modelo. */
    val pos = FloatArray(COUNT * 3)

    private var phase = 0f      // 0..1, posição dentro do ciclo
    private var walkW = 0f      // intensidade do andar (0..1)
    private var airW = 0f       // pose provisória de ar (0..1)
    private var ground = 1f     // "está no chão" suavizado (v.sstfqmu do addon)

    fun update(dt: Float, speed: Float, onGround: Boolean, flying: Boolean) {
        val moving = speed > 0.3f
        val targetWalk = if (onGround && !flying && moving) min(1f, speed / WALK_SPEED) else 0f
        val targetAir = if (!onGround && !flying) 1f else 0f
        val k = min(1f, dt * 10f)              // transição de ~0,2 s, como o controlador do addon
        walkW += (targetWalk - walkW) * k
        airW += (targetAir - airW) * k
        ground += ((if (onGround) 1f else 0f) - ground) * min(1f, dt * 12f)

        if (walkW > 0.02f) {
            // cadência fixa como no addon; só desacelera um pouco com o analógico pela metade
            phase = (phase + dt * CYCLES_PER_SEC * (0.35f + 0.65f * walkW)) % 1f
        }
        evaluate()
    }

    private fun evaluate() {
        java.util.Arrays.fill(rot, 0f)
        java.util.Arrays.fill(pos, 0f)
        val w = walkW
        if (w > 0.001f) {
            val a = phase
            val c1 = a * 360f
            val c2 = a * 720f
            // cintura: inclina de lado e o corpo "afunda" a cada passo
            val bob = -cd(c2) * 3.1f + min(0f, cd(c2) * 2.3f)
            bedrock(WAIST, 0f, 0f, -cd(c1) * 5f, 0f, bob * ground, 0f, w)
            // corpo: torção
            bedrock(BODY, 0f, -sd(c1) * 24f, 0f, 0f, 0f, 0f, w)
            // cabeça: balanço leve
            bedrock(HEAD, sd(c2 - 12f) * 6f, -sd(c1 - 45f) * 6f, -sd(c1) * 5f, 0f, 0f, 0f, w)
            // braços (opostos às pernas)
            val armBob = -cd(c2) * 0.5f
            bedrock(L_ARM, sd(c1 + 10f) * 55f, sd(c1) * 18f, 0f, 0f, armBob, 0f, w)
            bedrock(R_ARM, -sd(c1 + 10f) * 55f, sd(c1) * 18f, 0f, 0f, armBob, 0f, w)
            // pernas (keyframes originais)
            bedrock(R_LEG, sample(RLEG_ROT_T, RLEG_ROT_X, a), 0f, 0f,
                0f, sample(RLEG_POS_T, RLEG_POS_Y, a), sample(RLEG_POS_T, RLEG_POS_Z, a), w)
            bedrock(L_LEG, sample(LLEG_ROT_T, LLEG_ROT_X, a), 0f, 0f,
                0f, sample(LLEG_POS_T, LLEG_POS_Y, a), sample(LLEG_POS_T, LLEG_POS_Z, a), w)
        }
        if (airW > 0.001f) {
            // pose de ar provisória (a de pulo/queda do addon vem num próximo passo)
            val p = airW * 25f
            rot[L_LEG * 3] += p
            rot[R_LEG * 3] -= p
            rot[L_ARM * 3] -= p
            rot[R_ARM * 3] += p
        }
    }

    /**
     * Soma uma pose no formato do Bedrock (como está no JSON do addon) convertendo para o
     * jogo. O jogo olha para -Z com a direita do personagem em +X, e o Bedrock inverte o
     * sentido de X e Y das rotações: +X inclina a frente para baixo, +Y gira para a direita.
     */
    private fun bedrock(
        b: Int, rx: Float, ry: Float, rz: Float,
        px: Float, py: Float, pz: Float, w: Float
    ) {
        rot[b * 3] += -rx * w
        rot[b * 3 + 1] += -ry * w
        rot[b * 3 + 2] += rz * w
        pos[b * 3] += -px * w
        pos[b * 3 + 1] += py * w
        pos[b * 3 + 2] += pz * w
    }
}
