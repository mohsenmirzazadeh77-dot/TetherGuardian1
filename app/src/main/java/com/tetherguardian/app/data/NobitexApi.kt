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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * دریافت کندل‌های OHLC
     *
     * 240 = چهار ساعت
     * 360 = شش ساعت
     * 720 = دوازده ساعت
     * D   = روزانه
     */
    fun getOhlc(
        symbol: String = "USDTIRT",
        timeframe: String
    ): List<Candle> {

        val now = System.currentTimeMillis() / 1000

        val url =
            "https://api.nobitex.ir/market/udf/history" +
                    "?symbol=$symbol" +
                    "&resolution=$timeframe" +
                    "&from=0" +
                    "&to=$now"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException(
                    "خطای HTTP نوبیتکس: ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException(
                    "پاسخ نوبیتکس خالی است"
                )

            val root =
                json.parseToJsonElement(body).jsonObject

            val status =
                root["s"]
                    ?.jsonPrimitive
                    ?.content

            if (status != "ok") {
                throw IOException(
                    "وضعیت OHLC نوبیتکس: $status"
                )
            }

            val timestamps =
                root["t"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات زمان کندل دریافت نشد"
                    )

            val opens =
                root["o"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات Open دریافت نشد"
                    )

            val highs =
                root["h"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات High دریافت نشد"
                    )

            val lows =
                root["l"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات Low دریافت نشد"
                    )

            val closes =
                root["c"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات Close دریافت نشد"
                    )

            val volumes =
                root["v"]?.jsonArray
                    ?: throw IOException(
                        "اطلاعات Volume دریافت نشد"
                    )

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
                    timestamp =
                        timestamps[index]
                            .jsonPrimitive
                            .content
                            .toLong(),

                    open =
                        opens[index]
                            .jsonPrimitive
                            .content,

                    high =
                        highs[index]
                            .jsonPrimitive
                            .content,

                    low =
                        lows[index]
                            .jsonPrimitive
                            .content,

                    close =
                        closes[index]
                            .jsonPrimitive
                            .content,

                    volume =
                        volumes[index]
                            .jsonPrimitive
                            .content
                )
            }
        }
    }

    /**
     * دریافت آخرین قیمت معامله
     * از API عمومی Order Book نوبیتکس
     */
    fun getCurrentPrice(
        symbol: String = "USDTIRT"
    ): String {

        val url =
            "https://api.nobitex.ir/v3/orderbook/$symbol"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException(
                    "خطای HTTP قیمت لحظه‌ای: ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException(
                    "پاسخ قیمت لحظه‌ای خالی است"
                )

            val root =
                json.parseToJsonElement(body).jsonObject

            val status =
                root["status"]
                    ?.jsonPrimitive
                    ?.content

            if (status != "ok") {
                throw IOException(
                    "وضعیت قیمت نوبیتکس: $status"
                )
            }

            return root["lastTradePrice"]
                ?.jsonPrimitive
                ?.content
                ?: throw IOException(
                    "lastTradePrice در پاسخ نوبیتکس وجود ندارد"
                )
        }
    }
}
