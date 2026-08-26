package com.tetherguardian.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tetherguardian.app.data.NobitexApi
import com.tetherguardian.app.data.Trade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringActivity : AppCompatActivity() {
    private lateinit var stateText: TextView
    private lateinit var scoreText: TextView
    private lateinit var buyText: TextView
    private lateinit var sellText: TextView
    private lateinit var activityText: TextView
    private lateinit var reasonText: TextView
    private lateinit var latestText: TextView
    private lateinit var refreshButton: Button
    private val api = NobitexApi()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var refreshJob: Job? = null
    private val number = DecimalFormat("#,##0.##")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trade_monitoring)
        stateText = findViewById(R.id.marketStateText)
        scoreText = findViewById(R.id.scoreText)
        buyText = findViewById(R.id.buyPressureText)
        sellText = findViewById(R.id.sellPressureText)
        activityText = findViewById(R.id.activityText)
        reasonText = findViewById(R.id.reasonText)
        latestText = findViewById(R.id.latestTradesText)
        refreshButton = findViewById(R.id.refreshTradesButton)
        refreshButton.setOnClickListener { loadTrades() }
        loadTrades()
        refreshJob = scope.launch {
            while (isActive) {
                delay(10_000)
                loadTrades()
            }
        }
    }

    private fun loadTrades() {
        stateText.text = "● در حال پایش معاملات..."
        scope.launch {
            try {
                val trades = withContext(Dispatchers.IO) { api.getRecentTrades("USDTIRT") }
                render(trades)
            } catch (e: Exception) {
                stateText.text = "● دریافت معاملات ناموفق"
                scoreText.text = "شاخص رفتار غیرعادی: -- / 100"
                reasonText.text = "چرایی وضعیت\nامکان دریافت معاملات عمومی نوبیتکس وجود نداشت.\n${e.message ?: "خطای نامشخص"}"
            }
        }
    }

    private fun render(trades: List<Trade>) {
        if (trades.isEmpty()) {
            stateText.text = "● داده‌ای برای تحلیل دریافت نشد"
            reasonText.text = "چرایی وضعیت\nپاسخ معاملات خالی بود."
            return
        }
        val recent = trades.take(50)
        var buy = 0.0
        var sell = 0.0
        var largeCount = 0
        var maxVolume = 0.0
        recent.forEach { trade ->
            val volume = trade.volume.toDoubleOrNull() ?: 0.0
            if (trade.type.equals("buy", true)) buy += volume
            if (trade.type.equals("sell", true)) sell += volume
            if (volume >= 5_000.0) largeCount++
            maxVolume = max(maxVolume, volume)
        }
        val total = buy + sell
        val buyPct = if (total > 0) buy * 100.0 / total else 50.0
        val sellPct = 100.0 - buyPct
        val imbalance = abs(buyPct - sellPct)
        val score = min(100, max(0, (imbalance * 0.7 + largeCount * 4.0).toInt()))
        val state = when {
            score >= 70 -> "هشدار شدید"
            score >= 50 -> "غیرعادی"
            score >= 30 -> "تحت نظر"
            else -> "عادی"
        }
        stateText.text = "● $state"
        scoreText.text = "شاخص رفتار غیرعادی: $score / 100"
        buyText.text = "فشار خرید\n${number.format(buyPct)}٪"
        sellText.text = "فشار فروش\n${number.format(sellPct)}٪"
        activityText.text = "پایش اخیر\nتعداد معاملات دریافتی: ${recent.size}\nحجم خرید: ${number.format(buy)} USDT\nحجم فروش: ${number.format(sell)} USDT\nمعاملات ≥ ۵۰۰۰ USDT: $largeCount\nبزرگ‌ترین حجم: ${number.format(maxVolume)} USDT"
        reasonText.text = buildReason(state, buyPct, sellPct, largeCount, recent.size)
        latestText.text = "آخرین معاملات\n" + recent.take(8).joinToString("\n") { trade ->
            val type = if (trade.type.equals("buy", true)) "خرید" else if (trade.type.equals("sell", true)) "فروش" else trade.type
            "${formatTime(trade.time)}  $type  ${number.format(trade.volume.toDoubleOrNull() ?: 0.0)} USDT  @ ${trade.price}"
        }
    }

    private fun buildReason(state: String, buyPct: Double, sellPct: Double, largeCount: Int, count: Int): String {
        val dominant = if (buyPct >= sellPct) "خرید" else "فروش"
        val largeReason = if (largeCount > 0) {
            "⚠ $largeCount معامله با حجم حداقل ۵۰۰۰ USDT دیده شد."
        } else {
            "✓ معامله بزرگ با آستانه آزمایشی ۵۰۰۰ USDT مشاهده نشد."
        }
        return "چرایی وضعیت\n✓ $count معامله در نمونه اخیر بررسی شد.\n✓ فشار $dominant بیشتر است (${number.format(max(buyPct, sellPct))}٪).\n$largeReason\n\nنتیجه فعلی: $state. این مرحله آزمایشی است و هنوز الگوریتم نهایی هشدار فعال نشده است."
    }

    private fun formatTime(seconds: Long): String {
        val millis = if (seconds < 10_000_000_000L) seconds * 1000 else seconds
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
