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
import org.json.JSONArray
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
    private lateinit var priceText: TextView
    private lateinit var reasonText: TextView
    private lateinit var latestTradesText: TextView
    private lateinit var refreshButton: Button

    private val client = OkHttpClient()
    private var refreshJob: Job? = null
    private val numberFormat = DecimalFormat("#,##0.##")

    data class Trade(val time: Long, val price: Double, val volume: Double, val type: String)

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
                if (time >= 0 && price != null && volume != null) result += Trade(time, price, volume, type)
            }
            return result
        }
    }

    private fun render(trades: List<Trade>) {
        if (trades.isEmpty()) {
            statusText.text = "⚪ داده‌ای دریافت نشد"
            reasonText.text = "نوبیتکس پاسخ داد، اما معامله قابل پردازشی در پاسخ وجود نداشت."
            return
        }

        val buy = trades.filter { it.type.equals("buy", true) }.sumOf { it.volume }
        val sell = trades.filter { it.type.equals("sell", true) }.sumOf { it.volume }
        val total = buy + sell
        val buyPct = if (total > 0) buy / total * 100.0 else 50.0
        val sellPct = 100.0 - buyPct
        val largest = trades.maxOfOrNull { it.volume } ?: 0.0
        val largeCount = trades.count { it.volume >= 5000.0 }
        val score = calculateScore(buyPct, sellPct, largeCount, trades.size)
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
        volumeText.text = "حجم معاملات: ${numberFormat.format(total)} USDT"
        speedText.text = "سرعت معاملات: ${trades.size} معامله در داده اخیر"
        largeTradeText.text = "$largeCount معامله بالای ۵۰۰۰ USDT | بزرگ‌ترین: ${numberFormat.format(largest)} USDT"
        priceText.text = "آخرین قیمت معامله: ${numberFormat.format(trades.first().price)} تومان"
        reasonText.text = buildReason(score, buyPct, sellPct, largeCount, trades.size)
        latestTradesText.text = trades.take(10).joinToString("\n") {
            "${formatTime(it.time)} | ${it.type} | ${numberFormat.format(it.price)} | ${numberFormat.format(it.volume)} USDT"
        }
    }

    private fun calculateScore(buyPct: Double, sellPct: Double, largeCount: Int, count: Int): Int {
        val imbalance = min(40.0, abs(buyPct - sellPct) * 0.8)
        val large = min(30.0, largeCount * 10.0)
        val activity = min(30.0, max(0, count - 20) * 1.5)
        return min(100, (imbalance + large + activity).toInt())
    }

    private fun buildReason(score: Int, buyPct: Double, sellPct: Double, largeCount: Int, count: Int): String {
        val direction = if (buyPct >= sellPct) "خرید" else "فروش"
        return buildString {
            append("وضعیت بر اساس معاملات واقعی دریافت‌شده از نوبیتکس محاسبه شده است.\n")
            append("• فشار $direction بیشتر است (${numberFormat.format(max(buyPct, sellPct))}٪).\n")
            append("• تعداد معاملات دریافت‌شده: $count\n")
            append("• معاملات بزرگ: $largeCount\n")
            when {
                score < 30 -> append("نتیجه: شاخص‌های فعلی در محدوده عادی هستند.")
                score < 50 -> append("نتیجه: افزایش فعالیت دیده می‌شود و بازار تحت نظر است.")
                else -> append("نتیجه: چند شاخص هم‌زمان افزایش یافته‌اند و رفتار بازار غیرعادی‌تر شده است.")
            }
        }
    }

    private fun formatTime(epoch: Long): String {
        val millis = if (epoch < 10_000_000_000L) epoch * 1000L else epoch
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }
}
