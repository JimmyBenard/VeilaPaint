package studio.nuvora.veila.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircularPalettePickerTest {
    @Test
    fun wheelSelectsHueAndDistanceControlsSaturation() {
        val result = CircularPalettePicker.select(100f, 0f, 100f, 24f, PaletteSelection(220f, .5f, .8f), null)
        assertEquals(0f, result.hue, .01f)
        assertEquals(1f, result.saturation, .01f)
        assertEquals(.8f, result.value, .01f)
    }

    @Test
    fun pipetteUsesLastTouchedPixelWhenAvailable() {
        val result = CircularPalettePicker.select(0f, 0f, 100f, 24f, PaletteSelection(220f, .5f, .8f), 0xff00ff00.toInt())
        assertEquals(120f, result.hue, .01f)
        assertEquals(1f, result.saturation, .01f)
        assertEquals(1f, result.value, .01f)
    }

    @Test
    fun centerWithoutSampleKeepsAValidWheelSelection() {
        val result = CircularPalettePicker.select(0f, 0f, 100f, 24f, PaletteSelection(220f, .5f, .8f), null)
        assertTrue(result.saturation >= .05f)
        assertEquals(.8f, result.value, .01f)
    }
}
