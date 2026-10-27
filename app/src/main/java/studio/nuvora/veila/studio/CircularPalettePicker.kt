package studio.nuvora.veila.studio

import kotlin.math.atan2
import kotlin.math.hypot

/** Pure selection rules shared by the touch shortcut and its circular overlay. */
data class PaletteSelection(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

object CircularPalettePicker {
    fun isPipette(dx: Float, dy: Float, pipetteRadius: Float): Boolean =
        hypot(dx, dy) <= pipetteRadius

    fun select(
        dx: Float,
        dy: Float,
        wheelRadius: Float,
        pipetteRadius: Float,
        fallback: PaletteSelection,
        sampledArgb: Int?,
    ): PaletteSelection {
        if (isPipette(dx, dy, pipetteRadius) && sampledArgb != null) return fromArgb(sampledArgb)
        val hue = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        val saturation = (hypot(dx, dy) / wheelRadius).coerceIn(.05f, 1f)
        return PaletteSelection(hue, saturation, fallback.value.coerceIn(0f, 1f))
    }

    fun fromArgb(argb: Int): PaletteSelection {
        val r = ((argb shr 16) and 0xff) / 255f
        val g = ((argb shr 8) and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue =
            when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
        return PaletteSelection(hue, if (max == 0f) 0f else delta / max, max)
    }
}
