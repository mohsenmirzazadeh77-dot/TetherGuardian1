package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.tetherguardian.app.action.START_MONITORING"
        const val ACTION_STOP = "com.tetherguardian.app.action.STOP_MONITORING"
        const val ACTION_PRICE_UPDATE = "com.tetherguardian.app.action.PRICE_UPDATE"
        const val EXTRA_PRICE = "extra_price"
        const val EXTRA_TIME = "extra_time"
        const val EXTRA_BASE_PRICE = "extra_base_price"
        const val EXTRA_BASE_TIME = "extra_base_time"
        const val PREFS_NAME = "tether_guardian_state"
        const val KEY_ACTIVE = "active"
        const val KEY_BASE_PRICE = "base_price"
        const val KEY_BASE_TIME = "base_time"
    }

    private val api = NobitexApi()
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var monitoringJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
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
        startForeground(NOTIFICATION_ID, buildNotification())
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

            if (oldBase == null || numericPrice > oldBase) {
                basePrice = numericPrice
                baseTime = now
                prefs.edit()
                    .putString(KEY_BASE_PRICE, numericPrice.toString())
                    .putLong(KEY_BASE_TIME, baseTime)
                    .apply()
            }

            sendBroadcast(
                Intent(ACTION_PRICE_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PRICE, numericPrice)
                    putExtra(EXTRA_TIME, now)
                    putExtra(EXTRA_BASE_PRICE, basePrice ?: numericPrice)
                    putExtra(EXTRA_BASE_TIME, baseTime)
                }
            )
        } catch (_: Exception) {
            // Retry on the next 10-second cycle.
        }
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("نگهبان تتر")
            .setContentText("پایش قیمت تتر فعال است")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "پایش قیمت تتر",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "اعلان دائمی هنگام فعال بودن پایش قیمت تتر"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
