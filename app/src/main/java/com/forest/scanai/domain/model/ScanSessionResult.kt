package com.forest.scanai.domain.model

import io.github.sceneview.math.Position

data class ScanSessionResult(
    val volume: Double,
    val length: Double,
    val maxHeight: Double,
    val maxWidth: Double,
    val points: List<Position>,
    val topPoints: List<Position>,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)
