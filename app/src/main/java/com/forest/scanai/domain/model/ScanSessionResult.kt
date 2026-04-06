package com.forest.scanai.domain.model

import android.location.Location
import io.github.sceneview.math.Position

data class ScanSessionResult(
    val volume: Double,
    val length: Double,
    val maxHeight: Double,
    val maxWidth: Double,
    val points: List<Position>,
    val topPoints: List<Position>,
    val trajectory: List<Location>, // Trayectoria GPS real del recorrido
    val coverage: Float, // 0.0 a 1.0
    val completeness: CompletenessLevel,
    val confidence: Float,
    val pointsCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

enum class CompletenessLevel {
    INSUFFICIENT, // Menos de 20% o sin movimiento
    PARTIAL,      // Captura frontal solamente
    ACCEPTABLE,   // Recorrido de al menos 2 costados
    COMPLETE      // Perímetro cerrado o casi cerrado
}
