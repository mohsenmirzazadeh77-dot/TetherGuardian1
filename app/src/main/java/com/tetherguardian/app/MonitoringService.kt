package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
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

        const val CHANNEL_ID =
            "tether_monitoring"

        const val ALERT_CHANNEL_ID =
            "tether_alerts"

        const val NOTIFICATION_ID =
            1001

        const val ALERT_NOTIFICATION_ID =
            2001

        const val ACTION_START =
            "com.tetherguardian.app.action.START_MONITORING"

        const val ACTION_STOP =
            "com.tetherguardian.app.action.STOP_MONITORING"

        const val ACTION_REFRESH_ALERT_CHANNEL =
            "com.tetherguardian.app.action.REFRESH_ALERT_CHANNEL"

        const val ACTION_PRICE_UPDATE =
            "com.tetherguardian.app.action.PRICE_UPDATE"

        const val ACTION_ALERT_ACKNOWLEDGED =
            "com.tetherguardian.app.action.ALERT_ACKNOWLEDGED"

        const val EXTRA_PRICE =
            "extra_price"

        const val EXTRA_TIME =
            "extra_time"

        const val EXTRA_BASE_PRICE =
            "extra_base_price"

        const val EXTRA_BASE_TIME =
            "extra_base_time"

        const val EXTRA_DROP_LIMIT =
            "extra_drop_limit"

        const val EXTRA_ALERT_TRIGGERED =
            "extra_alert_triggered"

        const val EXTRA_ALERT_PRICE =
            "extra_alert_price"

        const val EXTRA_ALERT_BASE =
            "extra_alert_base"

        const val EXTRA_ALERT_DROP =
            "extra_alert_drop"

        const val EXTRA_ALERT_ACKNOWLEDGED =
            "extra_alert_acknowledged"

        const val PREFS_NAME =
            "tether_guardian_state"

        const val KEY_ACTIVE =
            "active"

        const val KEY_BASE_PRICE =
            "base_price"

        const val KEY_BASE_TIME =
            "base_time"

        const val KEY_DROP_PERCENT =
            "drop_percent"

        const val KEY_SOUND_INDEX =
            "sound_index"
    }

    private val api =
        NobitexApi()

    private val serviceScope =
        CoroutineScope(Dispatchers.IO)

    private var monitoringJob: Job? =
        null

    private var alertCycleJob: Job? =
        null

    private var alertAcknowledged =
        false

    private var currentRingtone: Ringtone? =
        null

    override fun onCreate() {

        super.onCreate()

        createNotificationChannels()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {

                stopMonitoring()

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

                stopSelf()

                return START_NOT_STICKY
            }

            ACTION_REFRESH_ALERT_CHANNEL -> {

                recreateAlertChannel()
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

        startForeground(
            NOTIFICATION_ID,
            buildMonitoringNotification()
        )
    }

    private fun startPriceLoop() {

        if (
            monitoringJob?.isActive == true
        ) {
            return
        }

        monitoringJob =
            serviceScope.launch {

                while (isActive) {

                    fetchAndProcessPrice()

                    delay(10_000)
                }
            }
    }

    private suspend fun fetchAndProcessPrice() {

        try {

            val price =
                api.getCurrentPrice(
                    "USDTIRT"
                )

            val numericPrice =
                price.toDoubleOrNull()
                    ?: return

            val now =
                System.currentTimeMillis()

            val prefs =
                getSharedPreferences(
                    PREFS_NAME,
                    MODE_PRIVATE
                )

            val oldBase =
                prefs.getString(
                    KEY_BASE_PRICE,
                    null
                )?.toDoubleOrNull()

            var basePrice =
                oldBase

            var baseTime =
                prefs.getLong(
                    KEY_BASE_TIME,
                    0L
                )

            var alertTriggered =
                false

            /*
             * اولین قیمت:
             * خودش مبنا می‌شود.
             *
             * بعد از آن هر قیمت بالاتر،
             * مبنای جدید می‌شود.
             */
            if (
                oldBase == null ||
                numericPrice > oldBase
            ) {

                basePrice =
                    numericPrice

                baseTime =
                    now

                prefs.edit()
                    .putString(
                        KEY_BASE_PRICE,
                        numericPrice.toString()
                    )
                    .putLong(
                        KEY_BASE_TIME,
                        baseTime
                    )
                    .apply()

            } else {

                val dropPercent =
                    prefs.getString(
                        KEY_DROP_PERCENT,
                        "3.0"
                    )?.toDoubleOrNull()
                        ?: 3.0

                val dropLimit =
                    basePrice!! *
                        (
                            1.0 -
                                dropPercent / 100.0
                            )

                /*
                 * هر قیمت مساوی یا پایین‌تر
                 * از حد هشدار معتبر است.
                 *
                 * منتظر رسیدن دقیق به عدد
                 * محاسبه‌شده نمی‌مانیم.
                 */
                if (
                    numericPrice <= dropLimit
                ) {

                    val previousBase =
                        basePrice

                    val drop =
                        if (
                            previousBase > 0.0
                        ) {
                            (
                                (
                                    numericPrice -
                                        previousBase
                                    ) /
                                        previousBase
                                    ) *
                                    100.0
                        } else {
                            0.0
                        }

                    /*
                     * قیمت واقعی دریافت‌شده
                     * فوراً مبنای جدید می‌شود.
                     */
                    basePrice =
                        numericPrice

                    baseTime =
                        now

                    alertTriggered =
                        true

                    prefs.edit()
                        .putString(
                            KEY_BASE_PRICE,
                            numericPrice.toString()
                        )
                        .putLong(
                            KEY_BASE_TIME,
                            baseTime
                        )
                        .apply()

                    /*
                     * سیستم محاسبه مستقل از
                     * سیستم اطلاع‌رسانی است.
                     */
                    startAlertCycle(
                        numericPrice,
                        numericPrice,
                        drop
                    )
                }
            }

            val dropPercent =
                prefs.getString(
                    KEY_DROP_PERCENT,
                    "3.0"
                )?.toDoubleOrNull()
                    ?: 3.0

            val currentDropLimit =
                basePrice?.let {

                    it *
                        (
                            1.0 -
                                dropPercent / 100.0
                            )

                } ?: 0.0

            /*
             * به‌روزرسانی اعلان دائمی
             */
            updateMonitoringNotification(
                numericPrice,
                currentDropLimit
            )

            sendBroadcast(
                Intent(
                    ACTION_PRICE_UPDATE
                ).apply {

                    setPackage(
                        packageName
                    )

                    putExtra(
                        EXTRA_PRICE,
                        numericPrice
                    )

                    putExtra(
                        EXTRA_TIME,
                        now
                    )

                    putExtra(
                        EXTRA_BASE_PRICE,
                        basePrice
                            ?: numericPrice
                    )

                    putExtra(
                        EXTRA_BASE_TIME,
                        baseTime
                    )

                    putExtra(
                        EXTRA_DROP_LIMIT,
                        currentDropLimit
                    )

                    putExtra(
                        EXTRA_ALERT_TRIGGERED,
                        alertTriggered
                    )
                }
            )

        } catch (_: Exception) {

            /*
             * در صورت خطا، چرخه بعدی
             * دوباره تلاش می‌کند.
             */
        }
    }

    /*
     * =========================================================
     * چرخه هشدار
     * =========================================================
     *
     * فقط دو نوبت:
     *
     * هشدار اول: 30 ثانیه
     * سکوت: 15 ثانیه
     * هشدار دوم: 30 ثانیه
     * پایان
     *
     * بعد از آن صفحه هشدار باقی می‌ماند
     * ولی هیچ هشدار سومی وجود ندارد.
     */
    private fun startAlertCycle(
        alertPrice: Double,
        newBase: Double,
        drop: Double
    ) {

        /*
         * اگر یک چرخه هشدار قبلی هنوز
         * در حال اجراست، چرخه جدیدی
         * روی آن ایجاد نمی‌کنیم.
         */
        if (
            alertCycleJob?.isActive == true
        ) {
            return
        }

        alertAcknowledged =
            false

        alertCycleJob =
            serviceScope.launch {

                /*
                 * -------------------------
                 * هشدار اول
                 * -------------------------
                 */
                showAlertPage(
                    alertPrice,
                    newBase,
                    drop
                )

                playAlertSound()

                delay(30_000)

                if (
                    alertAcknowledged
                ) {
                    stopAlertSound()
                    return@launch
                }

                stopAlertSound()

                /*
                 * -------------------------
                 * سکوت 15 ثانیه
                 * -------------------------
                 */
                delay(15_000)

                if (
                    alertAcknowledged
                ) {
                    return@launch
                }

                /*
                 * -------------------------
                 * هشدار دوم
                 * -------------------------
                 *
                 * Activity جدید باز نمی‌کنیم.
                 *
                 * همان صفحه هشدار قبلی باقی می‌ماند.
                 * فقط صدا و ویبره دوباره اجرا می‌شوند.
                 */
                playAlertSound()

                delay(30_000)

                stopAlertSound()

                /*
                 * پایان کامل چرخه.
                 *
                 * صفحه هشدار بسته نمی‌شود.
                 *
                 * سرویس پایش قیمت همچنان
                 * هر 10 ثانیه فعال است.
                 */
                alertCycleJob = null
            }
    }

    /*
     * فقط یک بار صفحه هشدار را باز می‌کنیم.
     *
     * بنابراین مشکل:
     *
     * روشن → خاموش → روشن → خاموش
     *
     * ایجاد نمی‌شود.
     */
    private fun showAlertPage(
        alertPrice: Double,
        newBase: Double,
        drop: Double
    ) {

        val activityIntent =
            Intent(
                this,
                AlertActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP

                putExtra(
                    EXTRA_ALERT_PRICE,
                    alertPrice
                )

                putExtra(
                    EXTRA_ALERT_BASE,
                    newBase
                )

                putExtra(
                    EXTRA_ALERT_DROP,
                    drop
                )
            }

        startActivity(
            activityIntent
        )

        /*
         * اعلان نیز روی Lock Screen
         * قابل مشاهده خواهد بود.
         */
        showAlertNotification(
            alertPrice,
            newBase,
            drop
        )
    }

    private fun showAlertNotification(
        alertPrice: Double,
        newBase: Double,
        drop: Double
    ) {

        val activityIntent =
            Intent(
                this,
                AlertActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP

                putExtra(
                    EXTRA_ALERT_PRICE,
                    alertPrice
                )

                putExtra(
                    EXTRA_ALERT_BASE,
                    newBase
                )

                putExtra(
                    EXTRA_ALERT_DROP,
                    drop
                )
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                2100,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val price =
            DecimalFormat(
                "#,##0"
            ).format(alertPrice)

        val notification =
            NotificationCompat.Builder(
                this,
                ALERT_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(
                    "هشدار ریزش تتر"
                )
                .setContentText(
                    "قیمت: $price تومان"
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "قیمت: $price تومان\n" +
                                "ریزش: ${
                                    String.format(
                                        "%.2f",
                                        drop
                                    )
                                }٪"
                        )
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(false)
                .setOngoing(true)
                .setVisibility(
                    NotificationCompat.VISIBILITY_PUBLIC
                )
                .setContentIntent(
                    pendingIntent
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            ALERT_NOTIFICATION_ID,
            notification
        )
    }

    /*
     * =========================================================
     * صدای هشدار
     * =========================================================
     *
     * صدا به صورت تکرارشونده اجرا می‌شود
     * تا یک صدای بسیار کوتاه و قابل اشتباه
     * نباشد.
     */
    private fun playAlertSound() {

        stopAlertSound()

        try {

            val uri =
                selectedSoundUri()

            val ringtone =
                RingtoneManager.getRingtone(
                    applicationContext,
                    uri
                )

            ringtone.audioAttributes =
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_NOTIFICATION_EVENT
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()

            currentRingtone =
                ringtone

            ringtone.play()

        } catch (_: Exception) {
        }
    }

    private fun stopAlertSound() {

        try {

            currentRingtone?.stop()

        } catch (_: Exception) {
        }

        currentRingtone =
            null
    }

    private fun selectedSoundUri(): Uri {

        val index =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            ).getInt(
                KEY_SOUND_INDEX,
                0
            )

        return when (index) {

            1 ->
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_ALARM
                )

            2 ->
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_RINGTONE
                )

            else ->
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION
                )
        }
    }

    private fun acknowledgeAlert() {

        alertAcknowledged =
            true

        alertCycleJob?.cancel()

        alertCycleJob =
            null

        stopAlertSound()

        dismissAlertSurface()
    }

    private fun dismissAlertSurface() {

        getSystemService(
            NotificationManager::class.java
        ).cancel(
            ALERT_NOTIFICATION_ID
        )
    }

    private fun markActive() {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_ACTIVE,
                true
            )
            .apply()
    }

    private fun stopMonitoring() {

        monitoringJob?.cancel()

        monitoringJob =
            null

        alertCycleJob?.cancel()

        alertCycleJob =
            null

        alertAcknowledged =
            true

        stopAlertSound()

        dismissAlertSurface()

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .edit()
            .clear()
            .apply()
    }

    /*
     * =========================================================
     * اعلان دائمی پایش
     * =========================================================
     */
    private fun buildMonitoringNotification(): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_popup_sync
            )
            .setContentTitle(
                "نگهبان تتر — پایش فعال"
            )
            .setContentText(
                "در حال دریافت قیمت..."
            )
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .setVisibility(
                NotificationCompat.VISIBILITY_PUBLIC
            )
            .build()
    }

    private fun updateMonitoringNotification(
        price: Double,
        dropLimit: Double
    ) {

        val priceText =
            DecimalFormat(
                "#,##0"
            ).format(price)

        val limitText =
            DecimalFormat(
                "#,##0"
            ).format(dropLimit)

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_popup_sync
                )
                .setContentTitle(
                    "نگهبان تتر — پایش فعال"
                )
                .setContentText(
                    "قیمت: $priceText تومان | حد هشدار: $limitText تومان"
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .setVisibility(
                    NotificationCompat.VISIBILITY_PUBLIC
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            notification
        )
    }

    /*
     * =========================================================
     * Notification Channels
     * =========================================================
     */
    private fun createNotificationChannels() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val monitoringChannel =
            NotificationChannel(
                CHANNEL_ID,
                "پایش قیمت تتر",
                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    "اعلان دائمی هنگام فعال بودن پایش قیمت تتر"

                setShowBadge(false)
            }

        manager.createNotificationChannel(
            monitoringChannel
        )

        recreateAlertChannel()
    }

    private fun recreateAlertChannel() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.deleteNotificationChannel(
            ALERT_CHANNEL_ID
        )

        val alertChannel =
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "هشدارهای ریزش تتر",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "هشدار صوتی و نمایشی ریزش قیمت تتر"

                enableVibration(true)

                vibrationPattern =
                    longArrayOf(
                        0,
                        700,
                        300,
                        700,
                        300,
                        900
                    )

                /*
                 * صدای خود Notification Channel
                 * خاموش است.
                 *
                 * صدا را خود سرویس کنترل می‌کند
                 * تا بتوانیم دقیقاً 30 ثانیه
                 * روشن و سپس خاموش کنیم.
                 */
                setSound(
                    null,
                    null
                )

                lockscreenVisibility =
                    NotificationCompat.VISIBILITY_PUBLIC
            }

        manager.createNotificationChannel(
            alertChannel
        )
    }

    override fun onDestroy() {

        monitoringJob?.cancel()

        alertCycleJob?.cancel()

        stopAlertSound()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
