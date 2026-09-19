package uk.noammm.kav.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

object StopStore {
    private const val MAGIC = 1263620145

    class Saved(val stops: List<Moovit.Stop>, val nextId: Int, val complete: Boolean)

    private fun file(ctx: Context) = File(ctx.filesDir, "moovit-stops.bin")

    fun load(ctx: Context): Saved? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val complete = inp.readBoolean()
            val nextId = inp.readInt()
            val n = inp.readInt()
            val out = ArrayList<Moovit.Stop>(n)
            repeat(n) {
                val id = inp.readInt(); val lat = inp.readInt(); val lon = inp.readInt()
                out.add(Moovit.Stop(id, lat / 1e6, lon / 1e6, inp.readUTF()))
            }
            Saved(out, nextId, complete)
        }
    }.getOrNull()

    fun save(ctx: Context, stops: List<Moovit.Stop>, nextId: Int, complete: Boolean) {
        runCatching {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeBoolean(complete)
                out.writeInt(nextId)
                out.writeInt(stops.size)
                for (s in stops) {
                    out.writeInt(s.id)
                    out.writeInt((s.lat * 1e6).toInt()); out.writeInt((s.lon * 1e6).toInt())
                    out.writeUTF(s.name.take(120))
                }
            }
            tmp.renameTo(f)
        }
    }
}
