package com.mineblocos.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Animações do jogador no estilo Minecraft (rig do Bedrock) usando os dados do pack
 * "Actions And Stuff" (assets/player_anims.json, gerado por tools/extract_player_anims.py).
 *
 * Unidades iguais às do Bedrock: rotação em graus, posição em "pixels" do modelo (1 px = 1/16 de bloco
 * na escala do modelo), frente do modelo = +Z, braço direito em -X.
 * Convenção de sinais (conferida com os dados do pack): rotação X+ = membro vai para trás / cabeça olha
 * para baixo; Y+ = gira para a direita do personagem; Z+ = braço/perna direita abre para fora.
 * Nas matrizes de mão direita do OpenGL isso vira os ângulos (rx, -ry, -rz) — ver GameRenderer.drawPlayer.
 */

// ------------------------------------------------------------------------------------------------
// Clip (uma animação do pack)
// ------------------------------------------------------------------------------------------------

internal class Key(val time: Float, val pre: Array<MNode>, val post: Array<MNode>, val smooth: Boolean)

internal class Chan(val const: Array<MNode>?, val keys: Array<Key>?) {
    private val tmp = FloatArray(3)
    private val p0 = FloatArray(3)
    private val p1 = FloatArray(3)
    private val p2 = FloatArray(3)
    private val p3 = FloatArray(3)

    private fun fill(dst: FloatArray, src: Array<MNode>, e: MolangEnv) {
        for (i in 0 until 3) {
            e.self = dst[i]
            dst[i] = src[i].eval(e)
        }
    }

    fun sample(t: Float, e: MolangEnv, out: FloatArray) {
        if (const != null) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            fill(out, const, e)
            return
        }
        val k = keys!!
        val n = k.size
        if (t <= k[0].time) { fill(out, k[0].post, e); return }
        if (t >= k[n - 1].time) { fill(out, k[n - 1].post, e); return }
        var i = 0
        while (i < n - 2 && t >= k[i + 1].time) i++
        val a = k[i]
        val b = k[i + 1]
        val u = ((t - a.time) / (b.time - a.time)).coerceIn(0f, 1f)
        fill(p1, a.post, e)
        fill(p2, b.pre, e)
        if (a.smooth || b.smooth) {
            if (i > 0) fill(p0, k[i - 1].post, e) else { p0[0] = p1[0]; p0[1] = p1[1]; p0[2] = p1[2] }
            if (i + 2 < n) fill(p3, k[i + 2].pre, e) else { p3[0] = p2[0]; p3[1] = p2[1]; p3[2] = p2[2] }
            val u2 = u * u
            val u3 = u2 * u
            for (c in 0 until 3) {
                out[c] = 0.5f * ((2f * p1[c]) + (-p0[c] + p2[c]) * u +
                    (2f * p0[c] - 5f * p1[c] + 4f * p2[c] - p3[c]) * u2 +
                    (-p0[c] + 3f * p1[c] - 3f * p2[c] + p3[c]) * u3)
            }
        } else {
            for (c in 0 until 3) out[c] = p1[c] + (p2[c] - p1[c]) * u
        }
    }
}

internal class BoneTracks(val rot: Chan?, val pos: Chan?, val scl: Chan?)

