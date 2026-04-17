package com.forest.scanai.domain.engine

import com.forest.scanai.core.ScanParams
import com.forest.scanai.domain.model.ScanResult
import io.github.sceneview.math.Position
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

class VolumeCalculator(
    private val params: ScanParams,
    private val axisEstimator: PileAxisEstimator = PileAxisEstimator()
) {

    private var lastCalculatedVolume = 0.0

    private data class ProjectedPoint(
        val source: Position,
        val along: Float,
        val cross: Float
    )

    private data class SliceMetrics(
        val area: Double,
        val crestY: Float,
        val groundY: Float,
        val isUseful: Boolean
    )

    fun calculate(points: List<Position>): ScanResult {
        if (points.size < 500) return ScanResult(0.0, emptyList())

        val axis = axisEstimator.estimate(points) ?: return ScanResult(0.0, emptyList())
        if (axis.length < 0.5) return ScanResult(0.0, emptyList())

        val projectedPoints = points.map { point ->
            ProjectedPoint(
                source = point,
                along = axisEstimator.projectAlong(point, axis),
                cross = axisEstimator.projectCross(point, axis)
            )
        }

        val sliceWidth = params.sliceWidth.toFloat().coerceAtLeast(0.12f)
        val totalLength = (axis.maxAlong - axis.minAlong).coerceAtLeast(0.01f)
        val numSlices = max(1, ceil(totalLength / sliceWidth).toInt())

        val sliceAreas = MutableList(numSlices) { 0.0 }
        val topPoints = mutableListOf<Position>()
        var usefulSlices = 0

        for (sliceIndex in 0 until numSlices) {
            val startAlong = axis.minAlong + sliceIndex * sliceWidth
            val endAlong = min(axis.maxAlong, startAlong + sliceWidth)
            val centerAlong = (startAlong + endAlong) / 2f

            val pointsInSlice = projectedPoints.filter { it.along in startAlong..endAlong }
            val metrics = evaluateSlice(pointsInSlice)

            sliceAreas[sliceIndex] = metrics.area
            if (metrics.isUseful) usefulSlices++

            val topY = if (metrics.isUseful) metrics.crestY else metrics.groundY
            topPoints.add(axisEstimator.pointOnAxis(axis, centerAlong, topY))
        }

        val stabilizedSliceAreas = smoothSliceAreas(sliceAreas)
        val smoothedTopPoints = smoothTopPoints(topPoints)

        var rawVolume = 0.0
        for (i in 0 until stabilizedSliceAreas.size - 1) {
            rawVolume += ((stabilizedSliceAreas[i] + stabilizedSliceAreas[i + 1]) / 2.0) * sliceWidth
        }

        val usefulSliceRatio = usefulSlices / numSlices.toDouble()
        val edgeRecoveryFactor = when {
            usefulSliceRatio < 0.40 -> 1.20
            usefulSliceRatio < 0.60 -> 1.12
            usefulSliceRatio < 0.75 -> 1.06
            else -> 1.0
        }
        val correctedVolume = rawVolume * edgeRecoveryFactor

        val alpha = if (abs(correctedVolume - lastCalculatedVolume) < correctedVolume * 0.08) 0.07 else 0.04
        val smoothedStereoVolume = if (lastCalculatedVolume == 0.0) {
            correctedVolume
        } else {
            lastCalculatedVolume + alpha * (correctedVolume - lastCalculatedVolume)
        }
        val netVolumeEstimate = smoothedStereoVolume * 0.45

        lastCalculatedVolume = smoothedStereoVolume

        return ScanResult(
            volume = smoothedStereoVolume.coerceAtLeast(0.0),
            topPoints = smoothedTopPoints,
            geometricVolumeRaw = rawVolume.coerceAtLeast(0.0),
            geometricVolumeCorrected = correctedVolume.coerceAtLeast(0.0),
            stereoVolumeSmoothed = smoothedStereoVolume.coerceAtLeast(0.0),
            netVolumeEstimate = netVolumeEstimate.coerceAtLeast(0.0),
            debugInfo = mapOf(
                "volume_input_points" to points.size.toString(),
                "volume_slices_total" to numSlices.toString(),
                "volume_slices_useful" to usefulSlices.toString(),
                "volume_slice_ratio" to String.format("%.3f", usefulSliceRatio),
                "volume_edge_recovery_factor" to String.format("%.3f", edgeRecoveryFactor),
                "volume_temporal_alpha" to String.format("%.3f", alpha),
                "volume_geometric_raw" to String.format("%.3f", rawVolume),
                "volume_geometric_corrected" to String.format("%.3f", correctedVolume),
                "volume_stereo_smoothed" to String.format("%.3f", smoothedStereoVolume),
                "volume_net_estimate" to String.format("%.3f", netVolumeEstimate)
            )
        )
    }

    private fun evaluateSlice(pointsInSlice: List<ProjectedPoint>): SliceMetrics {
        if (pointsInSlice.size < 20) {
            return SliceMetrics(
                area = 0.0,
                crestY = 0f,
                groundY = 0f,
                isUseful = false
            )
        }

        val ys = pointsInSlice.map { it.source.y }.sorted()
        val sliceGround = percentile(ys, 0.12f)
        val pileBase = percentile(ys, 0.24f)
        val crestY = percentile(ys, 0.94f)

        val rawHeight = (crestY - sliceGround).toDouble()
        if (rawHeight <= params.groundMargin) {
            return SliceMetrics(
                area = 0.0,
                crestY = crestY,
                groundY = sliceGround,
                isUseful = false
            )
        }

        val pileCandidatePoints = pointsInSlice.filter { it.source.y >= pileBase + 0.03f }
        if (pileCandidatePoints.size < 12) {
            return SliceMetrics(
                area = 0.0,
                crestY = crestY,
                groundY = sliceGround,
                isUseful = false
            )
        }

        val pileYs = pileCandidatePoints.map { it.source.y }.sorted()
        val refinedCrestY = percentile(pileYs, 0.95f)

        val crossValues = pileCandidatePoints.map { it.cross }.sorted()
        val crossMin = percentile(crossValues, 0.07f)
        val crossMax = percentile(crossValues, 0.93f)
        val effectiveWidth = (crossMax - crossMin).toDouble().coerceAtLeast(0.0)

        if (effectiveWidth <= 0.05) {
            return SliceMetrics(
                area = 0.0,
                crestY = refinedCrestY,
                groundY = sliceGround,
                isUseful = false
            )
        }

        val area = integrateSliceArea(
            pointsInSlice = pileCandidatePoints,
            crossMin = crossMin,
            crossMax = crossMax,
            fallbackGround = sliceGround,
            fallbackCrest = refinedCrestY
        )

        return SliceMetrics(
            area = area,
            crestY = refinedCrestY,
            groundY = sliceGround,
            isUseful = area > 0.0
        )
    }

    private fun integrateSliceArea(
        pointsInSlice: List<ProjectedPoint>,
        crossMin: Float,
        crossMax: Float,
        fallbackGround: Float,
        fallbackCrest: Float
    ): Double {
        val width = (crossMax - crossMin).toDouble()
        if (width <= 0.0) return 0.0

        val estimatedBins = ceil(width / 0.22).toInt()
        val bins = estimatedBins.coerceIn(5, 12)
        val binWidth = width / bins

        var totalArea = 0.0
        var contributingBins = 0

        for (binIndex in 0 until bins) {
            val binStart = crossMin + (binIndex * binWidth).toFloat()
            val binEnd = binStart + binWidth.toFloat()

            val binPoints = pointsInSlice.filter { it.cross in binStart..binEnd }
            if (binPoints.size < 4) continue

            val ys = binPoints.map { it.source.y }.sorted()
            val ground = min(fallbackGround, percentile(ys, 0.12f))
            val top = percentile(ys, 0.91f)
            val binHeight = (top - ground).toDouble()

            if (binHeight > params.groundMargin) {
                totalArea += binHeight * binWidth
                contributingBins++
            }
        }

        if (contributingBins == 0) {
            val fallbackHeight = (fallbackCrest - fallbackGround).toDouble().coerceAtLeast(0.0)
            return fallbackHeight * width * 0.70
        }

        return totalArea
    }

    private fun smoothSliceAreas(sliceAreas: List<Double>): List<Double> {
        if (sliceAreas.size < 5) return sliceAreas
        return sliceAreas.mapIndexed { index, value ->
            if (index in 1 until sliceAreas.lastIndex) {
                val window = listOf(sliceAreas[index - 1], value, sliceAreas[index + 1]).sorted()
                window[1]
            } else {
                value
            }
        }
    }

    private fun smoothTopPoints(points: List<Position>): List<Position> {
        if (points.size < 5) return points

        return points.mapIndexed { index, point ->
            if (index in 2 until points.lastIndex - 1) {
                val avgY = (
                        points[index - 2].y +
                                points[index - 1].y +
                                points[index].y +
                                points[index + 1].y +
                                points[index + 2].y
                        ) / 5f

                Position(point.x, avgY, point.z)
            } else {
                point
            }
        }
    }

    private fun percentile(sorted: List<Float>, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted.first()

        val clampedQ = q.coerceIn(0f, 1f)
        val index = clampedQ * sorted.lastIndex
        val lower = index.toInt()
        val upper = min(lower + 1, sorted.lastIndex)
        val weight = index - lower

        return sorted[lower] * (1f - weight) + sorted[upper] * weight
    }
}
