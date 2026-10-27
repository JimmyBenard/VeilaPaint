package studio.nuvora.veila.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.nuvora.veila.studio.formatSimulatedStatusDate
import studio.nuvora.veila.studio.formatStatusBarTime

private data class BatteryStatus(val percent: Int?, val charging: Boolean)

@Composable
private fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    var state by remember(context) { mutableStateOf(batteryStatus(null)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state = batteryStatus(intent)
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        state = batteryStatus(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun batteryStatus(intent: Intent?): BatteryStatus {
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
    return BatteryStatus(percent, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

@Composable
fun SimulatedStatusBar(
    simulatedEpochMillis: Long,
    workMillis: Long,
    onResetDate: () -> Unit,
    onResetWorkTime: () -> Unit,
) {
    val battery = rememberBatteryStatus()
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            formatStatusBarTime(simulatedEpochMillis),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onResetWorkTime).semantics { contentDescription = "Réinitialiser le temps simulé" },
        )
        Text(
            formatSimulatedStatusDate(simulatedEpochMillis),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onResetDate).semantics { contentDescription = "Réinitialiser la date simulée" },
        )
        Spacer(Modifier.weight(1f))
        SilentIcon()
        WifiIcon()
        Text(battery.percent?.let { "$it%" } ?: "—", color = Color.White, fontSize = 13.sp)
        BatteryIcon(battery.percent, battery.charging)
    }
}

@Composable
private fun SilentIcon() = Canvas(Modifier.size(16.dp)) {
    val speaker = Path().apply {
        moveTo(size.width * .10f, size.height * .40f)
        lineTo(size.width * .36f, size.height * .40f)
        lineTo(size.width * .62f, size.height * .18f)
        lineTo(size.width * .62f, size.height * .82f)
        lineTo(size.width * .36f, size.height * .60f)
        lineTo(size.width * .10f, size.height * .60f)
        close()
    }
    drawPath(speaker, Color.White)
    drawLine(Color.White, Offset(size.width * .16f, size.height * .12f), Offset(size.width * .87f, size.height * .88f), 1.8.dp.toPx(), StrokeCap.Round)
}

@Composable
private fun WifiIcon() = Canvas(Modifier.size(17.dp)) {
    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawArc(Color.White, 218f, 104f, false, Offset(0f, 0f), Size(size.width, size.height), style = stroke)
    drawArc(Color.White, 218f, 104f, false, Offset(size.width * .19f, size.height * .19f), Size(size.width * .62f, size.height * .62f), style = stroke)
    drawCircle(Color.White, 1.5.dp.toPx(), Offset(size.width / 2f, size.height * .77f))
}

@Composable
private fun BatteryIcon(percent: Int?, charging: Boolean) = Canvas(Modifier.width(22.dp).height(13.dp)) {
    val stroke = 1.2.dp.toPx()
    drawRoundRect(Color.White, Offset(0f, 0f), Size(size.width - 3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
    drawRoundRect(Color.White, Offset(size.width - 2.2.dp.toPx(), size.height * .32f), Size(2.2.dp.toPx(), size.height * .36f), CornerRadius(1.dp.toPx()))
    val fill = percent?.let { ((size.width - 3.dp.toPx() - 2 * stroke) * (it / 100f)).coerceAtLeast(0f) } ?: 0f
    if (fill > 0f) drawRoundRect(Color.White, Offset(stroke, stroke), Size(fill, size.height - 2 * stroke), CornerRadius(1.dp.toPx()))
    if (charging) {
        val bolt = Path().apply {
            moveTo(size.width * .56f, size.height * .12f); lineTo(size.width * .38f, size.height * .53f)
            lineTo(size.width * .55f, size.height * .53f); lineTo(size.width * .42f, size.height * .90f)
            lineTo(size.width * .70f, size.height * .44f); lineTo(size.width * .53f, size.height * .44f); close()
        }
        drawPath(bolt, Panel)
    }
}
