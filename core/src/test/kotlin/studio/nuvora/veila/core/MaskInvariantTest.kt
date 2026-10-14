package studio.nuvora.veila.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MaskInvariantTest {
    @Test
    fun irregularTilesAndSoftPressureStrokesKeepExactPixelSumThroughHistory() {
        val e = RevealEngine(93, 77, 3)
        val random = Random(21)
        repeat(70) { n ->
            val brush = Brush(random.nextFloat() * 20 + 2, .1f, .3f, true, true)
            e.begin(
                Point(random.nextFloat() * 93, random.nextFloat() * 77),
                random.nextFloat(),
                brush,
                n % 4 == 0,
            )
            repeat(4) {
                e.move(Point(random.nextFloat() * 93, random.nextFloat() * 77), random.nextFloat())
            }
            e.end()
            assertEquals(e.mask.copyValues().sumOf { it.toLong() }, e.mask.sum)
        }
        val final = e.mask.copyValues()
        val finalProgress = e.progress
        var count = 0
        while (e.undo()) {
            count++
            assertEquals(e.mask.copyValues().sumOf { it.toLong() }, e.mask.sum)
        }
        assertEquals(0L, e.mask.sum)
        repeat(count) { assertTrue(e.redo()) }
        assertArrayEquals(final, e.mask.copyValues())
        assertEquals(finalProgress, e.progress, 0.0)
    }

    @Test
    fun panAloneChangesCameraButNeitherZoomNorReveal() {
        val e = RevealEngine(16, 16, 3)
        e.begin(Point(8f, 8f), 1f, Brush(100f, .426f, 1f))
        e.end()
        val camera = Camera(8f, 8f)
        camera.transform(Point(100f, 100f), Point(200f, 140f), 1f, 800, 600, 16, 16)
        assertEquals(1f, camera.zoom, 0f)
        assertEquals(.426, e.progress, 0.0)
        assertNotEquals(8f, camera.centerX)
    }
}
