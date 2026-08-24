package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MonitoringService : Service() {

    companion object {
        const val CHANNEL_ID = "tether_monitoring"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.tetherguardian.app.action.START_MONITORING"
        const val ACTION_STOP = "com.tetherguardian.app.action.STOP_MONITORING"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitoringForeground()
        }

        // Android may recreate the service after termination.
        // The actual monitoring loop will be added in the next stage.
        return START_STICKY
    }

    private fun startMonitoringForeground() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
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

    override fun onBind(intent: Intent?): IBinder? = null
}
