package com.tetherguardian.app

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

    private val endAlertDisplay = Runnable {
        if (!acknowledged) {
            // The alert remains as an activity, but it must no longer keep
            // the display awake. While the phone is locked, hide this window;
            // after the user unlocks, Android can show the activity again.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(false)
                setShowWhenLocked(false)
            }
        }
    }

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

        setContentView(R.layout.activity_alert)

        priceText = findViewById(R.id.alertPriceText)
        baseText = findViewById(R.id.alertBaseText)
        dropText = findViewById(R.id.alertDropText)
        acknowledgeButton = findViewById(R.id.acknowledgeButton)

        val price = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_PRICE, 0.0)
        val base = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_BASE, 0.0)
        val drop = intent.getDoubleExtra(MonitoringService.EXTRA_ALERT_DROP, 0.0)

        priceText.text = formatPrice(price)
        baseText.text = formatPrice(base)
        dropText.text = String.format(Locale.US, "%+.2f%%", drop)

        acknowledgeButton.setOnClickListener {
            acknowledgeAndClose()
        }

        handler.postDelayed(endAlertDisplay, 30_000)
    }

    private fun acknowledgeAndClose() {
        if (acknowledged) return
        acknowledged = true
        handler.removeCallbacks(endAlertDisplay)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        startService(
            Intent(this, MonitoringService::class.java)
                .setAction(MonitoringService.ACTION_ALERT_ACKNOWLEDGED)
                .putExtra(MonitoringService.EXTRA_ALERT_ACKNOWLEDGED, true)
        )

        finish()
    }

    override fun onBackPressed() {
        // Back does not acknowledge the alert.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(false)
            setShowWhenLocked(false)
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(endAlertDisplay)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        super.onDestroy()
    }

    private fun formatPrice(value: Double): String {
        return if (value > 0) {
            DecimalFormat("#,##0").format(value) + " تومان"
        } else {
            "--"
        }
    }
}
