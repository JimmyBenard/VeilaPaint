package studio.nuvora.veila.studio

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtelierClockTest {
    @Test
    fun statusBarTimeUsesSimulatedClockAndNoSeconds() {
        val time = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 7, 13, 28, 0) }.timeInMillis
        assertEquals("13:28", formatStatusBarTime(time))
        assertNotEquals(formatStatusBarTime(time), formatStatusBarTime(time + 60_000))
    }

    @Test fun formatsTheSimulatedDateForTheStatusBarInFrench() {
        val time = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 15, 13, 28, 0) }.timeInMillis
        assertEquals("jeu. 15 oct.", formatSimulatedStatusDate(time))
    }
}
