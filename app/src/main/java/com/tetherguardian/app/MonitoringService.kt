package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tetherguardian.app.data.NobitexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {

    companion object {
        const val CHANNEL_ID = "tether_monitoring"
        const val ALERT_CHANNEL_ID = "tether_alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 2001
        const val ACTION_START = "com.tetherguardian.app.action.START_MONITORING"
        const val ACTION_STOP = "com.tetherguardian.app.action.STOP_MONITORING"
        const val ACTION_PRICE_UPDATE = "com.tetherguardian.app.action.PRICE_UPDATE"
        const val ACTION_ALERT_ACKNOWLEDGED = "com.tetherguardian.app.action.ALERT_ACKNOWLEDGED"
        const val EXTRA_PRICE = "extra_price"
        const val EXTRA_TIME = "extra_time"
        const val EXTRA_BASE_PRICE = "extra_base_price"
        const val EXTRA_BASE_TIME = "extra_base_time"
        const val EXTRA_DROP_LIMIT = "extra_drop_limit"
        const val EXTRA_ALERT_TRIGGERED = "extra_alert_triggered"
        const val EXTRA_ALERT_PRICE = "extra_alert_price"
        const val EXTRA_ALERT_BASE = "extra_alert_base"
        const val EXTRA_ALERT_DROP = "extra_alert_drop"
        const val EXTRA_ALERT_ACKNOWLEDGED = "extra_alert_acknowledged"
        const val PREFS_NAME = "tether_guardian_state"
        const val KEY_ACTIVE = "active"
        const val KEY_BASE_PRICE = "base_price"
        const val KEY_BASE_TIME = "base_time"
        const val KEY_DROP_PERCENT = "drop_percent"
        const val KEY_SOUND_INDEX = "sound_index"
    }

    private val api = NobitexApi()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var alertCycleJob: Job? = null
    private var alertAcknowledged = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ALERT_ACKNOWLEDGED -> {
                acknowledgeAlert()
            }
            else -> {
                startMonitoringForeground()
                markActive()
                startPriceLoop()
            }
        }
        return START_STICKY
    }

    private fun startMonitoringForeground() {
        startForeground(NOTIFICATION_ID, buildMonitoringNotification())
    }

    private fun startPriceLoop() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = serviceScope.launch {
            while (isActive) {
                fetchAndProcessPrice()
                delay(10_000)
            }
        }
    }

    private suspend fun fetchAndProcessPrice() {
        try {
            val price = api.getCurrentPrice("USDTIRT")
            val numericPrice = price.toDoubleOrNull() ?: return
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            val oldBase = prefs.getString(KEY_BASE_PRICE, null)?.toDoubleOrNull()
            var basePrice = oldBase
            var baseTime = prefs.getLong(KEY_BASE_TIME, 0L)
            var alertTriggered = false
            var alertDrop = 0.0

            if (oldBase == null || numericPrice > oldBase) {
                basePrice = numericPrice
                baseTime = now
                prefs.edit()
                    .putString(KEY_BASE_PRICE, numericPrice.toString())
                    .putLong(KEY_BASE_TIME, baseTime)
                    .apply()
            } else {
                val dropPercent = prefs.getString(KEY_DROP_PERCENT, "3.0")?.toDoubleOrNull() ?: 3.0
                val dropLimit = basePrice * (1.0 - dropPercent / 100.0)

                if (numericPrice <= dropLimit) {
                    val previousBase = basePrice
                    alertDrop = if (previousBase > 0.0) {
                        ((numericPrice - previousBase) / previousBase) * 100.0
                    } else 0.0

                    // Immediately establish the first real price at/below the limit as the new base.
                    basePrice = numericPrice
                    baseTime = now
                    alertTriggered = true

                    prefs.edit()
                        .putString(KEY_BASE_PRICE, numericPrice.toString())
                        .putLong(KEY_BASE_TIME, baseTime)
                        .apply()

                    startAlertCycle(
                        alertPrice = numericPrice,
                        newBase = numericPrice,
                        drop = alertDrop
                    )
                }
            }

            val dropPercent = prefs.getString(KEY_DROP_PERCENT, "3.0")?.toDoubleOrNull() ?: 3.0
            val currentDropLimit = basePrice?.let {
                it * (1.0 - dropPercent / 100.0)
            } ?: 0.0

            sendBroadcast(
                Intent(ACTION_PRICE_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PRICE, numericPrice)
                    putExtra(EXTRA_TIME, now)
                    putExtra(EXTRA_BASE_PRICE, basePrice ?: numericPrice)
                    putExtra(EXTRA_BASE_TIME, baseTime)
                    putExtra(EXTRA_DROP_LIMIT, currentDropLimit)
                    putExtra(EXTRA_ALERT_TRIGGERED, alertTriggered)
                }
            )
        } catch (_: Exception) {
            // Retry on the next 10-second cycle.
        }
    }

    private fun startAlertCycle(alertPrice: Double, newBase: Double, drop: Double) {
        // One visual/audio alert cycle at a time. Price calculation never stops.
        if (alertCycleJob?.isActive == true) return

        alertAcknowledged = false
        alertCycleJob = serviceScope.launch {
            while (isActive && !alertAcknowledged) {
                showAlert(alertPrice, newBase, drop)
                delay(30_000)
                if (alertAcknowledged) break

                dismissAlertSurface()
                delay(15_000)
                if (alertAcknowledged) break
            }
        }
    }

    private fun showAlert(alertPrice: Double, newBase: Double, drop: Double) {
        val activityIntent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_PRICE, alertPrice)
            putExtra(EXTRA_ALERT_BASE, newBase)
            putExtra(EXTRA_ALERT_DROP, drop)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2100,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("هشدار ریزش تتر")
            .setContentText("قیمت از مبنای قبلی به حد هشدار رسیده است")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun dismissAlertSurface() {
        getSystemService(NotificationManager::class.java)
            .cancel(ALERT_NOTIFICATION_ID)
    }

    private fun acknowledgeAlert() {
        alertAcknowledged = true
        alertCycleJob?.cancel()
        alertCycleJob = null
        dismissAlertSurface()
    }

    private fun markActive() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        alertCycleJob?.cancel()
        alertCycleJob = null
        alertAcknowledged = true
        dismissAlertSurface()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun buildMonitoringNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("نگهبان تتر")
            .setContentText("پایش قیمت تتر فعال است")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val monitoringChannel = NotificationChannel(
            CHANNEL_ID,
            "پایش قیمت تتر",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "اعلان دائمی هنگام فعال بودن پایش قیمت تتر"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "هشدارهای ریزش تتر",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "هشدار صوتی و نمایشی ریزش قیمت تتر"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 900)
            setSound(selectedSoundUri(), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(monitoringChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun selectedSoundUri(): Uri {
        val index = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_SOUND_INDEX, 0)
        return when (index) {
            1 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            2 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        alertCycleJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
