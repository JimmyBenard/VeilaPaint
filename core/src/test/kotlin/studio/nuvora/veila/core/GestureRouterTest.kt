package studio.nuvora.veila.core

import org.junit.Assert.*
import org.junit.Test

class GestureRouterTest {
    private fun finger(id: Int, x: Float = id * 40f, y: Float = 100f) =
        Pointer(id, x, y, Tool.FINGER)

    private fun frame(
        action: Action,
        time: Long,
        pointers: List<Pointer>,
        changed: Int = pointers.last().id,
    ) = InputFrame(action, time, pointers, changed)

    private fun tap(count: Int, router: GestureRouter = GestureRouter()): List<Gesture> {
        val result = mutableListOf<Gesture>()
        for (n in 1..count) result +=
            router.handle(
                frame(
                    if (n == 1) Action.DOWN else Action.POINTER_DOWN,
                    n * 20L,
                    (1..n).map { finger(it) },
                )
            )
        for (n in count downTo 1) result +=
            router.handle(
                frame(
                    if (n == 1) Action.UP else Action.POINTER_UP,
                    160L + count - n,
                    (1..n).map { finger(it) },
                )
            )
        return result
    }

    @Test
    fun oneFingerUsesTheSamePaintingGesturesWhenEnabled() {
        val r = GestureRouter()
        val out =
            r.handle(frame(Action.DOWN, 0, listOf(finger(1)))) +
                r.handle(frame(Action.MOVE, 20, listOf(finger(1, 200f)))) +
                r.handle(frame(Action.UP, 40, listOf(finger(1, 200f))))
        assertTrue(out.first() is Gesture.PenDown)
        assertTrue(out.any { it is Gesture.PenMove })
        assertTrue(out.last() is Gesture.PenUp)
    }