class AnimClip internal constructor(
    val name: String,
    val length: Float,
    /** 0 = toca uma vez, 1 = loop, 2 = segura o último quadro */
    val loop: Int,
    private val timeUpdate: MNode?,
    private val bones: Map<String, BoneTracks>
) {
    private val r = FloatArray(3)
    private val p = FloatArray(3)
    private val s = FloatArray(3)

    /** avança o tempo local da animação */
    fun advance(cur: Float, dt: Float, e: MolangEnv): Float {
        e.q["anim_time"] = cur
        e.q["delta_time"] = dt
        var t = if (timeUpdate != null) timeUpdate.eval(e) else cur + dt
        if (length > 0f) {
            if (loop == 1) {
                if (t >= length || t < 0f) t -= length * floor(t / length)
            } else if (t > length) t = length
        }
        return t
    }

    fun finished(t: Float) = loop == 0 && length > 0f && t >= length - 1e-4f

    fun has(bone: String) = bones.containsKey(bone.lowercase())

    /** soma (peso * valor) nos acumuladores */
    fun apply(bone: String, t: Float, w: Float, e: MolangEnv, rot: FloatArray, pos: FloatArray, scl: FloatArray) {
        val bt = bones[bone.lowercase()] ?: return
        e.q["anim_time"] = t
        if (bt.rot != null) {
            bt.rot.sample(t, e, r)
            rot[0] += r[0] * w; rot[1] += r[1] * w; rot[2] += r[2] * w
        }
        if (bt.pos != null) {
            bt.pos.sample(t, e, p)
            pos[0] += p[0] * w; pos[1] += p[1] * w; pos[2] += p[2] * w
        }
        if (bt.scl != null) {
            bt.scl.sample(t, e, s)
            scl[0] += (s[0] - 1f) * w; scl[1] += (s[1] - 1f) * w; scl[2] += (s[2] - 1f) * w
        }
    }

    companion object {
        private fun node(o: Any?): MNode = when (o) {
            is Number -> Molang.parse(o.toString())
            is String -> Molang.parse(o)
            else -> Molang.parse("0")
        }

        private fun vec(o: Any?): Array<MNode> = when (o) {
            is JSONArray -> Array(3) { node(if (it < o.length()) o.get(it) else 0) }
            else -> { val n = node(o); arrayOf(n, n, n) }
        }

        private fun chan(o: Any?): Chan? {
            if (o == null) return null
            if (o !is JSONObject) return Chan(vec(o), null)
            val names = ArrayList<String>()
            val it = o.keys()
            while (it.hasNext()) names.add(it.next())
            names.sortBy { n -> n.toFloat() }
            val keys = ArrayList<Key>()
            for (n in names) {
                val v = o.get(n)
                if (v is JSONObject) {
                    val post = if (v.has("post")) vec(v.get("post")) else vec(v.get("pre"))
                    val pre = if (v.has("pre")) vec(v.get("pre")) else post
                    keys.add(Key(n.toFloat(), pre, post, v.optString("lerp_mode") == "catmullrom"))
                } else {
                    val x = vec(v)
                    keys.add(Key(n.toFloat(), x, x, false))
                }
            }
            if (keys.isEmpty()) return null
            if (keys.size == 1) return Chan(keys[0].post, null)
            return Chan(null, keys.toTypedArray())
        }

        fun parse(name: String, a: JSONObject): AnimClip {
            val loopV = a.opt("loop")
            val loop = when {
                loopV == true -> 1
                loopV is String && loopV == "hold_on_last_frame" -> 2
                else -> 0
            }
            val tu = if (a.has("anim_time_update")) Molang.parse(a.getString("anim_time_update")) else null
            val map = HashMap<String, BoneTracks>()
            val bs = a.optJSONObject("bones")
            if (bs != null) {
                val it = bs.keys()
                while (it.hasNext()) {
                    val bn = it.next()
                    val b = bs.getJSONObject(bn)
                    map[bn.lowercase()] = BoneTracks(chan(b.opt("rotation")), chan(b.opt("position")), chan(b.opt("scale")))
                }
            }
            return AnimClip(name, a.optDouble("animation_length", 0.0).toFloat(), loop, tu, map)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Animador do jogador
// ------------------------------------------------------------------------------------------------

class PlayerAnimator(json: String?) {

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

        /** velocidade de caminhada do Minecraft (blocos/s), usada para normalizar q.modified_move_speed */
        const val WALK_SPEED = 4.317f
        private const val DEG = (180.0 / PI).toFloat()
        private const val SWING_TIME = 0.30f
    }

    val bones = Array(8) { Bone() }

    /** direção do corpo (rad, mesma convenção de GameState.yaw) */
    var bodyYaw = 0f
        private set

    private class Track(val clip: AnimClip) {
        var t = 0f
        var w = 0f
        var target = 0f
        var fade = 5f          // 1 / tempo de transição (s)
        var oneShot = false
        var playing = false
    }

    private val clips = HashMap<String, AnimClip>()
    private val tr = HashMap<String, Track>()
    private val env = MolangEnv()

    // estado
    private var life = 0f
    private var dist = 0f
    private var sstfqmu = 1f
    private var pukebbg = 0f
    private var wasGround = true
    private var wasSneak = false
    private var wasFlying = false
    private var airTime = 0f
    private var lastVy = 0f
    private var footRight = true
    private var flyW = 0f
    private var sitW = 0f
    private var fldvozz = 0f
    private var fwdSmooth = 1f
    private var swingP = -1f          // <0: parado
    private var swingHeld = false
    private var inited = false

    val available: Boolean get() = clips.isNotEmpty()

    /** mensagem de erro se o JSON das animações não pôde ser lido (para depuração) */
    var loadError: String? = null
        private set

    init {
        if (json != null) {
            try {
                val root = JSONObject(json)
                val it = root.keys()
                while (it.hasNext()) {
                    val n = it.next()
                    val c = AnimClip.parse(n, root.getJSONObject(n))
                    clips[n] = c
                    tr[n] = Track(c)
                }
            } catch (e: Exception) {
                loadError = e.toString()
                clips.clear(); tr.clear()
            }
        }
        env.q["is_in_ui"] = 0f
        env.v["is_paperdoll"] = 0f
        env.v["xktopwo"] = 0f
        env.q["is_in_water"] = 0f
    }

    private fun play(name: String, fade: Float, weight: Float = 1f) {
        val k = tr[name] ?: return
        k.t = 0f; k.target = weight; k.fade = fade
        k.oneShot = true; k.playing = true
    }

    private fun set(name: String, target: Float, fade: Float) {
        val k = tr[name] ?: return
        k.target = target; k.fade = fade
    }

    private fun wrapPi(a: Float): Float {
        var x = a
        while (x > PI) x -= (2 * PI).toFloat()
        while (x < -PI) x += (2 * PI).toFloat()
        return x
    }

    private fun lerpPerFrame(cur: Float, tgt: Float, rate: Float, dt: Float): Float {
        val k = 1f - (1f - rate).pow(dt * 60f)
        return cur + (tgt - cur) * k
    }

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
        if (!inited) { bodyYaw = yaw; inited = true }
        val d = if (dt > 0.1f) 0.1f else dt
        val noAir = flying || swimming
        life += d
        val gs = sqrt(vx * vx + vz * vz)
        val mod = gs / WALK_SPEED
        dist += gs * 4f * d

        // ---- variáveis usadas pelas expressões do pack
        env.q["life_time"] = life
        env.q["is_on_ground"] = if (onGround) 1f else 0f
        env.q["is_sprinting"] = if (sprinting) 1f else 0f
        env.q["is_sneaking"] = if (sneaking) 1f else 0f
        env.q["ground_speed"] = gs
        env.q["modified_move_speed"] = mod
        env.q["modified_distance_moved"] = dist
        env.q["vertical_speed"] = vy
        sstfqmu = lerpPerFrame(sstfqmu, if (onGround && !noAir) 1f else 0f, 0.2f, d)
        pukebbg = lerpPerFrame(pukebbg, if (onGround && !noAir) 0f else 1f, if (onGround && !noAir) 0.1f else 0.01f, d)
        env.v["sstfqmu"] = sstfqmu
        env.v["pukebbg"] = pukebbg

        // frente/trás em relação ao olhar
        if (gs > 0.4f) {
            val f = (vx * sin(yaw) - vz * cos(yaw)) / gs
            fwdSmooth += (f - fwdSmooth) * min(1f, d * 10f)
        }
        val backward = fwdSmooth < -0.3f

        // ---- orientação do corpo (cabeça livre até 50°, o corpo acompanha ao andar)
        if (gs > 0.3f || noAir) {
            bodyYaw += wrapPi(yaw - bodyYaw) * min(1f, d * 14f)
        } else {
            val dd = wrapPi(yaw - bodyYaw)
            val lim = (50.0 * PI / 180.0).toFloat()
            if (abs(dd) > lim) bodyYaw += dd - (if (dd > 0f) lim else -lim)
        }

        // ---- máquina de estados (espelha os animation controllers do pack)
        val grounded = onGround && !noAir
        val airborne = !grounded

        // pousou?
        if (grounded && !wasGround) {
            val impact = (-lastVy).coerceAtLeast(0f)
            if (airTime > 0.14f && impact > 2.5f) {
                val moving = mod > 0.5f || sprinting
                val k = if (moving) "zijlcyt_move" else "zijlcyt"
                play(k, 8f, (0.35f + impact / 14f).coerceIn(0.35f, 1f))
            }
        }
        // decolou? (pulo)
        if (airborne && wasGround && !noAir && vy > 0.5f) {
            val nm = if (sprinting) (if (footRight) "qlobuam_right" else "qlobuam_left") else "qlobuam"
            footRight = !footRight
            play(nm, 4f)
        }
        if (airborne && !noAir) airTime += d else if (grounded) airTime = 0f
        if (noAir) airTime = 0f

        // sneak
        if (sneaking && !noAir) {
            if (!wasSneak) {
                play("jczgdau_tsailnj", 8f)
                tr["jczgdau_bghnmsg"]?.let { it.target = 0f; it.fade = 12f; it.playing = false }
            }
        } else if (wasSneak) {
            set("jczgdau_tsailnj", 0f, 12f)
            if (!noAir) play("jczgdau_bghnmsg", 10f)
        }

        // idle (respiração) sempre ativo
        set("dhyaxuw", 1f, 3f)

        // locomoção
        val locW = (mod - pukebbg).coerceIn(0f, 1.2f)
        val sneakLoc = sneaking && !noAir
        val sneakW = min(1f, 3f * (mod - pukebbg)).coerceAtLeast(0f)
        setLoop("move", if (!sneakLoc && !backward && !noAir) locW else 0f, 5f)
        setLoop("move_pqnqsct", if (!sneakLoc && backward && !noAir) locW else 0f, 5f)
        setLoop("vcnwlon", if (sneakLoc && !backward) sneakW else 0f, 8f)
        setLoop("vcnwlon_pqnqsct", if (sneakLoc && backward) sneakW else 0f, 8f)

        // ar
        val rising = airborne && !noAir && vy > -7f
        for (n in arrayOf("qlobuam", "qlobuam_right", "qlobuam_left")) {
            val k = tr[n] ?: continue
            if (!rising && k.playing && k.target > 0f) { k.target = 0f; k.fade = 4f }
        }
        val falling = airborne && !noAir && vy < -0.15f
        val fallI = ((-vy - 6f) / 14f).coerceIn(0f, 1f)
        setLoop("cpjhrkw", if (falling) 0.5f + fallI * 0.5f else 0f, 4f)
        setLoop("kadhbmz_cpjhrkw", if (falling) fallI else 0f, 4f)

        // voo
        flyW += ((if (flying) 1f else 0f) - flyW) * min(1f, d * 6f)

        // sentado (riding_legs do pack) -> zera locomoção
        set("riding_legs", if (sitting) 1f else 0f, 6f)
        tr["riding_legs"]?.let { it.oneShot = false }
        if (sitting) {
            for (n in arrayOf("move", "move_pqnqsct", "vcnwlon", "vcnwlon_pqnqsct", "cpjhrkw", "kadhbmz_cpjhrkw")) set(n, 0f, 10f)
        }
        sitW += ((if (sitting) 1f else 0f) - sitW) * min(1f, d * 8f)

        // nadando: superfície (swim_arms + swim_legs) ou corpo todo (ozrmicj) quando sobe/desce/olha para baixo
        val deep = swimming && (abs(vy) > 1.5f || pitch < -0.35f)
        val tgtF = if (gs > 0.1f) (-DEG * kotlin.math.asin((vy / gs).coerceIn(-1f, 1f))).coerceIn(-90f, 90f) else (if (vy > 0.1f) -90f else if (vy < -0.1f) 90f else 0f)
        fldvozz += (tgtF - fldvozz) * (d * 4f).coerceIn(0f, 1f)
        env.v["fldvozz"] = fldvozz
        setLoop("swim_arms", if (swimming && !deep) 1f else 0f, 5f)
        setLoop("swim_legs", if (swimming && !deep) 1f else 0f, 5f)
        setLoop("ozrmicj", if (deep) 1f else 0f, 5f)
        if (swimming) {
            for (n in arrayOf("move", "move_pqnqsct", "vcnwlon", "vcnwlon_pqnqsct", "cpjhrkw", "kadhbmz_cpjhrkw", "jczgdau_tsailnj")) set(n, 0f, 10f)
        }

        // golpe de braço
        if (swingStart) { swingP = 0f }
        if (swingHold && swingP < 0f) swingP = 0f
        swingHeld = swingHold
        if (swingP >= 0f) {
            swingP += d / SWING_TIME
            if (swingP >= 1f) swingP = if (swingHold) 0f else -1f
        }

        wasGround = grounded
        wasSneak = sneaking && !noAir
        wasFlying = flying
        lastVy = vy

        // ---- avança e mistura
        for (b in bones) {
            b.rot.fill(0f); b.pos.fill(0f)
            b.scl[0] = 1f; b.scl[1] = 1f; b.scl[2] = 1f
        }
        for (k in tr.values) {
            // peso -> alvo
            val step = k.fade * d
            k.w = if (k.w < k.target) min(k.target, k.w + step) else max(k.target, k.w - step)
            if (k.w <= 0.001f && k.target <= 0f) { k.w = 0f; if (k.oneShot) k.playing = false; continue }
            // tempo local (cada clip tem seu próprio anim_time_update no pack)
            k.t = k.clip.advance(k.t, d, env)
            if (k.oneShot && k.clip.finished(k.t) && k.target > 0f && k.clip.loop == 0) {
                k.target = 0f; k.fade = 8f
            }
            for (bi in bones.indices) {
                val bn = NAMES[bi]
                if (k.clip.has(bn)) {
                    val b = bones[bi]
                    k.clip.apply(bn, k.t, k.w, env, b.rot, b.pos, b.scl)
                }
            }
        }

        // ---- poses procedurais (voo, golpe, olhar)
        if (flyW > 0.01f) applyFly(gs, vy, flyW)
        if (sitW > 0.01f) applySit(sitW)
        if (swingP >= 0f) applySwing(swingP, pitch)

        // olhar: a cabeça segue a câmera (pitch + diferença de yaw em relação ao corpo)
        val head = bones[HEAD]
        head.rot[0] += -pitch * DEG
        head.rot[1] += wrapPi(yaw - bodyYaw) * DEG   // y+ = olhar para a direita (convenção Bedrock)
    }

    private fun setLoop(name: String, target: Float, fade: Float) {
        val k = tr[name] ?: return
        k.target = target
        k.fade = fade
        k.oneShot = false
    }

    /** Pose de voo (Superman leve): corpo inclina para frente com a velocidade, pernas ficam para trás. */
    private fun applyFly(gs: Float, vy: Float, w: Float) {
        val sp = (gs / 9f).coerceIn(0f, 1f)
        val flutter = sin(life * 9f)
        val flutter2 = sin(life * 9f + 1.6f)
        val lean = 26f * sp - (vy * 1.2f).coerceIn(-8f, 8f)
        bones[WAIST].rot[0] += lean * w
        bones[WAIST].pos[2] += 0.9f * sp * w
        bones[HEAD].rot[0] += -lean * 0.55f * w
        val legBack = (8f + 26f * sp) * w
        bones[R_LEG].rot[0] += legBack + flutter * 4f * w
        bones[L_LEG].rot[0] += legBack + flutter2 * 4f * w
        bones[R_LEG].rot[2] += 3f * w
        bones[L_LEG].rot[2] -= 3f * w
        bones[R_ARM].rot[0] += (14f + 16f * sp) * w + flutter * 3f * w
        bones[L_ARM].rot[0] += (14f + 16f * sp) * w + flutter2 * 3f * w
        bones[R_ARM].rot[2] += (10f + 8f * flutter) * w
        bones[L_ARM].rot[2] -= (10f + 8f * flutter2) * w
        bones[ROOT].pos[1] += sin(life * 2.2f) * 0.35f * w
    }

    /** Sentado: o corpo desce até o assento e as mãos descansam no colo. */
    private fun applySit(w: Float) {
        bones[ROOT].pos[1] += -11f * w
        bones[R_ARM].rot[0] += -22f * w
        bones[L_ARM].rot[0] += -22f * w
        bones[R_ARM].rot[2] += 4f * w
        bones[L_ARM].rot[2] -= 4f * w
    }

    /** Golpe de braço do Minecraft (HumanoidModel.setupAttackAnimation) */
    private fun applySwing(p: Float, pitch: Float) {
        val f1 = sin((1f - (1f - p).pow(4)) * PI.toFloat())
        val f2 = sin(p * PI.toFloat()) * (pitch + 0.7f) * 0.75f
        val bodyTwist = sin(sqrt(p) * 2f * PI.toFloat()) * 0.2f * DEG
        // y- = ombro direito avança (convenção Bedrock); a cabeça compensa para continuar olhando em frente
        bones[BODY].rot[1] -= bodyTwist
        bones[HEAD].rot[1] += bodyTwist
        val a = bones[R_ARM]
        a.rot[0] += -(f1 * 1.2f + f2) * DEG
        a.rot[1] -= bodyTwist * 2f
        bones[L_ARM].rot[1] -= bodyTwist
    }
}
