package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
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
import java.text.DecimalFormat

class MonitoringService : Service() {

    companion object {
        const val CHANNEL_ID = "tether_monitoring"
        const val ALERT_CHANNEL_ID = "tether_alerts"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 2001

        const val ACTION_START = "com.tetherguardian.app.action.START_MONITORING"
        const val ACTION_STOP = "com.tetherguardian.app.action.STOP_MONITORING"
        const val ACTION_REFRESH_ALERT_CHANNEL = "com.tetherguardian.app.action.REFRESH_ALERT_CHANNEL"
        const val ACTION_PRICE_UPDATE = "com.tetherguardian.app.action.PRICE_UPDATE"
        const val ACTION_ALERT_ACKNOWLEDGED = "com.tetherguardian.app.action.ALERT_ACKNOWLEDGED"
        const val ACTION_TEST_SOUND = "com.tetherguardian.app.action.TEST_SOUND"

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
        const val KEY_SOUND_URI = "sound_uri"
        const val KEY_SOUND_TITLE = "sound_title"
    }

    private val api = NobitexApi()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null
    private var alertJob: Job? = null
    private var alertAcknowledged = false
    private var alertPlayer: MediaPlayer? = null
    private val priceFormatter = DecimalFormat("#,##0")

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
            ACTION_REFRESH_ALERT_CHANNEL -> recreateAlertChannel()
            ACTION_ALERT_ACKNOWLEDGED -> acknowledgeAlert()
            ACTION_TEST_SOUND -> {
                startForeground(NOTIFICATION_ID, buildMonitoringNotification(null, null))
                testSelectedSound()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildMonitoringNotification(null, null))
                markActive()
                startPriceLoop()
            }
        }
        return START_STICKY
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
            if (numericPrice <= 0.0) return

            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val oldBase = prefs.getString(KEY_BASE_PRICE, null)?.toDoubleOrNull()
            var basePrice = oldBase
            var baseTime = prefs.getLong(KEY_BASE_TIME, 0L)
            var alertTriggered = false

            if (oldBase == null || numericPrice > oldBase) {
                basePrice = numericPrice
                baseTime = now
                prefs.edit()
                    .putString(KEY_BASE_PRICE, numericPrice.toString())
                    .putLong(KEY_BASE_TIME, baseTime)
                    .apply()
            } else {
                val dropPercent = prefs.getString(KEY_DROP_PERCENT, "0.5")?.toDoubleOrNull() ?: 0.5
                val dropLimit = basePrice * (1.0 - dropPercent / 100.0)

                if (numericPrice <= dropLimit && alertJob?.isActive != true) {
                    val previousBase = basePrice
                    val drop = if (previousBase > 0.0) {
                        ((numericPrice - previousBase) / previousBase) * 100.0
                    } else 0.0

                    basePrice = numericPrice
                    baseTime = now
                    alertTriggered = true

                    prefs.edit()
                        .putString(KEY_BASE_PRICE, numericPrice.toString())
                        .putLong(KEY_BASE_TIME, baseTime)
                        .apply()

                    startAlertCycle(numericPrice, numericPrice, drop)
                }
            }

            val dropPercent = prefs.getString(KEY_DROP_PERCENT, "0.5")?.toDoubleOrNull() ?: 0.5
            val currentDropLimit = basePrice?.let { it * (1.0 - dropPercent / 100.0) } ?: 0.0

            updateMonitoringNotification(numericPrice, currentDropLimit)

            sendBroadcast(Intent(ACTION_PRICE_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_PRICE, numericPrice)
                putExtra(EXTRA_TIME, now)
                putExtra(EXTRA_BASE_PRICE, basePrice ?: numericPrice)
                putExtra(EXTRA_BASE_TIME, baseTime)
                putExtra(EXTRA_DROP_LIMIT, currentDropLimit)
                putExtra(EXTRA_ALERT_TRIGGERED, alertTriggered)
            })
        } catch (_: Exception) {
        }
    }

    private fun startAlertCycle(alertPrice: Double, newBase: Double, drop: Double) {
        if (alertJob?.isActive == true) return

        alertAcknowledged = false
        alertJob = serviceScope.launch {
            showAlert(alertPrice, newBase, drop)
            startAlertSound()

            delay(30_000)

            if (!alertAcknowledged) {
                stopAlertSound()
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
            .setContentText("قیمت به حد هشدار رسیده است")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun dismissAlertSurface() {
        getSystemService(NotificationManager::class.java)
            .cancel(ALERT_NOTIFICATION_ID)
    }
