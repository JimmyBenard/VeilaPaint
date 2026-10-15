package studio.nuvora.veila.studio

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local device time for the simulated system status bar. */
fun formatStatusBarTime(context: Context, epochMillis: Long = System.currentTimeMillis()): String {
    val now = Date(epochMillis)
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(now)
}

/** Date projected by the same simulated clock as the work duration. */
fun formatSimulatedStatusDate(epochMillis: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(epochMillis)).lowercase(Locale.FRANCE)
