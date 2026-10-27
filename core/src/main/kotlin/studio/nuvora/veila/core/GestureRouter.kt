package studio.nuvora.veila.core

import kotlin.math.*

enum class Tool {
    PEN,
    ERASER,
    FINGER,
    MOUSE,
    UNKNOWN,
}

enum class Action {
    DOWN,
    POINTER_DOWN,
    MOVE,
    POINTER_UP,
    UP,
    CANCEL,
}

data class Pointer(
    val id: Int,
    val x: Float,
    val y: Float,
    val tool: Tool,
    val pressure: Float = 1f,
    val eraserButton: Boolean = false,
    val tilt: Float = 0f,
)

data class InputFrame(
    val action: Action,
    val time: Long,
    val pointers: List<Pointer>,
    val changedId: Int,
    val canceledPointer: Boolean = false,
)

sealed interface Gesture {
    data class PenDown(val p: Pointer) : Gesture

    data class PenMove(val p: Pointer) : Gesture

    data object PenUp : Gesture

    data object PenCancel : Gesture

    data class Navigate(val from: Point, val to: Point, val ratio: Float, val angle: Float = 0f) :
        Gesture

    data object Undo : Gesture

    data object Redo : Gesture

    data object ToggleUnrevealedOverlay : Gesture

    data object ToggleProgress : Gesture
}

/**
 * Routes one writer at a time to the reveal engine.
 *
 * A finger may be a painter when explicitly enabled. The instant a second finger is present,
 * painting is suspended and that contact sequence stays navigation-only until every contact has
 * gone up. Native pens always win over a simultaneous finger.
 */
