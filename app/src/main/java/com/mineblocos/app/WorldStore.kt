package com.mineblocos.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object WorldStore {
    private const val MAGIC = 0x4D420005
    private const val MAGIC_OLD = 0x4D420003

    class Loaded(
        val world: World,
        val px: Float, val py: Float, val pz: Float,
        val yaw: Float, val pitch: Float, val time: Float
    )

    private fun file(c: Context) = File(c.filesDir, "mundo.bin")

    fun exists(c: Context) = file(c).exists()

    fun delete(c: Context) {
        file(c).delete()
    }

    @Synchronized
    fun save(
        c: Context, blocks: ShortArray, seed: Int,
        px: Float, py: Float, pz: Float, yaw: Float, pitch: Float, time: Float
    ) {
        try {
            val tmp = File(c.filesDir, "mundo.tmp")
            DataOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp)))).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(seed)
                o.writeFloat(px); o.writeFloat(py); o.writeFloat(pz)
                o.writeFloat(yaw); o.writeFloat(pitch); o.writeFloat(time)
                val bb = java.nio.ByteBuffer.allocate(blocks.size * 2)
                bb.asShortBuffer().put(blocks)
                o.write(bb.array())
            }
            val dest = file(c)
            dest.delete()
            tmp.renameTo(dest)
        } catch (e: Exception) {
            // ignora: tenta de novo no próximo salvamento
        }
    }

    fun load(c: Context): Loaded? {
        val f = file(c)
        if (!f.exists()) return null
        return try {
            DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(f)))).use { i ->
                val magic = i.readInt()
                if (magic != MAGIC) return null
                val seed = i.readInt()
                val px = i.readFloat(); val py = i.readFloat(); val pz = i.readFloat()
                val yaw = i.readFloat(); val pitch = i.readFloat(); val time = i.readFloat()
                val w = World(seed)
                val bytes = ByteArray(w.blocks.size * 2)
                i.readFully(bytes)
                java.nio.ByteBuffer.wrap(bytes).asShortBuffer().get(w.blocks)
                w.markAllDirty()
                Loaded(w, px, py, pz, yaw, pitch, time)
            }
        } catch (e: Exception) {
            null
        }
    }
}
