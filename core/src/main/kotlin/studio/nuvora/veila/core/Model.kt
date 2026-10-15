package studio.nuvora.veila.core

import kotlin.math.*

object ProjectRules {
    const val MAX_PIXELS = 8_388_608L

    fun validate(count: Int, width: Int, height: Int) {
        require(count in 2..20) { "Un projet doit contenir de 2 à 20 calques." }
        require(width in 16..8192 && height in 16..8192 && width.toLong() * height <= MAX_PIXELS) {
            "Toile : 16 à 8192 px par côté, 8 mégapixels maximum (4K UHD acceptée)."
        }
    }
}

data class Brush(
    val size: Float = 10f,
    val intensity: Float = .5f,
    val hardness: Float = .8f,
    val pressureSize: Boolean = true,
    val pressureIntensity: Boolean = true,
    val sideButtonEraser: Boolean = false,
    /** Local mask assistance. Old projects opt out during JSON migration. */
    val directionalReveal: Boolean = true,
    val directionalStrength: DirectionalStrength = DirectionalStrength.MODERATE,
    /** Keeps the reveal footprint on the locally selected contour when a map is available. */
    val strokeIsolation: Boolean = false,
    /** In strict mode ambiguous or neighbouring contours receive no contribution. */
    val strictStrokeIsolation: Boolean = false,
) {
    init {
        require(size.isFinite() && size in 1f..1600f)
        require(intensity.isFinite() && intensity in .01f..1f)
        require(hardness.isFinite() && hardness in 0f..1f)
    }
}

enum class DirectionalStrength(val mismatchFloor: Float, val exponent: Float) {
    LOW(.55f, .7f),
    MODERATE(.28f, 1.15f),
    HIGH(.10f, 1.8f),
}

/**
 * Sparse, image-local assistance map. It does not contain drawing data and can always be
 * discarded: the reveal mask remains the sole persisted source of truth.
 */
class DirectionalGuide(
    val width: Int,
    val height: Int,
    val cell: Int,
    private val tangentX: FloatArray,
    private val tangentY: FloatArray,
    private val confidenceValues: ByteArray,
) {
    private val columns = (width + cell - 1) / cell
    private fun index(x: Int, y: Int) =
        (y.coerceIn(0, height - 1) / cell) * columns + (x.coerceIn(0, width - 1) / cell)

    fun tangentAt(point: Point): Point? {
        val i = index(point.x.toInt(), point.y.toInt())
        if ((confidenceValues[i].toInt() and 0xff) < 28) return null
        return Point(tangentX[i], tangentY[i])
    }

    fun weight(x: Int, y: Int, direction: Point?, followed: Point?, strength: DirectionalStrength): Float {
        if (direction == null) return 1f
        val i = index(x, y)
        val confidence = (confidenceValues[i].toInt() and 0xff) / 255f
        if (confidence < .18f) return 1f
        val angular = kotlin.math.abs(direction.x * tangentX[i] + direction.y * tangentY[i])
            .coerceIn(0f, 1f)
        val continuity =
            followed?.let { kotlin.math.abs(it.x * tangentX[i] + it.y * tangentY[i]) } ?: 1f
        val compatible = (angular * .78f + continuity.coerceIn(0f, 1f) * .22f)
        val selective = strength.mismatchFloor + (1f - strength.mismatchFloor) *
            compatible.coerceIn(0f, 1f).pow(strength.exponent)
        // Confidence only removes assistance; uncertain regions remain a conventional brush.
        return 1f - confidence * (1f - selective)
    }
}

/**
 * Cached, image-local contour segmentation used by the ordinary reveal brush.  Labels are
 * advisory and are never saved as drawing data: the mask remains the only source of truth.
 * A non-positive label denotes a region where no reliable individual contour was identified.
 */