class GestureRouter(
    private var slop: Float = 18f,
    var mouseEnabled: Boolean = false,
    var fingerDrawingEnabled: Boolean = true,
) {
    fun setTouchSlop(pixels: Float) {
        require(pixels > 0)
        slop = pixels
    }

    private var painter: Int? = null
    private var painterTool: Tool? = null
    private var painterOrigin: Point? = null
    private var painterStarted = 0L
    private var painterMoved = false
    private var blockedPainter = false
    private var started = 0L
    private var lastAdded = 0L
    private var maximum = 0
    private var moved = false
    private val origins = mutableMapOf<Int, Point>()
    private var pair: List<Pointer> = emptyList()
    private var pairIds = emptySet<Int>()
    /** A two-finger hold owns its contact sequence and must not become the red-overlay tap. */
    private var twoFingerHoldClaimed = false

    fun claimTwoFingerHold() {
        if (maximum == 2 && !moved) twoFingerHoldClaimed = true
    }

    /** A long one-finger shortcut takes over after cancelling its provisional brush dot. */
    fun claimSingleFingerHold() {
        if (painterTool == Tool.FINGER && painter != null) {
            clearPainter()
            blockedPainter = true
        }
    }

    fun handle(f: InputFrame): List<Gesture> {
        val out = mutableListOf<Gesture>()
        if (f.action == Action.CANCEL) {
            if (painter != null) out += Gesture.PenCancel
            reset()
            return out
        }
        val fingers = f.pointers.filter { it.tool == Tool.FINGER }.sortedBy { it.id }
        val goingUp = f.action == Action.UP || f.action == Action.POINTER_UP
        val remaining = if (goingUp) f.pointers.filterNot { it.id == f.changedId } else f.pointers
        val remainingFingers = remaining.filter { it.tool == Tool.FINGER }.sortedBy { it.id }
        val changed = f.pointers.firstOrNull { it.id == f.changedId }
        // A rejected palm is not a tap and must not cancel a different, valid painter.
        if (goingUp && f.canceledPointer) {
            moved = true
            if (f.changedId == painter) {
                out += Gesture.PenCancel
                clearPainter()
            }
            origins.remove(f.changedId)
            pair = remainingFingers
            pairIds = pair.map { it.id }.toSet()
            if (remainingFingers.isEmpty()) clearFingers()
            if (remaining.isEmpty()) blockedPainter = false
            return out
        }
        val nativePen = f.pointers.firstOrNull(::isNativePen)
        val exclusiveTouchGesture =
            fingers.size >= 2 ||
                maximum >= 2 ||
                (fingers.isNotEmpty() && nativePen == null && !fingerDrawingEnabled)
        // Two fingers are an exclusive navigation sequence. A tap that rapidly grows to three
        // or four fingers rolls back its initial touch dot before it invokes Undo/Redo.
        if (exclusiveTouchGesture) {
            val interruptedMovingStroke = painterTool == Tool.FINGER && painterMoved
            if (painter != null) {
                val shouldRollbackTap =
                    painterTool == Tool.FINGER &&
                        !painterMoved &&
                        f.time - painterStarted <= MULTI_TAP_WINDOW
                out += if (shouldRollbackTap) Gesture.PenCancel else Gesture.PenUp
                clearPainter()
            }
            if (fingers.size >= 2 || maximum >= 2) blockedPainter = true
            if (origins.isEmpty()) {
                started = f.time
                moved = interruptedMovingStroke
                maximum = 0
            }
            for (p in fingers) {
                if (p.id !in origins) {
                    origins[p.id] = Point(p.x, p.y)
                    lastAdded = f.time
                }
                val origin = origins.getValue(p.id)
                if (hypot(p.x - origin.x, p.y - origin.y) > slop) moved = true
            }
            maximum = max(maximum, fingers.size)
            if (fingers.size == 2 && maximum == 2 && moved && !goingUp && f.action == Action.MOVE) {
                val ids = fingers.map { it.id }.toSet()
                if (ids == pairIds && pair.size == 2) {
                    val d = distance(pair)
                    val n = distance(fingers)
                    if (d > 5f && n > 5f)
                        out +=
                            Gesture.Navigate(
                                focus(pair),
                                focus(fingers),
                                n / d,
                                Camera.normalizeAngle(angle(fingers) - angle(pair)),
                            )
                }
            }
            pair = remainingFingers
            pairIds = remainingFingers.map { it.id }.toSet()
            if (remainingFingers.isEmpty()) {
                val tap = !moved && f.time - started <= 280 && lastAdded - started <= 160
                if (tap && maximum == 2 && !twoFingerHoldClaimed) out += Gesture.ToggleUnrevealedOverlay
                if (tap && maximum == 3) out += Gesture.Undo
                if (tap && maximum == 4) out += Gesture.Redo
                if (tap && maximum == 5) out += Gesture.ToggleProgress
                clearFingers()
                if (remaining.isEmpty()) blockedPainter = false
            }
            return out
        }
        if (maximum < 2) clearFingers()
        val preferred =
            nativePen
                ?: f.pointers.firstOrNull { it.tool == Tool.MOUSE && mouseEnabled }
                ?: f.pointers.firstOrNull { it.tool == Tool.FINGER && fingerDrawingEnabled }
        if (
            (f.action == Action.DOWN || f.action == Action.POINTER_DOWN) &&
                !blockedPainter &&
                preferred != null &&
                preferred.id != painter
        ) {
            // A native pen arriving while a finger is drawing keeps the completed finger segment,
            // then becomes the only writer. This prevents two simultaneous reveal strokes.
            if (painter != null) out += Gesture.PenUp
            startPainter(preferred, f.time)
            out += Gesture.PenDown(preferred)
        } else if (f.action == Action.MOVE && !blockedPainter) {
            f.pointers.firstOrNull { it.id == painter && isPaintPointer(it) }?.let {
                markPainterMovement(it)
                out += Gesture.PenMove(it)
            }
        } else if (goingUp && f.changedId == painter) {
            changed?.takeIf(::isPaintPointer)?.let {
                markPainterMovement(it)
                out += Gesture.PenMove(it)
            }
            out += Gesture.PenUp
            clearPainter()
        }
        if (remaining.isEmpty()) blockedPainter = false
        return out
    }

    fun reset() {
        clearPainter()
        blockedPainter = false
        clearFingers()
    }

    private fun startPainter(pointer: Pointer, time: Long) {
        painter = pointer.id
        painterTool = pointer.tool
        painterOrigin = Point(pointer.x, pointer.y)
        painterStarted = time
        painterMoved = false
    }

    private fun markPainterMovement(pointer: Pointer) {
        val origin = painterOrigin ?: return
        if (hypot(pointer.x - origin.x, pointer.y - origin.y) > slop) painterMoved = true
    }

    private fun clearPainter() {
        painter = null
        painterTool = null
        painterOrigin = null
        painterStarted = 0L
        painterMoved = false
    }

    private fun clearFingers() {
        origins.clear()
        pair = emptyList()
        pairIds = emptySet()
        maximum = 0
        twoFingerHoldClaimed = false
    }

    private fun isNativePen(p: Pointer) = p.tool == Tool.PEN || p.tool == Tool.ERASER

    private fun isPaintPointer(p: Pointer) =
        isNativePen(p) ||
            (p.tool == Tool.MOUSE && mouseEnabled) ||
            (p.tool == Tool.FINGER && fingerDrawingEnabled)

    private fun focus(p: List<Pointer>) = Point((p[0].x + p[1].x) * .5f, (p[0].y + p[1].y) * .5f)

    private fun angle(p: List<Pointer>) = atan2(p[1].y - p[0].y, p[1].x - p[0].x)

    private fun distance(p: List<Pointer>) = hypot(p[0].x - p[1].x, p[0].y - p[1].y)

    private companion object {
        const val MULTI_TAP_WINDOW = 280L
    }
}
