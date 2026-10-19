package com.mineblocos.app

class GameState {
    @Volatile var px = 64.5f
    @Volatile var py = 40f
    @Volatile var pz = 64.5f
    @Volatile var vx = 0f
    @Volatile var vy = 0f
    @Volatile var vz = 0f
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var moveX = 0f
    @Volatile var moveZ = 0f
    @Volatile var jump = false
    @Volatile var down = false
    @Volatile var flying = false
    @Volatile var sitting = false
    @Volatile var breaking = false
    @Volatile var placeRequest = false
    @Volatile var selected = 0
    @Volatile var camMode = 1 // 0 = 1ª pessoa, 1 = 3ª atrás, 2 = 3ª de frente
    @Volatile var fps = 0
    @Volatile var time = 0.3f
    @Volatile var ready = false
    @Volatile var onGround = false
    @Volatile var targetName: String? = null

    var sens = 0.005f
    var showFps = false

    private var lx = 0f
    private var ly = 0f

    @Synchronized
    fun addLook(dx: Float, dy: Float) {
        lx += dx
        ly += dy
    }

    @Synchronized
    fun takeLook(out: FloatArray) {
        out[0] = lx
        out[1] = ly
        lx = 0f
        ly = 0f
    }

    val hotbar = Blocks.hotbar.copyOf()

    val block: Int get() = hotbar[selected]
}
