package com.tetherguardian.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tetherguardian.app.data.NobitexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val api = NobitexApi()
    private var refreshJob: Job? = null
    private val numberFormat = DecimalFormat("#,##0.##")

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
            runCatching { api.getTrades("USDTIRT") }
                .onSuccess { trades ->
                    withContext(Dispatchers.Main) { render(trades) }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        statusText.text = "⚪ دریافت داده ناموفق"
                        reasonText.text = "دلیل: ${error.message ?: "خطای نامشخص"}"
                    }
                }
        }
    }

    private fun render(trades: List<NobitexApi.Trade>) {
        if (trades.isEmpty()) {
            statusText.text = "⚪ داده‌ای دریافت نشد"
            return
        }

        val buy = trades.filter { it.type.equals("buy", true) }.sumOf { it.volume }
        val sell = trades.filter { it.type.equals("sell", true) }.sumOf { it.volume }
        val total = buy + sell
        val buyPct = if (total > 0) buy / total * 100 else 50.0
        val sellPct = 100.0 - buyPct
        val largest = trades.maxOfOrNull { it.volume } ?: 0.0
        val largeCount = trades.count { it.volume >= 5000.0 }
        val score = calculateScore(buyPct, sellPct, largeCount, trades.size)
        val state = when {
            score >= 70 -> "🔴 هشدار شدید"
            score >= 50 -> "🟠 غیرعادی"
            score >= 30 -> "🟡 تحت نظر"
            else -> "🟢 عادی"
        }

        statusText.text = state
        scoreText.text = "${score}/100"
        buyPressureText.text = "${numberFormat.format(buyPct)}٪"
        sellPressureText.text = "${numberFormat.format(sellPct)}٪"
        volumeText.text = "${numberFormat.format(total)} USDT"
        speedText.text = "${trades.size} معامله در بازه اخیر"
        largeTradeText.text = "$largeCount معامله بالای ۵۰۰۰ USDT | بزرگ‌ترین: ${numberFormat.format(largest)} USDT"
        priceText.text = "آخرین قیمت: ${numberFormat.format(trades.first().price)} تومان"
        reasonText.text = buildReason(score, buyPct, sellPct, largeCount, trades.size)
        latestTradesText.text = trades.take(10).joinToString("\n") {
            "${formatTime(it.time)} | ${it.type} | ${numberFormat.format(it.price)} | ${numberFormat.format(it.volume)} USDT"
        }
    }

    private fun calculateScore(buyPct: Double, sellPct: Double, largeCount: Int, count: Int): Int {
        val imbalance = min(40.0, kotlin.math.abs(buyPct - sellPct) * 0.8)
        val large = min(30.0, largeCount * 10.0)
        val activity = min(30.0, max(0, count - 20) * 1.5)
        return min(100, (imbalance + large + activity).toInt())
    }

    private fun buildReason(score: Int, buyPct: Double, sellPct: Double, largeCount: Int, count: Int): String {
        val direction = if (buyPct >= sellPct) "خرید" else "فروش"
        val lines = mutableListOf<String>()
        lines += "وضعیت بر اساس معاملات دریافت‌شده از بازار محاسبه شده است."
        lines += "• فشار $direction بیشتر است (${numberFormat.format(max(buyPct, sellPct))}٪)."
        lines += "• تعداد معاملات دریافت‌شده: $count"
        lines += "• معاملات بزرگ: $largeCount"
        if (score < 30) lines += "نتیجه: شاخص‌ها هنوز از محدوده هشدار عبور نکرده‌اند."
        else if (score < 50) lines += "نتیجه: افزایش فعالیت دیده می‌شود و بازار تحت نظر است."
        else lines += "نتیجه: چند شاخص هم‌زمان افزایش یافته‌اند و رفتار بازار غیرعادی‌تر شده است."
        return lines.joinToString("\n")
    }

    private fun formatTime(epoch: Long): String {
        val millis = if (epoch < 10_000_000_000L) epoch * 1000 else epoch
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }
}
