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
    private val renderDist: Int
) : GLSurfaceView.Renderer {

    private class Mesh(var vbo: Int, var count: Int)
    private class Hit(val x: Int, val y: Int, val z: Int, val nx: Int, val ny: Int, val nz: Int)
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
    private var wFogColor = 0; private var wFogStart = 0; private var wFogEnd = 0
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
    private val anim = PlayerAnim()

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

    private fun rebuildChunks(budgetMs: Float) {
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
    }

    // ---------------------------------------------------------------- lógica

    private fun collides(x: Float, y: Float, z: Float): Boolean {
        val minX = floor(x - 0.3f).toInt(); val maxX = floor(x + 0.3f).toInt()
        val minY = floor(y).toInt(); val maxY = floor(y + 1.8f).toInt()
        val minZ = floor(z - 0.3f).toInt(); val maxZ = floor(z + 0.3f).toInt()
        for (iy in minY..maxY) for (iz in minZ..maxZ) for (ix in minX..maxX) {
            if (world.solid(ix, iy, iz)) return true
        }
        return false
    }

    private fun intersectsPlayer(x: Int, y: Int, z: Int): Boolean {
        return s.px + 0.3f > x && s.px - 0.3f < x + 1 &&
            s.py + 1.8f > y && s.py < y + 1 &&
            s.pz + 0.3f > z && s.pz - 0.3f < z + 1
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
            if (world.get(x, y, z) != Blocks.AIR) return Hit(x, y, z, nx, ny, nz)
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

    private fun physics(dt: Float) {
        var rem = dt
        var ground = false
        val wasGround = s.onGround
        val moving = abs(s.moveX) + abs(s.moveZ) > 0.1f
        while (rem > 0f) {
            val h = min(rem, 0.02f)
            rem -= h
            val nx = s.px + s.vx * h
            if (!collides(nx, s.py, s.pz)) {
                s.px = nx
            } else if (moving && wasGround && !s.flying && !collides(nx, s.py + 1.05f, s.pz)) {
                s.vy = 8.6f
            } else {
                s.vx = 0f
            }
            val nz = s.pz + s.vz * h
            if (!collides(s.px, s.py, nz)) {
                s.pz = nz
            } else if (moving && wasGround && !s.flying && !collides(s.px, s.py + 1.05f, nz)) {
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

    private fun update(dt: Float) {
        s.takeLook(look)
        s.yaw += look[0] * s.sens
        s.pitch = (s.pitch - look[1] * s.sens).coerceIn(-1.553f, 1.553f)
        s.time = (s.time + dt / 300f) % 1f
        timeAcc += dt

        val sy = sin(s.yaw)
        val cy = cos(s.yaw)
        val speed = if (s.flying) 9f else 4.6f
        val tx = (sy * s.moveZ + cy * s.moveX) * speed
        val tz = (-cy * s.moveZ + sy * s.moveX) * speed
        val k = min(1f, dt * 14f)
        s.vx += (tx - s.vx) * k
        s.vz += (tz - s.vz) * k
        if (s.flying) {
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
        physics(dt)
        if (s.py < -20f) {
            s.py = (world.surfaceY(World.W / 2, World.D / 2) + 2).toFloat(); s.px = World.W / 2 + 0.5f; s.pz = World.D / 2 + 0.5f; s.vy = 0f
        }

        val spd = sqrt(s.vx * s.vx + s.vz * s.vz)
        if (s.onGround && spd > 0.5f) bobT += dt * spd * 1.9f
        anim.update(dt, spd, s.onGround, s.flying)

        // interação
        val cp = cos(s.pitch)
        val dx = sy * cp
        val dy = sin(s.pitch)
        val dz = -cy * cp
        val h = raycast(s.px, s.py + 1.62f, s.pz, dx, dy, dz, 5f)
        hit = h
        s.targetName = if (h != null) Blocks.name(world.get(h.x, h.y, h.z)) else null

        punch = max(0f, punch - dt * 4f)
        handWave = 0f
        if (s.breaking && h != null) {
            handWave = abs(sin(timeAcc * 14f))
            if (hasBreak && h.x == breakX && h.y == breakY && h.z == breakZ) {
                val b = world.get(h.x, h.y, h.z)
                val total = if (Reg.isPlant(b)) 0.08f else if (b == Blocks.LEAVES || b == Blocks.GLASS) 0.22f else 0.55f
                breakProg += dt / total
                if (breakProg >= 1f) {
                    spawnParticles(h.x, h.y, h.z, b)
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
            if (h != null) {
                val bx = h.x + h.nx
                val by = h.y + h.ny
                val bz = h.z + h.nz
                if (world.inBounds(bx, by, bz) && world.get(bx, by, bz) == Blocks.AIR && Reg.placeable(s.block) && !intersectsPlayer(bx, by, bz)) {
                    world.set(bx, by, bz, s.block)
                    punch = 1f
                }
            }
        }

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

        GLES20.glClearColor(skyR, skyG, skyB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val cp = cos(s.pitch)
        val dirX = sin(s.yaw) * cp
        val dirY = sin(s.pitch)
        val dirZ = -cos(s.yaw) * cp
        val bob = if (s.onGround) sin(bobT) * 0.04f else 0f
        val eyeX = s.px
        val eyeY = s.py + 1.62f + bob
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
        if (sdy > -0.12f) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, ex + sdx * 220f, ey + sdy * 220f, ez + 60f)
            Matrix.scaleM(model, 0, 34f, 34f, 34f)
            Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
            drawColorCube(model, 1f, 0.93f, 0.55f, 1f)
        }
        if (sdy < 0.12f) {
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
        GLES20.glUniform3f(wFogColor, skyR, skyG, skyB)
        GLES20.glUniform1f(wFogStart, fogEnd * 0.5f)
        GLES20.glUniform1f(wFogEnd, fogEnd)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
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
            if (abs(s.pitch) < 1.0f && ccx * hx + ccz * hz < -14f) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
            GLES20.glVertexAttribPointer(wPos, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glVertexAttribPointer(wUV, 2, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glVertexAttribPointer(wLight, 1, GLES20.GL_FLOAT, false, 24, 20)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count)
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
            if (breakProg > 0f) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, h.x - 0.004f, h.y - 0.004f, h.z - 0.004f)
                Matrix.scaleM(model, 0, 1.008f, 1.008f, 1.008f)
                drawColorCube(model, 0f, 0f, 0f, breakProg * 0.65f)
                GLES20.glDepthMask(true)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
            GLES20.glVertexAttribPointer(cPos, 3, GLES20.GL_FLOAT, false, 12, 0)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, h.x - 0.005f, h.y - 0.005f, h.z - 0.005f)
            Matrix.scaleM(model, 0, 1.01f, 1.01f, 1.01f)
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
    private val boneM = Array(PlayerAnim.COUNT) { FloatArray(16) }

    // Esqueleto do personagem, nas proporções do Minecraft (Steve: 32 px = 1,8 bloco).
    // X+ é a direita do personagem; o rosto aponta para -Z.
    private val PX = 1.8f / 32f
    private val PIVOT = floatArrayOf(
        0f, 12f, 0f,     // cintura
        0f, 24f, 0f,     // corpo
        0f, 24f, 0f,     // cabeça
        5f, 22f, 0f,     // braço direito
        -5f, 22f, 0f,    // braço esquerdo
        1.9f, 12f, 0f,   // perna direita
        -1.9f, 12f, 0f   // perna esquerda
    )
    // cintura -> corpo -> cabeça/braços; pernas presas na raiz (como no Minecraft)
    private val PARENT = intArrayOf(
        -1, PlayerAnim.WAIST, PlayerAnim.BODY, PlayerAnim.BODY, PlayerAnim.BODY, -1, -1
    )

    /** Monta a matriz do osso: posição da animação, depois rotação em torno do pivô. */
    private fun poseBone(i: Int, extraPitch: Float) {
        val parent = if (PARENT[i] < 0) base else boneM[PARENT[i]]
        val m = boneM[i]
        System.arraycopy(parent, 0, m, 0, 16)
        val px = PIVOT[i * 3]
        val py = PIVOT[i * 3 + 1]
        val pz = PIVOT[i * 3 + 2]
        Matrix.translateM(m, 0, px + anim.pos[i * 3], py + anim.pos[i * 3 + 1], pz + anim.pos[i * 3 + 2])
        Matrix.rotateM(m, 0, anim.rot[i * 3 + 2], 0f, 0f, 1f)
        Matrix.rotateM(m, 0, anim.rot[i * 3 + 1], 0f, 1f, 0f)
        Matrix.rotateM(m, 0, anim.rot[i * 3] + extraPitch, 1f, 0f, 0f)
        Matrix.translateM(m, 0, -px, -py, -pz)
    }

    /** Caixa em coordenadas do modelo (pixels), presa a um osso. */
    private fun box(
        bone: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, lum: Float
    ) {
        System.arraycopy(boneM[bone], 0, pm, 0, 16)
        Matrix.translateM(pm, 0, x0, y0, z0)
        Matrix.scaleM(pm, 0, x1 - x0, y1 - y0, z1 - z0)
        drawColorCube(pm, r * lum, g * lum, b * lum, 1f)
    }

    private fun drawPlayer(lum: Float) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, s.px, s.py, s.pz)
        Matrix.rotateM(base, 0, -Math.toDegrees(s.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(base, 0, PX, PX, PX)
        val pitch = Math.toDegrees(s.pitch.toDouble()).toFloat()
        for (i in 0 until PlayerAnim.COUNT) {
            poseBone(i, if (i == PlayerAnim.HEAD) pitch else 0f)
        }
        // pernas
        box(PlayerAnim.R_LEG, -0.1f, 0f, -2f, 3.9f, 12f, 2f, 0.2f, 0.2f, 0.52f, lum)
        box(PlayerAnim.L_LEG, -3.9f, 0f, -2f, 0.1f, 12f, 2f, 0.22f, 0.22f, 0.58f, lum)
        // corpo
        box(PlayerAnim.BODY, -4f, 12f, -2f, 4f, 24f, 2f, 0.0f, 0.68f, 0.68f, lum)
        // braços
        box(PlayerAnim.R_ARM, 4f, 12f, -2f, 8f, 24f, 2f, 0.8f, 0.6f, 0.45f, lum)
        box(PlayerAnim.L_ARM, -8f, 12f, -2f, -4f, 24f, 2f, 0.84f, 0.64f, 0.48f, lum)
        // cabeça, cabelo e olhos
        val h = PlayerAnim.HEAD
        box(h, -4f, 24f, -4f, 4f, 32f, 4f, 0.86f, 0.66f, 0.5f, lum)
        box(h, -4.4f, 30.2f, -4.4f, 4.4f, 32.4f, 4.4f, 0.28f, 0.17f, 0.08f, lum)
        box(h, -4.4f, 24f, 2.8f, 4.4f, 30.2f, 4.4f, 0.28f, 0.17f, 0.08f, lum)
        box(h, -2.8f, 26.8f, -4.3f, -0.8f, 28.2f, -3.95f, 0.15f, 0.2f, 0.65f, lum)
        box(h, 0.8f, 26.8f, -4.3f, 2.8f, 28.2f, -3.95f, 0.15f, 0.2f, 0.65f, lum)
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
        Matrix.scaleM(model, 0, 0.36f, 0.36f, 0.36f)
        Matrix.translateM(model, 0, -0.5f, -0.5f, -0.5f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)

        GLES20.glUseProgram(progWorld)
        GLES20.glUniformMatrix4fv(wMVP, 1, false, mvp, 0)
        GLES20.glUniform3f(wCam, 0f, 0f, 0f)
        GLES20.glUniform1f(wSun, max(bright, 0.6f))
        GLES20.glUniform3f(wFogColor, 0f, 0f, 0f)
        GLES20.glUniform1f(wFogStart, 0f)
        GLES20.glUniform1f(wFogEnd, 10000f)
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
            varying vec2 vUV;
            varying float vLight;
            varying float vDist;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                if (c.a < 0.5) discard;
                vec3 col = c.rgb * (vLight * uSun);
                float f = clamp((uFogEnd - vDist) / (uFogEnd - uFogStart), 0.0, 1.0);
                gl_FragColor = vec4(mix(uFogColor, col, f), 1.0);
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
