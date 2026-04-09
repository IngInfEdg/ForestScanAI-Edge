package com.forest.scanai.domain.model

import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position

data class ScanUiState(
    val stereoVolume: Double = 0.0,
    val netVolume: Double = 0.0,
    val distance: Double = 0.0,
    val trackingState: TrackingState = TrackingState.PAUSED,
    val coveragePercentage: Float = 0f,
    val topPoints: List<Position> = emptyList(),
    val isSaving: Boolean = false,
    val isMeasuring: Boolean = false,
    val error: String? = null,

    val arDistanceWalked: Double = 0.0,
    val gpsDistanceWalked: Double = 0.0,
    val gpsPointCount: Int = 0,
    val observerSampleCount: Int = 0,
    val coveredSectors: Int = 0,
    val totalSectors: Int = 12,
    val completeness: CompletenessLevel = CompletenessLevel.INSUFFICIENT,
    val guidanceMessage: String = "Inicia el recorrido alrededor de la pila.",
    val canFinishMeasurement: Boolean = false,
    val appVersionDisplay: String = ""
)

data class ScanResult(
    val volume: Double,
    val topPoints: List<Position>
)

enum class CompletenessLevel {
    INSUFFICIENT, PARTIAL, ACCEPTABLE, COMPLETE
}
