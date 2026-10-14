package studio.nuvora.veila.studio

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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
    fun usesAndroidClockPreferenceAndUpdatesWithTheCivilMinute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val time = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 7, 13, 28, 0) }.timeInMillis
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        val twentyFour = formatAtelierDateTime(context, time)
        assertTrue(twentyFour.contains("13:28"))
        assertNotEquals(twentyFour, formatAtelierDateTime(context, time + 60_000))
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        val twelve = formatAtelierDateTime(context, time)
        assertTrue(twelve.contains("1:28"))
        assertFalse(twelve.contains("13:28"))
    }
}
