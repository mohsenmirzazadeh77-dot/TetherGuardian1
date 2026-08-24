package com.tetherguardian.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tetherguardian.app.data.NobitexApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    private var currentOpen: Double? = null
    private var currentPrice: Double? = null

    private val decimalFormat =
        DecimalFormat("#,##0.########")

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

        refreshButton.setOnClickListener {
            loadMarketData()
        }

        loadMarketData()
        startPriceUpdater()
    }

    private fun initializeViews() {

        connectionText = findViewById(R.id.connectionText)
        openText = findViewById(R.id.openText)
        priceText = findViewById(R.id.priceText)
        changeText = findViewById(R.id.changeText)
        updateText = findViewById(R.id.updateText)
        timeframeSpinner = findViewById(R.id.timeframeSpinner)
        refreshButton = findViewById(R.id.refreshButton)
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
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    loadMarketData()
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }
    }

    private fun loadMarketData() {

        val selected =
            timeframes.getOrNull(
                timeframeSpinner.selectedItemPosition
            ) ?: return

        connectionText.text =
            "● در حال آزمایش ارتباط..."

        lifecycleScope.launch {

            try {

                val result =
                    withContext(Dispatchers.IO) {

                        val candles =
                            nobitexApi.getOhlc(
                                symbol = "USDTIRT",
                                timeframe = selected.resolution
                            )

                        val price =
                            nobitexApi.getCurrentPrice(
                                symbol = "USDTIRT"
                            )

                        Pair(
                            candles.lastOrNull(),
                            price
                        )
                    }

                val candle = result.first
                val price = result.second

                if (candle == null) {

                    connectionText.text =
                        "خطا: کندل دریافت نشد"

                    openText.text = "--"
                    priceText.text = "--"

                    return@launch
                }

                currentOpen =
                    candle.open.toDoubleOrNull()

                currentPrice =
                    price.toDoubleOrNull()

                openText.text =
                    formatPrice(candle.open)

                priceText.text =
                    formatPrice(price)

                updateChange()

                connectionText.text =
                    "● اتصال موفق به نوبیتکس"

                updateText.text =
                    "دریافت موفق • ${currentTime()}"

            } catch (e: Exception) {

                connectionText.text =
                    "● خطا در دریافت اطلاعات"

                openText.text =
                    "خطا"

                priceText.text =
                    "خطا"

                changeText.text =
                    "--"

                updateText.text =
                    e.message ?: "خطای نامشخص"
            }
        }
    }

    private fun startPriceUpdater() {

        lifecycleScope.launch {

            while (isActive) {

                delay(10_000)

                updateCurrentPrice()
            }
        }
    }

    private fun updateCurrentPrice() {

        lifecycleScope.launch {

            try {

                val price =
                    withContext(Dispatchers.IO) {

                        nobitexApi.getCurrentPrice(
                            symbol = "USDTIRT"
                        )
                    }

                currentPrice =
                    price.toDoubleOrNull()

                priceText.text =
                    formatPrice(price)

                updateChange()

                connectionText.text =
                    "● اتصال موفق به نوبیتکس"

                updateText.text =
                    "قیمت به‌روزرسانی شد • ${currentTime()}"

            } catch (e: Exception) {

                connectionText.text =
                    "● خطای قیمت لحظه‌ای"

                updateText.text =
                    e.message ?: "خطای نامشخص"
            }
        }
    }

    private fun updateChange() {

        val open = currentOpen
        val price = currentPrice

        if (
            open == null ||
            price == null ||
            open == 0.0
        ) {
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

    private fun formatPrice(
        value: String
    ): String {

        val number =
            value.toDoubleOrNull()
                ?: return value

        return decimalFormat.format(number) +
                " تومان"
    }

    private fun currentTime(): String {

        return SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    private data class Timeframe(
        val title: String,
        val resolution: String
    )
}
