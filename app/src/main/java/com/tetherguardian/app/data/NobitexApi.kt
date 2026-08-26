package com.tetherguardian.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class NobitexApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getOhlc(symbol: String = "USDTIRT", timeframe: String): List<Candle> {
        val now = System.currentTimeMillis() / 1000
        val url = "https://apiv2.nobitex.ir/market/udf/history?symbol=$symbol&resolution=$timeframe&from=0&to=$now"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("خطای HTTP نوبیتکس OHLC: ${response.code}")
            val body = response.body?.string() ?: throw IOException("پاسخ OHLC نوبیتکس خالی است")
            val root = json.parseToJsonElement(body).jsonObject
            if (root["s"]?.jsonPrimitive?.content != "ok") throw IOException("وضعیت OHLC نوبیتکس ناموفق است")
            val timestamps = root["t"]?.jsonArray ?: throw IOException("اطلاعات زمان کندل دریافت نشد")
            val opens = root["o"]?.jsonArray ?: throw IOException("اطلاعات Open دریافت نشد")
            val highs = root["h"]?.jsonArray ?: throw IOException("اطلاعات High دریافت نشد")
            val lows = root["l"]?.jsonArray ?: throw IOException("اطلاعات Low دریافت نشد")
            val closes = root["c"]?.jsonArray ?: throw IOException("اطلاعات Close دریافت نشد")
            val volumes = root["v"]?.jsonArray ?: throw IOException("اطلاعات Volume دریافت نشد")
            val count = minOf(timestamps.size, opens.size, highs.size, lows.size, closes.size, volumes.size)
            return (0 until count).map { i ->
                Candle(
                    timestamp = timestamps[i].jsonPrimitive.content.toLong(),
                    open = opens[i].jsonPrimitive.content,
                    high = highs[i].jsonPrimitive.content,
                    low = lows[i].jsonPrimitive.content,
                    close = closes[i].jsonPrimitive.content,
                    volume = volumes[i].jsonPrimitive.content
                )
            }
        }
    }

    fun getCurrentPrice(symbol: String = "USDTIRT"): String {
        val url = "https://apiv2.nobitex.ir/v3/orderbook/$symbol"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("خطای HTTP قیمت لحظه‌ای نوبیتکس: ${response.code}")
            val body = response.body?.string() ?: throw IOException("پاسخ قیمت لحظه‌ای نوبیتکس خالی است")
            val root = json.parseToJsonElement(body).jsonObject
            if (root["status"]?.jsonPrimitive?.content != "ok") throw IOException("وضعیت قیمت نوبیتکس ناموفق است")
            val lastTradePrice = root["lastTradePrice"]?.jsonPrimitive?.content ?: throw IOException("lastTradePrice در پاسخ نوبیتکس وجود ندارد")
            val price = lastTradePrice.toDoubleOrNull() ?: throw IOException("مقدار lastTradePrice عددی نیست")
            return (price / 10.0).toString()
        }
    }

    /** معاملات عمومی انجام‌شده در بازار؛ بدون نیاز به توکن حساب کاربری. */
    fun getRecentTrades(symbol: String = "USDTIRT"): List<Trade> {
        val url = "https://apiv2.nobitex.ir/v2/trades/$symbol"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("خطای HTTP معاملات نوبیتکس: ${response.code}")
            val body = response.body?.string() ?: throw IOException("پاسخ معاملات نوبیتکس خالی است")
            val root = json.parseToJsonElement(body).jsonObject
            if (root["status"]?.jsonPrimitive?.content != "ok") throw IOException("وضعیت معاملات نوبیتکس ناموفق است")
            val trades = root["trades"]?.jsonArray ?: return emptyList()
            return trades.mapNotNull { item ->
                val obj = item.jsonObject
                val time = obj["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val price = obj["price"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val volume = obj["volume"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.content ?: "unknown"
                Trade(time, price, volume, type)
            }
        }
    }
}

data class Trade(
    val time: Long,
    val price: String,
    val volume: String,
    val type: String
)
