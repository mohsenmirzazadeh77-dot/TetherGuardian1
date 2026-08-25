package com.tetherguardian.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tetherguardian.app.data.NobitexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SOUND = 3101
        private const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    }

    private lateinit var connectionText: TextView
    private lateinit var priceText: TextView
    private lateinit var updateText: TextView
    private lateinit var baseText: TextView
    private lateinit var baseTimeText: TextView
    private lateinit var dropPercentText: TextView
    private lateinit var dropLimitText: TextView
    private lateinit var monitoringStatusText: TextView
    private lateinit var monitoringButton: Button
    private lateinit var dropPercentSpinner: Spinner
    private lateinit var soundSpinner: Spinner
    private lateinit var soundTestButton: Button
    private lateinit var alertPreviewButton: Button

    private var settingUpSoundSelector = false
    private val nobitexApi = NobitexApi()
    private val decimalFormat = DecimalFormat("#,##0.########")
    private val integerPriceFormat = DecimalFormat("#,##0")
    private val dropOptions = (1..10).map { it * 0.5 }

    private val priceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MonitoringService.ACTION_PRICE_UPDATE) return
            val price = intent.getDoubleExtra(MonitoringService.EXTRA_PRICE, 0.0)
            val base = intent.getDoubleExtra(MonitoringService.EXTRA_BASE_PRICE, 0.0)
            val baseTime = intent.getLongExtra(MonitoringService.EXTRA_BASE_TIME, 0L)
            val receivedTime = intent.getLongExtra(MonitoringService.EXTRA_TIME, 0L)
            val dropLimit = intent.getDoubleExtra(MonitoringService.EXTRA_DROP_LIMIT, 0.0)
            if (price > 0) {
                priceText.text = formatPrice(price)
                connectionText.text = "● اتصال موفق به نوبیتکس"
                connectionText.setTextColor(getColorCompat(android.R.color.holo_green_dark))
            }
            if (base > 0) baseText.text = formatPrice(base)
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
            if (dropLimit > 0) dropLimitText.text = formatRoundedPrice(dropLimit)
            if (receivedTime > 0) updateText.text = "آخرین دریافت: ${formatTime(receivedTime)}"
            setMonitoringActiveAppearance()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeViews()
        setupDropPercentSpinner()
        setupSoundSpinner()
        registerPriceReceiver()
        restoreMonitoringState()
        monitoringButton.setOnClickListener { toggleMonitoring() }
        soundTestButton.setOnClickListener { testSelectedSound() }
        alertPreviewButton.setOnClickListener { previewAlertScreen() }
        loadCurrentPrice()
    }

    private fun initializeViews() {
        connectionText = findViewById(R.id.connectionText)
        priceText = findViewById(R.id.priceText)
        updateText = findViewById(R.id.updateText)
        baseText = findViewById(R.id.baseText)
        baseTimeText = findViewById(R.id.baseTimeText)
        dropPercentText = findViewById(R.id.dropPercentText)
        dropLimitText = findViewById(R.id.dropLimitText)
        monitoringStatusText = findViewById(R.id.monitoringStatusText)
        monitoringButton = findViewById(R.id.monitoringButton)
        dropPercentSpinner = findViewById(R.id.dropPercentSpinner)
        soundSpinner = findViewById(R.id.soundSpinner)
        soundTestButton = findViewById(R.id.soundTestButton)
        alertPreviewButton = findViewById(R.id.alertPreviewButton)
    }

    private fun setupDropPercentSpinner() {
        val labels = dropOptions.map { formatPercent(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dropPercentSpinner.adapter = adapter
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val savedPercent = prefs.getString(MonitoringService.KEY_DROP_PERCENT, "3.0")?.toDoubleOrNull() ?: 3.0
        val savedIndex = dropOptions.indexOfFirst { abs(it - savedPercent) < 0.001 }
        if (savedIndex >= 0) dropPercentSpinner.setSelection(savedIndex)
        dropPercentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = dropOptions.getOrNull(position) ?: 3.0
                dropPercentText.text = formatPercent(selected)
                prefs.edit().putString(MonitoringService.KEY_DROP_PERCENT, selected.toString()).apply()
                updateDropLimit(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val initial = dropOptions.getOrNull(if (savedIndex >= 0) savedIndex else 5) ?: 3.0
        dropPercentText.text = formatPercent(initial)
    }

    private fun setupSoundSpinner() {
        settingUpSoundSelector = true
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val customTitle = prefs.getString(MonitoringService.KEY_SOUND_TITLE, null)
        val label = if (customTitle.isNullOrBlank()) "انتخاب صدای هشدار از گوشی" else "صدای انتخاب‌شده: $customTitle"
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(label))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        soundSpinner.adapter = adapter
        soundSpinner.setSelection(0, false)
        soundSpinner.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && !settingUpSoundSelector) {
                openSoundPicker()
                true
            } else true
        }
        settingUpSoundSelector = false
    }

    private fun openSoundPicker() {
        val currentUri = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
            .getString(MonitoringService.KEY_SOUND_URI, null)
            ?.let { Uri.parse(it) }

        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای هشدار")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (currentUri != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        startActivityForResult(picker, REQUEST_SOUND)
    }

    @Deprecated("Android activity result API is retained for compatibility with the project's current structure")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SOUND || resultCode != RESULT_OK) return
        val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ?: data?.data ?: return
        val title = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (_: Exception) { null }
            ?: "صدای انتخاب‌شده"
        getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(MonitoringService.KEY_SOUND_INDEX, 3)
            .putString(MonitoringService.KEY_SOUND_URI, uri.toString())
            .putString(MonitoringService.KEY_SOUND_TITLE, title)
            .apply()
        setupSoundSpinner()
    }

    private fun previewAlertScreen() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val currentPrice = prefs.getString(MonitoringService.KEY_BASE_PRICE, null)?.toDoubleOrNull() ?: 0.0
        val dropPercent = prefs.getString(MonitoringService.KEY_DROP_PERCENT, "0.5")?.toDoubleOrNull() ?: 0.5
        val base = if (currentPrice > 0.0) currentPrice else 100_000.0
        val previewPrice = base * (1.0 - dropPercent / 100.0)
        startActivity(Intent(this, AlertActivity::class.java).apply {
            putExtra(MonitoringService.EXTRA_ALERT_PRICE, previewPrice)
            putExtra(MonitoringService.EXTRA_ALERT_BASE, base)
            putExtra(MonitoringService.EXTRA_ALERT_DROP, -dropPercent)
            putExtra("preview_mode", true)
        })
    }

    private fun toggleMonitoring() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)
        if (active) {
            ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP))
            setMonitoringInactiveAppearance()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
            val selected = dropOptions.getOrNull(dropPercentSpinner.selectedItemPosition) ?: 3.0
            prefs.edit().putString(MonitoringService.KEY_DROP_PERCENT, selected.toString()).apply()
            ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START))
            monitoringStatusText.text = "● در حال فعال‌سازی..."
            monitoringStatusText.setTextColor(getColorCompat(android.R.color.holo_green_dark))
            monitoringButton.text = "غیرفعال کردن برنامه"
            maybePromptBatteryOptimization()
        }
    }

    private fun maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            return
        }

        // Do not suppress the prompt just because it was shown in a previous
        // run. If optimization is still enabled, keep helping the user fix it.
        AlertDialog.Builder(this)
            .setTitle("پایداری پایش تتر")
            .setMessage("برای اطمینان از ادامه پایش قیمت و اجرای هشدار در پس‌زمینه، «نگهبان تتر» باید از بهینه‌سازی مصرف باتری مستثنی شود. در حال حاضر این دسترسی فعال نیست.")
            .setPositiveButton("باز کردن تنظیمات باتری") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
                }
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setNegativeButton("بعداً") { _, _ ->
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun restoreMonitoringState() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)
        val savedPercent = prefs.getString(MonitoringService.KEY_DROP_PERCENT, "3.0")?.toDoubleOrNull() ?: 3.0
        dropPercentText.text = formatPercent(savedPercent)
        if (active) {
            setMonitoringActiveAppearance()
            val base = prefs.getString(MonitoringService.KEY_BASE_PRICE, null)
            val baseTime = prefs.getLong(MonitoringService.KEY_BASE_TIME, 0L)
            if (base != null) baseText.text = formatPrice(base.toDouble())
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
            updateDropLimit(savedPercent)
        } else setMonitoringInactiveAppearance()
    }

    private fun setMonitoringActiveAppearance() {
        monitoringStatusText.text = "● پایش فعال"
        monitoringStatusText.setTextColor(getColorCompat(android.R.color.holo_green_dark))
        monitoringButton.text = "غیرفعال کردن برنامه"
    }

    private fun setMonitoringInactiveAppearance() {
        monitoringStatusText.text = "● پایش غیرفعال"
        monitoringStatusText.setTextColor(getColorCompat(android.R.color.holo_red_dark))
        monitoringButton.text = "فعال کردن برنامه"
        baseText.text = "--"
        baseTimeText.text = "زمان ثبت: --"
        dropLimitText.text = "--"
    }

    private fun updateDropLimit(percent: Double) {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val base = prefs.getString(MonitoringService.KEY_BASE_PRICE, null)?.toDoubleOrNull()
        if (base != null && base > 0) dropLimitText.text = formatRoundedPrice(base * (1.0 - percent / 100.0)) else dropLimitText.text = "--"
    }

    private fun loadCurrentPrice() {
        connectionText.text = "● در حال اتصال..."
        connectionText.setTextColor(getColorCompat(android.R.color.holo_orange_dark))
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val price = withContext(Dispatchers.IO) { nobitexApi.getCurrentPrice(symbol = "USDTIRT") }
                priceText.text = formatPrice(price)
                connectionText.text = "● اتصال موفق به نوبیتکس"
                connectionText.setTextColor(getColorCompat(android.R.color.holo_green_dark))
                updateText.text = "آخرین دریافت: ${currentTime()}"
            } catch (e: Exception) {
                connectionText.text = "● اتصال ناموفق به نوبیتکس"
                connectionText.setTextColor(getColorCompat(android.R.color.holo_red_dark))
                updateText.text = e.message ?: "خطای نامشخص"
            }
        }
    }

    private fun testSelectedSound() {
        ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_TEST_SOUND))
    }

    private fun registerPriceReceiver() {
        val filter = IntentFilter(MonitoringService.ACTION_PRICE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(priceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(priceReceiver, filter)
    }

    override fun onDestroy() {
        try { unregisterReceiver(priceReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun formatPrice(value: String): String = value.toDoubleOrNull()?.let { formatPrice(it) } ?: value
    private fun formatPrice(value: Double): String = decimalFormat.format(value) + " تومان"
    private fun formatRoundedPrice(value: Double): String = integerPriceFormat.format(round(value)) + " تومان"
    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
    private fun currentTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun getColorCompat(colorRes: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(colorRes) else {
            @Suppress("DEPRECATION")
            resources.getColor(colorRes)
        }
    }
}
