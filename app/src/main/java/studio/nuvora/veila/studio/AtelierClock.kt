package studio.nuvora.veila.studio

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Civil projection of the monotonic simulated work clock. */
fun formatAtelierDateTime(context: Context, epochMillis: Long): String {
    val now = Date(epochMillis)
    // Seconds are deliberately explicit so accelerated time shows each simulated second.
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a"
    val time = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
    return "${DateFormat.getDateFormat(context).format(now)} · $time"
}