    @Test
    fun oneFingerIsIgnoredWhenDrawingSettingIsDisabled() {
        val r = GestureRouter(fingerDrawingEnabled = false)
        val out =
            r.handle(frame(Action.DOWN, 0, listOf(finger(1)))) +
                r.handle(frame(Action.MOVE, 20, listOf(finger(1, 200f)))) +
                r.handle(frame(Action.UP, 40, listOf(finger(1, 200f))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun twoFingersNavigateAfterSuspendingTheSingleFingerPainter() {
        val r = GestureRouter()
        r.handle(frame(Action.DOWN, 0, listOf(finger(1))))
        r.handle(frame(Action.POINTER_DOWN, 20, listOf(finger(1), finger(2))))
        val out =
            r.handle(frame(Action.MOVE, 40, listOf(finger(1, 20f, 120f), finger(2, 100f, 120f))))
        val navigation = out.single() as Gesture.Navigate
        assertEquals(2f, navigation.ratio, 0f)
        assertEquals(Point(60f, 120f), navigation.to)
    }

    @Test
    fun secondFingerEndsTheStrokeAndTheRemainingFingerCannotRestartIt() {
        val r = GestureRouter()
        val first = finger(1, 60f, 60f)
        assertTrue(r.handle(frame(Action.DOWN, 0, listOf(first))).single() is Gesture.PenDown)
        assertTrue(r.handle(frame(Action.MOVE, 30, listOf(first.copy(x = 180f)))).single() is Gesture.PenMove)
        assertEquals(
            listOf(Gesture.PenUp),
            r.handle(frame(Action.POINTER_DOWN, 60, listOf(first.copy(x = 180f), finger(2, 220f, 60f)))),
        )
        assertTrue(
            r.handle(
                    frame(
                        Action.POINTER_UP,
                        90,
                        listOf(first.copy(x = 180f), finger(2, 220f, 60f)),
                        2,
                    )
                )
                .isEmpty()
        )
        assertTrue(r.handle(frame(Action.MOVE, 110, listOf(first.copy(x = 250f)))).isEmpty())
        r.handle(frame(Action.UP, 130, listOf(first.copy(x = 250f))))
        assertTrue(r.handle(frame(Action.DOWN, 200, listOf(finger(3)))).single() is Gesture.PenDown)
    }

    @Test
    fun threeFingerTapIsOneUndoWithoutCommittingTheInitialFingerDot() {
        val out = tap(3)
        assertEquals(1, out.count { it is Gesture.Undo })
        assertEquals(1, out.count { it is Gesture.PenCancel })
        assertTrue(out.none { it is Gesture.PenUp })
    }

    @Test
    fun fourFingerTapIsOneRedoWithoutIntermediateUndo() {
        val out = tap(4)
        assertEquals(1, out.count { it is Gesture.Redo })
        assertTrue(out.none { it is Gesture.Undo || it is Gesture.PenUp })
    }

    @Test
    fun twoFingerTapTogglesOnlyTheUnrevealedOverlay() {
        val out = tap(2)
        assertEquals(1, out.count { it is Gesture.ToggleUnrevealedOverlay })
        assertTrue(out.none { it is Gesture.Undo || it is Gesture.Redo || it is Gesture.Navigate })
    }

    @Test
    fun fiveFingerTapTogglesOnlyTheHiddenPercentage() {
        val out = tap(5)
        assertEquals(1, out.count { it is Gesture.ToggleProgress })
        assertTrue(out.none { it is Gesture.Undo || it is Gesture.Redo })
    }

    @Test
    fun twoFingerHoldAndPinchNeverToggleTheOverlay() {
        val hold = GestureRouter()
        val a = finger(1, 80f, 80f)
        val b = finger(2, 160f, 80f)
        var out = hold.handle(frame(Action.DOWN, 0, listOf(a)))
        out += hold.handle(frame(Action.POINTER_DOWN, 20, listOf(a, b)))
        out += hold.handle(frame(Action.POINTER_UP, 400, listOf(a, b), b.id))
        out += hold.handle(frame(Action.UP, 420, listOf(a), a.id))
        assertTrue(out.none { it is Gesture.ToggleUnrevealedOverlay })

        val pinch = GestureRouter(slop = 1f)
        out = pinch.handle(frame(Action.DOWN, 0, listOf(a)))
        out += pinch.handle(frame(Action.POINTER_DOWN, 20, listOf(a, b)))
        out += pinch.handle(frame(Action.MOVE, 40, listOf(a.copy(x = 40f), b.copy(x = 200f))))
        out += pinch.handle(frame(Action.POINTER_UP, 60, listOf(a.copy(x = 40f), b.copy(x = 200f)), b.id))
        out += pinch.handle(frame(Action.UP, 80, listOf(a.copy(x = 40f)), a.id))
        assertTrue(out.any { it is Gesture.Navigate })
        assertTrue(out.none { it is Gesture.ToggleUnrevealedOverlay })
    }

    @Test
    fun addingSecondFingerToAMovingStrokeDoesNotBecomeOverlayTap() {
        val r = GestureRouter()
        val a = finger(1, 40f, 80f)
        val b = finger(2, 180f, 80f)
        var out = r.handle(frame(Action.DOWN, 0, listOf(a)))
        out += r.handle(frame(Action.MOVE, 20, listOf(a.copy(x = 120f))))
        out += r.handle(frame(Action.POINTER_DOWN, 40, listOf(a.copy(x = 120f), b)))
        out += r.handle(frame(Action.POINTER_UP, 60, listOf(a.copy(x = 120f), b), b.id))
        out += r.handle(frame(Action.UP, 80, listOf(a.copy(x = 120f)), a.id))
        assertTrue(out.none { it is Gesture.ToggleUnrevealedOverlay })
    }

    @Test
    fun addingAThirdFingerAfterNavigationDoesNotUndo() {
        val r = GestureRouter()
        r.handle(frame(Action.DOWN, 0, listOf(finger(1))))
        r.handle(frame(Action.POINTER_DOWN, 20, listOf(finger(1), finger(2))))
        r.handle(frame(Action.MOVE, 40, listOf(finger(1, 10f), finger(2, 110f))))
        val p = listOf(finger(1, 10f), finger(2, 110f), finger(3))
        val out =
            r.handle(frame(Action.POINTER_DOWN, 60, p)) +
                r.handle(frame(Action.POINTER_UP, 80, p)) +
                r.handle(frame(Action.POINTER_UP, 100, p.take(2))) +
                r.handle(frame(Action.UP, 120, p.take(1)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun twoFingersSuspendPenUntilAllContactsAreReleased() {
        val r = GestureRouter()
        val pen = Pointer(9, 50f, 50f, Tool.PEN)
        assertTrue(r.handle(frame(Action.DOWN, 0, listOf(pen))).single() is Gesture.PenDown)
        assertTrue(r.handle(frame(Action.POINTER_DOWN, 10, listOf(pen, finger(1)))).isEmpty())
        assertEquals(
            listOf(Gesture.PenUp),
            r.handle(frame(Action.POINTER_DOWN, 20, listOf(pen, finger(1), finger(2)))),
        )
        assertTrue(r.handle(frame(Action.MOVE, 30, listOf(pen, finger(1), finger(2)))).isEmpty())
        r.handle(frame(Action.POINTER_UP, 40, listOf(pen, finger(1), finger(2))))
        r.handle(frame(Action.POINTER_UP, 60, listOf(pen, finger(1))))
        assertTrue(r.handle(frame(Action.MOVE, 80, listOf(pen))).isEmpty())
        r.handle(frame(Action.UP, 100, listOf(pen)))
        assertTrue(r.handle(frame(Action.DOWN, 200, listOf(pen))).single() is Gesture.PenDown)
    }

    @Test
    fun nativePenWinsOverASimultaneousFingerWithoutDoublePainting() {
        for (palmFirst in listOf(true, false)) {
            val r = GestureRouter()
            val pen = Pointer(9, 50f, 50f, Tool.PEN)
            if (palmFirst) {
                assertTrue(r.handle(frame(Action.DOWN, 0, listOf(finger(1)))).single() is Gesture.PenDown)
                assertEquals(
                    listOf(Gesture.PenUp, Gesture.PenDown(pen)),
                    r.handle(frame(Action.POINTER_DOWN, 10, listOf(finger(1), pen))),
                )
            } else {
                r.handle(frame(Action.DOWN, 0, listOf(pen)))
                assertTrue(
                    r.handle(frame(Action.POINTER_DOWN, 10, listOf(pen, finger(1)))).isEmpty()
                )
            }
            val next = pen.copy(x = 90f)
            assertEquals(
                listOf(Gesture.PenMove(next)),
                r.handle(frame(Action.MOVE, 20, listOf(finger(1, 300f), next))),
            )
            assertEquals(
                listOf(Gesture.PenMove(next), Gesture.PenUp),
                r.handle(frame(Action.POINTER_UP, 30, listOf(finger(1), next), next.id)),
            )
        }
    }

    @Test
    fun rejectedPalmCancelsNeitherPenNorTriggersUndo() {
        val r = GestureRouter()
        val pen = Pointer(9, 50f, 50f, Tool.PEN)
        r.handle(frame(Action.DOWN, 0, listOf(pen)))
        r.handle(frame(Action.POINTER_DOWN, 10, listOf(pen, finger(1))))
        assertTrue(
            r.handle(InputFrame(Action.POINTER_UP, 20, listOf(pen, finger(1)), 1, true)).isEmpty()
        )
        assertEquals(listOf(Gesture.PenMove(pen)), r.handle(frame(Action.MOVE, 30, listOf(pen))))
        assertEquals(
            listOf(Gesture.PenCancel),
            r.handle(InputFrame(Action.UP, 40, listOf(pen), 9, true)),
        )
    }

    @Test
    fun rejectedFingerNeverTurnsIntoThreeFingerUndo() {
        val r = GestureRouter()
        for (n in 1..3) r.handle(
            frame(
                if (n == 1) Action.DOWN else Action.POINTER_DOWN,
                n * 10L,
                (1..n).map { finger(it) },
            )
        )
        val out =
            r.handle(InputFrame(Action.POINTER_UP, 40, (1..3).map { finger(it) }, 3, true)) +
                r.handle(frame(Action.POINTER_UP, 50, listOf(finger(1), finger(2)))) +
                r.handle(frame(Action.UP, 60, listOf(finger(1))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun rotatingPairUsesPointerIdsEvenWhenAndroidReordersIndices() {
        val r = GestureRouter(slop = 1f)
        val a = finger(10, 100f, 100f)
        val b = finger(20, 200f, 100f)
        r.handle(frame(Action.DOWN, 0, listOf(a)))
        r.handle(frame(Action.POINTER_DOWN, 10, listOf(a, b)))
        val out =
            r.handle(
                frame(
                    Action.MOVE,
                    20,
                    listOf(b.copy(x = 150f, y = 150f), a.copy(x = 150f, y = 50f)),
                )
            )
        val g = out.single() as Gesture.Navigate
        assertEquals((Math.PI / 2).toFloat(), g.angle, .0001f)
        assertEquals(1f, g.ratio, .0001f)
        assertEquals(Point(150f, 100f), g.from)
        assertEquals(g.from, g.to)
    }

    @Test
    fun rotatingPastHalfTurnHasNoFullTurnJumpOrShortcut() {
        val r = GestureRouter(slop = 1f)
        val a = finger(1, 100f, 100f)
        fun other(degrees: Double) =
            finger(
                2,
                100 + 100 * kotlin.math.cos(Math.toRadians(degrees)).toFloat(),
                100 + 100 * kotlin.math.sin(Math.toRadians(degrees)).toFloat(),
            )
        r.handle(frame(Action.DOWN, 0, listOf(a)))
        r.handle(frame(Action.POINTER_DOWN, 10, listOf(a, other(179.0))))
        val g =
            r.handle(frame(Action.MOVE, 20, listOf(a, other(-179.0)))).single() as Gesture.Navigate
        assertEquals(Math.toRadians(2.0).toFloat(), g.angle, .0001f)
        val out =
            r.handle(frame(Action.POINTER_UP, 30, listOf(a, other(-179.0)))) +
                r.handle(frame(Action.UP, 40, listOf(a)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun unknownToolsNeverPaint() =
        assertTrue(
            GestureRouter()
                .handle(frame(Action.DOWN, 0, listOf(Pointer(1, 0f, 0f, Tool.UNKNOWN))))
                .isEmpty()
        )

    @Test
    fun mouseRequiresExplicitTestSetting() {
        val f = frame(Action.DOWN, 0, listOf(Pointer(1, 0f, 0f, Tool.MOUSE)))
        assertTrue(GestureRouter().handle(f).isEmpty())
        assertTrue(GestureRouter(mouseEnabled = true).handle(f).single() is Gesture.PenDown)
    }

    @Test
    fun cancellationNeverTriggersMultiFingerShortcut() {
        val r = GestureRouter(fingerDrawingEnabled = false)
        for (n in 1..3) r.handle(
            frame(
                if (n == 1) Action.DOWN else Action.POINTER_DOWN,
                n * 20L,
                (1..n).map { finger(it) },
            )
        )
        assertTrue(r.handle(frame(Action.CANCEL, 100, (1..3).map { finger(it) })).isEmpty())
        assertTrue(tap(1, r).isEmpty())
    }
}
