package com.tetherguardian.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NobitexOhlcResponse(
    val status: String,
    val candles: List<List<String>> = emptyList()
)

data class Candle(
    val timestamp: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String
)

data class MarketSnapshot(
    val timeframe: String,
    val candleOpen: String,
    val currentPrice: String,
    val changePercent: String,
    val updatedAt: Long
)
