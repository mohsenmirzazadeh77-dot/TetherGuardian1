package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringService : Service() {
    companion object {
        const val PREFS = "trade_monitoring_prefs"
        const val KEY_SEVERE_ALERT = "severe_alert_enabled"
        const val KEY_ACTIVE = "trade_monitoring_active"
        const val KEY_SOUND_URI = "trade_alert_sound_uri"
        const val ACTION_START = "com.tetherguardian.app.action.TRADE_MONITOR_START"
        const val ACTION_STOP = "com.tetherguardian.app.action.TRADE_MONITOR_STOP"
        const val ACTION_REFRESH = "com.tetherguardian.app.action.TRADE_MONITOR_REFRESH"
        const val ALERT_NOTIFICATION_ID = 4202
        private const val CHANNEL_ID = "trade_monitoring_channel"
        private const val NOTIFICATION_ID = 4201
    }

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val trades = LinkedHashMap<String, Trade>()
    private var severeAlreadyShown = false
    private data class Trade(val time: Long, val price: Double, val volume: Double, val type: String)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("وضعیت: عادی • در حال پایش معاملات"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_START, ACTION_REFRESH, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { monitorOnce() }
                delay(10_000L)
            }
        }
    }

    private fun stopMonitoring() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        job?.cancel(); job = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun monitorOnce() {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v2/trades/USDTIRT").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val root = JSONObject(response.body?.string() ?: "{}")
            val array = root.optJSONArray("trades") ?: return
            val now = System.currentTimeMillis()
            val cutoff = now - 5 * 60 * 1000L
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val time = item.optLong("time", -1L)
                val price = item.optString("price").toDoubleOrNull()
                val volume = item.optString("volume").toDoubleOrNull()
                val type = item.optString("type", "unknown")
                if (time < 0 || price == null || volume == null) continue
                trades["$time|$price|$volume|$type"] = Trade(time, price, volume, type)
            }
            trades.entries.removeIf { toMillis(it.value.time) < cutoff }
            val window = trades.values.filter { toMillis(it.time) >= cutoff }.sortedBy { toMillis(it.time) }
            val buy = window.filter { it.type.equals("buy", true) }.sumOf { it.volume }
            val sell = window.filter { it.type.equals("sell", true) }.sumOf { it.volume }
            val total = buy + sell
            val buyPct = if (total > 0) buy / total * 100 else 50.0
            val sellPct = 100.0 - buyPct
            val imbalance = abs(buyPct - sellPct)
            val activity = min(45.0, max(0.0, window.size - 20) * 1.5)
            val score = min(100, (min(55.0, imbalance * 1.1) + activity).toInt())
            val largeCount = window.count { it.volume >= 1000.0 }
            updateNotification("وضعیت: ${state(score)} • ${window.size} معامله • ≥۱۰۰۰ تتر: $largeCount")
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SEVERE_ALERT, true) && score >= 70 && !severeAlreadyShown) {
                severeAlreadyShown = true
                val alert = Intent(this, TradeMonitoringAlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("score", score)
                    putExtra("reason", if (sellPct > buyPct) "افزایش شدید احتمال ریزش" else "افزایش شدید احتمال صعود")
                    putExtra("buy_pressure", buyPct)
                    putExtra("sell_pressure", sellPct)
                }
                ContextCompat.startActivity(this, alert, null)
            }
            if (score < 60) severeAlreadyShown = false
        }
    }

    private fun state(score: Int) = when {
        score >= 70 -> "هشدار شدید"
        score >= 50 -> "غیرعادی"
        score >= 30 -> "تحت نظر"
        else -> "عادی"
    }

    private fun toMillis(value: Long) = if (value < 10_000_000_000L) value * 1000L else value

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "مانیتورینگ معاملات", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tether_eye)
        .setContentTitle("نگهبان تتر • مانیتورینگ معاملات")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 4202, Intent(this, TradeMonitoringActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        .build()

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
}
