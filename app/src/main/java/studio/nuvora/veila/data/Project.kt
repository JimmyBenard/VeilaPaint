package studio.nuvora.veila.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import studio.nuvora.veila.core.*

data class Stage(
    val id: String,
    val name: String,
    val source: String,
    val image: String,
    val thumbnail: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** 0.4 names stages as independent reveal layers; the binary asset model remains compatible. */
typealias Layer = Stage

fun defaultLayerName(index: Int): String =
    when (index) {
        0 -> "Page blanche"
        1 -> "Croquis"
        2 -> "Croquis détaillé"
        3 -> "Colorisation 1er aplat"
        4 -> "Colorisation 2ème aplat"
        5 -> "Colorisation 3ème aplat"
        else -> "Calque ${index + 1}"
    }

enum class ProjectCoverMode {
    AUTOMATIC,
    STAGE,
    IMPORTED,
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Nouvelle révélation",
    val created: Long = System.currentTimeMillis(),
    /** Civil origin projected by the same simulated work clock as [workMillis]. */
    val simulatedEpochMillis: Long = created,
    val modified: Long = System.currentTimeMillis(),
    val width: Int = 1080,
    val height: Int = 1350,
    val stages: List<Stage> = emptyList(),
    val currentStep: Int = 0,
    val progress: Double = 0.0,
    val overallProgress: Double = progress,
    val workMillis: Long = 0,
    val workSpeed: Float = 2f,
    val brush: Brush = Brush(),
    /** Local reveal mask eraser; source images and other layers are never changed. */
    val eraserEnabled: Boolean = false,
    /** Persistent display-only palette selection. */
    val paletteHue: Float = 285f,
    val paletteSaturation: Float = .75f,
    val paletteLight: Float = .85f,
    val camera: Camera = Camera(width / 2f, height / 2f),
    val mouseEnabled: Boolean = false,
    /** A single finger uses the same reveal brush as the stylus when enabled. */
    val fingerDrawingEnabled: Boolean = true,
    val videoHeight: Int = 720,
    val videoFps: Int = 30,
    val videoWithUi: Boolean = true,
    val coverMode: ProjectCoverMode = ProjectCoverMode.AUTOMATIC,
    val coverStageId: String? = null,
    val coverImage: String? = null,
    val coverThumbnail: String? = null,
) {
    val planWidth get() = width
    val planHeight get() = height
    val layers get() = stages
    val activeLayer get() = currentStep
    val overall get() = overallProgress.coerceIn(0.0, 1.0)

    fun json() =
        JSONObject().apply {
            put("schema", 6)
            put("id", id)
            put("name", name)
            put("created", created)
            put("simulatedEpochMillis", simulatedEpochMillis)
            put("modified", modified)
            put("width", width)
            put("height", height)
            put("currentStep", currentStep)
            put("progress", progress)
            put("overallProgress", overallProgress)
            put("workMillis", workMillis)
            put("workSpeed", workSpeed)
            put("eraserEnabled", eraserEnabled)
            put("paletteHue", paletteHue)
            put("paletteSaturation", paletteSaturation)
            put("paletteLight", paletteLight)
            put("mouseEnabled", mouseEnabled)
            put("fingerDrawingEnabled", fingerDrawingEnabled)
            put("videoHeight", videoHeight)
            put("videoFps", videoFps)
            put("videoWithUi", videoWithUi)
            put("coverMode", coverMode.name)
            put("coverStageId", coverStageId)
            put("coverImage", coverImage)
            put("coverThumbnail", coverThumbnail)
            put(
                "brush",
                JSONObject()
                    .put("size", brush.size)
                    .put("intensity", brush.intensity)
                    .put("hardness", brush.hardness)
                    .put("pressureSize", brush.pressureSize)
                    .put("pressureIntensity", brush.pressureIntensity)
                    .put("sideButtonEraser", brush.sideButtonEraser)
                    .put("directionalReveal", brush.directionalReveal)
                    .put("directionalStrength", brush.directionalStrength.name),
            )
            put(
                "camera",
                JSONObject()
                    .put("x", camera.centerX)
                    .put("y", camera.centerY)
                    .put("zoom", camera.zoom)
                    .put("rotation", camera.rotation),
            )
            put(
                "stages",
                JSONArray().apply {
                    stages.forEach { s ->
                        put(
                            JSONObject()
                                .put("id", s.id)
                                .put("name", s.name)
                                .put("source", s.source)
                                .put("image", s.image)
                                .put("thumbnail", s.thumbnail)
                                .put("sourceWidth", s.sourceWidth)
                                .put("sourceHeight", s.sourceHeight)
                        )
                    }
                },
            )
        }

    companion object {
        fun fromJson(j: JSONObject): Project {
            require(j.getInt("schema") in 1..6)
            val b = j.getJSONObject("brush")
            val c = j.getJSONObject("camera")
            val list = j.getJSONArray("stages")
            val w = j.getInt("width")
            val h = j.getInt("height")
            ProjectRules.validate(list.length(), w, h)
            val id = j.getString("id")
            UUID.fromString(id)
            val stages =
                List(list.length()) { i ->
                    val s = list.getJSONObject(i)
                    fun path(k: String) =
                        s.getString(k).also { require(it.matches(Regex("[A-Za-z0-9._-]+"))) }
                    Stage(
                        s.getString("id"),
                        s.getString("name"),
                        path("source"),
                        path("image"),
                        path("thumbnail"),
                        s.getInt("sourceWidth"),
                        s.getInt("sourceHeight"),
                    )
                }
            val step = j.getInt("currentStep")
            require(step in stages.indices)
            val cam =
                Camera(
                    c.getDouble("x").toFloat(),
                    c.getDouble("y").toFloat(),
                    c.getDouble("zoom").toFloat(),
                    c.optDouble("rotation", 0.0).toFloat(),
                )
            require(cam.rotation.isFinite())
            cam.rotation = Camera.normalizeAngle(cam.rotation)
            require(cam.centerX.isFinite() && cam.centerY.isFinite() && cam.zoom in .25f..24f)
            fun optionalPath(key: String) =
                if (j.has(key) && !j.isNull(key)) {
                    j.getString(key).also { require(it.matches(Regex("[A-Za-z0-9._-]+"))) }
                } else null
            val requestedMode =
                runCatching { ProjectCoverMode.valueOf(j.optString("coverMode", "AUTOMATIC")) }
                    .getOrDefault(ProjectCoverMode.AUTOMATIC)
            val requestedStage =
                if (j.has("coverStageId") && !j.isNull("coverStageId")) j.getString("coverStageId")
                else null
            val normalizedMode =
                when {
                    requestedMode == ProjectCoverMode.STAGE &&
                        stages.none { it.id == requestedStage } -> ProjectCoverMode.AUTOMATIC
                    requestedMode == ProjectCoverMode.IMPORTED &&
                        (optionalPath("coverImage") == null || optionalPath("coverThumbnail") == null) ->
                        ProjectCoverMode.AUTOMATIC
                    else -> requestedMode
                }
            val progress = j.getDouble("progress").coerceIn(0.0, 1.0)
            val schema = j.getInt("schema")
            val legacyOverall =
                if (stages.size < 2) 0.0
                else (step + if (step == stages.lastIndex) 0.0 else progress) / stages.lastIndex
            return Project(
                id = id,
                name = j.getString("name"),
                created = j.getLong("created"),
                simulatedEpochMillis = j.optLong("simulatedEpochMillis", j.getLong("created")),
                modified = j.getLong("modified"),
                width = w,
                height = h,
                stages = stages,
                currentStep = step,
                progress = progress,
                overallProgress =
                    j.optDouble("overallProgress", if (schema < 3) legacyOverall else progress)
                        .coerceIn(0.0, 1.0),
                workMillis = j.getLong("workMillis").coerceAtLeast(0),
                workSpeed = j.optDouble("workSpeed", 1.0).toFloat().coerceIn(.5f, 5f),
                eraserEnabled = j.optBoolean("eraserEnabled", false),
                paletteHue = j.optDouble("paletteHue", 285.0).toFloat().coerceIn(0f, 360f),
                paletteSaturation = j.optDouble("paletteSaturation", .75).toFloat().coerceIn(0f, 1f),
                paletteLight = j.optDouble("paletteLight", .85).toFloat().coerceIn(0f, 1f),
                brush = Brush(
                    b.getDouble("size").toFloat(),
                    b.getDouble("intensity").toFloat(),
                    b.getDouble("hardness").toFloat(),
                    b.getBoolean("pressureSize"),
                    b.getBoolean("pressureIntensity"),
                    b.optBoolean("sideButtonEraser", false),
                    // Existing projects preserve their classic brush unless the user opts in.
                    b.optBoolean("directionalReveal", false),
                    runCatching {
                        DirectionalStrength.valueOf(b.optString("directionalStrength", "MODERATE"))
                    }.getOrDefault(DirectionalStrength.MODERATE),
                ),
                camera = cam,
                mouseEnabled = j.optBoolean("mouseEnabled"),
                fingerDrawingEnabled = j.optBoolean("fingerDrawingEnabled", true),
                videoHeight = j.optInt("videoHeight", 720),
                videoFps = j.optInt("videoFps", 30),
                videoWithUi = j.optBoolean("videoWithUi"),
                coverMode = normalizedMode,
                coverStageId = requestedStage?.takeIf { normalizedMode == ProjectCoverMode.STAGE },
                coverImage = optionalPath("coverImage")?.takeIf {
                    normalizedMode == ProjectCoverMode.IMPORTED
                },
                coverThumbnail = optionalPath("coverThumbnail")?.takeIf {
                    normalizedMode == ProjectCoverMode.IMPORTED
                },
            )
        }
    }
}

data class LoadedProject(
    val project: Project,
    val engine: EngineSnapshot,
    val layerEngines: Map<String, EngineSnapshot> = migrateLegacyLayerSnapshots(project, engine),
    val recovered: Boolean = false,
)

/** Empty, independent reveal state used by every new 0.4 layer. */
fun emptyLayerSnapshot(width: Int, height: Int): EngineSnapshot =
    EngineSnapshot(width, height, 2, 0, ShortArray(width * height), emptyList(), emptyList())

/**
 * Converts the sequential 0.3 mask into independent 0.4 layer masks.
 * Finished earlier stages become fully revealed, the former current stage keeps its exact mask,
 * and future stages remain hidden. The final legacy image is represented as a fully revealed
 * final layer because the old engine did not need a separate mask once it reached that image.
 */
fun migrateLegacyLayerSnapshots(
    project: Project,
    legacy: EngineSnapshot,
): Map<String, EngineSnapshot> {
    require(legacy.width == project.width && legacy.height == project.height)
    val lastLegacyStage = legacy.step == legacy.totalSteps - 1
    return project.stages.mapIndexed { index, layer ->
        val mask =
            when {
                index < legacy.step -> ShortArray(project.width * project.height) { 1000 }
                index == legacy.step && lastLegacyStage ->
                    ShortArray(project.width * project.height) { 1000 }
                index == legacy.step -> legacy.mask.copyOf()
                else -> ShortArray(project.width * project.height)
            }
        layer.id to EngineSnapshot(project.width, project.height, 2, 0, mask, emptyList(), emptyList())
    }.toMap()
}

/** Nearest-neighbour mask migration when the shared work area changes dimensions. */
fun resizeLayerSnapshot(source: EngineSnapshot, width: Int, height: Int): EngineSnapshot {
    if (source.width == width && source.height == height && source.totalSteps == 2) return source
    val resized = ShortArray(width * height)
    for (y in 0 until height) {
        val sy = ((y.toLong() * source.height) / height).toInt().coerceAtMost(source.height - 1)
        for (x in 0 until width) {
            val sx = ((x.toLong() * source.width) / width).toInt().coerceAtMost(source.width - 1)
            resized[y * width + x] = source.mask[sy * source.width + sx]
        }
    }
    // Tile diffs are dimension-dependent, so only history is intentionally reset on resize.
    return EngineSnapshot(width, height, 2, 0, resized, emptyList(), emptyList())
}
