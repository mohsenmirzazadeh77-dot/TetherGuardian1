package com.tetherguardian.app.data

import kotlinx.serialization.json.Json
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
     * دریافت کندل‌های OHLC از API عمومی نوبیتکس.
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

            val parsed = json.parseToJsonElement(body)

            val status = parsed
                .jsonObject["s"]
                ?.toString()
                ?.trim('"')

            if (status != "ok") {
                throw IOException(
                    "Nobitex API status: $status"
                )
            }

            val obj = parsed.jsonObject

            val timestamps = obj["t"]
                ?.jsonArray
                ?: throw IOException("Missing timestamp data")

            val opens = obj["o"]
                ?.jsonArray
                ?: throw IOException("Missing open data")

            val highs = obj["h"]
                ?.jsonArray
                ?: throw IOException("Missing high data")

            val lows = obj["l"]
                ?.jsonArray
                ?: throw IOException("Missing low data")

            val closes = obj["c"]
                ?.jsonArray
                ?: throw IOException("Missing close data")

            val volumes = obj["v"]
                ?.jsonArray
                ?: throw IOException("Missing volume data")

            val count = listOf(
                timestamps.size,
                opens.size,
                highs.size,
                lows.size,
                closes.size,
                volumes.size
            ).minOrNull() ?: 0

            return (0 until count).map { i ->

                Candle(
                    timestamp = timestamps[i].toString().toLong(),
                    open = opens[i].toString(),
                    high = highs[i].toString(),
                    low = lows[i].toString(),
                    close = closes[i].toString(),
                    volume = volumes[i].toString()
                )
            }
        }
    }
}
