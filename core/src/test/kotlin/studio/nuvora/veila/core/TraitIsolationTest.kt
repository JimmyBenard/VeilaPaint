package studio.nuvora.veila.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraitIsolationTest {
    private fun map(width: Int, height: Int, labels: IntArray): TraitIsolationMap =
        TraitIsolationMap(
            width,
            height,
            1,
            labels,
            FloatArray(width * height) { 1f },
            FloatArray(width * height),
            ByteArray(width * height) { 0xff.toByte() },
        )

    private fun brush(strict: Boolean = true) =
        Brush(
            size = 6f,
            intensity = 1f,
            hardness = 1f,
            directionalReveal = false,
            strokeIsolation = true,
            strictStrokeIsolation = strict,
        )

    @Test
    fun strictIsolationKeepsNearbyParallelTraitHidden() {
        val width = 20
        val labels = IntArray(width * width)
        for (x in 0 until width) {
            labels[8 * width + x] = 1
            labels[10 * width + x] = 2
        }
        val engine = RevealEngine(width, width, 2, autoAdvance = false)
        engine.setTraitIsolationMap(map(width, width, labels))

        engine.begin(Point(2f, 8f), 1f, brush())
        engine.move(Point(17f, 8f), 1f)
        engine.end()

        assertTrue(engine.mask.get(12, 8) > 0)
        assertEquals(0, engine.mask.get(12, 10))
    }

    @Test
    fun crossingRetainsSelectedBranchInsideBrushFootprint() {
        val width = 20
        val labels = IntArray(width * width)
        for (x in 0 until width) labels[9 * width + x] = 1
        for (y in 0 until width) labels[y * width + 9] = 2
        labels[9 * width + 9] = 1 // shared pixel belongs to the followed horizontal branch.
        val engine = RevealEngine(width, width, 2, autoAdvance = false)
        engine.setTraitIsolationMap(map(width, width, labels))

        engine.begin(Point(2f, 9f), 1f, brush())
        engine.move(Point(17f, 9f), 1f)
        engine.end()

        assertTrue(engine.mask.get(9, 9) > 0)
        assertEquals(0, engine.mask.get(9, 7))
        assertEquals(0, engine.mask.get(9, 11))
    }

    @Test
    fun softIsolationAttenuatesButDoesNotGloballyBlockNeighbour() {
        val width = 20
        val labels = IntArray(width * width)
        for (x in 0 until width) {
            labels[8 * width + x] = 1
            labels[10 * width + x] = 2
        }
        val engine = RevealEngine(width, width, 2, autoAdvance = false)
        engine.setTraitIsolationMap(map(width, width, labels))

        engine.begin(Point(2f, 8f), 1f, brush(strict = false))
        engine.move(Point(17f, 8f), 1f)
        engine.end()

        val selected = engine.mask.get(12, 8)
        val neighbour = engine.mask.get(12, 10)
        assertTrue(selected > neighbour)
        assertTrue(neighbour > 0)
    }

    @Test
    fun strictAmbiguityDoesNotChooseATraitArbitrarily() {
        val width = 12
        val engine = RevealEngine(width, width, 2, autoAdvance = false)
        engine.setTraitIsolationMap(map(width, width, IntArray(width * width)))

        engine.begin(Point(6f, 6f), 1f, brush())
        engine.end()

        assertEquals(0, engine.mask.get(6, 6))
    }

    @Test
    fun disabledIsolationKeepsClassicBrushBehaviour() {
        val width = 20
        val labels = IntArray(width * width)
        for (x in 0 until width) {
            labels[8 * width + x] = 1
            labels[10 * width + x] = 2
        }
        val engine = RevealEngine(width, width, 2, autoAdvance = false)
        engine.setTraitIsolationMap(map(width, width, labels))
        val classic = brush().copy(strokeIsolation = false)

        engine.begin(Point(2f, 8f), 1f, classic)
        engine.move(Point(17f, 8f), 1f)
        engine.end()

        assertTrue(engine.mask.get(12, 10) > 0)
    }
}
