package com.tetherguardian.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class NobitexApi {

    private val client = OkHttpClient()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * دریافت کندل‌های OHLC از API عمومی نوبیتکس
     *
     * timeframe:
     * 240 = چهار ساعت
     * 360 = شش ساعت
     * 720 = دوازده ساعت
     * D   = روزانه
     */
    fun getOhlc(
        symbol: String = "USDTIRT",
        timeframe: String
    ): List<Candle> {

        val url =
            "https://api.nobitex.ir/market/udf/history" +
                    "?symbol=$symbol" +
                    "&resolution=$timeframe" +
                    "&from=0" +
                    "&to=${System.currentTimeMillis() / 1000}"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException(
                    "Nobitex HTTP error: ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException("Empty Nobitex response")

            val parsed = json.parseToJsonElement(body).jsonObject

            val status = parsed["s"]
                ?.toString()
                ?.trim('"')

            if (status != "ok") {
                throw IOException(
                    "Nobitex API status: $status"
                )
            }

            val timestamps = parsed["t"]?.jsonArray
                ?: throw IOException("Missing timestamp data")

            val opens = parsed["o"]?.jsonArray
                ?: throw IOException("Missing open data")

            val highs = parsed["h"]?.jsonArray
                ?: throw IOException("Missing high data")

            val lows = parsed["l"]?.jsonArray
                ?: throw IOException("Missing low data")

            val closes = parsed["c"]?.jsonArray
                ?: throw IOException("Missing close data")

            val volumes = parsed["v"]?.jsonArray
                ?: throw IOException("Missing volume data")

            val count = minOf(
                timestamps.size,
                opens.size,
                highs.size,
                lows.size,
                closes.size,
                volumes.size
            )

            return (0 until count).map { index ->

                Candle(
                    timestamp = timestamps[index].toString().toLong(),
                    open = opens[index].toString(),
                    high = highs[index].toString(),
                    low = lows[index].toString(),
                    close = closes[index].toString(),
                    volume = volumes[index].toString()
                )
            }
        }
    }
}
