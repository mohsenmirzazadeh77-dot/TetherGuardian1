package com.tetherguardian.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.widget.Button
import android.widget.Switch
import androidx.core.content.ContextCompat

class TetherGuardianApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                when (activity) {
                    is MainActivity -> {
                        activity.findViewById<Button>(R.id.tradeMonitoringButton)?.setOnClickListener {
                            activity.startActivity(Intent(activity, TradeMonitoringActivity::class.java))
                        }
                    }
                    is TradeMonitoringActivity -> {
                        val prefs = activity.getSharedPreferences(TradeMonitoringService.PREFS, MODE_PRIVATE)
                        val switch = activity.findViewById<Switch>(R.id.severeAlertSwitch)
                        switch?.isChecked = prefs.getBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, true)
                        switch?.setOnCheckedChangeListener { _, checked ->
                            prefs.edit().putBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, checked).apply()
                        }
                        ContextCompat.startForegroundService(
                            activity,
                            Intent(activity, TradeMonitoringService::class.java)
                        )
                    }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
