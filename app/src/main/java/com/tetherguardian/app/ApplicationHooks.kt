package com.tetherguardian.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.core.content.ContextCompat

class ApplicationHooks : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    activity.findViewById<Button>(R.id.tradeMonitoringButton)?.setOnClickListener {
                        activity.startActivity(Intent(activity, TradeMonitoringActivity::class.java))
                    }
                }
                if (activity is TradeMonitoringActivity) {
                    val prefs = activity.getSharedPreferences(TradeMonitoringService.PREFS, MODE_PRIVATE)
                    val alertSwitch = activity.findViewById<Switch>(R.id.severeAlertSwitch)
                    alertSwitch?.isChecked = prefs.getBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, true)
                    alertSwitch?.setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, checked).apply()
                    }
                    activity.findViewById<Button>(R.id.tradeAlertSoundButton)?.setOnClickListener {
                        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای هشدار مانیتورینگ")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        }
                        activity.startActivityForResult(picker, 4217)
                    }
                    ContextCompat.startForegroundService(activity, Intent(activity, TradeMonitoringService::class.java))
                }
            }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
