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
     * دریافت کندل‌های OHLC از نوبیتکس
     *
     * 240 = چهار ساعت
     * 360 = شش ساعت
     * 720 = دوازده ساعت
     * D   = روزانه
     *
     * قیمت‌های OHLC مستقیماً با همان مقداری که
     * API برمی‌گرداند استفاده می‌شوند.
     */
    fun getOhlc(
        symbol: String = "USDTIRT",
        timeframe: String
    ): List<Candle> {

        val now = System.currentTimeMillis() / 1000

        val url =
            "https://apiv2.nobitex.ir/market/udf/history" +
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
                    "خطای HTTP نوبیتکس OHLC: ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException(
                    "پاسخ OHLC نوبیتکس خالی است"
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
     * دریافت آخرین قیمت معامله USDT/IRT
     *
     * API نوبیتکس مقدار lastTradePrice را برمی‌گرداند.
     *
     * در این پروژه قیمت‌ها در رابط کاربری بر اساس تومان نمایش داده
     * می‌شوند. بنابراین مقدار دریافتی از Order Book به تومان تبدیل
     * می‌شود تا با قیمت کندل OHLC هم‌مقیاس باشد.
     */
    fun getCurrentPrice(
        symbol: String = "USDTIRT"
    ): String {

        val url =
            "https://apiv2.nobitex.ir/v3/orderbook/$symbol"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException(
                    "خطای HTTP قیمت لحظه‌ای نوبیتکس: ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException(
                    "پاسخ قیمت لحظه‌ای نوبیتکس خالی است"
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

            val lastTradePrice =
                root["lastTradePrice"]
                    ?.jsonPrimitive
                    ?.content
                    ?: throw IOException(
                        "lastTradePrice در پاسخ نوبیتکس وجود ندارد"
                    )

            val price =
                lastTradePrice.toDoubleOrNull()
                    ?: throw IOException(
                        "مقدار lastTradePrice عددی نیست: $lastTradePrice"
                    )

            /*
             * قیمت لحظه‌ای Order Book در مقیاس ریال است.
             * رابط کاربری برنامه بر اساس تومان است.
             *
             * مثال:
             * 1,600,000 ریال
             * تبدیل می‌شود به:
             * 160,000 تومان
             */
            val tomanPrice = price / 10.0

            return tomanPrice.toString()
        }
    }
}
