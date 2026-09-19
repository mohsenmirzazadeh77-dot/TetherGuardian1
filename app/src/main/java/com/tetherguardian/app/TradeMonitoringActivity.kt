package com.tetherguardian.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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

    companion object {
        private const val SOUND_REQUEST = 4217
    }

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

    private val numberFormat =
        DecimalFormat("#,##0.##")

    private val priceFormat =
        DecimalFormat("#,##0")

    private val volumeFormat =
        DecimalFormat("#,##0.##")

    private val recentTrades =
        LinkedHashMap<String, Trade>()

    data class Trade(
        val time: Long,
        val priceRial: Double,
        val volume: Double,
        val type: String
    )

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    TradeMonitoringService.ACTION_STATUS_UPDATE
                ) {
                    return
                }

                applyStatus(
                    intent.getIntExtra(
                        TradeMonitoringService.EXTRA_SCORE,
                        0
                    ),
                    intent.getDoubleExtra(
                        TradeMonitoringService.EXTRA_BUY_PRESSURE,
                        50.0
                    ),
                    intent.getDoubleExtra(
                        TradeMonitoringService.EXTRA_SELL_PRESSURE,
                        50.0
                    ),
                    intent.getIntExtra(
                        TradeMonitoringService.EXTRA_TRADE_COUNT,
                        0
                    ),
                    intent.getIntExtra(
                        TradeMonitoringService.EXTRA_COUNT_1000,
                        0
                    ),
                    intent.getStringExtra(
                        TradeMonitoringService.EXTRA_REASON
                    ) ?: ""
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_trade_monitoring
        )

        bindViews()

        findViewById<Button>(
            R.id.backToMainButton
        ).setOnClickListener {
            startActivity(
                Intent(
                    this,
                    MainActivity::class.java
                ).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }

        val prefs =
            getSharedPreferences(
                TradeMonitoringService.PREFS,
                MODE_PRIVATE
            )

        severeAlertSwitch.isChecked =
            prefs.getBoolean(
                TradeMonitoringService.KEY_SEVERE_ALERT,
                true
            )

        severeAlertSwitch.setOnCheckedChangeListener {
                _,
                enabled ->
            prefs.edit()
                .putBoolean(
                    TradeMonitoringService.KEY_SEVERE_ALERT,
                    enabled
                )
                .apply()
        }

        findViewById<Button>(
            R.id.tradeAlertSoundButton
        ).setOnClickListener {
            openSoundPicker()
        }

        refreshButton.setOnClickListener {
            refreshOnce()
        }

        loadLastStatus()

        refreshJob =
            CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    refreshOnce()
                    delay(10_000)
                }
            }
    }

    override fun onStart() {
        super.onStart()

        val filter =
            IntentFilter(
                TradeMonitoringService.ACTION_STATUS_UPDATE
            )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                statusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                statusReceiver,
                filter
            )
        }

        loadLastStatus()
    }

    private fun loadLastStatus() {

        val prefs =
            getSharedPreferences(
                TradeMonitoringService.PREFS,
                MODE_PRIVATE
            )

        if (
            !prefs.contains(
                TradeMonitoringService.KEY_LAST_SCORE
            )
        ) {
            return
        }

        applyStatus(
            prefs.getInt(
                TradeMonitoringService.KEY_LAST_SCORE,
                0
            ),
            prefs.getString(
                TradeMonitoringService.KEY_LAST_BUY,
                "50"
            )?.toDoubleOrNull() ?: 50.0,
            prefs.getString(
                TradeMonitoringService.KEY_LAST_SELL,
                "50"
            )?.toDoubleOrNull() ?: 50.0,
            prefs.getInt(
                TradeMonitoringService.KEY_LAST_COUNT,
                0
            ),
            prefs.getInt(
                TradeMonitoringService.KEY_LAST_COUNT_1000,
                0
            ),
            prefs.getString(
                TradeMonitoringService.KEY_LAST_REASON,
                ""
            ) ?: ""
        )
    }

    private fun applyStatus(
        score: Int,
        buyPct: Double,
        sellPct: Double,
        count: Int,
        count1000: Int,
        reason: String
    ) {
        statusText.text =
            when {
                score >= 70 -> "🟠 هشدار شدید"
                score >= 50 -> "🟠 غیرعادی"
                score >= 30 -> "🟡 تحت نظر"
                else -> "🟢 عادی"
            }

        scoreText.text =
            "$score/100"

        buyPressureText.text =
            "فشار خرید: ${numberFormat.format(buyPct)}٪"

        sellPressureText.text =
            "فشار فروش: ${numberFormat.format(sellPct)}٪"

        speedText.text =
            "تعداد معاملات ۵ دقیقه اخیر: $count"

        largeCountText.text =
            "تعداد معاملات ۵ دقیقه اخیر ≥ ۱۰۰۰ تتر: $count1000"

        if (reason.isNotBlank()) {
            reasonText.text = reason
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }

        super.onStop()
    }

    private fun bindViews() {
        statusText =
            findViewById(R.id.marketStateText)

        scoreText =
            findViewById(R.id.scoreText)

        buyPressureText =
            findViewById(R.id.buyPressureText)

        sellPressureText =
            findViewById(R.id.sellPressureText)

        volumeText =
            findViewById(R.id.volumeText)

        speedText =
            findViewById(R.id.speedText)

        largeCountText =
            findViewById(R.id.largeCountText)

        priceText =
            findViewById(R.id.priceText)

        reasonText =
            findViewById(R.id.reasonText)

        tradesTable =
            findViewById(R.id.tradesTable)

        refreshButton =
            findViewById(R.id.refreshTradesButton)

        severeAlertSwitch =
            findViewById(R.id.severeAlertSwitch)
    }

    private fun openSoundPicker() {

        val prefs =
            getSharedPreferences(
                TradeMonitoringService.PREFS,
                MODE_PRIVATE
            )

        val current =
            prefs.getString(
                TradeMonitoringService.KEY_SOUND_URI,
                null
            )?.let(Uri::parse)

        startActivityForResult(
            Intent(
                RingtoneManager.ACTION_RINGTONE_PICKER
            ).apply {
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_NOTIFICATION
                )

                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_TITLE,
                    "انتخاب صدای هشدار مانیتورینگ"
                )

                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,
                    true
                )

                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,
                    false
                )

                if (current != null) {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        current
                    )
                }
            },
            SOUND_REQUEST
        )
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != SOUND_REQUEST ||
            resultCode != RESULT_OK
        ) {
            return
        }

        val uri =
            data?.getParcelableExtra<Uri>(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            ) ?: return

        getSharedPreferences(
            TradeMonitoringService.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                TradeMonitoringService.KEY_SOUND_URI,
                uri.toString()
            )
            .apply()

        findViewById<Button>(
            R.id.tradeAlertSoundButton
        ).text =
            "🔊 صدای هشدار مانیتورینگ انتخاب شد"
    }

    private fun refreshOnce() {

        CoroutineScope(Dispatchers.IO).launch {

            runCatching {
                fetchTrades()
            }
                .onSuccess { trades ->
                    withContext(Dispatchers.Main) {
                        render(trades)
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        statusText.text =
                            "⚪ دریافت داده ناموفق"

                        reasonText.text =
                            "دلیل: ${
                                error.message
                                    ?: "خطای نامشخص"
                            }"
                    }
                }
        }
    }

    private fun fetchTrades(): List<Trade> {

        val request =
            Request.Builder()
                .url(
                    "https://apiv2.nobitex.ir/v2/trades/USDTIRT"
                )
                .get()
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    error(
                        "خطای HTTP نوبیتکس: ${response.code}"
                    )
                }

                val array =
                    JSONObject(
                        response.body?.string() ?: "{}"
                    )
                        .optJSONArray("trades")
                        ?: error(
                            "فهرست معاملات در پاسخ نوبیتکس وجود ندارد"
                        )

                return buildList {

                    for (i in 0 until array.length()) {

                        val item =
                            array.optJSONObject(i)
                                ?: continue

                        val time =
                            item.optLong(
                                "time",
                                -1L
                            )

                        val price =
                            item.optString(
                                "price"
                            ).toDoubleOrNull()

                        val volume =
                            item.optString(
                                "volume"
                            ).toDoubleOrNull()

                        val type =
                            item.optString(
                                "type",
                                "unknown"
                            )

                        if (
                            time >= 0 &&
                            price != null &&
                            volume != null
                        ) {
                            add(
                                Trade(
                                    time,
                                    price,
                                    volume,
                                    type
                                )
                            )
                        }
                    }
                }
            }
    }

    private fun render(
        fetched: List<Trade>
    ) {

        if (fetched.isEmpty()) {
            statusText.text =
                "⚪ داده‌ای دریافت نشد"
            return
        }

        val now =
            System.currentTimeMillis()

        val cutoff =
            now - 5 * 60 * 1000L

        fetched.forEach { trade ->

            recentTrades[
                "${trade.time}|${trade.priceRial}|${trade.volume}|${trade.type}"
            ] = trade
        }

        recentTrades.entries.removeIf {
            toMillis(it.value.time) < cutoff
        }

        val window =
            recentTrades.values
                .filter {
                    toMillis(it.time) >= cutoff
                }
                .sortedBy {
                    toMillis(it.time)
                }

        if (window.isEmpty()) return

        val buy =
            window
                .filter {
                    it.type.equals("buy", true)
                }
                .sumOf {
                    it.volume
                }

        val sell =
            window
                .filter {
                    it.type.equals("sell", true)
                }
                .sumOf {
                    it.volume
                }

        val total =
            buy + sell

        val buyPct =
            if (total > 0.0) {
                buy / total * 100.0
            } else {
                50.0
            }

        val sellPct =
            100.0 - buyPct

        /*
         * فقط آمار نمایشی.
         * دیگر شرط امتیاز شدید نیست.
         */
        val count1000 =
            window.count {
                it.volume >= 1000.0
            }

        val score =
            calculateScore(
                window,
                now
            )

        val enabled =
            severeAlertSwitch.isChecked

        statusText.text =
            when {
                score >= 70 -> "🟠 هشدار شدید"
                score >= 50 -> "🟠 غیرعادی"
                score >= 30 -> "🟡 تحت نظر"
                else -> "🟢 عادی"
            }

        scoreText.text =
            "$score/100"

        buyPressureText.text =
            "فشار خرید: ${
                numberFormat.format(buyPct)
            }٪"

        sellPressureText.text =
            "فشار فروش: ${
                numberFormat.format(sellPct)
            }٪"

        volumeText.text =
            "حجم معاملات ۵ دقیقه اخیر: ${
                numberFormat.format(total)
            } USDT"

        speedText.text =
            "تعداد معاملات ۵ دقیقه اخیر: ${window.size}"

        largeCountText.text =
            "تعداد معاملات ۵ دقیقه اخیر ≥ ۱۰۰۰ تتر: $count1000"

        val topTen =
            window
                .sortedByDescending {
                    it.volume
                }
                .take(10)
                .sortedBy {
                    toMillis(it.time)
                }

        renderTradeRows(topTen)

        priceText.text =
            "آخرین قیمت معامله: ${
                priceFormat.format(
                    window.last().priceRial / 10.0
                )
            } تومان"

        reasonText.text =
            buildReason(
                score,
                window,
                buyPct,
                sellPct,
                count1000,
                enabled,
                now
            )
    }

    /*
     * همان منطق Service، تا عدد صفحه و Service یکی باشد.
     */
    private fun calculateScore(
        window: List<Trade>,
        now: Long
    ): Int {

        if (window.isEmpty()) return 0

        val recentCutoff =
            now - 60_000L

        val recent =
            window.filter {
                toMillis(it.time) >= recentCutoff
            }

        val previous =
            window.filter {
                toMillis(it.time) < recentCutoff
            }

        val recentCount =
            recent.size

        val previousCountPerMinute =
            previous.size.toDouble() / 4.0

        val countAcceleration =
            if (previousCountPerMinute > 0.0) {
                recentCount.toDouble() /
                    previousCountPerMinute
            } else {
                if (recentCount >= 3) 2.5 else 1.0
            }

        val recentVolume =
            recent.sumOf {
                it.volume
            }

        val previousVolume =
            previous.sumOf {
                it.volume
            }

        val previousVolumePerMinute =
            previousVolume / 4.0

        val volumeAcceleration =
            if (previousVolumePerMinute > 0.0) {
                recentVolume /
                    previousVolumePerMinute
            } else {
                if (
                    recentVolume > 0.0 &&
                    recentCount >= 3
                ) {
                    2.5
                } else {
                    1.0
                }
            }

        val recentBuyVolume =
            recent
                .filter {
                    it.type.equals("buy", true)
                }
                .sumOf {
                    it.volume
                }

        val recentSellVolume =
            recent
                .filter {
                    it.type.equals("sell", true)
                }
                .sumOf {
                    it.volume
                }

        val recentVolumeTotal =
            recentBuyVolume +
                recentSellVolume

        val recentBuyVolumePct =
            if (recentVolumeTotal > 0.0) {
                recentBuyVolume /
                    recentVolumeTotal * 100.0
            } else {
                50.0
            }

        val recentBuyCount =
            recent.count {
                it.type.equals("buy", true)
            }

        val recentSellCount =
            recent.count {
                it.type.equals("sell", true)
            }

        val recentTradeTotal =
            recentBuyCount +
                recentSellCount

        val recentBuyCountPct =
            if (recentTradeTotal > 0) {
                recentBuyCount.toDouble() /
                    recentTradeTotal.toDouble() *
                    100.0
            } else {
                50.0
            }

        val directionalPressure =
            recentBuyVolumePct * 0.65 +
                recentBuyCountPct * 0.35

        val directionalStrength =
            abs(
                directionalPressure - 50.0
            )

        val directionScore =
            min(
                30.0,
                directionalStrength * 0.60
            )

        val countAccelerationScore =
            min(
                20.0,
                max(
                    0.0,
                    countAcceleration - 1.0
                ) * 10.0
            )

        val volumeAccelerationScore =
            min(
                20.0,
                max(
                    0.0,
                    volumeAcceleration - 1.0
                ) * 10.0
            )

        val sortedVolumes =
            window
                .map { it.volume }
                .sorted()

        val medianVolume =
            if (sortedVolumes.isEmpty()) {
                0.0
            } else {
                val middle =
                    sortedVolumes.size / 2

                if (
                    sortedVolumes.size % 2 == 0
                ) {
                    (
                        sortedVolumes[middle - 1] +
                            sortedVolumes[middle]
                        ) / 2.0
                } else {
                    sortedVolumes[middle]
                }
            }

        val unusualThreshold =
            if (medianVolume > 0.0) {
                medianVolume * 5.0
            } else {
                Double.MAX_VALUE
            }

        val unusualTrades =
            recent.filter {
                it.volume >= unusualThreshold
            }

        val unusualBuyVolume =
            unusualTrades
                .filter {
                    it.type.equals("buy", true)
                }
                .sumOf {
                    it.volume
                }

        val unusualSellVolume =
            unusualTrades
                .filter {
                    it.type.equals("sell", true)
                }
                .sumOf {
                    it.volume
                }

        val unusualTotalVolume =
            unusualBuyVolume +
                unusualSellVolume

        val unusualDirectionalStrength =
            if (unusualTotalVolume > 0.0) {
                abs(
                    unusualBuyVolume -
                        unusualSellVolume
                ) / unusualTotalVolume
            } else {
                0.0
            }

        val unusualDirectionScore =
            min(
                15.0,
                unusualDirectionalStrength * 15.0
            )

        val recentPriceMovePct =
            if (recent.size >= 2) {

                val firstPrice =
                    recent.first().priceRial

                val lastPrice =
                    recent.last().priceRial

                if (firstPrice > 0.0) {
                    abs(
                        lastPrice -
                            firstPrice
                    ) / firstPrice * 100.0
                } else {
                    0.0
                }

            } else {
                0.0
            }

        val priceConfirmationScore =
            min(
                10.0,
                recentPriceMovePct * 20.0
            )

        val overallBuy =
            window
                .filter {
                    it.type.equals("buy", true)
                }
                .sumOf {
                    it.volume
                }

        val overallSell =
            window
                .filter {
                    it.type.equals("sell", true)
                }
                .sumOf {
                    it.volume
                }

        val overallTotal =
            overallBuy + overallSell

        val overallBuyPct =
            if (overallTotal > 0.0) {
                overallBuy /
                    overallTotal * 100.0
            } else {
                50.0
            }

        val overallDirection =
            if (overallBuyPct >= 50.0) 1 else -1

        val recentDirection =
            if (directionalPressure >= 50.0) 1 else -1

        val persistenceScore =
            if (
                recentCount >= 3 &&
                overallDirection ==
                recentDirection &&
                directionalStrength >= 8.0
            ) {
                5.0
            } else {
                0.0
            }

        return min(
            100.0,
            (
                directionScore +
                    countAccelerationScore +
                    volumeAccelerationScore +
                    unusualDirectionScore +
                    priceConfirmationScore +
                    persistenceScore
                )
        ).toInt()
    }

    private fun buildReason(
        score: Int,
        window: List<Trade>,
        buyPct: Double,
        sellPct: Double,
        count1000: Int,
        enabled: Boolean,
        now: Long
    ): String {

        val recentCutoff =
            now - 60_000L

        val recent =
            window.filter {
                toMillis(it.time) >= recentCutoff
            }

        val previous =
            window.filter {
                toMillis(it.time) < recentCutoff
            }

        val previousCountPerMinute =
            previous.size.toDouble() / 4.0

        val countAcceleration =
            if (previousCountPerMinute > 0.0) {
                recent.size.toDouble() /
                    previousCountPerMinute
            } else {
                1.0
            }

        val recentVolume =
            recent.sumOf {
                it.volume
            }

        val previousVolume =
            previous.sumOf {
                it.volume
            }

        val previousVolumePerMinute =
            previousVolume / 4.0

        val volumeAcceleration =
            if (previousVolumePerMinute > 0.0) {
                recentVolume /
                    previousVolumePerMinute
            } else {
                1.0
            }

        return buildString {

            append(
                "وضعیت بر اساس معاملات واقعی پنج دقیقه اخیر نوبیتکس محاسبه شده است.\n"
            )

            append(
                "• فشار ${
                    if (buyPct >= sellPct) {
                        "خرید"
                    } else {
                        "فروش"
                    }
                } بیشتر است (${
                    numberFormat.format(
                        max(buyPct, sellPct)
                    )
                }٪).\n"
            )

            append(
                "• تعداد معاملات پنج دقیقه اخیر: ${
                    window.size
                }\n"
            )

            append(
                "• معاملات پنج دقیقه اخیر با حجم ≥ ۱۰۰۰ تتر: ${
                    count1000
                }\n"
            )

            append(
                "• تغییر سرعت معاملات در یک دقیقه اخیر: ${
                    numberFormat.format(
                        countAcceleration
                    )
                } برابر خط پایه.\n"
            )

            append(
                "• تغییر حجم معاملات در یک دقیقه اخیر: ${
                    numberFormat.format(
                        volumeAcceleration
                    )
                } برابر خط پایه.\n"
            )

            append(
                "• امتیاز بر اساس تغییر رفتار معاملات، جهت معاملات و معاملات غیرعادی محاسبه می‌شود.\n"
            )

            append(
                "• عدد ۱۰۰۰ تتر صرفاً برای نمایش آمار است و به‌تنهایی شرط هشدار شدید نیست.\n"
            )

            append(
                "• هشدار شدید: ${
                    if (enabled) {
                        "فعال"
                    } else {
                        "غیرفعال"
                    }
                }\n"
            )

            append(
                when {
                    score < 30 ->
                        "نتیجه: شاخص‌های فعلی در محدوده عادی هستند."

                    score < 50 ->
                        "نتیجه: نشانه‌هایی از تغییر فعالیت معاملات دیده می‌شود و بازار تحت نظر است."

                    score < 70 ->
                        "نتیجه: تغییر محسوسی در رفتار معاملات دیده شده و وضعیت غیرعادی‌تر شده است."

                    else ->
                        "نتیجه: تغییر شدید و جهت‌دار در رفتار معاملات شناسایی شده است."
                }
            )
        }
    }

    private fun renderTradeRows(
        rows: List<Trade>
    ) {

        while (
            tradesTable.childCount > 1
        ) {
            tradesTable.removeViewAt(1)
        }

        rows.forEachIndexed { index, trade ->

            val row =
                TableRow(this)

            row.setPadding(
                4,
                5,
                4,
                5
            )

            val values =
                listOf(
                    formatTime(trade.time),
                    priceFormat.format(
                        trade.priceRial / 10.0
                    ),
                    when {
                        trade.type.equals(
                            "buy",
                            true
                        ) ->
                            "خرید"

                        trade.type.equals(
                            "sell",
                            true
                        ) ->
                            "فروش"

                        else ->
                            trade.type
                    },
                    volumeFormat.format(
                        trade.volume
                    )
                )

            values.forEachIndexed {
                    column,
                    value ->

                val cell =
                    TextView(this)

                cell.text = value
                cell.textSize = 12f
                cell.gravity =
                    android.view.Gravity.CENTER

                cell.setPadding(
                    3,
                    7,
                    3,
                    7
                )

                cell.setBackgroundColor(
                    if (index % 2 == 0) {
                        0xFFEEF4E9.toInt()
                    } else {
                        0xFFF7FAF4.toInt()
                    }
                )

                val weight =
                    when (column) {
                        0 -> 1.1f
                        1 -> 1.3f
                        2 -> 0.9f
                        else -> 1.2f
                    }

                row.addView(
                    cell,
                    TableRow.LayoutParams(
                        0,
                        TableRow.LayoutParams.WRAP_CONTENT,
                        weight
                    )
                )
            }

            tradesTable.addView(row)
        }
    }

    private fun toMillis(
        value: Long
    ): Long =
        if (value < 10_000_000_000L) {
            value * 1000L
        } else {
            value
        }

    private fun formatTime(
        value: Long
    ): String =
        SimpleDateFormat(
            "HH:mm:ss",
            Locale.US
        ).format(
            Date(
                toMillis(value)
            )
        )

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }
}
