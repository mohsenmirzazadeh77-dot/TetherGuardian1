package com.tetherguardian.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var monitoringStatusText: TextView
    private lateinit var monitoringButton: Button
    private lateinit var timeframeSpinner: Spinner
    private lateinit var refreshButton: Button

    private val nobitexApi = NobitexApi()
    private val decimalFormat = DecimalFormat("#,##0.########")

    private val timeframes = listOf(
        Timeframe("۴ ساعت", "240"),
        Timeframe("۶ ساعت", "360"),
        Timeframe("۱۲ ساعت", "720"),
        Timeframe("روزانه", "D")
    )

    private val priceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MonitoringService.ACTION_PRICE_UPDATE) return

            val price = intent.getDoubleExtra(MonitoringService.EXTRA_PRICE, 0.0)
            val base = intent.getDoubleExtra(MonitoringService.EXTRA_BASE_PRICE, 0.0)
            val baseTime = intent.getLongExtra(MonitoringService.EXTRA_BASE_TIME, 0L)
            val receivedTime = intent.getLongExtra(MonitoringService.EXTRA_TIME, 0L)

            if (price > 0) priceText.text = formatPrice(price)
            if (base > 0) baseText.text = formatPrice(base)
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
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
        registerPriceReceiver()
        restoreMonitoringState()

        refreshButton.setOnClickListener { loadMarketData() }
        monitoringButton.setOnClickListener { toggleMonitoring() }

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
        monitoringStatusText = findViewById(R.id.monitoringStatusText)
        monitoringButton = findViewById(R.id.monitoringButton)
        timeframeSpinner = findViewById(R.id.timeframeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
    }

    private fun setupTimeframeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timeframes.map { it.title })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timeframeSpinner.adapter = adapter
        timeframeSpinner.setSelection(0)
        timeframeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                loadMarketData()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun toggleMonitoring() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)

        if (active) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP)
            )
            showMonitoringInactive()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }

            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START)
            )
            monitoringStatusText.text = "● در حال فعال‌سازی..."
            monitoringButton.text = "غیرفعال کردن برنامه"
        }
    }

    private fun restoreMonitoringState() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(MonitoringService.KEY_ACTIVE, false)
        if (active) {
            monitoringStatusText.text = "● پایش فعال"
            monitoringButton.text = "غیرفعال کردن برنامه"
            val base = prefs.getString(MonitoringService.KEY_BASE_PRICE, null)
            val baseTime = prefs.getLong(MonitoringService.KEY_BASE_TIME, 0L)
            if (base != null) baseText.text = formatPrice(base.toDouble())
            if (baseTime > 0) baseTimeText.text = "زمان ثبت: ${formatTime(baseTime)}"
        } else {
            showMonitoringInactive()
        }
    }

    private fun showMonitoringInactive() {
        monitoringStatusText.text = "● پایش غیرفعال"
        monitoringButton.text = "فعال کردن برنامه"
        baseText.text = "--"
        baseTimeText.text = "زمان ثبت: --"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(priceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(priceReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(priceReceiver)
        super.onDestroy()
    }

    private fun formatPrice(value: String): String = value.toDoubleOrNull()?.let { formatPrice(it) } ?: value

    private fun formatPrice(value: Double): String = decimalFormat.format(value) + " تومان"

    private fun currentTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private data class Timeframe(val title: String, val resolution: String)
}
