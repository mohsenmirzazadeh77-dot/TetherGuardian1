package com.tetherguardian.app

import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
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
    private var ringtone: Ringtone? = null
    private val endDisplay = Runnable { if (!acknowledged) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

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
            acknowledged = true; stopSound(); getSystemService(android.app.NotificationManager::class.java).cancel(TradeMonitoringService.ALERT_NOTIFICATION_ID)
            handler.removeCallbacks(endDisplay); startActivity(Intent(this, TradeMonitoringActivity::class.java)); finish()
        }
        playSelectedSound(); handler.postDelayed(endDisplay, 30_000)
    }

    private fun playSelectedSound() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val uri = prefs.getString(MonitoringService.KEY_SOUND_URI, null)?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching { ringtone = RingtoneManager.getRingtone(this, uri); ringtone?.play() }
    }
    private fun stopSound() { runCatching { ringtone?.stop() }; ringtone = null }
    private fun acknowledgeAndClose() {
        if (acknowledged) return; acknowledged = true; handler.removeCallbacks(endDisplay); stopSound()
        getSystemService(android.app.NotificationManager::class.java).cancel(TradeMonitoringService.ALERT_NOTIFICATION_ID); finish()
    }
    override fun onBackPressed() { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); moveTaskToBack(true) }
    override fun onDestroy() { handler.removeCallbacks(endDisplay); stopSound(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onDestroy() }
}
