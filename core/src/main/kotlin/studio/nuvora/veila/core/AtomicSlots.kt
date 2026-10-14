package studio.nuvora.veila.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** Two complete independently checksummed generations, one atomic replace at a time. */
class AtomicSlots(private val directory: File) {
    data class Value(val generation: Long, val bytes: ByteArray, val recovered: Boolean)

    init {
        require(directory.isDirectory || directory.mkdirs())
    }

    @Synchronized
    fun read(): Value? {
        val existing =
            listOf(File(directory, "state.a"), File(directory, "state.b")).filter { it.exists() }
        val valid = existing.mapNotNull { readFile(it) }
        return valid.maxByOrNull { it.generation }?.copy(recovered = valid.size < existing.size)
    }

    @Synchronized
    fun write(data: ByteArray) {
        val next = (read()?.generation ?: 0) + 1
        val destination = File(directory, if (next % 2L == 1L) "state.a" else "state.b")
        val temp = File(directory, "state.pending")
        FileOutputStream(temp).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.writeInt(0x56534c54)
            out.writeLong(next)
            out.writeInt(data.size)
            out.writeLong(CRC32().apply { update(data) }.value)
            out.write(data)
            out.flush()
            file.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readFile(file: File): Value? =
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use {
                require(it.readInt() == 0x56534c54)
                val generation = it.readLong()
                val length = it.readInt()
                val crc = it.readLong()
                require(
                    length in 1..120_000_000 &&
                        file.length() == length.toLong() + 24 &&
                        generation > 0
                )
                val data = ByteArray(length)
                it.readFully(data)
                require(CRC32().apply { update(data) }.value == crc)
                Value(generation, data, false)
            }
        } catch (_: Exception) {
            null
        }
}
