package studio.nuvora.veila.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.*
import java.util.UUID
import org.json.JSONObject
import studio.nuvora.veila.core.*

class ProjectRepository(private val context: Context) {
    private companion object { const val LAYER_MAGIC = 0x56503034 }
    val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun directory(id: String) = File(root, id.also { UUID.fromString(it) }).apply { mkdirs() }

    fun asset(project: Project, filename: String) = File(directory(project.id), filename)

    /** Used only for a new draft: its first image establishes the fixed common canvas. */
    fun dimensions(uri: Uri): Pair<Int, Int> {
        var dimensions = 0 to 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            dimensions = info.size.width to info.size.height
            require(info.size.width in 16..8192 && info.size.height in 16..8192)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(1, 1)
        }
        bitmap.recycle()
        ProjectRules.validate(2, dimensions.first, dimensions.second)
        return dimensions
    }

    /** Small image used by project cards; always falls back to a valid stage when available. */
    fun coverThumbnail(project: Project): File? {
        val preferred =
            when (project.coverMode) {
                ProjectCoverMode.IMPORTED -> project.coverThumbnail
                ProjectCoverMode.STAGE ->
                    project.stages.firstOrNull { it.id == project.coverStageId }?.thumbnail
                ProjectCoverMode.AUTOMATIC -> project.stages.firstOrNull()?.thumbnail
            }
        return preferred?.let { asset(project, it).takeIf(File::isFile) }
            ?: project.stages.firstNotNullOfOrNull { asset(project, it.thumbnail).takeIf(File::isFile) }
    }

    fun coverImage(project: Project): File? {
        val preferred =
            when (project.coverMode) {
                ProjectCoverMode.IMPORTED -> project.coverImage
                ProjectCoverMode.STAGE -> project.stages.firstOrNull { it.id == project.coverStageId }?.image
                ProjectCoverMode.AUTOMATIC -> project.stages.firstOrNull()?.image
            }
        return preferred?.let { asset(project, it).takeIf(File::isFile) }
            ?: project.stages.firstNotNullOfOrNull { asset(project, it.image).takeIf(File::isFile) }
    }

    fun list(): Pair<List<Project>, Int> {
        var errors = 0
        val projects =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { dir ->
                    try {
                        val stored =
                            AtomicSlots(dir).read()
                                ?: return@mapNotNull null.also {
                                    if (
                                        File(dir, "state.a").exists() ||
                                            File(dir, "state.b").exists()
                                    )
                                        errors++
                                }
                        readDocument(stored.bytes, false).first
                    } catch (_: Exception) {
                        errors++
                        null
                    }
                }
                .sortedByDescending { it.modified }
        return projects to errors
    }

    fun load(id: String): LoadedProject {
        val value =
            AtomicSlots(directory(id)).read() ?: error("Aucune sauvegarde valide pour ce projet.")
        val (meta, bytes) = readDocument(value.bytes, true)
        require(meta.id == id)
        val layers = decodeLayers(meta, bytes!!)
        val s = layers[meta.stages[meta.currentStep].id] ?: error("Masque du calque actif absent.")
        require(meta.stages.all { asset(meta, it.image).isFile && asset(meta, it.source).isFile }) {
            "Une image du projet est manquante."
        }
        val actual = s.mask.sumOf { it.toLong() }.toDouble() / (s.width.toLong() * s.height * 1000)
        val overall =
            layers.values.map { state ->
                state.mask.sumOf { it.toLong() }.toDouble() /
                    (state.width.toLong() * state.height * 1000)
            }.average().takeIf { !it.isNaN() } ?: 0.0
        return LoadedProject(
            meta.copy(progress = actual, overallProgress = overall),
            s,
            layers,
            value.recovered,
        )
    }

    fun save(project: Project, engine: EngineSnapshot) = save(project, migrateLegacy(project, engine))

    fun save(project: Project, layers: Map<String, EngineSnapshot>) {
        ProjectRules.validate(project.stages.size, project.width, project.height)
        require(project.stages.all { layers[it.id]?.let { s -> s.width == project.width && s.height == project.height && s.totalSteps == 2 } == true })
        val meta = project.json().toString().toByteArray(Charsets.UTF_8)
        val state = encodeLayers(project, layers)
        val out = ByteArrayOutputStream(meta.size + state.size + 8)
        DataOutputStream(out).use {
            it.writeInt(meta.size)
            it.write(meta)
            it.writeInt(state.size)
            it.write(state)
        }
        AtomicSlots(directory(project.id)).write(out.toByteArray())
    }

    private fun readDocument(data: ByteArray, withEngine: Boolean): Pair<Project, ByteArray?> {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            val n = input.readInt()
            require(n in 1..1_000_000)
            val meta = ByteArray(n)
            input.readFully(meta)
            val project = Project.fromJson(JSONObject(String(meta, Charsets.UTF_8)))
            if (!withEngine) return project to null
            val stateSize = input.readInt()
            require(stateSize in 1..256 * 1024 * 1024 && stateSize == input.available()) {
                "État de projet tronqué ou invalide."
            }
            val state = ByteArray(stateSize)
            input.readFully(state)
            require(input.read() == -1)
            return project to state
        }
    }

    private fun encodeLayers(project: Project, layers: Map<String, EngineSnapshot>): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(LAYER_MAGIC); data.writeInt(1); data.writeInt(project.stages.size)
                project.stages.forEach { layer ->
                    val id = layer.id.toByteArray(Charsets.UTF_8)
                    val state = EngineCodec.encode(layers.getValue(layer.id))
                    data.writeInt(id.size); data.write(id); data.writeInt(state.size); data.write(state)
                }
            }
        }.toByteArray()

    private fun decodeLayers(project: Project, bytes: ByteArray): Map<String, EngineSnapshot> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val marker = input.readInt()
        if (marker != LAYER_MAGIC) {
            return migrateLegacy(project, EngineCodec.decode(bytes))
        }
        require(input.readInt() == 1 && input.readInt() == project.stages.size)
        return buildMap {
            repeat(project.stages.size) {
                val idSize = input.readInt()
                require(idSize in 1..128 && idSize <= input.available())
                val id = ByteArray(idSize).also(input::readFully).toString(Charsets.UTF_8)
                val bodySize = input.readInt()
                require(bodySize in 1..128 * 1024 * 1024 && bodySize <= input.available())
                val body = ByteArray(bodySize).also(input::readFully)
                put(id, EngineCodec.decode(body))
            }
        }.also {
            require(input.read() == -1)
            require(it.keys == project.stages.map { s -> s.id }.toSet())
        }
    }

    private fun migrateLegacy(project: Project, legacy: EngineSnapshot) =
        migrateLegacyLayerSnapshots(project, legacy)

    fun rename(project: Project, name: String) {
        val p = load(project.id)
        save(
            p.project.copy(
                name = name.trim().ifEmpty { project.name },
                modified = System.currentTimeMillis(),
            ),
            p.layerEngines,
        )
    }

    fun duplicate(project: Project): Project {
        val p = load(project.id)
        val copy =
            p.project.copy(
                id = UUID.randomUUID().toString(),
                name = "${project.name} · copie",
                created = System.currentTimeMillis(),
                modified = System.currentTimeMillis(),
            )
        try {
            for (s in copy.stages)
                for (n in listOf(s.source, s.image, s.thumbnail))
                    asset(project, n).copyTo(asset(copy, n))
            listOfNotNull(copy.coverImage, copy.coverThumbnail).forEach { name ->
                asset(project, name).takeIf(File::isFile)?.copyTo(asset(copy, name))
            }
            save(copy, p.layerEngines)
        } catch (e: Exception) {
            directory(copy.id).deleteRecursively()
            throw e
        }
        return copy
    }

    fun delete(project: Project) {
        check(directory(project.id).deleteRecursively()) { "Suppression impossible." }
    }

    fun import(project: Project, uri: Uri, name: String): Stage {
        ProjectRules.validate(2, project.width, project.height)
        val id = UUID.randomUUID().toString()
        val source = asset(project, "$id.source")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        require(size <= 96L * 1024 * 1024) { "Image trop lourde (96 Mo maximum)." }
                        output.write(buf, 0, n)
                    }
                    output.fd.sync()
                }
            } ?: error("Impossible de lire l’image sélectionnée.")
            val dims = normalize(source, asset(project, "$id.png"), project.width, project.height)
            thumbnail(asset(project, "$id.png"), asset(project, "$id.thumb.png"))
            return Stage(id, name, source.name, "$id.png", "$id.thumb.png", dims.first, dims.second)
        } catch (e: Throwable) {
            listOf(source, asset(project, "$id.png"), asset(project, "$id.thumb.png")).forEach {
                it.delete()
            }
            if (e is OutOfMemoryError)
                throw IOException(
                    "Mémoire insuffisante pour cette image. Choisissez une toile plus petite.",
                    e,
                )
            throw e
        }
    }

    data class CoverAssets(val image: String, val thumbnail: String)

    /** Imports an independent cover without changing the canvas, mask, stage order or progression. */
    fun importCover(project: Project, uri: Uri): CoverAssets {
        val id = UUID.randomUUID().toString()
        val image = "$id.cover"
        val thumb = "$id.cover.thumb.png"
        val target = asset(project, image)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 96L * 1024 * 1024) {
                            "Image de couverture trop lourde (96 Mo maximum)."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("Impossible de lire l’image de couverture.")
            thumbnail(target, asset(project, thumb))
            return CoverAssets(image, thumb)
        } catch (e: Throwable) {
            target.delete()
            asset(project, thumb).delete()
            if (e is OutOfMemoryError)
                throw IOException("Mémoire insuffisante pour cette image de couverture.", e)
            throw e
        }
    }

    fun removeCoverAssets(project: Project) {
        listOfNotNull(project.coverImage, project.coverThumbnail).forEach { asset(project, it).delete() }
    }

    fun resizeStages(project: Project): Project =
        project.copy(
            stages =
                project.stages.map { s ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(asset(project, s.image).absolutePath, bounds)
                    if (bounds.outWidth == project.width && bounds.outHeight == project.height) {
                        return@map s
                    }
                    val id = UUID.randomUUID().toString()
                    normalize(
                        asset(project, s.source),
                        asset(project, "$id.png"),
                        project.width,
                        project.height,
                    )
                    thumbnail(asset(project, "$id.png"), asset(project, "$id.thumb.png"))
                    s.copy(image = "$id.png", thumbnail = "$id.thumb.png")
                }
        )

    private fun normalize(
        source: File,
        destination: File,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        var dims = 0 to 0
        val decoded =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                dims = info.size.width to info.size.height
                require(info.size.width > 0 && info.size.height > 0)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s =
                    minOf(
                        width.toFloat() / info.size.width,
                        height.toFloat() / info.size.height,
                        1f,
                    )
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * s).toInt()),
                    maxOf(1, (info.size.height * s).toInt()),
                )
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        var normalized: Bitmap? = null
        try {
            normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val s = minOf(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
            val w = decoded.width * s
            val h = decoded.height * s
            Canvas(normalized)
                .drawBitmap(
                    decoded,
                    null,
                    RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            FileOutputStream(destination).use {
                check(normalized.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
        } finally {
            decoded.recycle()
            normalized?.recycle()
        }
        return dims
    }

    private fun thumbnail(source: File, destination: File) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(1f, 320f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * s).toInt()),
                    maxOf(1, (info.size.height * s).toInt()),
                )
            }
        try {
            FileOutputStream(destination).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
