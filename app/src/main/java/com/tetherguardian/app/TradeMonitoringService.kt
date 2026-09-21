package com.tetherguardian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TradeMonitoringService : Service() {

    companion object {
        const val PREFS = "trade_monitoring_prefs"
        const val KEY_SEVERE_ALERT = "severe_alert_enabled"
        const val KEY_VERY_SEVERE_ALERT = "very_severe_alert_enabled"
        const val KEY_SEVERE_SOUND_URI = "severe_alert_sound_uri"
        const val KEY_VERY_SEVERE_SOUND_URI = "very_severe_alert_sound_uri"
        const val KEY_ACTIVE = "trade_monitoring_active"
        const val KEY_SOUND_URI = "trade_monitoring_sound_uri"
        const val KEY_LAST_SCORE = "last_score"
        const val KEY_LAST_BUY = "last_buy_pressure"
        const val KEY_LAST_SELL = "last_sell_pressure"
        const val KEY_LAST_COUNT = "last_trade_count"
        const val KEY_LAST_COUNT_1000 = "last_count_1000"
        const val KEY_LAST_REASON = "last_reason"
        const val KEY_LAST_SUPPORT_BLOCK_PRICE = "last_support_block_price"
        const val KEY_LAST_SUPPORT_BLOCK_VOLUME = "last_support_block_volume"
        const val KEY_LAST_RESISTANCE_BLOCK_PRICE = "last_resistance_block_price"
        const val KEY_LAST_RESISTANCE_BLOCK_VOLUME = "last_resistance_block_volume"

        const val ACTION_START = "com.tetherguardian.app.action.TRADE_MONITOR_START"
        const val ACTION_STOP = "com.tetherguardian.app.action.TRADE_MONITOR_STOP"
        const val ACTION_REFRESH = "com.tetherguardian.app.action.TRADE_MONITOR_REFRESH"
        const val ACTION_STATUS_UPDATE = "com.tetherguardian.app.action.TRADE_MONITOR_STATUS_UPDATE"
        const val ACTION_ALERT_FINISHED = "com.tetherguardian.app.action.TRADE_MONITOR_ALERT_FINISHED"
        const val ACTION_RECALCULATE_BLOCKS = "com.tetherguardian.app.action.TRADE_MONITOR_RECALCULATE_BLOCKS"

        const val EXTRA_SCORE = "score"
        const val EXTRA_BUY_PRESSURE = "buy_pressure"
        const val EXTRA_SELL_PRESSURE = "sell_pressure"
        const val EXTRA_TRADE_COUNT = "trade_count"
        const val EXTRA_COUNT_1000 = "count_1000"
        const val EXTRA_REASON = "reason"
        const val EXTRA_ALERT_TYPE = "alert_type"
        const val EXTRA_BLOCK_PRICE = "block_price"
        const val EXTRA_BLOCK_TYPE = "block_type"
        const val EXTRA_SUPPORT_BLOCK_PRICE = "support_block_price"
        const val EXTRA_SUPPORT_BLOCK_VOLUME = "support_block_volume"
        const val EXTRA_RESISTANCE_BLOCK_PRICE = "resistance_block_price"
        const val EXTRA_RESISTANCE_BLOCK_VOLUME = "resistance_block_volume"
        const val ALERT_TYPE_SEVERE = "severe"
        const val ALERT_TYPE_VERY_SEVERE = "very_severe"

        const val ALERT_NOTIFICATION_ID = 4202
        private const val CHANNEL_ID = "trade_monitoring_channel"
        private const val ALERT_CHANNEL_ID = "trade_monitoring_alert_channel"
        private const val NOTIFICATION_ID = 4201
    }

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    private val trades = LinkedHashMap<String, Trade>()

    /*
     * این متغیر فقط وضعیت نمایش هشدار شدید را نگه می‌دارد.
     * منطق re-arm قبلی حفظ شده است:
     * با پایان نمایش هشدار، اجازه نمایش مجدد وجود دارد.
     */
    private var severeAlreadyShown = false
    private var verySevereAlreadyShown = false

    private data class OrderBookLevel(
        val price: Double,
        val volume: Double
    )

    private data class TrackedBlock(
        val price: Double,
        val baselineVolume: Double
    )

    private data class BlockCollapse(
        val price: Double,
        val side: String
    )

    private data class OrderBook(
        val asks: List<OrderBookLevel>,
        val bids: List<OrderBookLevel>
    )

    private var orderBlockBasePrice: Double? = null
    private var resistanceBlock: TrackedBlock? = null
    private var supportBlock: TrackedBlock? = null
    private var previousOrderBookPrice: Double? = null
    private var lastLiveTradePrice: Double? = null

    private data class Trade(
        val time: Long,
        val price: Double,
        val volume: Double,
        val type: String
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("مانیتورینگ فعال • در حال دریافت معاملات...")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }

            ACTION_ALERT_FINISHED -> {
                /*
                 * فقط اعلان سیستم بسته می‌شود.
                 * severeAlreadyShown عمداً reset نمی‌شود؛
                 * چون ممکن است همان وضعیت شدید هنوز ادامه داشته باشد.
                 */
                getSystemService(NotificationManager::class.java)
                    .cancel(ALERT_NOTIFICATION_ID)
            }

            ACTION_RECALCULATE_BLOCKS -> {
                scope.launch {
                    recalculateOrderBlocks()
                }
            }

            ACTION_START,
            ACTION_REFRESH,
            null -> startMonitoring()
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .apply()

        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                runCatching {
                    monitorOnce()
                }

                delay(10_000L)
            }
        }
    }

    private fun stopMonitoring() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply()

        job?.cancel()
        job = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun monitorOnce() {

        val request = Request.Builder()
            .url("https://apiv2.nobitex.ir/v2/trades/USDTIRT")
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) return

            val array = JSONObject(
                response.body?.string() ?: "{}"
            ).optJSONArray("trades") ?: return

            val now = System.currentTimeMillis()
            val cutoff = now - 5 * 60 * 1000L

            for (i in 0 until array.length()) {

                val item = array.optJSONObject(i) ?: continue

                val time = item.optLong("time", -1L)
                val price = item.optString("price").toDoubleOrNull()
                val volume = item.optString("volume").toDoubleOrNull()
                val type = item.optString("type", "unknown")

                if (time >= 0 && price != null && volume != null) {
                    trades["$time|$price|$volume|$type"] =
                        Trade(
                            time = time,
                            price = price,
                            volume = volume,
                            type = type
                        )
                }
            }

            trades.entries.removeIf {
                toMillis(it.value.time) < cutoff
            }

            val window = trades.values
                .filter { toMillis(it.time) >= cutoff }
                .sortedBy { toMillis(it.time) }

            if (window.isEmpty()) return

            val currentLivePrice = window.last().price
            lastLiveTradePrice = currentLivePrice

            val orderBook = fetchOrderBook()
            val blockCollapse =
                orderBook?.let {
                    updateOrderBlocks(
                        currentPrice = currentLivePrice,
                        asks = it.asks,
                        bids = it.bids
                    )
                }

            /*
             * فشار کلی پنج دقیقه اخیر
             */
            val buy = window
                .filter { it.type.equals("buy", true) }
                .sumOf { it.volume }

            val sell = window
                .filter { it.type.equals("sell", true) }
                .sumOf { it.volume }

            val total = buy + sell

            val buyPct =
                if (total > 0.0) buy / total * 100.0 else 50.0

            val sellPct = 100.0 - buyPct

            /*
             * فقط برای نمایش در صفحه نگه داشته شده.
             * دیگر هیچ نقشی در امتیاز یا هشدار شدید ندارد.
             */
            val largeCount = window.count {
                it.volume >= 1000.0
            }

            /*
             * ---------------------------------------------------------
             * ۱) تقسیم پنج دقیقه به دو بخش:
             *
             * recent = یک دقیقه اخیر
             * previous = چهار دقیقه قبل
             *
             * هدف: پیدا کردن "تغییر رفتار" نه صرفاً زیاد بودن فعالیت.
             * ---------------------------------------------------------
             */

            val recentCutoff = now - 60_000L

            val recent = window.filter {
                toMillis(it.time) >= recentCutoff
            }

            val previous = window.filter {
                toMillis(it.time) < recentCutoff
            }

            /*
             * اگر داده قبلی کم باشد، مقایسه با احتیاط انجام می‌شود.
             */
            val recentCount = recent.size
            val previousCountPerMinute =
                previous.size.toDouble() / 4.0

            val countAcceleration =
                if (previousCountPerMinute > 0.0) {
                    recentCount.toDouble() / previousCountPerMinute
                } else {
                    if (recentCount >= 3) 2.5 else 1.0
                }

            val recentVolume =
                recent.sumOf { it.volume }

            val previousVolume =
                previous.sumOf { it.volume }

            val previousVolumePerMinute =
                previousVolume / 4.0

            val volumeAcceleration =
                if (previousVolumePerMinute > 0.0) {
                    recentVolume / previousVolumePerMinute
                } else {
                    if (recentVolume > 0.0 && recentCount >= 3) 2.5 else 1.0
                }

            /*
             * ---------------------------------------------------------
             * ۲) جهت معاملات در یک دقیقه اخیر
             *
             * حجم اهمیت بیشتری از تعداد معامله دارد.
             * وزن:
             *   حجم = 65٪
             *   تعداد = 35٪
             * ---------------------------------------------------------
             */

            val recentBuyVolume = recent
                .filter { it.type.equals("buy", true) }
                .sumOf { it.volume }

            val recentSellVolume = recent
                .filter { it.type.equals("sell", true) }
                .sumOf { it.volume }

            val recentVolumeTotal =
                recentBuyVolume + recentSellVolume

            val recentBuyVolumePct =
                if (recentVolumeTotal > 0.0) {
                    recentBuyVolume / recentVolumeTotal * 100.0
                } else {
                    50.0
                }

            val recentBuyCount = recent.count {
                it.type.equals("buy", true)
            }

            val recentSellCount = recent.count {
                it.type.equals("sell", true)
            }

            val recentTradeTotal =
                recentBuyCount + recentSellCount

            val recentBuyCountPct =
                if (recentTradeTotal > 0) {
                    recentBuyCount.toDouble() /
                        recentTradeTotal.toDouble() * 100.0
                } else {
                    50.0
                }

            val directionalPressure =
                recentBuyVolumePct * 0.65 +
                    recentBuyCountPct * 0.35

            /*
             * 0 یعنی کاملاً متعادل
             * 50 یعنی فشار خرید/فروش بسیار قوی
             */
            val directionalStrength =
                abs(directionalPressure - 50.0)

            val directionScore =
                min(30.0, directionalStrength * 0.60)

            /*
             * ---------------------------------------------------------
             * ۳) افزایش سرعت معاملات
             * ---------------------------------------------------------
             */

            val countAccelerationScore =
                min(
                    20.0,
                    max(
                        0.0,
                        countAcceleration - 1.0
                    ) * 10.0
                )

            /*
             * ---------------------------------------------------------
             * ۴) افزایش حجم معاملات
             * ---------------------------------------------------------
             */

            val volumeAccelerationScore =
                min(
                    20.0,
                    max(
                        0.0,
                        volumeAcceleration - 1.0
                    ) * 10.0
                )

            /*
             * ---------------------------------------------------------
             * ۵) شناسایی معاملات غیرعادی نسبت به خود بازار
             *
             * دیگر عدد ثابت ۱۰۰۰ مبنای امتیازدهی نیست.
             * اندازه معمول معامله از میانه معاملات پنج دقیقه اخیر
             * به دست می‌آید.
             * ---------------------------------------------------------
             */

            val sortedVolumes =
                window.map { it.volume }.sorted()

            val medianVolume =
                if (sortedVolumes.isEmpty()) {
                    0.0
                } else {
                    val middle = sortedVolumes.size / 2

                    if (sortedVolumes.size % 2 == 0) {
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
                    .filter { it.type.equals("buy", true) }
                    .sumOf { it.volume }

            val unusualSellVolume =
                unusualTrades
                    .filter { it.type.equals("sell", true) }
                    .sumOf { it.volume }

            val unusualTotalVolume =
                unusualBuyVolume + unusualSellVolume

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

            /*
             * ---------------------------------------------------------
             * ۶) حرکت قیمت فقط نقش تأییدکننده دارد.
             * ---------------------------------------------------------
             */

            val recentPriceMovePct =
                if (recent.size >= 2) {
                    val firstPrice = recent.first().price
                    val lastPrice = recent.last().price

                    if (firstPrice > 0.0) {
                        abs(
                            lastPrice - firstPrice
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

            /*
             * ---------------------------------------------------------
             * ۷) تداوم جهت
             *
             * اگر جهت یک دقیقه اخیر با جهت پنج دقیقه اخیر هماهنگ باشد،
             * کمی امتیاز اضافه می‌شود.
             * ---------------------------------------------------------
             */

            val overallDirection =
                if (buyPct >= 50.0) 1 else -1

            val recentDirection =
                if (directionalPressure >= 50.0) 1 else -1

            val persistenceScore =
                if (
                    recentCount >= 3 &&
                    overallDirection == recentDirection &&
                    directionalStrength >= 8.0
                ) {
                    5.0
                } else {
                    0.0
                }

            /*
             * ---------------------------------------------------------
             * امتیاز نهایی
             * ---------------------------------------------------------
             */

            val finalScore = min(
                100.0,
                (
                    directionScore +
                        countAccelerationScore +
                        volumeAccelerationScore +
                        unusualDirectionScore +
                        priceConfirmationScore +
                        persistenceScore
                    )
            )

            val scoreInt = finalScore.toInt()

            /*
             * ---------------------------------------------------------
             * شرط هشدار شدید
             *
             * دیگر "۵ معامله بالای ۱۰۰۰" شرط هشدار نیست.
             *
             * شرط اول:
             * جهت مشخص + افزایش سرعت/حجم
             *
             * شرط دوم:
             * چند معامله غیرعادی هم‌جهت + فشار جهت‌دار قابل توجه
             *
             * شرط نهایی نمایش هشدار:
             * امتیاز بیش از ۸۰ و حداقل ۵ معامله با حجم ۱۰۰۰ تتر یا بیشتر
             *
             * این باعث می‌شود یک معامله بزرگ به تنهایی هشدار شدید نسازد.
             * ---------------------------------------------------------
             */

            val strongDirection =
                directionalStrength >= 22.0

            // برای هشدار شدید، افزایش سرعت و حجم باید هر دو
            // به‌طور محسوسی رخ داده باشند؛ یکی به تنهایی کافی نیست.
            val strongAcceleration =
                countAcceleration >= 2.0 &&
                    volumeAcceleration >= 2.0

            // معاملات غیرعادی باید هم متعدد و هم به‌طور واضح هم‌جهت باشند.
            val unusualSameDirection =
                unusualTrades.size >= 3 &&
                    unusualDirectionalStrength >= 0.75

            val severeCondition =
                recentCount >= 5 &&
                    (
                        (
                            strongDirection &&
                                strongAcceleration
                            ) ||
                            unusualSameDirection
                        )

            val reason =
                when {
                    severeCondition &&
                        directionalPressure >= 65.0 ->
                        "افزایش فشار خرید همراه با تغییر محسوس در سرعت و حجم معاملات"

                    severeCondition &&
                        directionalPressure <= 35.0 ->
                        "افزایش فشار فروش همراه با تغییر محسوس در سرعت و حجم معاملات"

                    countAcceleration >= 1.8 &&
                        volumeAcceleration >= 1.8 &&
                        directionalStrength >= 10.0 ->
                        "سرعت و حجم معاملات به‌طور محسوسی افزایش یافته است"

                    unusualSameDirection &&
                        directionalPressure >= 60.0 ->
                        "چند معامله غیرعادی هم‌جهت با فشار خرید دیده شد"

                    unusualSameDirection &&
                        directionalPressure <= 40.0 ->
                        "چند معامله غیرعادی هم‌جهت با فشار فروش دیده شد"

                    directionalPressure >= 60.0 ->
                        "تمایل خرید در معاملات اخیر افزایش یافته است"

                    directionalPressure <= 40.0 ->
                        "تمایل فروش در معاملات اخیر افزایش یافته است"

                    countAcceleration >= 1.5 ->
                        "سرعت معاملات نسبت به دقایق قبل افزایش یافته است"

                    volumeAcceleration >= 1.5 ->
                        "حجم معاملات نسبت به دقایق قبل افزایش یافته است"

                    else ->
                        "فعالیت معاملات در محدوده عادی قرار دارد"
                }

            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_SCORE, scoreInt)
                .putString(KEY_LAST_BUY, buyPct.toString())
                .putString(KEY_LAST_SELL, sellPct.toString())
                .putInt(KEY_LAST_COUNT, window.size)
                .putInt(KEY_LAST_COUNT_1000, largeCount)
                .putString(KEY_LAST_REASON, reason)
                .apply()

            val supportVolume = orderBook?.let { book ->
                supportBlock?.let { block ->
                    volumeAtPrice(book.bids, block.price)
                }
            }

            val resistanceVolume = orderBook?.let { book ->
                resistanceBlock?.let { block ->
                    volumeAtPrice(book.asks, block.price)
                }
            }

            saveBlockStatus(
                supportBlock?.price,
                supportVolume,
                resistanceBlock?.price,
                resistanceVolume
            )

            updateNotification(
                "وضعیت: ${state(scoreInt)} • " +
                    "${window.size} معامله • ≥۱۰۰۰ تتر: $largeCount"
            )

            sendStatus(
                scoreInt,
                buyPct,
                sellPct,
                window.size,
                largeCount,
                reason,
                supportBlock?.price,
                supportVolume,
                resistanceBlock?.price,
                resistanceVolume
            )

            val prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE)

            val severeDisplayCondition =
                severeCondition &&
                    scoreInt > 80 &&
                    largeCount >= 5

            val verySevereCondition =
                severeDisplayCondition &&
                    blockCollapse != null

            var verySevereShownNow = false

            if (
                prefs.getBoolean(
                    KEY_VERY_SEVERE_ALERT,
                    true
                ) &&
                verySevereCondition &&
                !verySevereAlreadyShown
            ) {
                verySevereAlreadyShown = true
                severeAlreadyShown = true
                verySevereShownNow = true

                showVerySevereAlert(
                    scoreInt,
                    reason,
                    buyPct,
                    sellPct,
                    window.size,
                    largeCount,
                    blockCollapse!!.price,
                    blockCollapse.side
                )
            }

            if (
                prefs.getBoolean(
                    KEY_SEVERE_ALERT,
                    true
                ) &&
                severeDisplayCondition &&
                !severeAlreadyShown &&
                !verySevereShownNow
            ) {
                severeAlreadyShown = true

                showSevereAlert(
                    scoreInt,
                    reason,
                    buyPct,
                    sellPct,
                    window.size,
                    largeCount
                )
            }

            if (scoreInt < 50 || !severeCondition) {
                severeAlreadyShown = false
                verySevereAlreadyShown = false
            }

            if (!prefs.getBoolean(KEY_VERY_SEVERE_ALERT, true)) {
                verySevereAlreadyShown = false
            }
        }
    }

    private fun fetchOrderBook(): OrderBook? {
        val request = Request.Builder()
            .url("https://api.nobitex.ir/v3/orderbook/USDTIRT")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)

                if (json.optString("status") != "ok") {
                    return@use null
                }

                fun parseLevels(name: String): List<OrderBookLevel> {
                    val array = json.optJSONArray(name) ?: return emptyList()
                    val result = ArrayList<OrderBookLevel>(array.length())

                    for (i in 0 until array.length()) {
                        val row = array.optJSONArray(i) ?: continue
                        val price = row.optString(0).toDoubleOrNull() ?: continue
                        val volume = row.optString(1).toDoubleOrNull() ?: continue

                        if (price > 0.0 && volume >= 0.0) {
                            result.add(OrderBookLevel(price, volume))
                        }
                    }

                    return result
                }

                OrderBook(
                    asks = parseLevels("asks"),
                    bids = parseLevels("bids")
                )
            }
        }.getOrNull()
    }

    private fun updateOrderBlocks(
        currentPrice: Double,
        asks: List<OrderBookLevel>,
        bids: List<OrderBookLevel>
    ): BlockCollapse? {
        if (currentPrice <= 0.0) return null

        val base = orderBlockBasePrice

        if (base == null) {
            orderBlockBasePrice = currentPrice
            resistanceBlock = findFirstBlock(asks, currentPrice, true)
            supportBlock = findFirstBlock(bids, currentPrice, false)
            previousOrderBookPrice = currentPrice
            return null
        }

        val previousPrice = previousOrderBookPrice

        resistanceBlock?.let { block ->
            val currentVolume = volumeAtPrice(asks, block.price)

            if (
                currentVolume <= block.baselineVolume * 0.10 &&
                currentPrice >= block.price
            ) {
                orderBlockBasePrice = block.price
                resistanceBlock = findFirstBlock(asks, block.price, true)
                supportBlock = findFirstBlock(bids, block.price, false)
                previousOrderBookPrice = currentPrice

                return BlockCollapse(block.price, "resistance")
            }
        }

        supportBlock?.let { block ->
            val currentVolume = volumeAtPrice(bids, block.price)

            if (
                currentVolume <= block.baselineVolume * 0.10 &&
                currentPrice <= block.price
            ) {
                orderBlockBasePrice = block.price
                resistanceBlock = findFirstBlock(asks, block.price, true)
                supportBlock = findFirstBlock(bids, block.price, false)
                previousOrderBookPrice = currentPrice

                return BlockCollapse(block.price, "support")
            }
        }

        val newResistance = findFirstBlock(asks, base, true)
        val newSupport = findFirstBlock(bids, base, false)

        if (
            resistanceBlock == null ||
            newResistance?.price != resistanceBlock?.price
        ) {
            resistanceBlock = newResistance
        }

        if (
            supportBlock == null ||
            newSupport?.price != supportBlock?.price
        ) {
            supportBlock = newSupport
        }

        previousOrderBookPrice = currentPrice
        return null
    }

    private fun findFirstBlock(
        levels: List<OrderBookLevel>,
        basePrice: Double,
        above: Boolean
    ): TrackedBlock? {
        val aggregated =
            levels
                .filter {
                    if (above) it.price > basePrice else it.price < basePrice
                }
                .groupBy { it.price }
                .map { (price, rows) ->
                    price to rows.sumOf { it.volume }
                }
                .filter { it.second > 20_000.0 }
                .sortedWith(
                    if (above) {
                        compareBy { it.first }
                    } else {
                        compareByDescending { it.first }
                    }
                )

        val first = aggregated.firstOrNull() ?: return null

        return TrackedBlock(
            price = first.first,
            baselineVolume = first.second
        )
    }

    private fun volumeAtPrice(
        levels: List<OrderBookLevel>,
        price: Double
    ): Double =
        levels
            .filter { it.price == price }
            .sumOf { it.volume }

    private fun showVerySevereAlert(
        score: Int,
        reason: String,
        buyPct: Double,
        sellPct: Double,
        count: Int,
        count1000: Int,
        blockPrice: Double,
        blockSide: String
    ) {
        val activityIntent =
            Intent(this, TradeMonitoringAlertActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(EXTRA_SCORE, score)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_BUY_PRESSURE, buyPct)
                putExtra(EXTRA_SELL_PRESSURE, sellPct)
                putExtra(EXTRA_TRADE_COUNT, count)
                putExtra(EXTRA_COUNT_1000, count1000)
                putExtra(EXTRA_ALERT_TYPE, ALERT_TYPE_VERY_SEVERE)
                putExtra(EXTRA_BLOCK_PRICE, blockPrice)
                putExtra(EXTRA_BLOCK_TYPE, blockSide)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                4204,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val blockType =
            if (blockSide == "support") "حمایتی" else "مقاومتی"

        val direction =
            if (blockSide == "support") "کاهش قیمت" else "افزایش قیمت"

        val blockPriceToman = (blockPrice / 10.0).toLong()

        val title =
            "هشدار بسیار شدید $direction به دلیل فروپاشی بلوک " +
                "$blockType " + blockPriceToman + " تومانی"

        val notification =
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tether_eye)
                .setContentTitle(title)
                .setContentText(reason)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun showSevereAlert(
        score: Int,
        reason: String,
        buyPct: Double,
        sellPct: Double,
        count: Int,
        count1000: Int
    ) {
        val activityIntent =
            Intent(
                this,
                TradeMonitoringAlertActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(EXTRA_SCORE, score)
                putExtra(EXTRA_REASON, reason)
                putExtra(EXTRA_BUY_PRESSURE, buyPct)
                putExtra(EXTRA_SELL_PRESSURE, sellPct)
                putExtra(EXTRA_TRADE_COUNT, count)
                putExtra(EXTRA_COUNT_1000, count1000)
                putExtra(EXTRA_ALERT_TYPE, ALERT_TYPE_SEVERE)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                4203,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                this,
                ALERT_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_tether_eye)
                .setContentTitle(
                    "نگهبان تتر • هشدار شدید معاملات"
                )
                .setContentText(reason)
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(
                    pendingIntent,
                    true
                )
                .setVisibility(
                    NotificationCompat.VISIBILITY_PUBLIC
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        ).notify(
            ALERT_NOTIFICATION_ID,
            notification
        )
    }

    private fun sendStatus(
        score: Int,
        buyPct: Double,
        sellPct: Double,
        count: Int,
        count1000: Int,
        reason: String,
        supportPrice: Double?,
        supportVolume: Double?,
        resistancePrice: Double?,
        resistanceVolume: Double?
    ) {
        sendBroadcast(
            Intent(ACTION_STATUS_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SCORE, score)
                putExtra(EXTRA_BUY_PRESSURE, buyPct)
                putExtra(EXTRA_SELL_PRESSURE, sellPct)
                putExtra(EXTRA_TRADE_COUNT, count)
                putExtra(EXTRA_COUNT_1000, count1000)
                putExtra(EXTRA_REASON, reason)
                if (supportPrice != null) putExtra(EXTRA_SUPPORT_BLOCK_PRICE, supportPrice)
                if (supportVolume != null) putExtra(EXTRA_SUPPORT_BLOCK_VOLUME, supportVolume)
                if (resistancePrice != null) putExtra(EXTRA_RESISTANCE_BLOCK_PRICE, resistancePrice)
                if (resistanceVolume != null) putExtra(EXTRA_RESISTANCE_BLOCK_VOLUME, resistanceVolume)
            }
        )
    }

    private fun saveBlockStatus(
        supportPrice: Double?,
        supportVolume: Double?,
        resistancePrice: Double?,
        resistanceVolume: Double?
    ) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            if (supportPrice != null) putString(KEY_LAST_SUPPORT_BLOCK_PRICE, supportPrice.toString())
            else remove(KEY_LAST_SUPPORT_BLOCK_PRICE)
            if (supportVolume != null) putString(KEY_LAST_SUPPORT_BLOCK_VOLUME, supportVolume.toString())
            else remove(KEY_LAST_SUPPORT_BLOCK_VOLUME)
            if (resistancePrice != null) putString(KEY_LAST_RESISTANCE_BLOCK_PRICE, resistancePrice.toString())
            else remove(KEY_LAST_RESISTANCE_BLOCK_PRICE)
            if (resistanceVolume != null) putString(KEY_LAST_RESISTANCE_BLOCK_VOLUME, resistanceVolume.toString())
            else remove(KEY_LAST_RESISTANCE_BLOCK_VOLUME)
        }.apply()
    }

    private fun recalculateOrderBlocks() {
        val currentPrice = lastLiveTradePrice ?: return
        val orderBook = fetchOrderBook() ?: return

        orderBlockBasePrice = currentPrice
        resistanceBlock = findFirstBlock(orderBook.asks, currentPrice, true)
        supportBlock = findFirstBlock(orderBook.bids, currentPrice, false)
        previousOrderBookPrice = currentPrice

        val supportVolume = supportBlock?.let { volumeAtPrice(orderBook.bids, it.price) }
        val resistanceVolume = resistanceBlock?.let { volumeAtPrice(orderBook.asks, it.price) }

        saveBlockStatus(
            supportBlock?.price,
            supportVolume,
            resistanceBlock?.price,
            resistanceVolume
        )

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sendStatus(
            prefs.getInt(KEY_LAST_SCORE, 0),
            prefs.getString(KEY_LAST_BUY, "50")?.toDoubleOrNull() ?: 50.0,
            prefs.getString(KEY_LAST_SELL, "50")?.toDoubleOrNull() ?: 50.0,
            prefs.getInt(KEY_LAST_COUNT, 0),
            prefs.getInt(KEY_LAST_COUNT_1000, 0),
            prefs.getString(KEY_LAST_REASON, "") ?: "",
            supportBlock?.price,
            supportVolume,
            resistanceBlock?.price,
            resistanceVolume
        )
    }

    private fun state(score: Int) =
        when {
            score >= 70 -> "هشدار شدید"
            score >= 50 -> "غیرعادی"
            score >= 30 -> "تحت نظر"
            else -> "عادی"
        }

    private fun toMillis(value: Long): Long =
        if (value < 10_000_000_000L) {
            value * 1000L
        } else {
            value
        }

    private fun createChannel() {
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        if (Build.VERSION.SDK_INT >= 26) {

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "مانیتورینگ معاملات",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "هشدار شدید معاملات",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility =
                        NotificationCompat.VISIBILITY_PUBLIC
                    setSound(null, null)
                }
            )
        }
    }

    private fun buildNotification(
        text: String
    ): Notification =
        NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_tether_eye)
            .setContentTitle(
                "نگهبان تتر • مانیتورینگ معاملات"
            )
            .setContentText(text)
            .setOngoing(true)
            .setNumber(0)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    4202,
                    Intent(
                        this,
                        TradeMonitoringActivity::class.java
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(
        text: String
    ) =
        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
