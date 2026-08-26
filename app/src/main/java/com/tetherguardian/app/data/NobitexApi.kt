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
            if (root["s"]?.jsonPrimitive?.content != "ok") throw IOException("وضعیت OHLC نوبیتکس: ${root["s"]}")
            val t = root["t"]?.jsonArray ?: throw IOException("اطلاعات زمان کندل دریافت نشد")
            val o = root["o"]?.jsonArray ?: throw IOException("اطلاعات Open دریافت نشد")
            val h = root["h"]?.jsonArray ?: throw IOException("اطلاعات High دریافت نشد")
            val l = root["l"]?.jsonArray ?: throw IOException("اطلاعات Low دریافت نشد")
            val c = root["c"]?.jsonArray ?: throw IOException("اطلاعات Close دریافت نشد")
            val v = root["v"]?.jsonArray ?: throw IOException("اطلاعات Volume دریافت نشد")
            val count = minOf(t.size, o.size, h.size, l.size, c.size, v.size)
            return (0 until count).map { i -> Candle(t[i].jsonPrimitive.content.toLong(), o[i].jsonPrimitive.content, h[i].jsonPrimitive.content, l[i].jsonPrimitive.content, c[i].jsonPrimitive.content, v[i].jsonPrimitive.content) }
        }
    }

    fun getCurrentPrice(symbol: String = "USDTIRT"): String {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v3/orderbook/$symbol").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("خطای HTTP قیمت لحظه‌ای نوبیتکس: ${response.code}")
            val root = json.parseToJsonElement(response.body?.string() ?: throw IOException("پاسخ قیمت لحظه‌ای نوبیتکس خالی است")).jsonObject
            if (root["status"]?.jsonPrimitive?.content != "ok") throw IOException("وضعیت قیمت نوبیتکس: ${root["status"]}")
            val price = root["lastTradePrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: throw IOException("lastTradePrice عددی نیست")
            return (price / 10.0).toString()
        }
    }

    fun getTrades(symbol: String = "USDTIRT"): List<Trade> {
        val request = Request.Builder().url("https://apiv2.nobitex.ir/v2/trades/$symbol").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("خطای HTTP معاملات نوبیتکس: ${response.code}")
            val body = response.body?.string() ?: throw IOException("پاسخ معاملات نوبیتکس خالی است")
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["trades"]?.jsonArray ?: throw IOException("فهرست معاملات در پاسخ نوبیتکس وجود ندارد")
            return items.mapNotNull { item ->
                val o = item.jsonObject
                val time = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val price = o["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val volume = o["volume"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val type = o["type"]?.jsonPrimitive?.content ?: "unknown"
                Trade(time, price, volume, type)
            }
        }
    }

    data class Trade(val time: Long, val price: Double, val volume: Double, val type: String)
}
