package com.tetherguardian.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tetherguardian.app.data.NobitexApi
import com.tetherguardian.app.data.NobitexWebSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var connectionText: TextView
    private lateinit var openText: TextView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var updateText: TextView
    private lateinit var timeframeSpinner: Spinner
    private lateinit var refreshButton: Button

    private val nobitexApi = NobitexApi()

    private lateinit var nobitexWebSocket: NobitexWebSocket

    private var currentOpen: Double? = null
    private var currentPrice: Double? = null

    private val decimalFormat = DecimalFormat("#,##0.########")

    private val timeframes = listOf(
        Timeframe("۴ ساعت", "240"),
        Timeframe("۶ ساعت", "360"),
        Timeframe("۱۲ ساعت", "720"),
        Timeframe("روزانه", "D")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        initializeViews()
        setupTimeframeSpinner()
        setupWebSocket()

        refreshOpenPrice()
    }

    private fun initializeViews() {

        connectionText = findViewById(R.id.connectionText)
        openText = findViewById(R.id.openText)
        priceText = findViewById(R.id.priceText)
        changeText = findViewById(R.id.changeText)
        updateText = findViewById(R.id.updateText)
        timeframeSpinner = findViewById(R.id.timeframeSpinner)
        refreshButton = findViewById(R.id.refreshButton)

        refreshButton.setOnClickListener {
            refreshOpenPrice()
        }
    }

    private fun setupTimeframeSpinner() {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            timeframes.map { it.title }
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        timeframeSpinner.adapter = adapter

        timeframeSpinner.setSelection(0)

        timeframeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    refreshOpenPrice()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                }
            }
    }

    private fun setupWebSocket() {

        nobitexWebSocket = NobitexWebSocket(

            onPriceUpdate = { price ->

                runOnUiThread {

                    currentPrice = price.toDoubleOrNull()

                    priceText.text =
                        formatPrice(price)

                    updateChange()

                    updateText.text =
                        "آخرین دریافت: ${currentTime()}"
                }
            },

            onConnectionChanged = { connected ->

                runOnUiThread {

                    if (connected) {

                        connectionText.text =
                            "● متصل به نوبیتکس"

                    } else {

                        connectionText.text =
                            "● اتصال قطع است"
                    }
                }
            },

            onError = { error ->

                runOnUiThread {

                    connectionText.text =
                        error
                }
            }
        )

        nobitexWebSocket.connect()
    }

    private fun refreshOpenPrice() {

        val selected =
            timeframes.getOrNull(
                timeframeSpinner.selectedItemPosition
            ) ?: return

        openText.text = "در حال دریافت..."

        lifecycleScope.launch {

            try {

                val candle = withContext(Dispatchers.IO) {

                    nobitexApi
                        .getOhlc(
                            symbol = "USDTIRT",
                            timeframe = selected.resolution
                        )
                        .lastOrNull()
                }

                if (candle == null) {

                    openText.text =
                        "داده‌ای دریافت نشد"

                    return@launch
                }

                currentOpen =
                    candle.open.toDoubleOrNull()

                openText.text =
                    formatPrice(candle.open)

                updateChange()

                updateText.text =
                    "Open کندل به‌روزرسانی شد • ${currentTime()}"

            } catch (e: Exception) {

                openText.text =
                    "خطا در دریافت Open"

                connectionText.text =
                    "خطا: ${e.message ?: "نامشخص"}"
            }
        }
    }

    private fun updateChange() {

        val open = currentOpen
        val price = currentPrice

        if (open == null || price == null || open == 0.0) {
            changeText.text = "--"
            return
        }

        val percent =
            ((price - open) / open) * 100.0

        changeText.text =
            String.format(
                Locale.US,
                "%+.2f%%",
                percent
            )
    }

    private fun formatPrice(value: String): String {

        val number =
            value.toDoubleOrNull()
                ?: return value

        return decimalFormat.format(number) + " تومان"
    }

    private fun currentTime(): String {

        return SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    override fun onDestroy() {

        if (::nobitexWebSocket.isInitialized) {
            nobitexWebSocket.disconnect()
        }

        super.onDestroy()
    }

    private data class Timeframe(
        val title: String,
        val resolution: String
    )
}
