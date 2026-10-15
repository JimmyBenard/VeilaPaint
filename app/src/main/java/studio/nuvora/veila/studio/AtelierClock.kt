package studio.nuvora.veila.studio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Time projected by the simulated clock, deliberately fixed to Android-style HH:mm. */
fun formatStatusBarTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(epochMillis))

/** Date projected by the same simulated clock as the work duration. */
fun formatSimulatedStatusDate(epochMillis: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(epochMillis)).lowercase(Locale.FRANCE)
