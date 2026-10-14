package studio.nuvora.veila.core

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class CameraRotationTest {
    @Test
    fun screenToImageStaysAlignedAtEveryQuarterTurnAndOrientation() {
        for ((vw, vh) in listOf(1600 to 900, 900 to 1600)) {
            for (angle in listOf(0f, .4f, (PI / 2).toFloat(), PI.toFloat(), -2.4f)) {
                val camera = Camera(423f, 613f, 2.7f, angle)
                val point = Point(350f, 620f)
                val screen = camera.screenPoint(point, vw, vh, 1080, 1350)
                val back = camera.imagePoint(screen.x, screen.y, vw, vh, 1080, 1350)
                assertEquals(point.x, back.x, .0002f)
                assertEquals(point.y, back.y, .0002f)
            }
        }
    }

    @Test
    fun combinedZoomPanRotationKeepsIntrinsicPointUnderGestureFocus() {
        val camera = Camera(540f, 675f, 1.5f, .6f)
        val from = Point(400f, 370f)
        val to = Point(510f, 390f)
        val anchor = camera.imagePoint(from.x, from.y, 1400, 900, 1080, 1350)
        camera.transform(from, to, 1.7f, 1400, 900, 1080, 1350, .8f)
        val after = camera.imagePoint(to.x, to.y, 1400, 900, 1080, 1350)
        assertEquals(anchor.x, after.x, .0002f)
        assertEquals(anchor.y, after.y, .0002f)
        assertEquals(1.4f, camera.rotation, .0002f)
    }

    @Test
    fun rotationNeverChangesAnyMaskPixelProgressOrHistory() {
        val e = RevealEngine(32, 32, 3)
        e.begin(Point(16f, 16f), 1f, Brush(100f, .426f, 1f))
        e.end()
        val values = e.mask.copyValues()
        val camera = Camera(16f, 16f)
        repeat(150) {
            camera.transform(Point(400f, 300f), Point(402f, 302f), 1.01f, 800, 600, 32, 32, .08f)
        }
        assertEquals(.426, e.progress, 0.0)
        assertArrayEquals(values, e.mask.copyValues())
        e.undo()
        assertEquals(0.0, e.progress, 0.0)
        e.redo()
        assertArrayEquals(values, e.mask.copyValues())
    }

    @Test
    fun stylusAtRotatedCoordinatePaintsIdenticalIntrinsicPixels() {
        val straight = RevealEngine(64, 64, 3)
        val rotated = RevealEngine(64, 64, 3)
        val camera = Camera(32f, 32f, 2f, (PI / 2).toFloat())
        val point = Point(24f, 42f)
        val screen = camera.screenPoint(point, 1000, 800, 64, 64)
        straight.begin(point, 1f, Brush(10f, .2f, 1f))
        straight.end()
        rotated.begin(
            camera.imagePoint(screen.x, screen.y, 1000, 800, 64, 64),
            1f,
            Brush(10f, .2f, 1f),
        )
        rotated.end()
        assertArrayEquals(straight.mask.copyValues(), rotated.mask.copyValues())
    }

    @Test
    fun invalidGestureCannotPoisonCamera() {
        val camera = Camera(32f, 32f)
        val original = camera.copy()
        camera.transform(Point(20f, 20f), Point(30f, 30f), 1f, 800, 600, 64, 64, Float.NaN)
        assertEquals(original, camera)
    }
}
