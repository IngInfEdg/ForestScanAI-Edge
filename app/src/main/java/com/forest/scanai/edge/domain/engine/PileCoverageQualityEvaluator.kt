package com.forest.scanai.edge.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class PileCoverageQualityLevel {
    POOR,
    FAIR,
    GOOD,
    COMPLETE
}

data class PileCoverageQualityResult(
    val axisLength: Double,
    val longitudinalCoverageRatio: Float,
    val coveredLengthBins: Int,
    val totalLengthBins: Int,
    val edgeCoverageStart: Boolean,
    val edgeCoverageEnd: Boolean,
    val verticalCoverageRatio: Float,
    val occupiedBinRatio: Float,
    val qualityLevel: PileCoverageQualityLevel,
    val confidencePenalty: Float
)

class PileCoverageQualityEvaluator(
    private val axisEstimator: PileAxisEstimator = PileAxisEstimator()
) {

    private data class ProjectedPoint(
        val along: Float,
        val cross: Float,
        val y: Float
    )

    fun evaluate(points: List<Position>): PileCoverageQualityResult? {
        if (points.size < 300) return null

        val axis = axisEstimator.estimate(points) ?: return null
        if (axis.length < 1.0) return null

        val projected = points.map { point ->
            ProjectedPoint(
                along = axisEstimator.projectAlong(point, axis),
                cross = axisEstimator.projectCross(point, axis),
                y = point.y
            )
        }

        val length = (axis.maxAlong - axis.minAlong).coerceAtLeast(0.01f)
        val binSize = 0.50f
        val totalBins = max(6, ceil(length / binSize).toInt())

        val bins = MutableList(totalBins) { mutableListOf<ProjectedPoint>() }

        projected.forEach { p ->
            val normalized = ((p.along - axis.minAlong) / length).coerceIn(0f, 0.9999f)
            val index = (normalized * totalBins).toInt().coerceIn(0, totalBins - 1)
            bins[index].add(p)
        }

        val globalYs = projected.map { it.y }.sorted()
        val globalGround = percentile(globalYs, 0.10f)
        val globalTop = percentile(globalYs, 0.90f)
        val globalHeight = (globalTop - globalGround).coerceAtLeast(0.01f)

        var coveredBins = 0
        var verticalGoodBins = 0

        bins.forEach { bin ->
            if (bin.size >= 12) {
                coveredBins++
            }

            if (bin.size >= 12) {
                val ys = bin.map { it.y }.sorted()
                val localGround = percentile(ys, 0.15f)
                val localTop = percentile(ys, 0.85f)
                val localHeight = localTop - localGround

                if (localHeight >= globalHeight * 0.25f) {
                    verticalGoodBins++
                }
            }
        }

        val occupiedBinRatio = coveredBins.toFloat() / totalBins.toFloat()
        val verticalCoverageRatio = verticalGoodBins.toFloat() / totalBins.toFloat()

        val edgeCoverageStart = bins.firstOrNull()?.size ?: 0 >= 10
        val edgeCoverageEnd = bins.lastOrNull()?.size ?: 0 >= 10

        val longitudinalCoverageRatio = when {
            edgeCoverageStart && edgeCoverageEnd -> occupiedBinRatio
            else -> occupiedBinRatio * 0.85f
        }

        val qualityLevel = when {
            longitudinalCoverageRatio >= 0.85f &&
                    verticalCoverageRatio >= 0.65f &&
                    edgeCoverageStart &&
                    edgeCoverageEnd -> PileCoverageQualityLevel.COMPLETE

            longitudinalCoverageRatio >= 0.70f &&
                    verticalCoverageRatio >= 0.50f -> PileCoverageQualityLevel.GOOD

            longitudinalCoverageRatio >= 0.50f &&
                    verticalCoverageRatio >= 0.35f -> PileCoverageQualityLevel.FAIR

            else -> PileCoverageQualityLevel.POOR
        }

        val confidencePenalty = when (qualityLevel) {
            PileCoverageQualityLevel.COMPLETE -> 0.00f
            PileCoverageQualityLevel.GOOD -> 0.08f
            PileCoverageQualityLevel.FAIR -> 0.18f
            PileCoverageQualityLevel.POOR -> 0.30f
        }

        return PileCoverageQualityResult(
            axisLength = axis.length,
            longitudinalCoverageRatio = longitudinalCoverageRatio,
            coveredLengthBins = coveredBins,
            totalLengthBins = totalBins,
            edgeCoverageStart = edgeCoverageStart,
            edgeCoverageEnd = edgeCoverageEnd,
            verticalCoverageRatio = verticalCoverageRatio,
            occupiedBinRatio = occupiedBinRatio,
            qualityLevel = qualityLevel,
            confidencePenalty = confidencePenalty
        )
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