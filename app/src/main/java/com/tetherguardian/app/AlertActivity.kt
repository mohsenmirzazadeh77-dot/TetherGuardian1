package com.tetherguardian.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat
import java.util.Locale

class AlertActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var acknowledged = false

    private lateinit var priceText: TextView
    private lateinit var baseText: TextView
    private lateinit var dropText: TextView
    private lateinit var acknowledgeButton: Button
    private lateinit var delayedButton: Button

    private val autoClose = Runnable {
        if (!acknowledged) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, null)
        }

        setContentView(R.layout.activity_alert)

        priceText = findViewById(R.id.alertPriceText)
        baseText = findViewById(R.id.alertBaseText)
        dropText = findViewById(R.id.alertDropText)
        acknowledgeButton = findViewById(R.id.acknowledgeButton)
        delayedButton = findViewById(R.id.delayedButton)

        val price = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_PRICE, 0.0)
        val base = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_BASE, 0.0)
        val drop = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_DROP, 0.0)

        priceText.text = formatPrice(price)
        baseText.text = formatPrice(base)
        dropText.text = String.format(Locale.US, "%+.2f%%", drop)

        acknowledgeButton.setOnClickListener { acknowledgeAndClose() }
        delayedButton.setOnClickListener { acknowledgeAndClose() }

        handler.postDelayed(autoClose, 30_000)
    }

    private fun acknowledgeAndClose() {
        if (acknowledged) return
        acknowledged = true

        startService(
            Intent(this, MonitoringService::class.java)
                .setAction(MonitoringService.ACTION_ALERT_ACKNOWLEDGED)
                .putExtra(MonitoringService.EXTRA_ALERT_ACKNOWLEDGED, true)
        )

        finish()
    }

    override fun onBackPressed() {
        // Back does not acknowledge the alert.
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }

    private fun formatPrice(value: Double): String {
        return if (value > 0) {
            DecimalFormat("#,##0.########").format(value) + " تومان"
        } else "--"
    }
}
