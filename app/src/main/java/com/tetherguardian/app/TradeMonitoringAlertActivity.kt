package com.tetherguardian.app

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TradeMonitoringAlertActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var acknowledged = false
    private var alertPlayer: MediaPlayer? = null
    private val stopSoundRunnable = Runnable { stopSound() }
    private val endDisplay = Runnable { if (!acknowledged) { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContentView(R.layout.trade_monitoring_alert)
        findViewById<TextView>(R.id.tradeAlertScoreText).text = "امتیاز هشدار: ${intent.getIntExtra("score", 0)}/100"
        findViewById<TextView>(R.id.tradeAlertReasonText).text = intent.getStringExtra("reason") ?: "رفتار غیرعادی معاملات شناسایی شد"
        findViewById<TextView>(R.id.tradeAlertCountText).text = "تعداد معاملات ۵ دقیقه اخیر: ${intent.getIntExtra("trade_count", 0)}"
        findViewById<TextView>(R.id.tradeAlertLargeCountText).text = "تعداد معاملات ≥ ۱۰۰۰ تتر: ${intent.getIntExtra("count_1000", 0)}"
        findViewById<Button>(R.id.tradeAlertAcknowledgeButton).setOnClickListener { acknowledgeAndClose() }
        findViewById<Button>(R.id.tradeAlertMonitorButton).setOnClickListener {
            acknowledged = true
            stopSound()
            getSystemService(android.app.NotificationManager::class.java).cancel(TradeMonitoringService.ALERT_NOTIFICATION_ID)
            handler.removeCallbacks(endDisplay)
            notifyAlertFinished()
            startActivity(Intent(this, TradeMonitoringActivity::class.java))
            finish()
        }
        playSelectedSound()
        handler.postDelayed(stopSoundRunnable, 10_000)
        handler.postDelayed(endDisplay, 10_000)
    }

    private fun playSelectedSound() {
        val prefs = getSharedPreferences(TradeMonitoringService.PREFS, MODE_PRIVATE)
        val uri = prefs.getString(TradeMonitoringService.KEY_SOUND_URI, null)?.let(Uri::parse)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            val player = MediaPlayer.create(applicationContext, uri) ?: return
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            player.isLooping = true
            alertPlayer = player
            player.start()
        }
    }

    private fun stopSound() {
        try { alertPlayer?.stop() } catch (_: Exception) { }
        try { alertPlayer?.release() } catch (_: Exception) { }
        alertPlayer = null
    }

    private fun notifyAlertFinished() {
        sendBroadcast(Intent(TradeMonitoringService.ACTION_ALERT_FINISHED).apply { setPackage(packageName) })
    }

    private fun acknowledgeAndClose() {
        if (acknowledged) return
        acknowledged = true
        handler.removeCallbacks(stopSoundRunnable)
        handler.removeCallbacks(endDisplay)
        stopSound()
        getSystemService(android.app.NotificationManager::class.java).cancel(TradeMonitoringService.ALERT_NOTIFICATION_ID)
        notifyAlertFinished()
        finish()
    }

    override fun onBackPressed() { acknowledgeAndClose() }
    override fun onDestroy() { handler.removeCallbacks(stopSoundRunnable); handler.removeCallbacks(endDisplay); stopSound(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); notifyAlertFinished(); super.onDestroy() }
}
