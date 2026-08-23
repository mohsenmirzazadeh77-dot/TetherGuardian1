package com.tetherguardian.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
            .url("wss://wss.nobitex.ir/connection/websocket")
            .build()

        webSocket = client.newWebSocket(
            request,
            Listener()
        )
    }

    fun disconnect() {

        manuallyStopped = true

        webSocket?.close(
            1000,
            "Stopped by user"
        )

        webSocket = null

        onConnectionChanged(false)
    }

    private fun sendConnectMessage(webSocket: WebSocket) {

        val message = """
            {
                "connect": {},
                "id": 1
            }
        """.trimIndent()

        webSocket.send(message)
    }

    private fun subscribeToOrderBook(webSocket: WebSocket) {

        val message = """
            {
                "id": 2,
                "subscribe": {
                    "channel": "public:orderbook-USDTIRT"
                }
            }
        """.trimIndent()

        webSocket.send(message)
    }

    private fun handleMessage(text: String) {

        try {

            val root =
                json.parseToJsonElement(text).jsonObject

            /*
             * پاسخ‌های push نوبیتکس:
             *
             * {
             *   "push": {
             *     "channel": "...",
             *     "pub": {
             *       "data": "{...}"
             *     }
             *   }
             * }
             */

            val push =
                root["push"]?.jsonObject
                    ?: return

            val pub =
                push["pub"]?.jsonObject
                    ?: return

            val dataText =
                pub["data"]?.jsonPrimitive?.content
                    ?: return

            val data =
                json.parseToJsonElement(dataText).jsonObject

            val lastTradePrice =
                data["lastTradePrice"]
                    ?.jsonPrimitive
                    ?.content

            if (!lastTradePrice.isNullOrBlank()) {

                onPriceUpdate(lastTradePrice)
            }

        } catch (e: Exception) {

            onError(
                "خطا در پردازش داده نوبیتکس: " +
                        "${e.message ?: "نامشخص"}"
            )
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(
            webSocket: WebSocket,
            response: Response
        ) {

            onConnectionChanged(true)

            /*
             * مرحله اول:
             * برقراری اتصال Centrifugo
             */
            sendConnectMessage(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {

            /*
             * اگر پاسخ مربوط به اتصال باشد،
             * بعد از آن subscription انجام می‌شود.
             */
            try {

                val root =
                    json.parseToJsonElement(text).jsonObject

                if (root.containsKey("connect")) {

                    subscribeToOrderBook(webSocket)

                    return
                }

            } catch (_: Exception) {
                // پیام ممکن است داده بازار باشد.
            }

            handleMessage(text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            // فعلاً پیام باینری استفاده نمی‌شود.
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
            response: Response?
        ) {

            onConnectionChanged(false)

            onError(
                "ارتباط با نوبیتکس قطع شد: " +
                        "${t.message ?: "خطای نامشخص"}"
            )
        }
    }
}
