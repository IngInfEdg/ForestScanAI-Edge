package com.forest.scanai.edge.domain.model

import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position

/**
 * Representa los datos técnicos y métricas resultantes de una sesión de escaneo.
 * Este modelo es agnóstico a la UI y es el que se utiliza para exportación (CSV).
 */
data class ScanMetrics(
    val stereoVolume: Double = 0.0,
    val netVolume: Double = 0.0,
    val distance: Double = 0.0,
    val trackingState: TrackingState = TrackingState.PAUSED,
    val coveragePercentage: Float = 0f,
    val topPoints: List<Position> = emptyList(),
    val arDistanceWalked: Double = 0.0,
    val gpsDistanceWalked: Double = 0.0,
    val gpsPointCount: Int = 0,
    val observerSampleCount: Int = 0,
    val coveredSectors: Int = 0,
    val totalSectors: Int = 12,
    val completeness: CompletenessLevel = CompletenessLevel.INSUFFICIENT,
    val diagnostics: List<String> = emptyList(),
    val appVersionDisplay: String = ""
)
