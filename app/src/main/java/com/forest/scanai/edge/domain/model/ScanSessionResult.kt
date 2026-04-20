package com.forest.scanai.edge.domain.model

import android.location.Location
import com.forest.scanai.edge.domain.engine.ReviewPoint3D
import io.github.sceneview.math.Position

data class ScanSessionResult(
    val volume: Double,
    val length: Double,
    val maxHeight: Double,
    val maxWidth: Double,

    val points: List<Position>,
    val topPoints: List<Position>,

    val trajectory: List<Location>,
    val observerPath: List<Position>,

    val coverage: Float,
    val completeness: CompletenessLevel,
    val confidence: Float,

    val pointsCount: Int,
    val arDistanceWalked: Double,
    val gpsDistanceWalked: Double,
    val gpsPointCount: Int,

    val coveredSectors: Int,
    val totalSectors: Int,
    val missingSectors: List<Int>,

    val guidanceSummary: String,

    val groundPoints: List<Position> = emptyList(),
    val pileOnlyPoints: List<Position> = emptyList(),

    val groundReference: Double = 0.0,
    val pileBaseReference: Double = 0.0,
    val meanPileHeight: Double = 0.0,
    val p95PileHeight: Double = 0.0,

    val reviewModelPoints: List<ReviewPoint3D> = emptyList(),
    val reviewModelWidth: Float = 0f,
    val reviewModelHeight: Float = 0f,
    val reviewModelDepth: Float = 0f,

    val pileDetectionConfidence: Float = 0f,
    val pileDetectionQuality: String = "FALLBACK",
    val pileDetectionReasons: List<String> = emptyList(),
    val detectionDebugInfo: Map<String, String> = emptyMap(),

    val geometricVolumeRaw: Double = 0.0,
    val geometricVolumeCorrected: Double = 0.0,
    val stereoVolumeSmoothed: Double = 0.0,
    val netVolumeEstimate: Double = 0.0,

    val referenceBarMeasurement: ReferenceBarMeasurement? = null,
    val scaleValidationScore: Float = 0f,
    val volumeStabilityScore: Float = 0f,

    val appVersionName: String = "",
    val appVersionCode: Long = 0,
    val appVersionDisplay: String = "",

    val timestamp: Long = System.currentTimeMillis()
) {
    // Backward compatibility for existing callers/exports.
    val volumeBeforeCorrection: Double
        get() = geometricVolumeRaw

    val volumeAfterCorrection: Double
        get() = geometricVolumeCorrected
}
