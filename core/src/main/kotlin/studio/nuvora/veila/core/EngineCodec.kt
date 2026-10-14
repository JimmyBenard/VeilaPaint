package studio.nuvora.veila.core

import java.io.*
import java.util.zip.*

object EngineCodec {
    fun encode(s: EngineSnapshot): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(body)).use { out ->
            out.writeInt(s.width)
            out.writeInt(s.height)
            out.writeInt(s.totalSteps)
            out.writeInt(s.step)
            for (v in s.mask) out.writeShort(v.toInt())
            writeHistory(out, s.undo)
            writeHistory(out, s.redo)
        }
        val data = body.toByteArray()
        return ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(0x5645494c)
                    it.writeInt(1)
                    it.writeInt(data.size)
                    it.writeLong(CRC32().apply { update(data) }.value)
                    it.write(data)
                }
            }
            .toByteArray()
    }

    fun decode(bytes: ByteArray): EngineSnapshot {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x5645494c)
            require(input.readInt() == 1)
            val n = input.readInt()
            require(n in 1..100_000_000 && n == bytes.size - 20)
            val crc = input.readLong()
            val data = ByteArray(n)
            input.readFully(data)
            require(CRC32().apply { update(data) }.value == crc) { "Sauvegarde endommagée" }
            DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { body ->
                val w = body.readInt()
                val h = body.readInt()
                val total = body.readInt()
                val step = body.readInt()
                ProjectRules.validate(total, w, h)
                require(step in 0 until total)
                val mask =
                    ShortArray(w * h) { body.readShort().also { require(it.toInt() in 0..1000) } }
                val undo = readHistory(body, w, h, total)
                val redo = readHistory(body, w, h, total)
                require(body.read() == -1)
                return EngineSnapshot(w, h, total, step, mask, undo, redo)
            }
        }
    }

    private fun writeHistory(out: DataOutputStream, edits: List<Edit>) {
        out.writeInt(edits.size)
        for (e in edits) {
            out.writeInt(e.beforeStep)
            out.writeInt(e.afterStep)
            out.writeInt(e.tiles.size)
            for (t in e.tiles) {
                out.writeInt(t.id)
                out.writeInt(t.xor.size)
                out.write(t.xor)
            }
        }
    }

    private fun readHistory(input: DataInputStream, w: Int, h: Int, total: Int): List<Edit> {
        val count = input.readInt()
        require(count in 0..150)
        val tiles = ((w + 63) / 64) * ((h + 63) / 64)
        var bytes = 0
        return List(count) {
            val before = input.readInt()
            val after = input.readInt()
            require(before in 0 until total && after in before..minOf(before + 1, total - 1))
            val n = input.readInt()
            require(n in 0..tiles)
            val ids = HashSet<Int>()
            Edit(
                before,
                after,
                List(n) {
                    val id = input.readInt()
                    val size = input.readInt()
                    require(id in 0 until tiles && ids.add(id) && size in 1..20_000)
                    bytes += size
                    require(bytes <= 64 * 1024 * 1024)
                    TileDiff(id, ByteArray(size).also { input.readFully(it) })
                },
            )
        }
    }
}