class TraitIsolationMap(
    val width: Int,
    val height: Int,
    val cell: Int,
    private val labels: IntArray,
    private val tangentX: FloatArray,
    private val tangentY: FloatArray,
    private val confidenceValues: ByteArray,
) {
    data class Candidate(
        val id: Int,
        val score: Float,
        val distance: Float,
        val tangent: Point,
        val confidence: Float,
    )

    private val columns = (width + cell - 1) / cell
    private val rows = (height + cell - 1) / cell

    init {
        require(labels.size == columns * rows)
        require(tangentX.size == labels.size && tangentY.size == labels.size)
        require(confidenceValues.size == labels.size)
    }

    private fun index(x: Int, y: Int) =
        (y.coerceIn(0, height - 1) / cell) * columns + (x.coerceIn(0, width - 1) / cell)

    /**
     * Finds a nearby labelled contour.  Direction is orientation-only, so both drawing senses
     * remain equivalent.  The followed id only provides a small hysteresis, never a hard lock.
     */
    fun select(point: Point, direction: Point?, followedId: Int?, radius: Float): Candidate? =
        find(point, direction, followedId, radius, null)

    fun candidateFor(id: Int, point: Point, direction: Point?, radius: Float): Candidate? =
        find(point, direction, id, radius, id)

    private fun find(
        point: Point,
        direction: Point?,
        followedId: Int?,
        radius: Float,
        onlyId: Int?,
    ): Candidate? {
        val cx = (point.x.toInt().coerceIn(0, width - 1) / cell)
        val cy = (point.y.toInt().coerceIn(0, height - 1) / cell)
        val scan = (ceil((radius.coerceAtLeast(cell.toFloat()) * 1.25f) / cell).toInt() + 1)
            .coerceIn(2, 12)
        var best: Candidate? = null
        for (y in (cy - scan).coerceAtLeast(0)..(cy + scan).coerceAtMost(rows - 1)) {
            for (x in (cx - scan).coerceAtLeast(0)..(cx + scan).coerceAtMost(columns - 1)) {
                val i = y * columns + x
                val id = labels[i]
                if (id <= 0 || (onlyId != null && id != onlyId)) continue
                val confidence = (confidenceValues[i].toInt() and 0xff) / 255f
                if (confidence < .20f) continue
                val dx = (x * cell + cell * .5f) - point.x
                val dy = (y * cell + cell * .5f) - point.y
                val distance = hypot(dx, dy)
                val proximity = (1f - distance / ((scan + .5f) * cell)).coerceIn(0f, 1f)
                val angular = direction?.let {
                    abs(it.x * tangentX[i] + it.y * tangentY[i]).coerceIn(0f, 1f)
                } ?: 1f
                val continuity = if (id == followedId) .16f else 0f
                val score = confidence * (.58f * proximity + .42f * angular) + continuity
                val candidate = Candidate(
                    id,
                    score,
                    distance,
                    Point(tangentX[i], tangentY[i]),
                    confidence,
                )
                if (best == null || candidate.score > best!!.score) best = candidate
            }
        }
        return best
    }

    /** Local contribution only; no query can reveal a location outside the brush footprint. */
    fun weight(x: Int, y: Int, selectedId: Int?, strict: Boolean, strength: DirectionalStrength): Float {
        if (selectedId == null) return if (strict) 0f else 1f
        val i = index(x, y)
        val label = labels[i]
        if (label == selectedId) return 1f
        if (label <= 0) return if (strict) 0f else 1f
        if (strict) return 0f
        // A neighbouring identified contour is attenuated, never selected automatically.
        return strength.mismatchFloor.coerceIn(.05f, .65f)
    }
}

data class Point(val x: Float, val y: Float)

