package com.tetherguardian.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class NobitexWebSocket(
    private val onPriceUpdate: (String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private var manuallyStopped = false

    fun connect() {
        manuallyStopped = false

        val request = Request.Builder()
            .url("wss://ws.nobitex.ir/connection/websocket")
            .build()

        webSocket = client.newWebSocket(
            request,
            Listener()
        )
    }

    fun disconnect() {
        manuallyStopped = true
        webSocket?.close(1000, "Stopped by user")
        webSocket = null
        onConnectionChanged(false)
    }

    private fun subscribe(webSocket: WebSocket) {

        val message = """
            {
              "method": "subscribe",
              "params": {
                "channel": "public:orderbook-USDTIRT"
              }
            }
        """.trimIndent()

        webSocket.send(message)
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(
            webSocket: WebSocket,
            response: okhttp3.Response
        ) {
            onConnectionChanged(true)
            subscribe(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {
            try {
                val root = json.parseToJsonElement(text).jsonObject

                val data = root["data"]
                    ?.jsonObject

                val lastTradePrice = data
                    ?.get("lastTradePrice")
                    ?.toString()
                    ?.trim('"')

                if (!lastTradePrice.isNullOrBlank()) {
                    onPriceUpdate(lastTradePrice)
                }

            } catch (e: Exception) {
                onError(
                    "خطا در پردازش داده نوبیتکس: ${e.message}"
                )
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            // پیام‌های باینری فعلاً استفاده نمی‌شوند.
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            onConnectionChanged(false)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            onConnectionChanged(false)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: okhttp3.Response?
        ) {
            onConnectionChanged(false)

            onError(
                "ارتباط با نوبیتکس قطع شد: ${t.message ?: "خطای نامشخص"}"
            )
        }
    }
}
