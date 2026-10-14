package studio.nuvora.veila.studio

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import studio.nuvora.veila.core.*

/** Copies pooled Android events before GL dispatch, including imperfect stylus driver reports. */
object AndroidInputMapper {
    private fun explicitStylusSource(event: MotionEvent) =
        event.isFromSource(InputDevice.SOURCE_STYLUS) ||
            event.isFromSource(InputDevice.SOURCE_BLUETOOTH_STYLUS)

    fun tool(event: MotionEvent, index: Int): Tool =
        when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS -> Tool.PEN
            MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            // Some tablet drivers report the tip as FINGER while still explicitly identifying
            // the event device as a stylus. Accept only a lone, explicit stylus source: regular
            // touchscreen contacts remain FINGER and are governed by the one-finger setting.
            MotionEvent.TOOL_TYPE_FINGER ->
                if (event.pointerCount == 1 && explicitStylusSource(event)) Tool.PEN else Tool.FINGER
            MotionEvent.TOOL_TYPE_MOUSE -> Tool.MOUSE
            // A few drivers identify the event source but omit the pointer's tool type.
            MotionEvent.TOOL_TYPE_UNKNOWN ->
                if (event.pointerCount == 1 && explicitStylusSource(event))
                    Tool.PEN
                else Tool.UNKNOWN
            else -> Tool.UNKNOWN
        }

    fun frames(event: MotionEvent): List<InputFrame> {
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Action.DOWN
                MotionEvent.ACTION_POINTER_DOWN -> Action.POINTER_DOWN
                MotionEvent.ACTION_MOVE -> Action.MOVE
                MotionEvent.ACTION_POINTER_UP -> Action.POINTER_UP
                MotionEvent.ACTION_UP -> Action.UP
                MotionEvent.ACTION_CANCEL -> Action.CANCEL
                else -> return emptyList() // Hover and button-only events never paint.
            }
        fun pointers(history: Int? = null) =
            List(event.pointerCount) { i ->
                val type = tool(event, i)
                val raw =
                    if (history == null) event.getPressure(i)
                    else event.getHistoricalPressure(i, history)
                val pressure =
                    when {
                        type == Tool.MOUSE || !raw.isFinite() -> 1f
                        raw == 0f &&
                            event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source) ==
                                null -> 1f
                        else -> raw.coerceIn(0f, 1f)
                    }
                Pointer(
                    event.getPointerId(i),
                    if (history == null) event.getX(i) else event.getHistoricalX(i, history),
                    if (history == null) event.getY(i) else event.getHistoricalY(i, history),
                    type,
                    pressure,
                    event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0,
                    if (history == null) event.getAxisValue(MotionEvent.AXIS_TILT, i)
                    else event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, history),
                )
            }
        val id = event.getPointerId(event.actionIndex)
        return buildList {
            if (action == Action.MOVE)
                for (h in 0 until event.historySize) add(
                    InputFrame(Action.MOVE, event.getHistoricalEventTime(h), pointers(h), id)
                )
            add(
                InputFrame(
                    action,
                    event.eventTime,
                    pointers(),
                    id,
                    Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0,
                )
            )
        }
    }

    fun diagnostic(event: MotionEvent): String {
        val i = event.actionIndex
        val name =
            when (tool(event, i)) {
                Tool.PEN -> "Stylet reconnu"
                Tool.ERASER -> "Gomme du stylet reconnue"
                Tool.FINGER -> "Contact tactile reçu"
                Tool.MOUSE -> "Souris reçue (option de test requise)"
                Tool.UNKNOWN -> "Type d’entrée non reconnu"
            }
        return "$name\n${event.device?.name ?: "Périphérique Android"} · outil ${event.getToolType(i)} · source 0x${event.source.toString(16)}"
    }
}
