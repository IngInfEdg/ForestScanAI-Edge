package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class PointClass {
    GROUND,
    PILE
}

data class ClassifiedPoint(
    val position: Position,
    val pointClass: PointClass,
    val along: Float,
    val cross: Float,
    val relativeHeight: Float
)

data class PileHeightSummary(
    val groundReference: Double,
    val pileBaseReference: Double,
    val maxHeight: Double,
    val p95Height: Double,
    val meanHeight: Double,
    val occupiedSlices: Int,
    val totalSlices: Int
)

data class SegmentedPileResult(
    val axis: PileAxisResult,
    val classifiedPoints: List<ClassifiedPoint>,
    val groundPoints: List<Position>,
    val pilePoints: List<Position>,
    val heightSummary: PileHeightSummary
)

class GroundPileSegmenter(
    private val axisEstimator: PileAxisEstimator = PileAxisEstimator()
) {

    fun segment(points: List<Position>): SegmentedPileResult? {
        if (points.size < 300) return null

        val axis = axisEstimator.estimate(points) ?: return null
        val axisLength = (axis.maxAlong - axis.minAlong)
        if (axisLength < 0.8f) return null

        val sliceSize = 0.35f
        val sliceCount = max(6, ceil(axisLength / sliceSize).toInt())
        val slices = MutableList(sliceCount) { mutableListOf<Position>() }

        points.forEach { point ->
            val along = axisEstimator.projectAlong(point, axis)
            val normalized = ((along - axis.minAlong) / axisLength).coerceIn(0f, 0.9999f)
            val index = (normalized * sliceCount).toInt().coerceIn(0, sliceCount - 1)
            slices[index].add(point)
        }

        val sliceGroundLevels = FloatArray(sliceCount) { Float.NaN }
        val slicePileBases = FloatArray(sliceCount) { Float.NaN }

        for (i in slices.indices) {
            val ys = slices[i].map { it.y }.sorted()
            if (ys.size >= 12) {
                sliceGroundLevels[i] = percentile(ys, 0.10f)
                slicePileBases[i] = percentile(ys, 0.20f)
            }
        }

        smoothMissingValues(sliceGroundLevels)
        smoothMissingValues(slicePileBases)

        val classified = mutableListOf<ClassifiedPoint>()
        val groundPoints = mutableListOf<Position>()
        val pilePoints = mutableListOf<Position>()
        val pileHeights = mutableListOf<Float>()
        var occupiedSlices = 0

        for (i in slices.indices) {
            val slice = slices[i]
            if (slice.isEmpty()) continue

            val groundRef = sliceGroundLevels[i]
            val pileBaseRef = slicePileBases[i]
            var sliceHasPile = false

            for (point in slice) {
                val along = axisEstimator.projectAlong(point, axis)
                val cross = axisEstimator.projectCross(point, axis)
                val relativeHeight = (point.y - groundRef).coerceAtLeast(0f)

                val isPile = point.y >= pileBaseRef + 0.08f
                val pointClass = if (isPile) PointClass.PILE else PointClass.GROUND

                classified += ClassifiedPoint(
                    position = point,
                    pointClass = pointClass,
                    along = along,
                    cross = cross,
                    relativeHeight = relativeHeight
                )

                if (isPile) {
                    pilePoints += point
                    pileHeights += relativeHeight
                    sliceHasPile = true
                } else {
                    groundPoints += point
                }
            }

            if (sliceHasPile) occupiedSlices++
        }

        if (pilePoints.isEmpty()) return null

        val validGrounds = sliceGroundLevels.filter { !it.isNaN() }
        val validPileBases = slicePileBases.filter { !it.isNaN() }

        val globalGround = if (validGrounds.isNotEmpty()) validGrounds.average() else 0.0
        val globalPileBase = if (validPileBases.isNotEmpty()) validPileBases.average() else globalGround

        val sortedHeights = pileHeights.sorted()
        val heightSummary = PileHeightSummary(
            groundReference = globalGround,
            pileBaseReference = globalPileBase,
            maxHeight = sortedHeights.maxOrNull()?.toDouble() ?: 0.0,
            p95Height = percentile(sortedHeights, 0.95f).toDouble(),
            meanHeight = if (sortedHeights.isNotEmpty()) sortedHeights.average() else 0.0,
            occupiedSlices = occupiedSlices,
            totalSlices = sliceCount
        )

        return SegmentedPileResult(
            axis = axis,
            classifiedPoints = classified,
            groundPoints = groundPoints,
            pilePoints = pilePoints,
            heightSummary = heightSummary
        )
    }

    private fun smoothMissingValues(values: FloatArray) {
        for (i in values.indices) {
            if (!values[i].isNaN()) continue

            var left: Float? = null
            var right: Float? = null

            for (j in i - 1 downTo 0) {
                if (!values[j].isNaN()) {
                    left = values[j]
                    break
                }
            }

            for (j in i + 1 until values.size) {
                if (!values[j].isNaN()) {
                    right = values[j]
                    break
                }
            }

            values[i] = when {
                left != null && right != null -> (left + right) / 2f
                left != null -> left
                right != null -> right
                else -> 0f
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