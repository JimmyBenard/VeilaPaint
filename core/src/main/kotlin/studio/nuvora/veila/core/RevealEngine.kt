package studio.nuvora.veila.core

import java.io.*
import java.util.zip.*
import kotlin.math.*

data class TileDiff(val id: Int, val xor: ByteArray)

data class Edit(val beforeStep: Int, val afterStep: Int, val tiles: List<TileDiff>) {
    val bytes
        get() = tiles.sumOf { it.xor.size + 8 }
}

data class EngineSnapshot(
    val width: Int,
    val height: Int,
    val totalSteps: Int,
    val step: Int,
    val mask: ShortArray,
    val undo: List<Edit>,
    val redo: List<Edit>,
)

/** Single-writer deterministic engine. Each stroke takes the maximum coverage per pixel. */
class RevealEngine(
    val width: Int,
    val height: Int,
    val totalSteps: Int,
    restored: EngineSnapshot? = null,
    private val autoAdvance: Boolean = true,
) {
    var step = restored?.step ?: 0
        private set

    val mask = MaskGrid(width, height, restored?.mask)
    private val past = ArrayDeque<Edit>()
    private val future = ArrayDeque<Edit>()
    private var baseline: MutableMap<Int, ShortArray>? = null
    private var previous: Point? = null
    private var erase = false
    private var brush = Brush()
    private var allowCompletionRollback = false
    private var directionalGuide: DirectionalGuide? = null
    private var lastDirection: Point? = null
    private var followedTangent: Point? = null
    private data class CompletionRollback(val edit: Edit, val past: List<Edit>, val future: List<Edit>)
    private var completionRollback: CompletionRollback? = null
    var revision = 0L
        private set

    init {
        require(totalSteps in 2..20 && step in 0 until totalSteps)
        restored?.let {
            require(it.width == width && it.height == height && it.totalSteps == totalSteps)
            past.addAll(it.undo)
            future.addAll(it.redo)
        }
    }

    val finished
        get() = autoAdvance && step == totalSteps - 1

    val inStroke
        get() = baseline != null || completionRollback != null

    val canUndo
        get() = past.isNotEmpty()

    val canRedo
        get() = future.isNotEmpty()

    val progress
        get() = if (finished) 1.0 else mask.fraction

    val overallProgress
        get() = (step + if (finished) 0.0 else mask.fraction) / (totalSteps - 1)

    fun begin(
        point: Point,
        pressure: Float,
        settings: Brush,
        erasing: Boolean = false,
        cancellableCompletion: Boolean = false,
    ) {
        if (finished || inStroke) return
        baseline = linkedMapOf()
        previous = null
        brush = settings
        erase = erasing
        lastDirection = null
        followedTangent = null
        allowCompletionRollback = cancellableCompletion
        move(point, pressure)
    }

    fun move(point: Point, pressure: Float) {
        if (baseline == null || !point.x.isFinite() || !point.y.isFinite()) return
        val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f
        val radius = max(.5f, brush.size * .5f * if (brush.pressureSize) max(.05f, p) else 1f)
        val strength = brush.intensity * if (brush.pressureIntensity) p else 1f
        val delta = previous?.let { Point(point.x - it.x, point.y - it.y) }
        val length = delta?.let { hypot(it.x, it.y) } ?: 0f
        val direction =
            if (length >= 1.25f) Point(delta!!.x / length, delta.y / length).also { lastDirection = it }
            else lastDirection
        capsule(previous ?: point, point, radius, strength, direction)
        if (!erase && brush.directionalReveal && direction != null)
            directionalGuide?.tangentAt(point)?.let { followedTangent = it }
        // Exact synchronous handoff: no scheduler, rounded threshold or animation.
        if (mask.complete && autoAdvance) {
            finish(allowCompletionRollback)
            return
        }
        previous = point
    }

    fun setDirectionalGuide(guide: DirectionalGuide?) {
        if (!inStroke) directionalGuide = guide
    }

    private fun capsule(a: Point, b: Point, radius: Float, strength: Float, direction: Point?) {
        val saved = baseline ?: return
        val left = floor(min(a.x, b.x) - radius).toInt().coerceAtLeast(0)
        val right = ceil(max(a.x, b.x) + radius).toInt().coerceAtMost(width - 1)
        val top = floor(min(a.y, b.y) - radius).toInt().coerceAtLeast(0)
        val bottom = ceil(max(a.y, b.y) + radius).toInt().coerceAtMost(height - 1)
        if (left > right || top > bottom) return
        val r2 = radius * radius
        val vx = b.x - a.x
        val vy = b.y - a.y
        val length2 = vx * vx + vy * vy
        for (ty in top / 64..bottom / 64) for (tx in left / 64..right / 64) {
            val id = ty * mask.columns + tx
            val original = saved.getOrPut(id) { mask.copyTile(id) }
            val (ox, oy) = mask.tileOrigin(id)
            val tw = mask.tileWidth(id)
            for (y in max(top, oy)..min(bottom, oy + mask.tileHeight(id) - 1)) for (x in
                max(left, ox)..min(right, ox + tw - 1)) {
                val current = mask.get(x, y)
                if ((!erase && current == 1000) || (erase && current == 0)) continue
                val px = x + .5f - a.x
                val py = y + .5f - a.y
                val t =
                    if (length2 > 0f && length2.isFinite())
                        ((px * vx + py * vy) / length2).coerceIn(0f, 1f)
                    else 0f
                val dx = px - vx * t
                val dy = py - vy * t
                val d2 = dx * dx + dy * dy
                if (d2 > r2) continue
                val n = if (brush.hardness == 1f) 0f else sqrt(d2) / radius
                val coverage =
                    if (n <= brush.hardness) 1f
                    else ((1f - n) / (1f - brush.hardness)).coerceIn(0f, 1f)
                val assistance =
                    if (!erase && brush.directionalReveal)
                        directionalGuide?.weight(x, y, direction, followedTangent, brush.directionalStrength) ?: 1f
                    else 1f
                val amount = (1000 * strength * coverage * assistance).roundToInt()
                if (amount == 0) continue
                val base = original[(y - oy) * tw + x - ox].toInt()
                val value =
                    if (erase) min(current, (base - amount).coerceAtLeast(0))
                    else max(current, (base + amount).coerceAtMost(1000))
                mask.set(x, y, value)
            }
        }
    }

    fun end(): Boolean {
        allowCompletionRollback = false
        if (completionRollback != null) {
            completionRollback = null
            revision++
            return true
        }
        return finish(false)
    }

    private fun finish(keepRollback: Boolean): Boolean {
        val saved = baseline ?: return false
        baseline = null
        previous = null
        lastDirection = null
        followedTangent = null
        val diffs =
            saved.mapNotNull { (id, before) ->
                val after = mask.copyTile(id)
                if (before.contentEquals(after)) null else TileDiff(id, compressXor(before, after))
            }
        if (diffs.isEmpty()) return false
        val old = step
        if (mask.complete && autoAdvance) {
            step++
            mask.fill(0)
        }
        val edit = Edit(old, step, diffs)
        if (keepRollback) {
            // Only references to existing compressed edits are copied, never a full mask.
            // The step is already promoted visually; a growing touch gesture can still cancel it.
            completionRollback = CompletionRollback(edit, past.toList(), future.toList())
        }
        past.addLast(edit)
        future.clear()
        var bytes = past.sumOf { it.bytes }
        while (past.size > 1 && (bytes > 24 * 1024 * 1024 || past.size > 150)) bytes -=
            past.removeFirst().bytes
        revision++
        return true
    }

    fun cancel() {
        completionRollback?.let { saved ->
            step = saved.edit.beforeStep
            mask.fill(1000)
            toggle(saved.edit)
            past.clear()
            past.addAll(saved.past)
            future.clear()
            future.addAll(saved.future)
            revision++
        }
        completionRollback = null
        allowCompletionRollback = false
        baseline?.forEach { (id, data) -> mask.replaceTile(id, data) }
        baseline = null
        previous = null
        lastDirection = null
        followedTangent = null
    }

    fun undo(): Boolean {
        cancel()
        val edit = past.removeLastOrNull() ?: return false
        if (autoAdvance && edit.beforeStep != edit.afterStep) {
            step = edit.beforeStep
            mask.fill(1000)
        }
        toggle(edit)
        future.addLast(edit)
        revision++
        return true
    }

    fun redo(): Boolean {
        cancel()
        val edit = future.removeLastOrNull() ?: return false
        toggle(edit)
        if (autoAdvance && edit.beforeStep != edit.afterStep) {
            step = edit.afterStep
            mask.fill(0)
        }
        past.addLast(edit)
        revision++
        return true
    }

    private fun toggle(edit: Edit) {
        for (tile in edit.tiles) {
            val data = mask.copyTile(tile.id)
            DataInputStream(InflaterInputStream(ByteArrayInputStream(tile.xor))).use { input ->
                for (i in data.indices) data[i] =
                    (data[i].toInt() xor input.readUnsignedShort()).toShort()
                require(input.read() == -1)
            }
            mask.replaceTile(tile.id, data)
        }
    }

    fun snapshot(): EngineSnapshot {
        check(!inStroke)
        return EngineSnapshot(
            width,
            height,
            totalSteps,
            step,
            mask.copyValues(),
            past.toList(),
            future.toList(),
        )
    }

    /** Re-hides the active layer without ever changing source pixels. */
    fun reset(): Boolean {
        cancel()
        if (mask.fraction == 0.0) return false
        val before = mask.copyValues()
        mask.fill(0)
        val tiles = (0 until mask.columns * mask.rows).mapNotNull { id ->
            val after = mask.copyTile(id)
            val (ox, oy) = mask.tileOrigin(id)
            val tw = mask.tileWidth(id)
            val th = mask.tileHeight(id)
            val old = ShortArray(tw * th)
            for (y in 0 until th) for (x in 0 until tw) old[y * tw + x] = before[(oy + y) * width + ox + x]
            if (old.contentEquals(after)) null else TileDiff(id, compressXor(old, after))
        }
        if (tiles.isEmpty()) return false
        past.addLast(Edit(step, step, tiles))
        future.clear()
        revision++
        return true
    }

    companion object {
        private fun compressXor(a: ShortArray, b: ShortArray): ByteArray {
            val out = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_SPEED)
            DataOutputStream(DeflaterOutputStream(out, deflater)).use { stream ->
                for (i in a.indices) stream.writeShort(a[i].toInt() xor b[i].toInt())
            }
            deflater.end()
            return out.toByteArray()
        }
    }
}
