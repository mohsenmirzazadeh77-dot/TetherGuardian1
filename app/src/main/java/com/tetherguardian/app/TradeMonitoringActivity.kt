package com.tetherguardian.app

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringActivity : AppCompatActivity() {
    companion object { private const val SOUND_REQUEST = 4217 }
    private lateinit var statusText: TextView
    private lateinit var scoreText: TextView
    private lateinit var buyPressureText: TextView
    private lateinit var sellPressureText: TextView
    private lateinit var volumeText: TextView
    private lateinit var speedText: TextView
    private lateinit var largeCountText: TextView
    private lateinit var priceText: TextView
    private lateinit var reasonText: TextView
    private lateinit var tradesTable: TableLayout
    private lateinit var refreshButton: Button
    private lateinit var severeAlertSwitch: Switch
    private val client = OkHttpClient()
    private var refreshJob: Job? = null
    private val numberFormat = DecimalFormat("#,##0.##")
    private val priceFormat = DecimalFormat("#,##0")
    private val volumeFormat = DecimalFormat("#,##0.##")
    private val recentTrades = LinkedHashMap<String, Trade>()
    data class Trade(val time: Long, val priceRial: Double, val volume: Double, val type: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trade_monitoring)
        bindViews()
        val prefs = getSharedPreferences(TradeMonitoringService.PREFS, MODE_PRIVATE)
        severeAlertSwitch.isChecked = prefs.getBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, true)
        severeAlertSwitch.setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean(TradeMonitoringService.KEY_SEVERE_ALERT, enabled).apply() }
        findViewById<Button>(R.id.tradeAlertSoundButton).setOnClickListener { openSoundPicker() }
        refreshButton.setOnClickListener { refreshOnce() }
        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) { refreshOnce(); delay(10_000) }
        }
    }

    private fun bindViews() {
        statusText = findViewById(R.id.marketStateText)
        scoreText = findViewById(R.id.scoreText)
        buyPressureText = findViewById(R.id.buyPressureText)
        sellPressureText = findViewById(R.id.sellPressureText)
        volumeText = findViewById(R.id.volumeText)
        speedText = findViewById(R.id.speedText)
        largeCountText = findViewById(R.id.largeCountText)
        priceText = findViewById(R.id.priceText)
        reasonText = findViewById(R.id.reasonText)
        tradesTable = findViewById(R.id.tradesTable)
        refreshButton = findViewById(R.id.refreshTradesButton)
        severeAlertSwitch = findViewById(R.id.severeAlertSwitch)
    }

    private fun openSoundPicker() {
        val prefs = getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString(MonitoringService.KEY_SOUND_URI, null)?.let(Uri::parse)
        startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "انتخاب صدای هشدار مانیتورینگ")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }, SOUND_REQUEST)
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SOUND_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return
        getSharedPreferences(MonitoringService.PREFS_NAME, MODE_PRIVATE).edit().putString(MonitoringService.KEY_SOUND_URI, uri.toString()).apply()
        findViewById<Button>(R.id.tradeAlertSoundButton).text = "🔊 صدای هشدار انتخاب شد"
    }

    private fun refreshOnce() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetchTrades() }
                .onSuccess { trades -> withContext(Dispatchers.Main) { render(trades) } }
                .onFailure { error -> withContext(Dispatchers.Main) { statusText.text = "⚪ دریافت داده ناموفق"; reasonText.text = "دلیل: ${error.message ?: "خطای نامشخص"}" } }
        }
    }

    private fun fetchTrades(): List<Trade> {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v2/trades/USDTIRT").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("خطای HTTP نوبیتکس: ${response.code}")
            val array = JSONObject(response.body?.string() ?: "{}").optJSONArray("trades") ?: error("فهرست معاملات در پاسخ نوبیتکس وجود ندارد")
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val time = item.optLong("time", -1L)
                    val price = item.optString("price").toDoubleOrNull()
                    val volume = item.optString("volume").toDoubleOrNull()
                    val type = item.optString("type", "unknown")
                    if (time >= 0 && price != null && volume != null) add(Trade(time, price, volume, type))
                }
            }
        }
    }

    private fun render(fetched: List<Trade>) {
        if (fetched.isEmpty()) { statusText.text = "⚪ داده‌ای دریافت نشد"; return }
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        fetched.forEach { t -> recentTrades["${t.time}|${t.priceRial}|${t.volume}|${t.type}"] = t }
        recentTrades.entries.removeIf { toMillis(it.value.time) < cutoff }
        val window = recentTrades.values.filter { toMillis(it.time) >= cutoff }.sortedBy { toMillis(it.time) }
        if (window.isEmpty()) return
        val buy = window.filter { it.type.equals("buy", true) }.sumOf { it.volume }
        val sell = window.filter { it.type.equals("sell", true) }.sumOf { it.volume }
        val total = buy + sell
        val buyPct = if (total > 0) buy / total * 100 else 50.0
        val sellPct = 100.0 - buyPct
        val count1000 = window.count { it.volume >= 1000.0 }
        val score = calculateScore(buyPct, sellPct, window.size)
        val enabled = severeAlertSwitch.isChecked
        statusText.text = when { score >= 70 && enabled -> "🟠 هشدار شدید"; score >= 50 -> "🟠 غیرعادی"; score >= 30 -> "🟡 تحت نظر"; else -> "🟢 عادی" }
        scoreText.text = "$score/100"
        buyPressureText.text = "فشار خرید: ${numberFormat.format(buyPct)}٪"
        sellPressureText.text = "فشار فروش: ${numberFormat.format(sellPct)}٪"
        volumeText.text = "حجم معاملات ۵ دقیقه اخیر: ${numberFormat.format(total)} USDT"
        speedText.text = "تعداد معاملات ۵ دقیقه اخیر: ${window.size}"
        largeCountText.text = "تعداد معاملات ۵ دقیقه اخیر ≥ ۱۰۰۰ تتر: $count1000"
        val topTen = window.sortedByDescending { it.volume }.take(10).sortedBy { toMillis(it.time) }
        renderTradeRows(topTen)
        priceText.text = "آخرین قیمت معامله: ${priceFormat.format(window.last().priceRial / 10.0)} تومان"
        reasonText.text = buildReason(score, buyPct, sellPct, count1000, window.size, enabled)
    }

    private fun renderTradeRows(rows: List<Trade>) {
        while (tradesTable.childCount > 1) tradesTable.removeViewAt(1)
        rows.forEachIndexed { index, trade ->
            val row = TableRow(this)
            row.setPadding(4, 5, 4, 5)
            val values = listOf(
                formatTime(trade.time),
                priceFormat.format(trade.priceRial / 10.0),
                when { trade.type.equals("buy", true) -> "خرید"; trade.type.equals("sell", true) -> "فروش"; else -> trade.type },
                volumeFormat.format(trade.volume)
            )
            values.forEachIndexed { column, value ->
                val cell = TextView(this)
                cell.text = value
                cell.textSize = 12f
                cell.gravity = android.view.Gravity.CENTER
                cell.setPadding(3, 7, 3, 7)
                cell.setBackgroundColor(if (index % 2 == 0) 0xFFEEF4E9.toInt() else 0xFFF7FAF4.toInt())
                val weight = when (column) { 0 -> 1.1f; 1 -> 1.3f; 2 -> 0.9f; else -> 1.2f }
                row.addView(cell, TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight))
            }
            tradesTable.addView(row)
        }
    }

    private fun calculateScore(buyPct: Double, sellPct: Double, count: Int): Int = min(100, (min(55.0, abs(buyPct - sellPct) * 1.1) + min(45.0, max(0, count - 20) * 1.5)).toInt())
    private fun buildReason(score: Int, buyPct: Double, sellPct: Double, count1000: Int, count: Int, enabled: Boolean): String = buildString {
        append("وضعیت بر اساس معاملات واقعی پنج دقیقه اخیر نوبیتکس محاسبه شده است.\n")
        append("• فشار ${if (buyPct >= sellPct) "خرید" else "فروش"} بیشتر است (${numberFormat.format(max(buyPct, sellPct))}٪).\n")
        append("• تعداد معاملات پنج دقیقه اخیر: $count\n• معاملات پنج دقیقه اخیر با حجم ≥ ۱۰۰۰ تتر: $count1000\n")
        append("• آستانه ۱۰۰۰ تتر فقط برای شمارش است و در امتیاز هشدار دخالت ندارد.\n• هشدار شدید: ${if (enabled) "فعال" else "غیرفعال"}\n")
        append(when { score < 30 -> "نتیجه: شاخص‌های فعلی در محدوده عادی هستند."; score < 50 -> "نتیجه: افزایش فعالیت دیده می‌شود و بازار تحت نظر است."; else -> "نتیجه: عدم‌تعادل یا فعالیت معاملاتی افزایش یافته و رفتار بازار غیرعادی‌تر شده است." })
    }
    private fun toMillis(value: Long): Long = if (value < 10_000_000_000L) value * 1000L else value
    private fun formatTime(value: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(toMillis(value)))
    override fun onDestroy() { refreshJob?.cancel(); super.onDestroy() }
}
