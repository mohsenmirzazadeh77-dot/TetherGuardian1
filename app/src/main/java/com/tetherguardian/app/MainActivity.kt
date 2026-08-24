package com.tetherguardian.app

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {

    private lateinit var connectionText: TextView
    private lateinit var openText: TextView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
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
    private lateinit var timeframeSpinner: Spinner
    private lateinit var refreshButton: Button

    private val nobitexApi = NobitexApi()
    private val decimalFormat = DecimalFormat("#,##0.########")
    private var testRingtone: Ringtone? = null

    private val timeframes = listOf(
        Timeframe("۴ ساعت", "240"),
        Timeframe("۶ ساعت", "360"),
        Timeframe("۱۲ ساعت", "720"),
        Timeframe("روزانه", "D")
    )

    private val dropOptions = (1..10).map { it * 0.5 }
    private val soundOptions = listOf("صدای اعلان", "صدای آلارم", "صدای زنگ")

    private val priceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MonitoringService.ACTION_PRICE_UPDATE) return
            val price = intent.getDoubleExtra(MonitoringService.EXTRA_PRICE, 0.0)
            val base = intent.getDoubleExtra(MonitoringService.EXTRA_BASE_PRICE, 0.0)
            val baseTime = intent.getLongExtra(MonitoringService.EXTRA_BASE_TIME, 0L)
            val receivedTime = intent.getLongExtra(MonitoringService.EXTRA_TIME, 0L)
            val dropLimit = intent.getDoubleExtra(MonitoringService.EXTRA_DROP_LIMIT, 0.0)
            if (price > 0) priceText.text = formatPrice(price)
            if (base > 0) baseText.text = formatPrice(base)
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
            if (dropLimit > 0) dropLimitText.text = formatPrice(dropLimit)
            if (receivedTime > 0) updateText.text = "قیمت به‌روزرسانی شد • ${formatTime(receivedTime)}"
            monitoringStatusText.text = "● پایش فعال"
            monitoringButton.text = "غیرفعال کردن برنامه"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeViews()
        setupTimeframeSpinner()
        setupDropPercentSpinner()
        setupSoundSpinner()
        registerPriceReceiver()
        restoreMonitoringState()
        refreshButton.setOnClickListener { loadMarketData() }
        monitoringButton.setOnClickListener { toggleMonitoring() }
        soundTestButton.setOnClickListener { testSelectedSound() }
        loadMarketData()
    }

    private fun initializeViews() {
        connectionText = findViewById(R.id.connectionText)
        openText = findViewById(R.id.openText)
        priceText = findViewById(R.id.priceText)
        changeText = findViewById(R.id.changeText)
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
        timeframeSpinner = findViewById(R.id.timeframeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
    }

    private fun setupTimeframeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timeframes.map { it.title })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timeframeSpinner.adapter = adapter
        timeframeSpinner.setSelection(0)
        timeframeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { loadMarketData() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupDropPercentSpinner() {
        val labels = dropOptions.map { formatPercent(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dropPercentSpinner.adapter = adapter
        dropPercentSpinner.setSelection(5)
        dropPercentSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = dropOptions.getOrNull(position) ?: 3.0
                dropPercentText.text = formatPercent(selected)
                getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(MonitoringService.KEY_DROP_PERCENT, selected.toString()).apply()
                updateDropLimitFromStoredBase(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupSoundSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, soundOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        soundSpinner.adapter = adapter
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        soundSpinner.setSelection(prefs.getInt(MonitoringService.KEY_SOUND_INDEX, 0))
        soundSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(MonitoringService.KEY_SOUND_INDEX, position).apply()
                if (prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)) {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, MonitoringService::class.java)
                            .setAction(MonitoringService.ACTION_REFRESH_ALERT_CHANNEL)
                    )
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun testSelectedSound() {
        testRingtone?.stop()
        testRingtone = RingtoneManager.getRingtone(this, selectedSoundUri())
        testRingtone?.play()
    }

    private fun selectedSoundUri(): Uri {
        return when (soundSpinner.selectedItemPosition) {
            1 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            2 -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    private fun toggleMonitoring() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)
        if (active) {
            ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP))
            showMonitoringInactive()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
            val selected = dropOptions.getOrNull(dropPercentSpinner.selectedItemPosition) ?: 3.0
            prefs.edit().putString(MonitoringService.KEY_DROP_PERCENT, selected.toString()).apply()
            ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START))
            monitoringStatusText.text = "● در حال فعال‌سازی..."
            monitoringButton.text = "غیرفعال کردن برنامه"
        }
    }

    private fun restoreMonitoringState() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)
        val savedPercent = prefs.getString(MonitoringService.KEY_DROP_PERCENT, "3.0")?.toDoubleOrNull() ?: 3.0
        val index = dropOptions.indexOfFirst { kotlin.math.abs(it - savedPercent) < 0.001 }
        if (index >= 0) dropPercentSpinner.setSelection(index)
        dropPercentText.text = formatPercent(savedPercent)
        if (active) {
            monitoringStatusText.text = "● پایش فعال"
            monitoringButton.text = "غیرفعال کردن برنامه"
            val base = prefs.getString(MonitoringService.KEY_BASE_PRICE, null)
            val baseTime = prefs.getLong(MonitoringService.KEY_BASE_TIME, 0L)
            if (base != null) baseText.text = formatPrice(base.toDouble())
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
            updateDropLimitFromStoredBase(savedPercent)
        } else showMonitoringInactive()
    }

    private fun showMonitoringInactive() {
        monitoringStatusText.text = "● پایش غیرفعال"
        monitoringButton.text = "فعال کردن برنامه"
        baseText.text = "--"
        baseTimeText.text = "زمان ثبت: --"
        dropLimitText.text = "--"
    }

    private fun updateDropLimitFromStoredBase(percent: Double) {
        val base = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
            .getString(MonitoringService.KEY_BASE_PRICE, null)?.toDoubleOrNull()
        if (base != null && base > 0) dropLimitText.text = formatPrice(base * (1.0 - percent / 100.0))
        else if (!getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE).getBoolean(MonitoringService.KEY_ACTIVE, false)) dropLimitText.text = "--"
    }

    private fun loadMarketData() {
        val selected = timeframes.getOrNull(timeframeSpinner.selectedItemPosition) ?: return
        connectionText.text = "● در حال آزمایش ارتباط..."
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val candles = nobitexApi.getOhlc(symbol = "USDTIRT", timeframe = selected.resolution)
                    val price = nobitexApi.getCurrentPrice(symbol = "USDTIRT")
                    Pair(candles.lastOrNull(), price)
                }
                val candle = result.first
                val price = result.second
                if (candle == null) {
                    connectionText.text = "خطا: کندل دریافت نشد"
                    openText.text = "--"
                    priceText.text = "--"
                    return@launch
                }
                openText.text = formatPrice(candle.open)
                priceText.text = formatPrice(price)
                changeText.text = calculateChange(candle.open, price)
                connectionText.text = "● اتصال موفق به نوبیتکس"
                updateText.text = "دریافت موفق • ${currentTime()}"
            } catch (e: Exception) {
                connectionText.text = "● خطا در دریافت اطلاعات"
                openText.text = "خطا"
                priceText.text = "خطا"
                changeText.text = "--"
                updateText.text = e.message ?: "خطای نامشخص"
            }
        }
    }

    private fun calculateChange(openValue: String, priceValue: String): String {
        val open = openValue.toDoubleOrNull() ?: return "--"
        val price = priceValue.toDoubleOrNull() ?: return "--"
        if (open == 0.0) return "--"
        return String.format(Locale.US, "%+.2f%%", ((price - open) / open) * 100.0)
    }

    private fun registerPriceReceiver() {
        val filter = IntentFilter(MonitoringService.ACTION_PRICE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(priceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(priceReceiver, filter)
    }

    override fun onDestroy() {
        testRingtone?.stop()
        unregisterReceiver(priceReceiver)
        super.onDestroy()
    }

    private fun formatPrice(value: String): String = value.toDoubleOrNull()?.let { formatPrice(it) } ?: value
    private fun formatPrice(value: Double): String = decimalFormat.format(value) + " تومان"
    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
    private fun currentTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    private data class Timeframe(val title: String, val resolution: String)
}