/** Camera centre and rotation stay in intrinsic image coordinates across orientation changes. */
data class Camera(
    var centerX: Float,
    var centerY: Float,
    var zoom: Float = 1f,
    var rotation: Float = 0f,
) {
    fun scale(vw: Int, vh: Int, iw: Int, ih: Int) =
        min(vw.toFloat() / iw, vh.toFloat() / ih).coerceAtLeast(.0001f) * zoom

    fun imagePoint(x: Float, y: Float, vw: Int, vh: Int, iw: Int, ih: Int): Point {
        val s = scale(vw, vh, iw, ih)
        val delta = rotate(Point((x - vw / 2f) / s, (y - vh / 2f) / s), -rotation)
        return Point(delta.x + centerX, delta.y + centerY)
    }

    fun screenPoint(p: Point, vw: Int, vh: Int, iw: Int, ih: Int): Point {
        val delta = rotate(Point(p.x - centerX, p.y - centerY), rotation)
        val s = scale(vw, vh, iw, ih)
        return Point(delta.x * s + vw / 2f, delta.y * s + vh / 2f)
    }

    fun transform(
        from: Point,
        to: Point,
        ratio: Float,
        vw: Int,
        vh: Int,
        iw: Int,
        ih: Int,
        angle: Float = 0f,
    ) {
        if (
            !ratio.isFinite() ||
                ratio <= 0 ||
                !angle.isFinite() ||
                !from.x.isFinite() ||
                !from.y.isFinite() ||
                !to.x.isFinite() ||
                !to.y.isFinite()
        )
            return
        val anchor = imagePoint(from.x, from.y, vw, vh, iw, ih)
        zoom = (zoom * ratio).coerceIn(.25f, 24f)
        rotation = normalizeAngle(rotation + angle)
        val s = scale(vw, vh, iw, ih)
        val delta = rotate(Point((to.x - vw / 2f) / s, (to.y - vh / 2f) / s), -rotation)
        centerX = (anchor.x - delta.x).coerceIn(-iw.toFloat(), iw * 2f)
        centerY = (anchor.y - delta.y).coerceIn(-ih.toFloat(), ih * 2f)
    }

    private fun rotate(p: Point, angle: Float) =
        Point(cos(angle) * p.x - sin(angle) * p.y, sin(angle) * p.x + cos(angle) * p.y)

    companion object {
        /** Radians, clockwise on the screen. The mask never moves. */
        fun normalizeAngle(angle: Float): Float = atan2(sin(angle), cos(angle))
    }
}

class WorkClock(initialMillis: Long = 0, private val now: () -> Long, speed: Float = 1f) {
    /** Keeps the project-0.4 construction syntax source-compatible. */
    constructor(initialMillis: Long = 0, now: () -> Long) : this(initialMillis, now, 1f)

    private var accumulated = initialMillis.coerceAtLeast(0)
    private var since: Long? = null
    var speed = speed.coerceIn(.5f, 5f)
        private set
    val running
        get() = since != null

    fun resume() {
        if (since == null) since = now()
    }

    fun pause() {
        since?.let { accumulated += elapsed(it) }
        since = null
    }

    /** Commits elapsed simulated time before changing scale, so paused time is never backfilled. */
    fun setSpeed(value: Float) {
        val next = value.coerceIn(.5f, 5f)
        if (next == speed) return
        since?.let { start ->
            accumulated += elapsed(start)
            since = now()
        }
        speed = next
    }

    fun millis() = accumulated + (since?.let(::elapsed) ?: 0L)

    /** Starts a new simulated work duration without changing speed or pause state. */
    fun reset() {
        accumulated = 0L
        if (since != null) since = now()
    }

    /** Projects a civil start instant on the same simulated monotonic timeline. */
    fun epochMillis(originEpochMillis: Long): Long = originEpochMillis + millis()

    private fun elapsed(start: Long) =
        ((now() - start).coerceAtLeast(0).toDouble() * speed).roundToLong()
}

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPING,
}

class RecordingLifecycle {
    @Volatile
    var state = RecordingState.IDLE
        private set

    fun start() {
        check(state == RecordingState.IDLE)
        state = RecordingState.RECORDING
    }

    fun pause() {
        check(state == RecordingState.RECORDING)
        state = RecordingState.PAUSED
    }

    fun resume() {
        check(state == RecordingState.PAUSED)
        state = RecordingState.RECORDING
    }

    fun stop() {
        check(state == RecordingState.RECORDING || state == RecordingState.PAUSED)
        state = RecordingState.STOPPING
    }

    fun finalized() {
        state = RecordingState.IDLE
    }
}
