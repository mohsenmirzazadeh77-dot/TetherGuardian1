package com.tetherguardian.app

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_trade_monitoring_alert)

        findViewById<TextView>(R.id.tradeAlertScore).text = "امتیاز هشدار: ${intent.getIntExtra("score", 0)}/100"
        findViewById<TextView>(R.id.tradeAlertCount).text = "معاملات ۵ دقیقه اخیر: ${intent.getIntExtra("trade_count", 0)}"
        findViewById<TextView>(R.id.tradeAlert1000).text = "معاملات ≥ ۱۰۰۰ تتر: ${intent.getIntExtra("count_1000", 0)}"
        findViewById<Button>(R.id.tradeAlertAcknowledge).setOnClickListener { acknowledge() }
        handler.postDelayed({ if (!acknowledged) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }, 30_000)
    }

    private fun acknowledge() {
        if (acknowledged) return
        acknowledged = true
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startService(Intent(this, TradeMonitoringService::class.java).setAction(TradeMonitoringService.ACTION_ACK))
        finish()
    }

    override fun onBackPressed() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
