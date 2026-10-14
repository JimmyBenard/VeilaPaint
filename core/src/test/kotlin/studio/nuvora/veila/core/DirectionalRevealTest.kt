package studio.nuvora.veila.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalRevealTest {
    private fun verticalGuide(size: Int): DirectionalGuide =
        DirectionalGuide(
            size, size, 1, FloatArray(size * size), FloatArray(size * size) { 1f },
            ByteArray(size * size) { 0xff.toByte() },
        )

    @Test
    fun incompatibleDirectionIsLocallyReducedButNeverBlocksCompletion() {
        val engine = RevealEngine(24, 24, 2, autoAdvance = false)
        engine.setDirectionalGuide(verticalGuide(24))
        val brush = Brush(size = 2f, intensity = 1f, hardness = 1f,
            directionalReveal = true, directionalStrength = DirectionalStrength.HIGH)
        engine.begin(Point(4f, 12f), 1f, brush)
        engine.move(Point(20f, 12f), 1f)
        engine.end()
        assertTrue(engine.mask.get(12, 12) in 1..200)

        engine.begin(Point(4f, 12f), 1f, brush)
        // A strict directional match deliberately contributes less per pass;
        // repeated local passes must nevertheless reach the exact mask maximum.
        repeat(32) { engine.move(Point(20f, 12f), 1f) }
        engine.end()
        assertEquals(1000, engine.mask.get(12, 12))
    }

    @Test
    fun oppositeDirectionsMatchTheSameContour() {
        val guide = verticalGuide(24)
        val brush = Brush(size = 2f, intensity = 1f, hardness = 1f,
            directionalReveal = true, directionalStrength = DirectionalStrength.MODERATE)
        fun coverage(from: Point, to: Point): Int {
            val engine = RevealEngine(24, 24, 2, autoAdvance = false)
            engine.setDirectionalGuide(guide)
            engine.begin(from, 1f, brush)
            engine.move(to, 1f)
            engine.end()
            return engine.mask.get(12, 12)
        }
        assertEquals(coverage(Point(12f, 4f), Point(12f, 20f)),
            coverage(Point(12f, 20f), Point(12f, 4f)))
    }
}
