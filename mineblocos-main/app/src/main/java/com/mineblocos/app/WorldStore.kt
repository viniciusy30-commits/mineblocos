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
    private const val MAGIC = 0x4D420007
    private const val MAGIC_V6 = 0x4D420006
    private const val MAGIC_V5 = 0x4D420005

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
        c: Context, blocks: ShortArray, meta: ByteArray, seed: Int,
        showcase: java.util.BitSet, areas: List<World.Area>,
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
                o.write(meta)
                val la = showcase.toLongArray()
                o.writeInt(la.size)
                for (v in la) o.writeLong(v)
                o.writeInt(areas.size)
                for (a in areas) {
                    o.writeUTF(a.name)
                    o.writeInt(a.x0); o.writeInt(a.x1); o.writeInt(a.z0); o.writeInt(a.z1)
                }
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
                if (magic != MAGIC && magic != MAGIC_V6 && magic != MAGIC_V5) return null
                val seed = i.readInt()
                val px = i.readFloat(); val py = i.readFloat(); val pz = i.readFloat()
                val yaw = i.readFloat(); val pitch = i.readFloat(); val time = i.readFloat()
                val w = World(seed)
                val bytes = ByteArray(w.blocks.size * 2)
                i.readFully(bytes)
                java.nio.ByteBuffer.wrap(bytes).asShortBuffer().get(w.blocks)
                if (magic != MAGIC_V5) i.readFully(w.meta)
                if (magic == MAGIC) {
                    val n = i.readInt()
                    val la = LongArray(n)
                    for (k in 0 until n) la[k] = i.readLong()
                    w.showcase.or(java.util.BitSet.valueOf(la))
                    val na = i.readInt()
                    for (k in 0 until na) {
                        val name = i.readUTF()
                        val x0 = i.readInt(); val x1 = i.readInt(); val z0 = i.readInt(); val z1 = i.readInt()
                        w.areas.add(World.Area(name, x0, x1, z0, z1))
                    }
                }
                w.markAllDirty()
                Loaded(w, px, py, pz, yaw, pitch, time)
            }
        } catch (e: Exception) {
            null
        }
    }
}
