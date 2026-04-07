package com.forest.scanai.domain.engine

import com.forest.scanai.core.ScanParams
import com.forest.scanai.domain.model.ScanResult
import io.github.sceneview.math.Position
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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

        val sliceWidth = params.sliceWidth.toFloat().coerceAtLeast(0.05f)
        val totalLength = (axis.maxAlong - axis.minAlong).coerceAtLeast(0.01f)
        val numSlices = max(1, ceil(totalLength / sliceWidth).toInt())

        val sliceAreas = MutableList(numSlices) { 0.0 }
        val topPoints = mutableListOf<Position>()

        for (sliceIndex in 0 until numSlices) {
            val startAlong = axis.minAlong + sliceIndex * sliceWidth
            val endAlong = min(axis.maxAlong, startAlong + sliceWidth)
            val centerAlong = (startAlong + endAlong) / 2f

            val pointsInSlice = projectedPoints.filter { it.along in startAlong..endAlong }

            if (pointsInSlice.size < 20) {
                topPoints.add(axisEstimator.pointOnAxis(axis, centerAlong, 0f))
                continue
            }

            val ys = pointsInSlice.map { it.source.y }.sorted()
            val sliceGround = percentile(ys, 0.12f)
            val sliceTop = percentile(ys, 0.88f)
            val sliceHeight = (sliceTop - sliceGround).toDouble()

            if (sliceHeight <= params.groundMargin) {
                topPoints.add(axisEstimator.pointOnAxis(axis, centerAlong, sliceGround))
                continue
            }

            val crossValues = pointsInSlice.map { it.cross }.sorted()
            val crossMin = percentile(crossValues, 0.10f)
            val crossMax = percentile(crossValues, 0.90f)
            val effectiveWidth = (crossMax - crossMin).toDouble().coerceAtLeast(0.0)

            topPoints.add(axisEstimator.pointOnAxis(axis, centerAlong, sliceTop))

            if (effectiveWidth <= 0.05) {
                continue
            }

            val area = integrateSliceArea(
                pointsInSlice = pointsInSlice,
                crossMin = crossMin,
                crossMax = crossMax,
                fallbackHeight = sliceHeight
            )

            sliceAreas[sliceIndex] = area
        }

        val smoothedTopPoints = smoothTopPoints(topPoints)

        var rawVolume = 0.0
        for (i in 0 until sliceAreas.size - 1) {
            rawVolume += ((sliceAreas[i] + sliceAreas[i + 1]) / 2.0) * sliceWidth
        }

        val finalVolume = if (lastCalculatedVolume == 0.0) {
            rawVolume
        } else {
            lastCalculatedVolume + 0.05 * (rawVolume - lastCalculatedVolume)
        }

        lastCalculatedVolume = finalVolume

        return ScanResult(
            volume = finalVolume.coerceAtLeast(0.0),
            topPoints = smoothedTopPoints
        )
    }

    private fun integrateSliceArea(
        pointsInSlice: List<ProjectedPoint>,
        crossMin: Float,
        crossMax: Float,
        fallbackHeight: Double
    ): Double {
        val width = (crossMax - crossMin).toDouble()
        if (width <= 0.0) return 0.0

        val estimatedBins = ceil(width / 0.25).toInt()
        val bins = estimatedBins.coerceIn(4, 10)
        val binWidth = width / bins

        var totalArea = 0.0
        var contributingBins = 0

        for (binIndex in 0 until bins) {
            val binStart = crossMin + (binIndex * binWidth).toFloat()
            val binEnd = binStart + binWidth.toFloat()

            val binPoints = pointsInSlice.filter { it.cross in binStart..binEnd }
            if (binPoints.size < 4) continue

            val ys = binPoints.map { it.source.y }.sorted()
            val ground = percentile(ys, 0.15f)
            val top = percentile(ys, 0.85f)
            val binHeight = (top - ground).toDouble()

            if (binHeight > params.groundMargin) {
                totalArea += binHeight * binWidth
                contributingBins++
            }
        }

        if (contributingBins == 0) {
            return fallbackHeight * width * 0.65
        }

        return totalArea
    }

    private fun smoothTopPoints(points: List<Position>): List<Position> {
        if (points.size < 7) return points

        return points.mapIndexed { index, point ->
            if (index in 3 until points.lastIndex - 2) {
                val avgY = (
                        points[index - 3].y +
                                points[index - 2].y +
                                points[index - 1].y +
                                points[index].y +
                                points[index + 1].y +
                                points[index + 2].y +
                                points[index + 3].y
                        ) / 7f

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