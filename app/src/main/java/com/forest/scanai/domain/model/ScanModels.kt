package com.forest.scanai.domain.model

import io.github.sceneview.math.Position
import com.google.ar.core.TrackingState

data class ScanUiState(
    val stereoVolume: Double = 0.0,
    val netVolume: Double = 0.0,
    val distance: Double = 0.0,
    val trackingState: TrackingState = TrackingState.PAUSED,
    val coveragePercentage: Float = 0f,
    val topPoints: List<Position> = emptyList(),
    val isSaving: Boolean = false,
    val isMeasuring: Boolean = false,
    val error: String? = null
)

data class ScanResult(
    val volume: Double,
    val topPoints: List<Position>
)
