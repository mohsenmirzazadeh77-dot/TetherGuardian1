package com.tetherguardian.app

import android.os.Bundle
import android.widget.Button
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
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var scoreText: TextView
    private lateinit var buyPressureText: TextView
    private lateinit var sellPressureText: TextView
    private lateinit var volumeText: TextView
    private lateinit var speedText: TextView
    private lateinit var largeTradeText: TextView
    private lateinit var largeCountText: TextView
    private lateinit var priceText: TextView
    private lateinit var reasonText: TextView
    private lateinit var latestTradesText: TextView
    private lateinit var refreshButton: Button

    private val client = OkHttpClient()
    private var refreshJob: Job? = null
    private val numberFormat = DecimalFormat("#,##0.##")
    private val priceFormat = DecimalFormat("#,##0")

    data class Trade(val time: Long, val priceRial: Double, val volume: Double, val type: String)

    // Cache the recent five-minute window so fast trades remain counted even after
    // they disappear from the API response. Duplicates are removed by a stable key.
    private val recentTrades = LinkedHashMap<String, Trade>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trade_monitoring)
        bindViews()
        refreshButton.setOnClickListener { refreshOnce() }
        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                refreshOnce()
                delay(10_000)
            }
        }
    }

    private fun bindViews() {
        statusText = findViewById(R.id.marketStateText)
        scoreText = findViewById(R.id.scoreText)
        buyPressureText = findViewById(R.id.buyPressureText)
        sellPressureText = findViewById(R.id.sellPressureText)
        volumeText = findViewById(R.id.volumeText)
        speedText = findViewById(R.id.speedText)
        largeTradeText = findViewById(R.id.largeTradeText)
        largeCountText = findViewById(R.id.largeCountText)
        priceText = findViewById(R.id.priceText)
        reasonText = findViewById(R.id.reasonText)
        latestTradesText = findViewById(R.id.latestTradesText)
        refreshButton = findViewById(R.id.refreshTradesButton)
    }

    private fun refreshOnce() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { fetchTrades() }
                .onSuccess { trades -> withContext(Dispatchers.Main) { render(trades) } }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        statusText.text = "⚪ دریافت داده ناموفق"
                        reasonText.text = "دلیل: ${error.message ?: "خطای نامشخص"}"
                    }
                }
        }
    }

    private fun fetchTrades(): List<Trade> {
        val request = Request.Builder()
            .url("https://apiv2.nobitex.ir/v2/trades/USDTIRT")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("خطای HTTP نوبیتکس: ${response.code}")
            val body = response.body?.string() ?: throw IllegalStateException("پاسخ نوبیتکس خالی است")
            val array = org.json.JSONObject(body).optJSONArray("trades")
                ?: throw IllegalStateException("فهرست معاملات در پاسخ نوبیتکس وجود ندارد")
            val result = ArrayList<Trade>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val time = item.optLong("time", -1L)
                val price = item.optString("price").toDoubleOrNull()
                val volume = item.optString("volume").toDoubleOrNull()
                val type = item.optString("type", "unknown")
                if (time >= 0 && price != null && volume != null) {
                    result += Trade(time, price, volume, type)
                }
            }
            return result
        }
    }

    private fun render(fetched: List<Trade>) {
        if (fetched.isEmpty()) {
            statusText.text = "⚪ داده‌ای دریافت نشد"
            reasonText.text = "نوبیتکس پاسخ داد، اما معامله قابل پردازشی در پاسخ وجود نداشت."
            return
        }

        val now = System.currentTimeMillis()
        val cutoff = now - 5 * 60 * 1000L

        // Keep all unique trades seen during the rolling five-minute window.
        for (trade in fetched) {
            val key = "${trade.time}|${trade.priceRial}|${trade.volume}|${trade.type}"
            recentTrades[key] = trade
        }
        recentTrades.entries.removeIf { toMillis(it.value.time) < cutoff }

        val windowTrades = recentTrades.values
            .filter { toMillis(it.time) >= cutoff }
            .sortedBy { toMillis(it.time) }

        if (windowTrades.isEmpty()) {
            statusText.text = "⚪ در انتظار معاملات پنج دقیقه اخیر"
            return
        }

        val buy = windowTrades.filter { it.type.equals("buy", true) }.sumOf { it.volume }
        val sell = windowTrades.filter { it.type.equals("sell", true) }.sumOf { it.volume }
        val total = buy + sell
        val buyPct = if (total > 0) buy / total * 100.0 else 50.0
        val sellPct = 100.0 - buyPct

        // The 1000-USDT threshold is ONLY a five-minute counter. It is not part
        // of the anomaly score and does not influence the market state.
        val thousandCount = windowTrades.count { it.volume >= 1000.0 }

        val score = calculateScore(buyPct, sellPct, windowTrades.size)
        val state = when {
            score >= 70 -> "🟠 هشدار شدید"
            score >= 50 -> "🟠 غیرعادی"
            score >= 30 -> "🟡 تحت نظر"
            else -> "🟢 عادی"
        }

        statusText.text = state
        scoreText.text = "$score/100"
        buyPressureText.text = "فشار خرید: ${numberFormat.format(buyPct)}٪"
        sellPressureText.text = "فشار فروش: ${numberFormat.format(sellPct)}٪"
        volumeText.text = "حجم معاملات ۵ دقیقه اخیر: ${numberFormat.format(total)} USDT"
        speedText.text = "تعداد معاملات ۵ دقیقه اخیر: ${windowTrades.size}"
        largeCountText.text = "تعداد معاملات ۵ دقیقه اخیر ≥ ۱۰۰۰ تتر: $thousandCount"
        largeTradeText.text = "۱۰ معامله بزرگ‌تر در ۵ دقیقه اخیر"

        // Top ten by volume, but displayed chronologically from old to new.
        val topTen = windowTrades
            .sortedByDescending { it.volume }
            .take(10)
            .sortedBy { toMillis(it.time) }

        latestTradesText.text = topTen.joinToString("\n") {
            val type = if (it.type.equals("buy", true)) "خرید" else if (it.type.equals("sell", true)) "فروش" else it.type
            "${formatTime(it.time)} | $type | ${numberFormat.format(it.volume)} USDT | ${priceFormat.format(it.priceRial / 10.0)} تومان"
        }

        // API price is received in Rial; UI is explicitly Toman.
        priceText.text = "آخرین قیمت معامله: ${priceFormat.format(windowTrades.last().priceRial / 10.0)} تومان"
        reasonText.text = buildReason(score, buyPct, sellPct, thousandCount, windowTrades.size)
    }

    private fun calculateScore(buyPct: Double, sellPct: Double, count: Int): Int {
        val imbalance = min(55.0, abs(buyPct - sellPct) * 1.1)
        val activity = min(45.0, max(0, count - 20) * 1.5)
        return min(100, (imbalance + activity).toInt())
    }

    private fun buildReason(score: Int, buyPct: Double, sellPct: Double, thousandCount: Int, count: Int): String {
        val direction = if (buyPct >= sellPct) "خرید" else "فروش"
        return buildString {
            append("وضعیت بر اساس معاملات واقعی پنج دقیقه اخیر نوبیتکس محاسبه شده است.\n")
            append("• فشار $direction بیشتر است (${numberFormat.format(max(buyPct, sellPct))}٪).\n")
            append("• تعداد معاملات پنج دقیقه اخیر: $count\n")
            append("• معاملات پنج دقیقه اخیر با حجم ≥ ۱۰۰۰ تتر: $thousandCount\n")
            append("• این عدد فقط شاخص آماری پنج دقیقه اخیر است و به‌تنهایی هشدار ایجاد نمی‌کند.\n")
            when {
                score < 30 -> append("نتیجه: شاخص‌های فعلی در محدوده عادی هستند.")
                score < 50 -> append("نتیجه: افزایش فعالیت دیده می‌شود و بازار تحت نظر است.")
                else -> append("نتیجه: عدم‌تعادل یا فعالیت معاملاتی افزایش یافته و رفتار بازار غیرعادی‌تر شده است.")
            }
        }
    }

    private fun toMillis(epoch: Long): Long = if (epoch < 10_000_000_000L) epoch * 1000L else epoch

    private fun formatTime(epoch: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(toMillis(epoch)))

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }
}
