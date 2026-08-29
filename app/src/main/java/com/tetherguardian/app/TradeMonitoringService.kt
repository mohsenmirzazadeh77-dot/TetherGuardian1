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
        const val KEY_SOUND_URI = "trade_monitoring_sound_uri"
        const val KEY_LAST_SCORE = "last_score"
        const val KEY_LAST_BUY = "last_buy_pressure"
        const val KEY_LAST_SELL = "last_sell_pressure"
        const val KEY_LAST_COUNT = "last_trade_count"
        const val KEY_LAST_COUNT_1000 = "last_count_1000"
        const val KEY_LAST_REASON = "last_reason"

        const val ACTION_START = "com.tetherguardian.app.action.TRADE_MONITOR_START"
        const val ACTION_STOP = "com.tetherguardian.app.action.TRADE_MONITOR_STOP"
        const val ACTION_REFRESH = "com.tetherguardian.app.action.TRADE_MONITOR_REFRESH"
        const val ACTION_STATUS_UPDATE = "com.tetherguardian.app.action.TRADE_MONITOR_STATUS_UPDATE"
        const val ACTION_ALERT_FINISHED = "com.tetherguardian.app.action.TRADE_MONITOR_ALERT_FINISHED"

        const val EXTRA_SCORE = "score"
        const val EXTRA_BUY_PRESSURE = "buy_pressure"
        const val EXTRA_SELL_PRESSURE = "sell_pressure"
        const val EXTRA_TRADE_COUNT = "trade_count"
        const val EXTRA_COUNT_1000 = "count_1000"
        const val EXTRA_REASON = "reason"

        const val ALERT_NOTIFICATION_ID = 4202
        private const val CHANNEL_ID = "trade_monitoring_channel"
        private const val ALERT_CHANNEL_ID = "trade_monitoring_alert_channel"
        private const val NOTIFICATION_ID = 4201
    }

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var severeRearmJob: Job? = null
    private val trades = LinkedHashMap<String, Trade>()
    private var severeAlreadyShown = false
    private data class Trade(val time: Long, val price: Double, val volume: Double, val type: String)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("مانیتورینگ فعال • در حال دریافت معاملات..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_ALERT_FINISHED -> {
                severeAlreadyShown = false
                severeRearmJob?.cancel()
                severeRearmJob = null
                getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
            }
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
        job?.cancel()
        job = null
        severeRearmJob?.cancel()
        severeRearmJob = null
        severeAlreadyShown = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun monitorOnce() {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v2/trades/USDTIRT").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val array = JSONObject(response.body?.string() ?: "{}").optJSONArray("trades") ?: return
            val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val time = item.optLong("time", -1L)
                val price = item.optString("price").toDoubleOrNull()
                val volume = item.optString("volume").toDoubleOrNull()
                val type = item.optString("type", "unknown")
                if (time >= 0 && price != null && volume != null) {
                    trades["$time|$price|$volume|$type"] = Trade(time, price, volume, type)
                }
            }

            trades.entries.removeIf { toMillis(it.value.time) < cutoff }
            val window = trades.values.filter { toMillis(it.time) >= cutoff }.sortedBy { toMillis(it.time) }
            val buy = window.filter { it.type.equals("buy", true) }.sumOf { it.volume }
            val sell = window.filter { it.type.equals("sell", true) }.sumOf { it.volume }
            val total = buy + sell
            val buyPct = if (total > 0) buy / total * 100 else 50.0
            val sellPct = 100.0 - buyPct
            val activity = min(45.0, max(0.0, window.size.toDouble() - 20.0) * 1.5)
            val baseScore = (min(55.0, abs(buyPct - sellPct) * 1.1) + activity).toInt()

            val largeTrades = window.filter { it.volume >= 1000.0 }
            val largeCount = largeTrades.size
            val largeBuyVolume = largeTrades.filter { it.type.equals("buy", true) }.sumOf { it.volume }
            val largeSellVolume = largeTrades.filter { it.type.equals("sell", true) }.sumOf { it.volume }
            val largeTotalVolume = largeBuyVolume + largeSellVolume
            val largeDirectionStrength = if (largeTotalVolume > 0) {
                abs(largeBuyVolume - largeSellVolume) / largeTotalVolume
            } else 0.0
            val largeDirectionBonus = min(10.0, largeDirectionStrength * 10.0)
            val maxLargeVolume = largeTrades.maxOfOrNull { it.volume } ?: 0.0
            val largeSizeBonus = if (maxLargeVolume >= 1000.0) {
                min(8.0, max(0.0, maxLargeVolume / 1000.0 - 1.0) * 1.5)
            } else 0.0
            val auxiliaryLargeBonus = min(8.0, largeCount.toDouble() * 2.0)

            var score = baseScore + auxiliaryLargeBonus + largeDirectionBonus + largeSizeBonus
            if (largeCount >= 5) score = max(score, 70.0)
            val finalScore = min(100.0, score)

            val reason = if (largeTotalVolume > 0 && largeBuyVolume > largeSellVolume) {
                "فشار خرید قوی در معاملات بزرگ"
            } else if (largeTotalVolume > 0 && largeSellVolume > largeBuyVolume) {
                "فشار فروش قوی در معاملات بزرگ"
            } else if (sellPct > buyPct) {
                "افزایش شدید احتمال ریزش"
            } else {
                "افزایش شدید احتمال صعود"
            }

            val scoreInt = finalScore.toInt()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_LAST_SCORE, scoreInt)
                .putString(KEY_LAST_BUY, buyPct.toString())
                .putString(KEY_LAST_SELL, sellPct.toString())
                .putInt(KEY_LAST_COUNT, window.size)
                .putInt(KEY_LAST_COUNT_1000, largeCount)
                .putString(KEY_LAST_REASON, reason)
                .apply()

            updateNotification("وضعیت: ${state(scoreInt)} • ${window.size} معامله • ≥۱۰۰۰ تتر: $largeCount")
            sendStatus(scoreInt, buyPct, sellPct, window.size, largeCount, reason)

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SEVERE_ALERT, true) && scoreInt >= 70 && !severeAlreadyShown) {
                severeAlreadyShown = true
                showSevereAlert(scoreInt, reason, buyPct, sellPct, window.size, largeCount)
            }
            if (scoreInt < 60) {
                severeAlreadyShown = false
                severeRearmJob?.cancel()
                severeRearmJob = null
            }
        }
    }

    private fun showSevereAlert(score: Int, reason: String, buyPct: Double, sellPct: Double, count: Int, count1000: Int) {
        val activityIntent = Intent(this, TradeMonitoringAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_REASON, reason)
            putExtra(EXTRA_BUY_PRESSURE, buyPct)
            putExtra(EXTRA_SELL_PRESSURE, sellPct)
            putExtra(EXTRA_TRADE_COUNT, count)
            putExtra(EXTRA_COUNT_1000, count1000)
        }
        val pendingIntent = PendingIntent.getActivity(this, 4203, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tether_eye)
            .setContentTitle("نگهبان تتر • هشدار شدید معاملات")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)

        severeRearmJob?.cancel()
        severeRearmJob = scope.launch {
            delay(11_000L)
            severeAlreadyShown = false
            severeRearmJob = null
        }
    }

    private fun sendStatus(score: Int, buyPct: Double, sellPct: Double, count: Int, count1000: Int, reason: String) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_BUY_PRESSURE, buyPct)
            putExtra(EXTRA_SELL_PRESSURE, sellPct)
            putExtra(EXTRA_TRADE_COUNT, count)
            putExtra(EXTRA_COUNT_1000, count1000)
            putExtra(EXTRA_REASON, reason)
        })
    }

    private fun state(score: Int) = when {
        score >= 70 -> "هشدار شدید"
        score >= 50 -> "غیرعادی"
        score >= 30 -> "تحت نظر"
        else -> "عادی"
    }

    private fun toMillis(value: Long) = if (value < 10_000_000_000L) value * 1000L else value

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "مانیتورینگ معاملات", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "هشدار شدید معاملات", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setSound(null, null)
            })
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tether_eye)
        .setContentTitle("نگهبان تتر • مانیتورینگ معاملات")
        .setContentText(text)
        .setOngoing(true)
        .setNumber(0)
        .setShowWhen(false)
        .setContentIntent(PendingIntent.getActivity(this, 4202, Intent(this, TradeMonitoringActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        severeRearmJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
