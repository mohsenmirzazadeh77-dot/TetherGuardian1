package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringService : Service() {
    companion object {
        const val CHANNEL_ID = "trade_monitoring_status"
        const val ALERT_CHANNEL_ID = "trade_monitoring_alerts"
        const val NOTIFICATION_ID = 3101
        const val ALERT_NOTIFICATION_ID = 3102
        const val ACTION_STOP = "com.tetherguardian.app.action.STOP_TRADE_MONITORING"
        const val ACTION_ACK = "com.tetherguardian.app.action.TRADE_ALERT_ACK"
        const val PREFS = "trade_monitoring_state"
        const val KEY_SEVERE_ALERT = "severe_alert_enabled"
    }

    data class Trade(val time: Long, val volume: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val recent = LinkedHashMap<String, Trade>()
    private var loop: Job? = null
    private var alertJob: Job? = null
    private var alertAcknowledged = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("🟢 عادی", false))
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    checkMarket()
                    delay(10_000)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun checkMarket() {
        runCatching {
            val trades = fetchTrades()
            val now = System.currentTimeMillis()
            val cutoff = now - 5 * 60 * 1000L
            for (trade in trades) {
                recent["${trade.time}|${trade.volume}"] = trade
            }
            recent.entries.removeIf { millis(it.value.time) < cutoff }
            val window = recent.values.filter { millis(it.time) >= cutoff }
            val count1000 = window.count { it.volume >= 1000.0 }
            val score = calculateScore(window.size)
            val state = when {
                score >= 70 -> "🟠 هشدار شدید"
                score >= 50 -> "🟠 غیرعادی"
                score >= 30 -> "🟡 تحت نظر"
                else -> "🟢 عادی"
            }
            val enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEVERE_ALERT, true)
            withContext(Dispatchers.Main) {
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(state, enabled))
            }
            if (score >= 70 && enabled && alertJob?.isActive != true) {
                startSevereAlert(score, window.size, count1000)
            }
        }
    }

    private fun fetchTrades(): List<Trade> {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v2/trades/USDTIRT").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val array = JSONObject(response.body?.string() ?: "{}").optJSONArray("trades") ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val time = item.optLong("time", -1L)
                    val volume = item.optString("volume").toDoubleOrNull() ?: continue
                    if (time >= 0 && volume >= 0) add(Trade(time, volume))
                }
            }
        }
    }

    private fun calculateScore(count: Int): Int = min(100, max(0, count - 20) * 4)

    private fun startSevereAlert(score: Int, count: Int, count1000: Int) {
        alertAcknowledged = false
        alertJob = scope.launch {
            val intent = Intent(this@TradeMonitoringService, TradeMonitoringAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("score", score)
                putExtra("trade_count", count)
                putExtra("count_1000", count1000)
            }
            val pending = PendingIntent.getActivity(this@TradeMonitoringService, 3102, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this@TradeMonitoringService, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("هشدار شدید مانیتورینگ معاملات")
                .setContentText("افزایش شدید فعالیت معاملاتی شناسایی شد")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
            delay(30_000)
            if (!alertAcknowledged) cancelAlert()
        }
    }

    private fun cancelAlert() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
        alertJob = null
    }

    private fun buildNotification(state: String, enabled: Boolean): Notification {
        val intent = Intent(this, TradeMonitoringActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 3101, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "وضعیت مانیتورینگ: $state | اجرای هشدار: ${if (enabled) "فعال" else "غیرفعال"}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("📊 مانیتورینگ معاملات")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "وضعیت مانیتورینگ معاملات", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "هشدار شدید مانیتورینگ معاملات", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableVibration(true)
        })
    }

    private fun stopMonitoring() {
        loop?.cancel()
        alertJob?.cancel()
        cancelAlert()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun millis(value: Long): Long = if (value < 10_000_000_000L) value * 1000 else value

    override fun onDestroy() {
        loop?.cancel()
        alertJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
