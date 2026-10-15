package studio.nuvora.veila.studio

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import studio.nuvora.veila.core.DirectionalGuide
import studio.nuvora.veila.core.TraitIsolationMap

data class BrushAssistanceMaps(
    val directionalGuide: DirectionalGuide,
    val traitIsolationMap: TraitIsolationMap,
)

/**
 * Bounded local contour analysis. It is deliberately advisory: weak, flat, or ambiguous regions
 * receive low confidence and therefore fall back to the ordinary reveal brush.
 */
object DirectionalGuideAnalyzer {
    fun build(target: Bitmap, previousComposite: Bitmap): DirectionalGuide =
        buildMaps(target, previousComposite).directionalGuide

    /** One bounded cache shared by directional reveal and individual contour isolation. */
    fun buildMaps(target: Bitmap, previousComposite: Bitmap): BrushAssistanceMaps {
        val width = target.width
        val height = target.height
        val cell = if (width.toLong() * height > 3_000_000L) 4 else 2
        val columns = (width + cell - 1) / cell
        val rows = (height + cell - 1) / cell
        val tx = FloatArray(columns * rows)
        val ty = FloatArray(columns * rows)
        val confidence = ByteArray(columns * rows)
        val targetPixels = IntArray(width * height)
        val previousPixels = IntArray(width * height)
        target.getPixels(targetPixels, 0, width, 0, 0, width, height)
        previousComposite.getPixels(previousPixels, 0, width, 0, 0, width, height)
        fun signal(x: Int, y: Int): Float {
            val i = y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)
            val a = targetPixels[i]
            val b = previousPixels[i]
            fun light(p: Int) =
                (Color.red(p) * .2126f + Color.green(p) * .7152f + Color.blue(p) * .0722f) *
                    Color.alpha(p) / 255f
            // Difference gives priority to what this target contributes; target light keeps
            // ordinary line-art usable when the preceding composite is transparent.
            return abs(light(a) - light(b)) * .72f + light(a) * .28f
        }
        for (gy in 0 until rows) for (gxCell in 0 until columns) {
            val x = (gxCell * cell + cell / 2).coerceAtMost(width - 1)
            val y = (gy * cell + cell / 2).coerceAtMost(height - 1)
            val dx = signal(x + cell, y) - signal(x - cell, y)
            val dy = signal(x, y + cell) - signal(x, y - cell)
            val magnitude = hypot(dx, dy)
            val i = gy * columns + gxCell
            if (magnitude < 8f) continue
            // A contour follows the tangent, perpendicular to the local luminance gradient.
            tx[i] = -dy / magnitude
            ty[i] = dx / magnitude
            confidence[i] = ((magnitude / 96f).coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        val labels = segmentContours(columns, rows, tx, ty, confidence)
        return BrushAssistanceMaps(
            DirectionalGuide(width, height, cell, tx, ty, confidence),
            TraitIsolationMap(width, height, cell, labels, tx, ty, confidence),
        )
    }

    /**
     * Components only join compatible tangents. This prevents a perpendicular crossing from
     * becoming one global trait; flattened ambiguous pixels remain a safe local fallback.
     */
    private fun segmentContours(
        columns: Int,
        rows: Int,
        tx: FloatArray,
        ty: FloatArray,
        confidence: ByteArray,
    ): IntArray {
        val labels = IntArray(columns * rows)
        val visited = BooleanArray(labels.size)
        val queue = ArrayDeque<Int>()
        var nextId = 1
        fun usable(index: Int) = (confidence[index].toInt() and 0xff) >= 52
        for (start in labels.indices) {
            if (visited[start] || !usable(start)) continue
            val component = ArrayList<Int>()
            visited[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                component += i
                val x = i % columns
                val y = i / columns
                for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(rows - 1)) {
                    for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(columns - 1)) {
                        val candidate = ny * columns + nx
                        if (candidate == i || visited[candidate] || !usable(candidate)) continue
                        val compatible = abs(tx[i] * tx[candidate] + ty[i] * ty[candidate])
                        if (compatible < .72f) continue
                        visited[candidate] = true
                        queue.addLast(candidate)
                    }
                }
            }
            // A single sampled cell is too uncertain to isolate as a trait.
            if (component.size < 2) continue
            component.forEach { labels[it] = nextId }
            nextId++
        }
        return labels
    }
}
