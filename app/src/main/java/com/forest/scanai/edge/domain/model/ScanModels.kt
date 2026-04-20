package com.forest.scanai.edge.domain.model

import io.github.sceneview.math.Position

// ScanUiState se movió a presentation.state.ScanUiState

data class ScanResult(
    val volume: Double,
    val topPoints: List<Position>,
    val geometricVolumeRaw: Double = 0.0,
    val geometricVolumeCorrected: Double = volume,
    val stereoVolumeSmoothed: Double = volume,
    val netVolumeEstimate: Double = volume * 0.45,
    val debugInfo: Map<String, String> = emptyMap()
) {
    // Backward compatibility for callers still using previous naming.
    val volumeBeforeCorrection: Double get() = geometricVolumeRaw
    val volumeAfterCorrection: Double get() = geometricVolumeCorrected
}

enum class CompletenessLevel {
    INSUFFICIENT, PARTIAL, ACCEPTABLE, COMPLETE
}
