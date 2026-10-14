package studio.nuvora.veila.studio

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.nuvora.veila.core.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidInputMapperTest {
    private fun event(
        tool: Int,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN,
        action: Int = MotionEvent.ACTION_DOWN,
        pressure: Float = .35f,
        flags: Int = 0,
        button: Int = 0,
    ): MotionEvent =
        MotionEvent.obtain(
            10,
            20,
            action,
            1,
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 9
                    toolType = tool
                }
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 80f
                    y = 90f
                    this.pressure = pressure
                    setAxisValue(MotionEvent.AXIS_TILT, .4f)
                }
            ),
            0,
            button,
            1f,
            1f,
            0,
            0,
            source,
            flags,
        )

    @Test
    fun androidStylusWithTouchscreenSourceIsStillAPen() {
        val e = event(MotionEvent.TOOL_TYPE_STYLUS)
        try {
            assertTrue(
                GestureRouter().handle(AndroidInputMapper.frames(e).single()).single()
                    is Gesture.PenDown
            )
        } finally {
            e.recycle()
        }
    }

    @Test
    fun unknownSinglePointerWithExplicitStylusSourceIsRecognized() {
        for (source in listOf(InputDevice.SOURCE_STYLUS, InputDevice.SOURCE_BLUETOOTH_STYLUS)) {
            val e = event(MotionEvent.TOOL_TYPE_UNKNOWN, source)
            try {
                assertEquals(Tool.PEN, AndroidInputMapper.tool(e, 0))
            } finally {
                e.recycle()
            }
        }
    }

    @Test
    fun explicitStylusSourceRepairsAFingerToolReportWithoutChangingTouchscreenInput() {
        for (source in listOf(InputDevice.SOURCE_STYLUS, InputDevice.SOURCE_BLUETOOTH_STYLUS)) {
            val e = event(MotionEvent.TOOL_TYPE_FINGER, source, pressure = 1f)
            try {
                val frame = AndroidInputMapper.frames(e).single()
                assertEquals(Tool.PEN, frame.pointers.single().tool)
                assertTrue(GestureRouter(fingerDrawingEnabled = false).handle(frame).single() is Gesture.PenDown)
            } finally {
                e.recycle()
            }
        }
        val touch = event(MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN, pressure = 1f)
        try {
            val frame = AndroidInputMapper.frames(touch).single()
            assertEquals(Tool.FINGER, frame.pointers.single().tool)
            assertTrue(GestureRouter(fingerDrawingEnabled = false).handle(frame).isEmpty())
            assertTrue(GestureRouter().handle(frame).single() is Gesture.PenDown)
        } finally {
            touch.recycle()
        }
    }

    @Test
    fun unknownTouchscreenPointerStaysUnknown() {
        val e = event(MotionEvent.TOOL_TYPE_UNKNOWN)
        try {
            assertEquals(Tool.UNKNOWN, AndroidInputMapper.tool(e, 0))
        } finally {
            e.recycle()
        }
    }

    @Test
    fun pressureTiltAndSideButtonReachCoreWithoutToolConfusion() {
        val e = event(MotionEvent.TOOL_TYPE_STYLUS, button = MotionEvent.BUTTON_STYLUS_PRIMARY)
        try {
            val p = AndroidInputMapper.frames(e).single().pointers.single()
            assertEquals(.35f, p.pressure, .0001f)
            assertEquals(.4f, p.tilt, .0001f)
            assertTrue(p.eraserButton)
            assertEquals(Tool.PEN, p.tool)
        } finally {
            e.recycle()
        }
    }

    @Test
    fun zeroPressureWithoutPressureAxisDoesNotDisablePen() {
        val e = event(MotionEvent.TOOL_TYPE_STYLUS, pressure = 0f)
        try {
            assertEquals(1f, AndroidInputMapper.frames(e).single().pointers.single().pressure, 0f)
        } finally {
            e.recycle()
        }
    }

    @Test
    fun canceledAndroidPointerIsMarkedForRollback() {
        val e =
            event(
                MotionEvent.TOOL_TYPE_STYLUS,
                action = MotionEvent.ACTION_UP,
                flags = MotionEvent.FLAG_CANCELED,
            )
        try {
            assertTrue(AndroidInputMapper.frames(e).single().canceledPointer)
        } finally {
            e.recycle()
        }
    }

    @Test
    fun hoverCannotPaintButReportsRecognizedStylus() {
        val e = event(MotionEvent.TOOL_TYPE_STYLUS, action = MotionEvent.ACTION_HOVER_ENTER)
        try {
            assertTrue(AndroidInputMapper.frames(e).isEmpty())
            assertTrue(AndroidInputMapper.diagnostic(e).startsWith("Stylet reconnu"))
        } finally {
            e.recycle()
        }
    }

    @Test
    fun historicalSamplesAreCopiedBeforeMotionEventIsRecycled() {
        val e = event(MotionEvent.TOOL_TYPE_STYLUS, action = MotionEvent.ACTION_MOVE)
        e.addBatch(
            30,
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 100f
                    y = 110f
                    pressure = .7f
                }
            ),
            0,
        )
        val frames = AndroidInputMapper.frames(e)
        e.recycle()
        assertEquals(2, frames.size)
        assertEquals(80f, frames[0].pointers.single().x, 0f)
        assertEquals(.35f, frames[0].pointers.single().pressure, .0001f)
        assertEquals(100f, frames[1].pointers.single().x, 0f)
    }
}
