package com.mineblocos.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class GameRenderer(
    private val world: World,
    private val s: GameState,
    private val renderDist: Int,
    animJson: String? = null
) : GLSurfaceView.Renderer {

    private class Mesh(var vbo: Int, var count: Int, var oc: Int = count)
    private val BB_FULL = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
    private class Hit(val x: Int, val y: Int, val z: Int, val nx: Int, val ny: Int, val nz: Int, val hy: Float = 0f)
    private class Particle(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        var life: Float, val size: Float,
        val r: Float, val g: Float, val b: Float
    )

    private val meshes = arrayOfNulls<Mesh>(World.CX * World.CZ)

    private var progWorld = 0
    private var progColor = 0
    private var wPos = 0; private var wUV = 0; private var wLight = 0
    private var wMVP = 0; private var wCam = 0; private var wTex = 0; private var wSun = 0
    private var wFogColor = 0; private var wFogStart = 0; private var wFogEnd = 0; private var wAlpha = 0
    private val waterList = ArrayList<Mesh>()
    private var cPos = 0; private var cMVP = 0; private var cColor = 0

    private var texId = 0
    private var cubeVbo = 0
    private var lineVbo = 0
    private var handVbo = 0
    private var handCount = 0
    private var handBlock = -1

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var aspect = 1.8f

    private var last = 0L
    private var fpsTimer = 0f
    private var fpsFrames = 0
    private var timeAcc = 0f
    private var bobT = 0f
    private val animator = PlayerAnimator(animJson)
    private var sneaking = false
    private var swimming = false
    private var ph = 1.8f          // altura da caixa de colisão (1.5 agachado)
    private var eyeSmooth = 1.62f  // altura dos olhos suavizada
    private var lastPunch = 0f

    private val look = FloatArray(2)
    private var hit: Hit? = null
    private var breakX = 0; private var breakY = 0; private var breakZ = 0
    private var hasBreak = false
    private var breakProg = 0f
    private var punch = 0f
    private var handWave = 0f

    private val parts = ArrayList<Particle>()
    private val rng = Random()

    // ---------------------------------------------------------------- GL setup

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        progWorld = program(WORLD_VS, WORLD_FS)
        progColor = program(COLOR_VS, COLOR_FS)
        wPos = GLES20.glGetAttribLocation(progWorld, "aPos")
        wUV = GLES20.glGetAttribLocation(progWorld, "aUV")
        wLight = GLES20.glGetAttribLocation(progWorld, "aLight")
        wMVP = GLES20.glGetUniformLocation(progWorld, "uMVP")
        wCam = GLES20.glGetUniformLocation(progWorld, "uCam")
        wTex = GLES20.glGetUniformLocation(progWorld, "uTex")
        wSun = GLES20.glGetUniformLocation(progWorld, "uSun")
        wFogColor = GLES20.glGetUniformLocation(progWorld, "uFogColor")
        wFogStart = GLES20.glGetUniformLocation(progWorld, "uFogStart")
        wFogEnd = GLES20.glGetUniformLocation(progWorld, "uFogEnd")
        wAlpha = GLES20.glGetUniformLocation(progWorld, "uAlpha")
        cPos = GLES20.glGetAttribLocation(progColor, "aPos")
        cMVP = GLES20.glGetUniformLocation(progColor, "uMVP")
        cColor = GLES20.glGetUniformLocation(progColor, "uColor")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, Atlas.bitmap, 0)

        val cube = Mesher.cubeTriangles()
        cubeVbo = makeVbo(cube, cube.size)
        val lines = Mesher.cubeLines()
        lineVbo = makeVbo(lines, lines.size)
        handVbo = 0
        handBlock = -1

        for (i in meshes.indices) meshes[i] = null
        world.markAllDirty()
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height).toFloat()
        Matrix.perspectiveM(proj, 0, 72f, aspect, 0.1f, 500f)
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        return sh
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun upload(id: Int, data: FloatArray, n: Int) {
        val fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data, 0, n)
        fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 4, fb, GLES20.GL_STATIC_DRAW)
    }

    private fun genBuffer(): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        return ids[0]
    }

    private fun makeVbo(data: FloatArray, n: Int): Int {
        val id = genBuffer()
        upload(id, data, n)
        return id
    }

    // ---------------------------------------------------------------- frame

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val raw = (now - last) / 1e9f
        last = now
        val dt = if (raw > 0.05f) 0.05f else raw
        fpsTimer += raw
        fpsFrames++
        if (fpsTimer >= 0.5f) {
            s.fps = (fpsFrames / fpsTimer).toInt()
            fpsTimer = 0f
            fpsFrames = 0
        }

        if (s.ready) update(dt)
        rebuildChunks(if (s.ready) 8f else 40f)
        if (!s.ready) {
            val ci = (floor(s.pz).toInt() shr 4) * World.CX + (floor(s.px).toInt() shr 4)
            if (ci in meshes.indices && meshes[ci] != null) s.ready = true
        }
        draw()
    }

    private var freeTick = 0

    /** Libera as malhas de chunks distantes (memória da GPU); elas voltam ao chegar perto. */
    private fun freeFarMeshes() {
        if (++freeTick % 90 != 0) return
        val lim = (renderDist + 4) * 16f
        for (i in meshes.indices) {
            val m = meshes[i] ?: continue
            val dx = (i % World.CX) * 16 + 8 - s.px
            val dz = (i / World.CX) * 16 + 8 - s.pz
            if (dx * dx + dz * dz > lim * lim) {
                GLES20.glDeleteBuffers(1, intArrayOf(m.vbo), 0)
                meshes[i] = null
                world.dirty[i] = true
            }
        }
    }

    private fun rebuildChunks(budgetMs: Float) {
        freeFarMeshes()
        val t0 = System.nanoTime()
        while (true) {
            var best = -1
            var bd = Float.MAX_VALUE
            for (i in world.dirty.indices) {
                if (!world.dirty[i]) continue
                val dx = (i % World.CX) * 16 + 8 - s.px
                val dz = (i / World.CX) * 16 + 8 - s.pz
                val d = dx * dx + dz * dz
                val lim = (renderDist + 2) * 16f
                if (d > lim * lim) continue
                if (d < bd) { bd = d; best = i }
            }
            if (best < 0) break
            world.dirty[best] = false
            buildMesh(best)
            if ((System.nanoTime() - t0) / 1e6f > budgetMs) break
        }
    }

    private fun buildMesh(i: Int) {
        val md = Mesher.buildChunk(world, i % World.CX, i / World.CX)
        var m = meshes[i]
        if (m == null) {
            m = Mesh(genBuffer(), 0)
            meshes[i] = m
        }
        if (md.size > 0) upload(m.vbo, md.data, md.size)
        m.count = md.size / 6
        m.oc = md.split / 6
    }

    // ---------------------------------------------------------------- lógica

    private fun collides(x: Float, y: Float, z: Float, hgt: Float = ph): Boolean {
        val minX = floor(x - 0.3f).toInt(); val maxX = floor(x + 0.3f).toInt()
        val minY = floor(y).toInt(); val maxY = floor(y + hgt - 0.001f).toInt()
        val minZ = floor(z - 0.3f).toInt(); val maxZ = floor(z + 0.3f).toInt()
        // começa uma célula abaixo: cercas têm 1,5 bloco; lajes e terra batida têm altura parcial
        for (iy in (minY - 1)..maxY) for (iz in minZ..maxZ) for (ix in minX..maxX) {
            if (!world.solid(ix, iy, iz)) continue
            val bk = world.get(ix, iy, iz)
            val mt = world.getMeta(ix, iy, iz)
            if (Shapes.boxCollide(bk)) {
                for (q in Shapes.collBoxes(world, ix, iy, iz, bk)) {
                    if (x + 0.3f > ix + q.x0 && x - 0.3f < ix + q.x1 &&
                        y + hgt > iy + q.y0 && y < iy + q.y1 &&
                        z + 0.3f > iz + q.z0 && z - 0.3f < iz + q.z1
                    ) return true
                }
                continue
            }
            if (y < iy + Shapes.hi(bk, mt) && y + hgt > iy + Shapes.lo(bk, mt)) return true
        }
        return false
    }

    private fun intersectsPlayer(x: Int, y: Int, z: Int): Boolean {
        return s.px + 0.3f > x && s.px - 0.3f < x + 1 &&
            s.py + ph > y && s.py < y + 1 &&
            s.pz + 0.3f > z && s.pz - 0.3f < z + 1
    }

    /** Raio contra as caixas de um bloco parcial; null se passar pelos vazios. */
    private fun hitBoxes(
        hb: FloatArray, x: Int, y: Int, z: Int,
        ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float,
        pnx: Int, pny: Int, pnz: Int
    ): Hit? {
        var best = Float.MAX_VALUE
        var bnx = pnx; var bny = pny; var bnz = pnz
        var i = 0
        while (i < hb.size) {
            var t0 = 0f
            var t1 = maxDist
            var fnx = 0; var fny = 0; var fnz = 0
            var ok = true
            for (a in 0 until 3) {
                val o = if (a == 0) ox else if (a == 1) oy else oz
                val d = if (a == 0) dx else if (a == 1) dy else dz
                val lo = (if (a == 0) x else if (a == 1) y else z) + hb[i + a]
                val hi = (if (a == 0) x else if (a == 1) y else z) + hb[i + 3 + a]
                if (abs(d) < 1e-6f) {
                    if (o < lo || o > hi) { ok = false; break }
                } else {
                    var ta = (lo - o) / d
                    var tb = (hi - o) / d
                    var sgn = -1
                    if (ta > tb) { val tt = ta; ta = tb; tb = tt; sgn = 1 }
                    if (ta > t0) {
                        t0 = ta
                        fnx = 0; fny = 0; fnz = 0
                        if (a == 0) fnx = sgn else if (a == 1) fny = sgn else fnz = sgn
                    }
                    if (tb < t1) t1 = tb
                    if (t0 > t1) { ok = false; break }
                }
            }
            if (ok && t0 < best) {
                best = t0
                if (fnx != 0 || fny != 0 || fnz != 0) { bnx = fnx; bny = fny; bnz = fnz }
            }
            i += 6
        }
        return if (best < Float.MAX_VALUE) Hit(x, y, z, bnx, bny, bnz, oy + dy * best) else null
    }

    /** Célula livre para construir: ar ou água. */
    private fun free(x: Int, y: Int, z: Int): Boolean {
        val b = world.get(x, y, z)
        return b == Blocks.AIR || (Reg.water > 0 && b == Reg.water)
    }

    private fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Hit? {
        var x = floor(ox).toInt()
        var y = floor(oy).toInt()
        var z = floor(oz).toInt()
        val stepX = if (dx > 0f) 1 else -1
        val stepY = if (dy > 0f) 1 else -1
        val stepZ = if (dz > 0f) 1 else -1
        val big = 1e30f
        val tdx = if (dx != 0f) abs(1f / dx) else big
        val tdy = if (dy != 0f) abs(1f / dy) else big
        val tdz = if (dz != 0f) abs(1f / dz) else big
        var tmx = if (dx > 0f) (x + 1 - ox) / dx else if (dx < 0f) (ox - x) / -dx else big
        var tmy = if (dy > 0f) (y + 1 - oy) / dy else if (dy < 0f) (oy - y) / -dy else big
        var tmz = if (dz > 0f) (z + 1 - oz) / dz else if (dz < 0f) (oz - z) / -dz else big
        var nx = 0; var ny = 0; var nz = 0
        var t = 0f
        while (t <= maxDist) {
            val bl = world.get(x, y, z)
            if (bl != Blocks.AIR && bl != Reg.water) {
                val hb = Shapes.hitBoxes(world, x, y, z, bl)
                if (hb == null) return Hit(x, y, z, nx, ny, nz, oy + dy * t)
                val hh = hitBoxes(hb, x, y, z, ox, oy, oz, dx, dy, dz, maxDist, nx, ny, nz)
                if (hh != null) return hh
            }
            if (tmx < tmy && tmx < tmz) {
                x += stepX; t = tmx; tmx += tdx; nx = -stepX; ny = 0; nz = 0
            } else if (tmy < tmz) {
                y += stepY; t = tmy; tmy += tdy; nx = 0; ny = -stepY; nz = 0
            } else {
                z += stepZ; t = tmz; tmz += tdz; nx = 0; ny = 0; nz = -stepZ
            }
        }
        return null
    }

    /** há chão sólido sob o personagem em (x, z)? (para não cair da borda agachado) */
    private fun supported(x: Float, y: Float, z: Float): Boolean {
        val by = floor(y - 0.05f).toInt()
        return world.solid(floor(x - 0.3f).toInt(), by, floor(z - 0.3f).toInt()) ||
            world.solid(floor(x + 0.3f).toInt(), by, floor(z - 0.3f).toInt()) ||
            world.solid(floor(x - 0.3f).toInt(), by, floor(z + 0.3f).toInt()) ||
            world.solid(floor(x + 0.3f).toInt(), by, floor(z + 0.3f).toInt())
    }

    private fun physics(dt: Float) {
        var rem = dt
        var ground = false
        val wasGround = s.onGround
        val moving = abs(s.moveX) + abs(s.moveZ) > 0.1f
        val edgeSafe = sneaking && wasGround && !s.flying
        while (rem > 0f) {
            val h = min(rem, 0.02f)
            rem -= h
            val nx = s.px + s.vx * h
            if (edgeSafe && !supported(nx, s.py, s.pz) && supported(s.px, s.py, s.pz)) {
                s.vx = 0f
            } else if (!collides(nx, s.py, s.pz)) {
                s.px = nx
            } else if (moving && (wasGround || swimming) && !s.flying && !collides(nx, s.py + 0.51f, s.pz)) {
                s.py += 0.51f
                s.px = nx
            } else if (moving && (wasGround || swimming) && !s.flying && !collides(nx, s.py + 1.05f, s.pz)) {
                s.vy = 8.6f
            } else {
                s.vx = 0f
            }
            val nz = s.pz + s.vz * h
            if (edgeSafe && !supported(s.px, s.py, nz) && supported(s.px, s.py, s.pz)) {
                s.vz = 0f
            } else if (!collides(s.px, s.py, nz)) {
                s.pz = nz
            } else if (moving && (wasGround || swimming) && !s.flying && !collides(s.px, s.py + 0.51f, nz)) {
                s.py += 0.51f
                s.pz = nz
            } else if (moving && (wasGround || swimming) && !s.flying && !collides(s.px, s.py + 1.05f, nz)) {
                s.vy = 8.6f
            } else {
                s.vz = 0f
            }
            val ny = s.py + s.vy * h
            if (!collides(s.px, ny, s.pz)) {
                s.py = ny
            } else {
                if (s.vy < 0f) ground = true
                s.vy = 0f
            }
        }
        s.onGround = ground
    }

    /** O olho do personagem está debaixo da superfície da água? */
    private fun eyeUnder(): Boolean {
        if (Reg.water <= 0) return false
        val ey = s.py + eyeH()
        val iy = floor(ey).toInt()
        val ix = floor(s.px).toInt()
        val iz = floor(s.pz).toInt()
        return world.isWater(ix, iy, iz) && (world.isWater(ix, iy + 1, iz) || ey - iy < 0.9f)
    }

    private fun eyeH(): Float = if (s.sitting) 0.95f else eyeSmooth

    private fun update(dt: Float) {
        s.takeLook(look)
        s.yaw += look[0] * s.sens
        s.pitch = (s.pitch - look[1] * s.sens).coerceIn(-1.553f, 1.553f)
        s.time = (s.time + dt / 300f) % 1f
        timeAcc += dt

        val sy = sin(s.yaw)
        val cy = cos(s.yaw)
        // água: nada sozinho quando ela cobre o corpo (precisa de 2+ blocos de profundidade; em 1 bloco só anda)
        val ixp = floor(s.px).toInt()
        val izp = floor(s.pz).toInt()
        val inWater = Reg.water > 0 && !s.flying && !s.sitting &&
            world.waterDepth(ixp, floor(s.py + (if (swimming) 0.5f else 1.15f)).toInt(), izp) >= 2
        s.inWater = inWater
        // agachar / correr
        if (s.flying || s.sitting || s.swim || inWater) s.sneak = false
        if (s.flying || s.sitting) s.swim = false
        if (s.swim || inWater) {
            swimming = true
        } else if (swimming && !collides(s.px, s.py, s.pz, 1.8f)) {
            swimming = false   // só levanta se houver espaço em cima
        }
        if (s.sneak && !swimming) {
            sneaking = true
        } else if (sneaking && !collides(s.px, s.py, s.pz, 1.8f)) {
            sneaking = false   // só levanta se houver espaço em cima
        }
        ph = if (swimming) 0.6f else if (sneaking) 1.5f else 1.8f
        eyeSmooth += ((if (swimming) 0.5f else if (sneaking) 1.27f else 1.62f) - eyeSmooth) * min(1f, dt * 14f)
        val sprinting = s.sprint && s.moveZ > 0.3f && !sneaking && !s.sitting
        val speed = when {
            s.flying -> if (s.sprint) 15f else 9f
            swimming -> if (s.sprint) 4.6f else 3.2f
            sneaking -> 2.0f
            sprinting -> 6.2f
            else -> 4.6f
        }
        val tx = (sy * s.moveZ + cy * s.moveX) * speed
        val tz = (-cy * s.moveZ + sy * s.moveX) * speed
        val k = min(1f, dt * 14f)
        s.vx += (tx - s.vx) * k
        s.vz += (tz - s.vz) * k
        if (swimming && inWater) {
            // dentro d'água: sem gravidade, flutua sozinho, boia na superfície, pula/desce com os botões
            val cell = floor(s.py + 0.5f).toInt()
            val surface = !world.isWater(ixp, cell + 1, izp)
            var ty = ((if (s.jump) 1f else 0f) - (if (s.down) 1f else 0f)) * 3.2f
            if (ty == 0f) ty = 1.2f   // flutua para cima sozinho
            if (surface && !s.down) {
                val hover = (cell + 0.9f + 0.03f - 0.5f) - s.py   // altura em que o olho fica logo acima da água
                if (s.jump || hover < 0.35f) ty = (hover * 6f).coerceIn(-1.5f, 3.2f)
            }
            s.vy += (ty - s.vy) * min(1f, dt * 8f)
        } else if (s.flying || swimming) {
            val ty = ((if (s.jump) 1f else 0f) - (if (s.down) 1f else 0f)) * 6.5f
            s.vy += (ty - s.vy) * k
        } else {
            s.vy -= 28f * dt
            if (s.vy < -45f) s.vy = -45f
            if (s.jump && s.onGround) {
                s.vy = 8.6f
                s.onGround = false
            }
        }
        if (s.sitting) {
            if (s.jump || abs(s.moveX) > 0.5f || abs(s.moveZ) > 0.5f) {
                s.sitting = false
                s.vy = 0f
                s.py += 0.6f
            } else {
                s.vx = 0f; s.vy = 0f; s.vz = 0f
            }
        }
        if (!s.sitting) physics(dt)
        if (s.py < -20f) {
            s.py = (world.surfaceY(World.W / 2, World.D / 2) + 2).toFloat(); s.px = World.W / 2 + 0.5f; s.pz = World.D / 2 + 0.5f; s.vy = 0f
        }

        val spd = sqrt(s.vx * s.vx + s.vz * s.vz)
        if (s.onGround && spd > 0.5f) bobT += dt * spd * 1.9f

        // interação
        val cp = cos(s.pitch)
        val dx = sy * cp
        val dy = sin(s.pitch)
        val dz = -cy * cp
        val h = raycast(s.px, s.py + eyeH(), s.pz, dx, dy, dz, 5f)
        hit = h
        s.targetName = if (h != null) Blocks.name(world.get(h.x, h.y, h.z)) else null
        s.targetShow = if (h != null && world.isShowcase(h.x, h.y, h.z)) world.get(h.x, h.y, h.z) else 0
        s.areaName = world.areaAt(floor(s.px).toInt(), floor(s.pz).toInt())

        punch = max(0f, punch - dt * 4f)
        handWave = 0f
        if (s.breaking && h != null && s.targetShow == 0) {
            handWave = abs(sin(timeAcc * 14f))
            if (hasBreak && h.x == breakX && h.y == breakY && h.z == breakZ) {
                val b = world.get(h.x, h.y, h.z)
                val total = if (Reg.isPlant(b)) 0.08f else if (b == Blocks.LEAVES || b == Blocks.GLASS) 0.22f else 0.55f
                breakProg += dt / total
                if (breakProg >= 1f) {
                    spawnParticles(h.x, h.y, h.z, b)
                    val mb = world.getMeta(h.x, h.y, h.z)
                    if (Shapes.isXModel(b) && (Models3D.get(Reg.key(b))?.hasDoor == true)) {
                        val f0 = mb and 3
                        var yy = h.y + 1
                        while (world.get(h.x, yy, h.z) == b && (world.getMeta(h.x, yy, h.z) and 3) == f0) { world.set(h.x, yy, h.z, Blocks.AIR); yy++ }
                        yy = h.y - 1
                        while (world.get(h.x, yy, h.z) == b && (world.getMeta(h.x, yy, h.z) and 3) == f0) { world.set(h.x, yy, h.z, Blocks.AIR); yy-- }
                    } else if (Shapes.isXModel(b) && (Models3D.get(Reg.key(b))?.multi == true)) {
                        val xm = Models3D.get(Reg.key(b))!!
                        val f = mb and 3
                        val seg = (mb shr 2) and 15
                        val me = xm.parts.firstOrNull { it.seg == seg }
                        if (me != null) {
                            val bx0 = h.x - me.off[f * 3]
                            val by0 = h.y - me.off[f * 3 + 1]
                            val bz0 = h.z - me.off[f * 3 + 2]
                            for (pt in xm.parts) {
                                val px = bx0 + pt.off[f * 3]; val py = by0 + pt.off[f * 3 + 1]; val pz = bz0 + pt.off[f * 3 + 2]
                                if (world.get(px, py, pz) == b) world.set(px, py, pz, Blocks.AIR)
                            }
                        }
                    } else if (Shapes.isDoor(b)) {
                        val oy2 = if ((mb and Shapes.M_UPPER) != 0) h.y - 1 else h.y + 1
                        if (world.get(h.x, oy2, h.z) == b) world.set(h.x, oy2, h.z, Blocks.AIR)
                    } else if (Shapes.isBed(b)) {
                        val sg = if ((mb and Shapes.M_SECOND) != 0) -1 else 1
                        val ox2 = h.x + Shapes.dirX(mb) * sg
                        val oz2 = h.z + Shapes.dirZ(mb) * sg
                        if (world.get(ox2, h.y, oz2) == b) world.set(ox2, h.y, oz2, Blocks.AIR)
                    }
                    world.set(h.x, h.y, h.z, Blocks.AIR)
                    breakProg = 0f
                    hasBreak = false
                }
            } else {
                breakX = h.x; breakY = h.y; breakZ = h.z
                hasBreak = true
                breakProg = 0f
            }
        } else {
            hasBreak = false
            breakProg = 0f
        }

        if (s.placeRequest) {
            s.placeRequest = false
            if (h != null) doPlace(h, sy, cy)
        }

        // animação do personagem (golpe quando `punch` acabou de ser disparado)
        animator.update(
            dt, s.onGround || s.sitting, s.flying, sneaking, sprinting,
            s.vx, s.vy, s.vz, s.yaw, s.pitch,
            punch > 0.97f, s.breaking && h != null && s.targetShow == 0,
            s.sitting, swimming
        )

        // partículas
        var i = parts.size - 1
        while (i >= 0) {
            val p = parts[i]
            p.life -= dt
            if (p.life <= 0f) {
                parts.removeAt(i)
            } else {
                p.vy -= 20f * dt
                val nx = p.x + p.vx * dt
                val ny = p.y + p.vy * dt
                val nz = p.z + p.vz * dt
                if (world.solid(floor(nx).toInt(), floor(ny).toInt(), floor(nz).toInt())) {
                    p.vy = 0f; p.vx *= 0.5f; p.vz *= 0.5f
                } else {
                    p.x = nx; p.y = ny; p.z = nz
                }
            }
            i--
        }
    }

    private fun doPlace(h: Hit, sy: Float, cy: Float) {
        val tb = world.get(h.x, h.y, h.z)
        val tm = world.getMeta(h.x, h.y, h.z)
        // sentar em cadeiras, bancos e sofás do addon
        if (Shapes.isXModel(tb)) {
            val txm = Models3D.get(Reg.key(tb))
            if (txm != null && txm.sit && !s.sitting) {
                val v = txm.variantAt(world, h.x, h.y, h.z, tm)
                var top = 0f
                for (q in v.sel) if (q.y1 > top) top = q.y1
                s.px = h.x + 0.5f
                s.pz = h.z + 0.5f
                s.py = h.y + min(0.6f, max(0.3f, top * 0.55f))
                s.vx = 0f; s.vy = 0f; s.vz = 0f
                s.sitting = true
                punch = 1f
                return
            }
        }
        // objetos do addon com estado (abrir/fechar, ligar/desligar)
        val tbit = Shapes.toggleBit(tb)
        if (tbit != 0) {
            val nm2 = tm xor tbit
            world.setMeta(h.x, h.y, h.z, nm2)
            val txm2 = Models3D.get(Reg.key(tb))
            if (txm2 != null && txm2.hasDoor) {
                // portas: todas as partes da coluna abrem e fecham juntas
                val f0 = tm and 3
                var yy = h.y + 1
                while (world.get(h.x, yy, h.z) == tb && (world.getMeta(h.x, yy, h.z) and 3) == f0) {
                    world.setMeta(h.x, yy, h.z, world.getMeta(h.x, yy, h.z) xor tbit); yy++
                }
                yy = h.y - 1
                while (world.get(h.x, yy, h.z) == tb && (world.getMeta(h.x, yy, h.z) and 3) == f0) {
                    world.setMeta(h.x, yy, h.z, world.getMeta(h.x, yy, h.z) xor tbit); yy--
                }
            }
            punch = 1f
            return
        }
        // abrir / fechar portas e portões
        if (Shapes.interactive(tb)) {
            val nm = tm xor Shapes.M_OPEN
            world.setMeta(h.x, h.y, h.z, nm)
            if (Shapes.isDoor(tb)) {
                val oy2 = if ((tm and Shapes.M_UPPER) != 0) h.y - 1 else h.y + 1
                if (world.get(h.x, oy2, h.z) == tb) world.setMeta(h.x, oy2, h.z, world.getMeta(h.x, oy2, h.z) xor Shapes.M_OPEN)
            }
            punch = 1f
            return
        }
        val blk = s.block
        if (!Reg.placeable(blk)) return
        val frac = h.hy - h.y
        // laje sobre laje vira bloco inteiro
        if (Shapes.isSlab(tb) && tb == blk && (tm and Shapes.M_SECOND) == 0) {
            val upper = (tm and Shapes.M_UPPER) != 0
            if ((h.ny == 1 && !upper) || (h.ny == -1 && upper)) {
                world.setMeta(h.x, h.y, h.z, tm or Shapes.M_SECOND)
                punch = 1f
                return
            }
        }
        // laje vertical: clicar na face de dentro completa o bloco
        if (Shapes.isVSlab(tb) && tb == blk && (tm and Shapes.M_SECOND) == 0 && (h.nx != 0 || h.nz != 0)) {
            val tf = tm and 3
            if (h.nx == -Shapes.dirX(tf) && h.nz == -Shapes.dirZ(tf)) {
                world.setMeta(h.x, h.y, h.z, tm or Shapes.M_SECOND)
                punch = 1f
                return
            }
        }
        val bx = h.x + h.nx
        val by = h.y + h.ny
        val bz = h.z + h.nz
        if (!world.inBounds(bx, by, bz) || !free(bx, by, bz)) return
        // direção em que o jogador olha: 0 = -Z, 1 = +X, 2 = +Z, 3 = -X
        val lx = sy
        val lz = -cy
        val face = if (abs(lx) > abs(lz)) (if (lx > 0f) 1 else 3) else (if (lz > 0f) 2 else 0)
        if (Shapes.isXModel(blk)) {
            val xm = Models3D.get(Reg.key(blk)) ?: return
            var f = face
            var mount = 0
            if (xm.hasMount && (h.nx != 0 || h.nz != 0)) {
                // preso na parede: a frente aponta para fora dela
                val ox = -h.nx; val oz = -h.nz
                f = if (oz < 0) 0 else if (ox > 0) 1 else if (oz > 0) 2 else 3
                mount = 32
            }
            if (xm.hasDoor) {
                // porta de 2 ou 3 blocos de altura (3 se houver espaço), com espelho automático em portas duplas
                if (!world.inBounds(bx, by + 1, bz) || !free(bx, by + 1, bz)) return
                val three = world.inBounds(bx, by + 2, bz) && free(bx, by + 2, bz)
                val lv = intArrayOf(0, 0, 0, 0)
                val dl = intArrayOf(-1, 0, 1, 0)   // esquerda do jogador (x) para face 0..3
                val dlz = intArrayOf(0, -1, 0, 1)
                val lxn = bx + dl[face]; val lzn = bz + dlz[face]
                val rxn = bx - dl[face]; val rzn = bz - dlz[face]
                fun baseDoor(px: Int, pz: Int): Int {
                    if (world.get(px, by, pz) != blk) return -1
                    val mm = world.getMeta(px, by, pz)
                    if ((mm and 3) != face) return -1
                    val pi = (mm shr 3) and 3
                    return if (pi == 2 || pi == 3) mm else -1
                }
                var mirror = 0
                val lm = baseDoor(lxn, lzn)
                val rm = baseDoor(rxn, rzn)
                if (lm >= 0 && (lm and 64) == 0) mirror = 64
                else if (rm >= 0 && (rm and 64) != 0) mirror = 0
                val hb = if (three) 32 else 0
                val types = if (three) intArrayOf(3, 1, 0) else intArrayOf(2, 0)
                for (i in types.indices) {
                    if (xm.solid && intersectsPlayer(bx, by + i, bz)) return
                }
                for (i in types.indices) world.set(bx, by + i, bz, blk, face or (types[i] shl 3) or hb or mirror)
                punch = 1f
                return
            }
            if (xm.hasHalf) {
                // colocação diagonal: mirando entre dois lados, o objeto fica na meia posição
                val fdx = Shapes.dirX(face).toFloat(); val fdz = Shapes.dirZ(face).toFloat()
                val fw = lx * fdx + lz * fdz
                val sd = lx * (-fdz) + lz * fdx
                val delta = Math.toDegrees(Math.atan2(sd.toDouble(), fw.toDouble()))
                if (abs(delta) in 22.5..67.5) mount = 64 or (if (delta > 0) 8 else 0)
            }
            if (xm.hasUp && h.ny == 1 && tb == blk) {
                // empilhar: alterna entre principal e de cima
                if ((tm and 8) == 0) mount = mount or 8
            }
            if (xm.multi) {
                for (pt in xm.parts) {
                    val px = bx + pt.off[f * 3]; val py = by + pt.off[f * 3 + 1]; val pz = bz + pt.off[f * 3 + 2]
                    if (!world.inBounds(px, py, pz) || !free(px, py, pz)) return
                    if (xm.solid && intersectsPlayer(px, py, pz)) return
                }
                for (pt in xm.parts) {
                    world.set(bx + pt.off[f * 3], by + pt.off[f * 3 + 1], bz + pt.off[f * 3 + 2], blk, f or (pt.seg shl 2))
                }
            } else {
                if (xm.solid && intersectsPlayer(bx, by, bz)) return
                world.set(bx, by, bz, blk, f or mount)
            }
            punch = 1f
            return
        }
        if (Shapes.isDoor(blk)) {
            if (!world.inBounds(bx, by + 1, bz) || !free(bx, by + 1, bz)) return
            if (intersectsPlayer(bx, by, bz) || intersectsPlayer(bx, by + 1, bz)) return
            world.set(bx, by, bz, blk, face)
            world.set(bx, by + 1, bz, blk, face or Shapes.M_UPPER)
            punch = 1f
            return
        }
        if (Shapes.isBed(blk)) {
            val hx = bx + Shapes.dirX(face)
            val hz = bz + Shapes.dirZ(face)
            if (!world.inBounds(hx, by, hz) || !free(hx, by, hz)) return
            if (intersectsPlayer(bx, by, bz) || intersectsPlayer(hx, by, hz)) return
            world.set(bx, by, bz, blk, face)
            world.set(hx, by, hz, blk, face or Shapes.M_SECOND)
            punch = 1f
            return
        }
        if (intersectsPlayer(bx, by, bz) && Reg.solid(blk)) return
        var meta = face
        if (Shapes.isSlab(blk) || Shapes.flippable(blk)) {
            meta = face or (if (h.ny == -1 || (h.ny == 0 && frac > 0.5f)) Shapes.M_UPPER else 0)
        }
        if (Shapes.isVSlab(blk) && (h.nx != 0 || h.nz != 0)) {
            // metade que encosta no bloco clicado
            val ox = -h.nx; val oz = -h.nz
            meta = if (oz < 0) 0 else if (ox > 0) 1 else if (oz > 0) 2 else 3
        }
        world.set(bx, by, bz, blk, meta)
        punch = 1f
    }

    private fun spawnParticles(x: Int, y: Int, z: Int, b: Int) {
        val col = Reg.avg(b)
        val cr = ((col shr 16) and 0xFF) / 255f
        val cg = ((col shr 8) and 0xFF) / 255f
        val cb = (col and 0xFF) / 255f
        for (i in 0 until 16) {
            val j = 0.85f + rng.nextFloat() * 0.3f
            parts.add(
                Particle(
                    x + 0.2f + rng.nextFloat() * 0.6f, y + 0.2f + rng.nextFloat() * 0.6f, z + 0.2f + rng.nextFloat() * 0.6f,
                    (rng.nextFloat() - 0.5f) * 4f, rng.nextFloat() * 4f + 1f, (rng.nextFloat() - 0.5f) * 4f,
                    0.5f + rng.nextFloat() * 0.5f, 0.07f + rng.nextFloat() * 0.07f,
                    min(1f, cr * j), min(1f, cg * j), min(1f, cb * j)
                )
            )
        }
    }

    // ---------------------------------------------------------------- desenho

    private fun smooth(v: Float): Float {
        val t = v.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun worldAttribs(enable: Boolean) {
        if (enable) {
            GLES20.glEnableVertexAttribArray(wPos)
            GLES20.glEnableVertexAttribArray(wUV)
            GLES20.glEnableVertexAttribArray(wLight)
        } else {
            GLES20.glDisableVertexAttribArray(wPos)
            GLES20.glDisableVertexAttribArray(wUV)
            GLES20.glDisableVertexAttribArray(wLight)
        }
    }

    private fun drawColorCube(m: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUniformMatrix4fv(cMVP, 1, false, mvp, 0)
        GLES20.glUniform4f(cColor, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun draw() {
        val ang = (2.0 * Math.PI * s.time).toFloat()
        val sdx = cos(ang)
        val sdy = sin(ang)
        val day = smooth((sdy + 0.15f) / 0.45f)
        val bright = 0.30f + 0.70f * day
        val warm = max(0f, 1f - abs(sdy) * 4f) * 0.35f
        val skyR = 0.03f + (0.52f - 0.03f) * day + warm
        val skyG = 0.04f + (0.78f - 0.04f) * day + warm * 0.45f
        val skyB = 0.10f + (0.98f - 0.10f) * day

        val under = eyeUnder()
        val uw = 0.35f + 0.65f * day
        val fogR = if (under) 0.06f * uw else skyR
        val fogG = if (under) 0.22f * uw else skyG
        val fogB = if (under) 0.46f * uw else skyB
        GLES20.glClearColor(fogR, fogG, fogB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val cp = cos(s.pitch)
        val dirX = sin(s.yaw) * cp
        val dirY = sin(s.pitch)
        val dirZ = -cos(s.yaw) * cp
        val bob = if (s.onGround) sin(bobT) * 0.04f else 0f
        val eyeX = s.px
        val eyeY = s.py + eyeH() + bob
        val eyeZ = s.pz
        var ex = eyeX
        var ey = eyeY
        var ez = eyeZ
        val mode = s.camMode
        if (mode == 0) {
            Matrix.setLookAtM(view, 0, ex, ey, ez, ex + dirX, ey + dirY, ez + dirZ, 0f, 1f, 0f)
        } else {
            val sg = if (mode == 1) -1f else 1f
            val t = camDist(eyeX, eyeY, eyeZ, dirX * sg, dirY * sg, dirZ * sg, 4.2f)
            ex = eyeX + dirX * sg * t
            ey = eyeY + dirY * sg * t
            ez = eyeZ + dirZ * sg * t
            if (mode == 1) {
                Matrix.setLookAtM(view, 0, ex, ey, ez, ex + dirX, ey + dirY, ez + dirZ, 0f, 1f, 0f)
            } else {
                Matrix.setLookAtM(view, 0, ex, ey, ez, eyeX, eyeY, eyeZ, 0f, 1f, 0f)
            }
        }
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // sol e lua
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(progColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cubeVbo)
        GLES20.glEnableVertexAttribArray(cPos)
        GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 12, 0)
        if (sdy > -0.12f && !under) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, ex + sdx * 220f, ey + sdy * 220f, ez + 60f)
            Matrix.scaleM(model, 0, 34f, 34f, 34f)
            Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
            drawColorCube(model, 1f, 0.93f, 0.55f, 1f)
        }
        if (sdy < 0.12f && !under) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, ex - sdx * 220f, ey - sdy * 220f, ez + 60f)
            Matrix.scaleM(model, 0, 24f, 24f, 24f)
            Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
            drawColorCube(model, 0.92f, 0.94f, 1f, 1f)
        }
        GLES20.glDisableVertexAttribArray(cPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // mundo
        val fogEnd = renderDist * 16f
        GLES20.glUseProgram(progWorld)
        GLES20.glUniformMatrix4fv(wMVP, 1, false, vp, 0)
        GLES20.glUniform3f(wCam, ex, ey, ez)
        GLES20.glUniform1f(wSun, bright)
        GLES20.glUniform3f(wFogColor, fogR, fogG, fogB)
        GLES20.glUniform1f(wFogStart, if (under) 0f else fogEnd * 0.5f)
        GLES20.glUniform1f(wFogEnd, if (under) 26f else fogEnd)
        GLES20.glUniform1f(wAlpha, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        Anim3D.tick(System.currentTimeMillis())
        GLES20.glUniform1i(wTex, 0)
        worldAttribs(true)

        val hl = sqrt(dirX * dirX + dirZ * dirZ)
        val hx = if (hl > 0.001f) dirX / hl else 0f
        val hz = if (hl > 0.001f) dirZ / hl else 0f
        val limit = fogEnd + 24f
        for (i in meshes.indices) {
            val m = meshes[i] ?: continue
            if (m.count == 0) continue
            val ccx = (i % World.CX) * 16 + 8 - ex
            val ccz = (i / World.CX) * 16 + 8 - ez
            if (ccx * ccx + ccz * ccz > limit * limit) continue
            if (abs(s.pitch) < 1.0f) {
                // fora do campo de visão horizontal (margem de 70°): não desenha
                val fwd = ccx * hx + ccz * hz
                val side = abs(ccx * (-hz) + ccz * hx)
                if (fwd + 12f < 0f || side - 12f > (fwd + 12f) * 2.75f) continue
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
            GLES20.glVertexAttribPointer(wPos, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glVertexAttribPointer(wUV, 2, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glVertexAttribPointer(wLight, 1, GLES20.GL_FLOAT, false, 24, 20)
            if (m.oc > 0) GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.oc)
            if (m.count > m.oc) waterList.add(m)
        }
        if (waterList.isNotEmpty()) {
            // água: translúcida, desenhada depois de todo o opaco
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            GLES20.glUniform1f(wAlpha, 0.72f)
            for (m in waterList) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
                GLES20.glVertexAttribPointer(wPos, 3, GLES20.GL_FLOAT, false, 24, 0)
                GLES20.glVertexAttribPointer(wUV, 2, GLES20.GL_FLOAT, false, 24, 12)
                GLES20.glVertexAttribPointer(wLight, 1, GLES20.GL_FLOAT, false, 24, 20)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, m.oc, m.count - m.oc)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUniform1f(wAlpha, 1f)
            waterList.clear()
        }
        worldAttribs(false)

        // partículas, contorno e rachaduras
        GLES20.glUseProgram(progColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cubeVbo)
        GLES20.glEnableVertexAttribArray(cPos)
        GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 12, 0)
        val lum = max(0.45f, bright)
        for (p in parts) {
            val sz = p.size * min(1f, p.life * 2.5f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, p.x, p.y, p.z)
            Matrix.scaleM(model, 0, sz, sz, sz)
            Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
            drawColorCube(model, p.r * lum, p.g * lum, p.b * lum, 1f)
        }
        if (mode != 0) drawPlayer(lum)
        val h = hit
        if (h != null) {
            val bb = Shapes.bounds(world, h.x, h.y, h.z, world.get(h.x, h.y, h.z)) ?: BB_FULL
            if (breakProg > 0f) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, h.x + bb[0] - 0.004f, h.y + bb[1] - 0.004f, h.z + bb[2] - 0.004f)
                Matrix.scaleM(model, 0, bb[3] - bb[0] + 0.008f, bb[4] - bb[1] + 0.008f, bb[5] - bb[2] + 0.008f)
                drawColorCube(model, 0f, 0f, 0f, breakProg * 0.65f)
                GLES20.glDepthMask(true)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
            GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 12, 0)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, h.x + bb[0] - 0.005f, h.y + bb[1] - 0.005f, h.z + bb[2] - 0.005f)
            Matrix.scaleM(model, 0, bb[3] - bb[0] + 0.01f, bb[4] - bb[1] + 0.01f, bb[5] - bb[2] + 0.01f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUniformMatrix4fv(cMVP, 1, false, mvp, 0)
            GLES20.glUniform4f(cColor, 0f, 0f, 0f, 1f)
            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24)
        }
        GLES20.glDisableVertexAttribArray(cPos)

        if (mode == 0) drawHand(bright)
    }

    private fun camDist(x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, maxD: Float): Float {
        var t = 0.2f
        while (t <= maxD) {
            if (world.solid(floor(x + dx * t).toInt(), floor(y + dy * t).toInt(), floor(z + dz * t).toInt())) {
                return max(0.3f, t - 0.3f)
            }
            t += 0.1f
        }
        return maxD
    }

    private val base = FloatArray(16)
    private val pm = FloatArray(16)
    // ---- personagem estilo Minecraft (Steve) -----------------------------------------------
    // Espaço do modelo = Bedrock: 1 px = 1/16 de bloco na escala do modelo, frente = +Z, braço direito em -X.
    // Ossos na ordem de PlayerAnimator: root, waist, body, head, rightArm, leftArm, rightLeg, leftLeg.
    private val boneM = Array(8) { FloatArray(16) }
    private val pivX = floatArrayOf(0f, 0f, 0f, 0f, -5f, 5f, -1.9f, 1.9f)
    private val pivY = floatArrayOf(0f, 12f, 24f, 24f, 22f, 22f, 12f, 12f)
    private val parentOf = intArrayOf(-1, 0, 1, 2, 2, 2, 0, 0)

    private fun buildBones() {
        for (i in 0 until 8) {
            val bo = animator.bones[i]
            val m = boneM[i]
            System.arraycopy(if (parentOf[i] < 0) base else boneM[parentOf[i]], 0, m, 0, 16)
            Matrix.translateM(m, 0, pivX[i] + bo.pos[0], pivY[i] + bo.pos[1], bo.pos[2])
            // convenção de sinais do Bedrock -> mão direita do OpenGL: (rx, -ry, -rz), ordem Z·Y·X
            Matrix.rotateM(m, 0, -bo.rot[2], 0f, 0f, 1f)
            Matrix.rotateM(m, 0, -bo.rot[1], 0f, 1f, 0f)
            Matrix.rotateM(m, 0, bo.rot[0], 1f, 0f, 0f)
            Matrix.scaleM(m, 0, bo.scl[0], bo.scl[1], bo.scl[2])
            Matrix.translateM(m, 0, -pivX[i], -pivY[i], 0f)
        }
    }

    private fun box(
        bone: Int, x0: Float, y0: Float, z0: Float, w: Float, h: Float, d: Float,
        r: Float, g: Float, b: Float, lum: Float
    ) {
        System.arraycopy(boneM[bone], 0, pm, 0, 16)
        Matrix.translateM(pm, 0, x0, y0, z0)
        Matrix.scaleM(pm, 0, w, h, d)
        drawColorCube(pm, r * lum, g * lum, b * lum, 1f)
    }

    private fun drawPlayer(lum: Float) {
        val px = 1.8f / 32f   // o modelo tem 32 px de altura = 1.8 blocos
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, s.px, s.py, s.pz)
        Matrix.rotateM(base, 0, -Math.toDegrees(animator.bodyYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(base, 0, 180f, 0f, 1f, 0f)   // frente do modelo (+Z) -> frente do jogo (-Z)
        Matrix.scaleM(base, 0, px, px, px)
        buildBones()

        val H = PlayerAnimator.HEAD
        val B = PlayerAnimator.BODY
        val RA = PlayerAnimator.R_ARM
        val LA = PlayerAnimator.L_ARM
        val RL = PlayerAnimator.R_LEG
        val LL = PlayerAnimator.L_LEG

        // pernas: calça + sapato
        box(RL, -3.9f, 2f, -2f, 3.8f, 10f, 4f, 0.23f, 0.24f, 0.62f, lum)
        box(RL, -3.9f, 0f, -2f, 3.8f, 2f, 4f, 0.42f, 0.42f, 0.44f, lum)
        box(LL, 0.1f, 2f, -2f, 3.8f, 10f, 4f, 0.2f, 0.21f, 0.56f, lum)
        box(LL, 0.1f, 0f, -2f, 3.8f, 2f, 4f, 0.38f, 0.38f, 0.4f, lum)
        // corpo (camisa)
        box(B, -4f, 12f, -2f, 8f, 12f, 4f, 0.0f, 0.66f, 0.67f, lum)
        // braços: manga + pele
        box(RA, -7.9f, 20f, -2f, 3.8f, 4f, 4f, 0.0f, 0.62f, 0.63f, lum)
        box(RA, -7.9f, 12f, -2f, 3.8f, 8f, 4f, 0.76f, 0.56f, 0.42f, lum)
        box(LA, 4.1f, 20f, -2f, 3.8f, 4f, 4f, 0.0f, 0.58f, 0.6f, lum)
        box(LA, 4.1f, 12f, -2f, 3.8f, 8f, 4f, 0.72f, 0.52f, 0.39f, lum)
        // cabeça
        box(H, -4f, 24f, -4f, 8f, 8f, 8f, 0.78f, 0.58f, 0.44f, lum)
        // cabelo (topo, atrás, costeletas)
        box(H, -4.2f, 30f, -4.2f, 8.4f, 2.2f, 8.4f, 0.27f, 0.17f, 0.09f, lum)
        box(H, -4.2f, 26f, -4.2f, 8.4f, 4.2f, 1.0f, 0.27f, 0.17f, 0.09f, lum)
        box(H, -4.2f, 28f, -4.2f, 0.4f, 2f, 5.2f, 0.27f, 0.17f, 0.09f, lum)
        box(H, 3.8f, 28f, -4.2f, 0.4f, 2f, 5.2f, 0.27f, 0.17f, 0.09f, lum)
        // rosto (frente = +Z): olhos, nariz, boca
        box(H, -3f, 27f, 4f, 1f, 1f, 0.1f, 0.95f, 0.95f, 0.95f, lum)
        box(H, -2f, 27f, 4f, 1f, 1f, 0.1f, 0.27f, 0.22f, 0.55f, lum)
        box(H, 1f, 27f, 4f, 1f, 1f, 0.1f, 0.27f, 0.22f, 0.55f, lum)
        box(H, 2f, 27f, 4f, 1f, 1f, 0.1f, 0.95f, 0.95f, 0.95f, lum)
        box(H, -1f, 26f, 4f, 2f, 1f, 0.1f, 0.66f, 0.46f, 0.34f, lum)
        box(H, -1f, 25f, 4f, 2f, 1f, 0.1f, 0.5f, 0.3f, 0.24f, lum)
    }

    private fun drawHand(bright: Float) {
        if (handBlock != s.block) {
            handBlock = s.block
            val md = Mesher.handMesh(handBlock)
            if (handVbo == 0) handVbo = genBuffer()
            upload(handVbo, md.data, md.size)
            handCount = md.size / 6
        }
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        val sw = max(punch, handWave)
        val moveAmt = if (s.onGround) min(1f, (abs(s.vx) + abs(s.vz)) / 4f) else 0f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            0.62f + sin(bobT * 0.5f) * 0.02f * moveAmt,
            -0.62f + sin(timeAcc * 1.6f) * 0.006f - abs(sin(bobT)) * 0.025f * moveAmt - sw * 0.10f,
            -1.0f - sw * 0.18f
        )
        Matrix.rotateM(model, 0, -sw * 55f, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, 28f, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, -12f, 1f, 0f, 0f)
        val flatHand = Reg.kind(handBlock) != Reg.K_CUBE && (Reg.kind(handBlock) != Reg.K_MODEL || Shapes.spriteItem(handBlock))
        if (flatHand) Matrix.rotateM(model, 0, 18f, 0f, 0f, 1f)
        val hk = if (flatHand) 0.62f else 0.36f
        Matrix.scaleM(model, 0, hk, hk, hk)
        Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)

        GLES20.glUseProgram(progWorld)
        GLES20.glUniformMatrix4fv(wMVP, 1, false, mvp, 0)
        GLES20.glUniform3f(wCam, 0f, 0f, 0f)
        GLES20.glUniform1f(wSun, max(bright, 0.6f))
        GLES20.glUniform3f(wFogColor, 0f, 0f, 0f)
        GLES20.glUniform1f(wFogStart, 0f)
        GLES20.glUniform1f(wFogEnd, 10000f)
        GLES20.glUniform1f(wAlpha, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(wTex, 0)
        worldAttribs(true)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, handVbo)
        GLES20.glVertexAttribPointer(wPos, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(wUV, 2, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glVertexAttribPointer(wLight, 1, GLES20.GL_FLOAT, false, 24, 20)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, handCount)
        worldAttribs(false)
    }

    companion object {
        private const val WORLD_VS = """
            uniform mat4 uMVP;
            uniform vec3 uCam;
            attribute vec3 aPos;
            attribute vec2 aUV;
            attribute float aLight;
            varying vec2 vUV;
            varying float vLight;
            varying float vDist;
            void main() {
                vUV = aUV;
                vLight = aLight;
                vDist = distance(aPos, uCam);
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val WORLD_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uSun;
            uniform vec3 uFogColor;
            uniform float uFogStart;
            uniform float uFogEnd;
            uniform float uAlpha;
            varying vec2 vUV;
            varying float vLight;
            varying float vDist;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                if (c.a < 0.5) discard;
                float lv = floor(vLight * 0.5);
                float sh = vLight - 2.0 * lv;
                float lit = max(sh * uSun, sh * (lv / 15.0));
                vec3 col = c.rgb * lit;
                float f = clamp((uFogEnd - vDist) / (uFogEnd - uFogStart), 0.0, 1.0);
                gl_FragColor = vec4(mix(uFogColor, col, f), uAlpha);
            }
        """
        private const val COLOR_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val COLOR_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
