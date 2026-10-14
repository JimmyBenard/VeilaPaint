package studio.nuvora.veila.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import studio.nuvora.veila.core.AtomicSlots

data class VideoEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fileName: String,
    val thumbnail: String? = null,
    val projectId: String? = null,
    val description: String = "",
    val durationMillis: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val created: Long = System.currentTimeMillis(),
    val modified: Long = System.currentTimeMillis(),
) {
    val validFileName get() = fileName.matches(Regex("[A-Za-z0-9._-]+"))

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("fileName", fileName)
            .put("thumbnail", thumbnail)
            .put("projectId", projectId)
            .put("description", description)
            .put("durationMillis", durationMillis)
            .put("width", width)
            .put("height", height)
            .put("created", created)
            .put("modified", modified)

    companion object {
        fun fromJson(json: JSONObject): VideoEntry {
            val id = json.getString("id").also(UUID::fromString)
            fun filename(key: String) =
                json.getString(key).also { require(it.matches(Regex("[A-Za-z0-9._-]+"))) }
            fun optional(key: String) =
                if (json.has(key) && !json.isNull(key)) filename(key) else null
            return VideoEntry(
                id = id,
                name = json.getString("name").trim().take(100).ifEmpty { "Vidéo Veila" },
                fileName = filename("fileName"),
                thumbnail = optional("thumbnail"),
                projectId =
                    if (json.has("projectId") && !json.isNull("projectId")) json.getString("projectId")
                    else null,
                description = json.optString("description").take(2_000),
                durationMillis = json.optLong("durationMillis", 0).coerceAtLeast(0),
                width = json.optInt("width", 0).coerceAtLeast(0),
                height = json.optInt("height", 0).coerceAtLeast(0),
                created = json.optLong("created", System.currentTimeMillis()).coerceAtLeast(0),
                modified = json.optLong("modified", System.currentTimeMillis()).coerceAtLeast(0),
            )
        }
    }
}

/** Metadata catalog for workshop recordings. Existing MP4s are adopted without deleting them. */
class VideoRepository(private val context: Context) {
    private val directory = File(context.filesDir, "video_catalog").apply { mkdirs() }
    private val thumbnails = File(directory, "thumbnails").apply { mkdirs() }
    private val files = File(context.filesDir, "videos").apply { mkdirs() }

    fun file(entry: VideoEntry) = File(files, entry.fileName)

    fun thumbnail(entry: VideoEntry) =
        entry.thumbnail?.let { File(thumbnails, it).takeIf(File::isFile) }

    @Synchronized
    fun list(): List<VideoEntry> {
        val stored = read()
        val known = stored.associateBy { it.fileName }.toMutableMap()
        var changed = false
        files.listFiles().orEmpty().filter { it.isFile && it.extension.equals("mp4", true) && !it.name.endsWith(".pending.mp4") }
            .forEach { file ->
                if (file.name !in known) {
                    val adopted = createEntry(file, null, "Enregistrement Veila")
                    known[file.name] = adopted
                    changed = true
                }
            }
        val result = known.values.sortedByDescending { it.modified }
        if (changed) write(result)
        return result
    }

    @Synchronized
    fun register(file: File, project: Project): VideoEntry {
        require(file.parentFile == files && file.extension.equals("mp4", true)) {
            "Fichier vidéo Veila invalide."
        }
        val entry = createEntry(file, project.id, "Speed Drawing — ${project.name}")
        val current = read().filterNot { it.fileName == file.name } + entry
        write(current)
        return entry
    }

    @Synchronized
    fun update(entry: VideoEntry): VideoEntry {
        require(entry.validFileName)
        val updated = entry.copy(
            name = entry.name.trim().take(100).ifEmpty { "Vidéo Veila" },
            description = entry.description.take(2_000),
            modified = System.currentTimeMillis(),
        )
        val all = read().toMutableList()
        val index = all.indexOfFirst { it.id == entry.id }
        require(index >= 0) { "Vidéo introuvable." }
        all[index] = updated
        write(all)
        return updated
    }

    @Synchronized
    fun delete(entry: VideoEntry) {
        val source = file(entry)
        if (source.exists()) check(source.delete()) { "Suppression du fichier vidéo impossible." }
        entry.thumbnail?.let { thumbnail ->
            val image = File(thumbnails, thumbnail)
            if (image.exists()) image.delete()
        }
        write(read().filterNot { it.id == entry.id })
    }

    private fun read(): List<VideoEntry> {
        val slots = AtomicSlots(directory).read() ?: return emptyList()
        return try {
            val json = JSONObject(String(slots.bytes, Charsets.UTF_8))
            require(json.getInt("schema") == 1)
            val entries = json.getJSONArray("entries")
            List(entries.length()) { VideoEntry.fromJson(entries.getJSONObject(it)) }
        } catch (_: Exception) {
            // A damaged catalog must not hide or delete the original MP4 recordings.
            emptyList()
        }
    }

    private fun write(entries: List<VideoEntry>) {
        val json =
            JSONObject()
                .put("schema", 1)
                .put("entries", JSONArray().apply { entries.forEach { put(it.json()) } })
        AtomicSlots(directory).write(json.toString().toByteArray())
    }

    private fun createEntry(file: File, projectId: String?, defaultName: String): VideoEntry {
        val id = UUID.randomUUID().toString()
        val metadata = readMetadata(file)
        val thumb = metadata?.let { createThumbnail(file, id) }
        val created = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return VideoEntry(
            id = id,
            name = defaultName.take(100),
            fileName = file.name,
            thumbnail = thumb,
            projectId = projectId,
            durationMillis = metadata?.first ?: 0,
            width = metadata?.second ?: 0,
            height = metadata?.third ?: 0,
            created = created,
            modified = created,
        )
    }

    private fun readMetadata(file: File): Triple<Long, Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
            var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180L != 0L) {
                val swap = width
                width = height
                height = swap
            }
            if (duration > 0 && width > 0 && height > 0) Triple(duration, width, height) else null
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun createThumbnail(file: File, id: String): String? {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var reduced: Bitmap? = null
        val name = "$id.jpg"
        try {
            retriever.setDataSource(file.absolutePath)
            val source =
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
            frame = source
            val scale = minOf(1f, 480f / maxOf(source.width, source.height))
            reduced =
                if (scale == 1f) source
                else Bitmap.createScaledBitmap(
                    source,
                    maxOf(1, (source.width * scale).toInt()),
                    maxOf(1, (source.height * scale).toInt()),
                    true,
                )
            val output = reduced ?: return null
            FileOutputStream(File(thumbnails, name)).use { stream ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                stream.fd.sync()
            }
            return name
        } catch (_: Exception) {
            File(thumbnails, name).delete()
            return null
        } finally {
            if (reduced != null && reduced !== frame) reduced.recycle()
            frame?.recycle()
            retriever.release()
        }
    }
}
