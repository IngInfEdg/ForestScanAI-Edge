package com.forest.scanai.edge.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class VerticalCoverageEvaluator(
    private val minPointsForEvaluation: Int = 180,
    private val middleDominanceThreshold: Float = 0.70f,
    private val bandWeakThreshold: Float = 0.09f,
    private val minimumTopCoverageScore: Float = 0.46f
) {

    fun evaluate(
        pilePoints: List<Position>,
        observerPath: List<Position> = emptyList(),
        recentTopCoverageScores: List<Float> = emptyList()
    ): VerticalCoverageResult {
        if (pilePoints.size < minPointsForEvaluation) {
            return VerticalCoverageResult(
                verticalCoverageScore = 0f,
                totalPoints = pilePoints.size,
                bandPointCounts = emptyMap(),
                bandRatios = emptyMap(),
                coveredBands = emptySet(),
                dominantBand = null,
                weakBands = emptySet(),
                observerHeightRange = 0f,
                observerDistanceVariation = 0f,
                topCoverageScore = 0f,
                topPointCount = 0,
                topBandDensity = 0f,
                topCoverageTemporalStability = 0f,
                topCoverageTrend = 0f,
                topCoverageState = TopCoverageState.TOP_MISSING,
                penaltyFlags = setOf(VerticalCoveragePenalty.TOO_FEW_POINTS),
                reasons = listOf("Pocos puntos útiles para evaluar cobertura vertical real.")
            )
        }

        val minY = pilePoints.minOf { it.y }
        val maxY = pilePoints.maxOf { it.y }
        val heightRange = (maxY - minY).coerceAtLeast(1e-4f)

        val counts = mutableMapOf(
            VerticalBand.LOWER to 0,
            VerticalBand.MIDDLE to 0,
            VerticalBand.UPPER to 0
        )

        pilePoints.forEach { point ->
            val normalized = ((point.y - minY) / heightRange).coerceIn(0f, 1f)
            val band = when {
                normalized < (1f / 3f) -> VerticalBand.LOWER
                normalized < (2f / 3f) -> VerticalBand.MIDDLE
                else -> VerticalBand.UPPER
            }
            counts[band] = counts.getValue(band) + 1
        }

        val total = pilePoints.size.toFloat()
        val ratios = counts.mapValues { (_, value) -> value / total }
        val coveredBands = counts.filterValues { it > 0 }.keys
        val weakBands = ratios.filterValues { it <= bandWeakThreshold }.keys
        val dominant = ratios.maxByOrNull { it.value }?.key
        val topMetrics = computeTopCoverageMetrics(
            pilePoints = pilePoints,
            minY = minY,
            maxY = maxY,
            upperBandRatio = ratios.getValue(VerticalBand.UPPER)
        )
        val temporalSeries = (recentTopCoverageScores.takeLast(9) + topMetrics.score).takeLast(10)
        val topCoverageTrend = computeTrend(temporalSeries)
        val topTemporalStability = computeTemporalStability(temporalSeries)
        val topCoverageState = classifyTopCoverageState(
            score = topMetrics.score,
            topPointCount = topMetrics.pointCount,
            topBandDensity = topMetrics.bandDensity,
            trend = topCoverageTrend,
            stability = topTemporalStability
        )

        val centerX = (pilePoints.minOf { it.x } + pilePoints.maxOf { it.x }) / 2f
        val centerZ = (pilePoints.minOf { it.z } + pilePoints.maxOf { it.z }) / 2f
        val observerHeightRange = observerPath
            .takeIf { it.isNotEmpty() }
            ?.let { (it.maxOf { p -> p.y } - it.minOf { p -> p.y }).coerceAtLeast(0f) }
            ?: 0f

        val observerDistanceVariation = observerPath
            .takeIf { it.size >= 4 }
            ?.map { p -> sqrt((p.x - centerX) * (p.x - centerX) + (p.z - centerZ) * (p.z - centerZ)) }
            ?.let { distances ->
                val mean = distances.average().coerceAtLeast(1e-6)
                val variance = distances.map { d -> (d - mean) * (d - mean) }.average()
                (sqrt(variance) / mean).toFloat()
            }
            ?: 0f

        val coveredScore = coveredBands.size / 3f
        val ideal = 1f / 3f
        val maxDeviation = ratios.values.maxOf { abs(it - ideal) }
        val balanceScore = (1f - (maxDeviation / ideal).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val spreadScore = ((heightRange - 0.30f) / 0.90f).coerceIn(0f, 1f)

        val lowerRatio = ratios.getValue(VerticalBand.LOWER)
        val middleRatio = ratios.getValue(VerticalBand.MIDDLE)
        val upperRatio = ratios.getValue(VerticalBand.UPPER)
        val lowerSupportScore = (lowerRatio / 0.22f).coerceIn(0f, 1f)
        val upperSupportScore = (upperRatio / 0.20f).coerceIn(0f, 1f)

        val observerHeightScore = if (observerPath.size < 4) 0.65f else (observerHeightRange / 0.85f).coerceIn(0f, 1f)
        val observerDistanceScore = if (observerPath.size < 4) 0.65f else (observerDistanceVariation / 0.15f).coerceIn(0f, 1f)
        val observerDiversityScore = ((observerHeightScore + observerDistanceScore) / 2f).coerceIn(0f, 1f)

        var score = (
            coveredScore * 0.30f +
                balanceScore * 0.20f +
                spreadScore * 0.15f +
                lowerSupportScore * 0.13f +
                upperSupportScore * 0.12f +
                topMetrics.score * 0.10f
            ).coerceIn(0f, 1f)
        score = (score * 0.92f + observerDiversityScore * 0.08f).coerceIn(0f, 1f)

        val penalties = mutableSetOf<VerticalCoveragePenalty>()

        if (lowerRatio <= bandWeakThreshold) {
            penalties += VerticalCoveragePenalty.MISSING_LOWER_BAND
            score -= 0.12f
        }

        if (upperRatio <= bandWeakThreshold) {
            penalties += VerticalCoveragePenalty.MISSING_UPPER_BAND
            score -= 0.14f
        }
        if (topMetrics.score < minimumTopCoverageScore) {
            penalties += VerticalCoveragePenalty.TOP_SURFACE_SPARSE
            score -= 0.08f
        }

        if (middleRatio >= middleDominanceThreshold) {
            penalties += VerticalCoveragePenalty.MIDDLE_DOMINANCE
            score -= 0.20f
        }

        if (heightRange < 0.40f) {
            penalties += VerticalCoveragePenalty.INSUFFICIENT_VERTICAL_SPREAD
            score -= 0.08f
        }

        if (observerPath.size >= 4 && observerHeightRange < 0.20f) {
            penalties += VerticalCoveragePenalty.SINGLE_HEIGHT_BAND_OBSERVER
            score -= 0.05f
        }

        if (observerPath.size >= 4 && observerDistanceVariation < 0.05f) {
            penalties += VerticalCoveragePenalty.CONSTANT_DISTANCE_OBSERVER
            score -= 0.04f
        }

        if (lowerRatio in (bandWeakThreshold + 0.001f)..0.18f) {
            penalties += VerticalCoveragePenalty.LOWER_BAND_IMPROVING
        }
        if (upperRatio in (bandWeakThreshold + 0.001f)..0.20f) {
            penalties += VerticalCoveragePenalty.UPPER_BAND_IMPROVING
        }

        val reasons = penalties.map { it.humanReadable }

        return VerticalCoverageResult(
            verticalCoverageScore = score.coerceIn(0f, 1f),
            totalPoints = pilePoints.size,
            bandPointCounts = counts,
            bandRatios = ratios,
            coveredBands = coveredBands,
            dominantBand = dominant,
            weakBands = weakBands,
            observerHeightRange = observerHeightRange,
            observerDistanceVariation = observerDistanceVariation,
            topCoverageScore = topMetrics.score,
            topPointCount = topMetrics.pointCount,
            topBandDensity = topMetrics.bandDensity,
            topCoverageTemporalStability = topTemporalStability,
            topCoverageTrend = topCoverageTrend,
            topCoverageState = topCoverageState,
            penaltyFlags = penalties,
            reasons = reasons
        )
    }

    private fun computeTopCoverageMetrics(
        pilePoints: List<Position>,
        minY: Float,
        maxY: Float,
        upperBandRatio: Float
    ): TopCoverageMetrics {
        val heightRange = (maxY - minY).coerceAtLeast(1e-4f)
        val topBandThreshold = maxY - heightRange * 0.18f
        val topPoints = pilePoints.filter { it.y >= topBandThreshold }
        if (topPoints.size < 16) {
            return TopCoverageMetrics(score = 0f, pointCount = topPoints.size, bandDensity = 0f)
        }

        val minTopX = topPoints.minOf { it.x }
        val maxTopX = topPoints.maxOf { it.x }
        val minTopZ = topPoints.minOf { it.z }
        val maxTopZ = topPoints.maxOf { it.z }
        val spanX = (maxTopX - minTopX).coerceAtLeast(1e-4f)
        val spanZ = (maxTopZ - minTopZ).coerceAtLeast(1e-4f)

        val bins = 4
        val occupiedBins = mutableSetOf<Pair<Int, Int>>()
        topPoints.forEach { point ->
            val bx = floor(((point.x - minTopX) / spanX) * bins).toInt().coerceIn(0, bins - 1)
            val bz = floor(((point.z - minTopZ) / spanZ) * bins).toInt().coerceIn(0, bins - 1)
            occupiedBins += bx to bz
        }

        val occupancyScore = (occupiedBins.size / (bins * bins).toFloat()).coerceIn(0f, 1f)
        val expectedTopPoints = (pilePoints.size * 0.14f).coerceAtLeast(24f)
        val densityScore = (topPoints.size / expectedTopPoints).coerceIn(0f, 1f)
        val upperRatioScore = (upperBandRatio / 0.20f).coerceIn(0f, 1f)
        val area = (spanX * spanZ).coerceAtLeast(1e-4f)
        val topBandDensity = (topPoints.size / area).coerceAtMost(240f)
        val bandDensityScore = (topBandDensity / 35f).coerceIn(0f, 1f)
        val score = (
            occupancyScore * 0.34f +
                densityScore * 0.26f +
                upperRatioScore * 0.14f +
                bandDensityScore * 0.26f
            ).coerceIn(0f, 1f)

        return TopCoverageMetrics(score = score, pointCount = topPoints.size, bandDensity = topBandDensity)
    }

    private fun classifyTopCoverageState(
        score: Float,
        topPointCount: Int,
        topBandDensity: Float,
        trend: Float,
        stability: Float
    ): TopCoverageState {
        if (score >= 0.64f && topPointCount >= 22 && topBandDensity >= 12f && stability >= 0.28f) {
            return TopCoverageState.TOP_OK
        }
        if (score >= 0.44f && topPointCount >= 12 && (trend >= 0.01f || stability >= 0.24f)) {
            return TopCoverageState.TOP_IMPROVING
        }
        return TopCoverageState.TOP_MISSING
    }

    private fun computeTrend(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val first = values.first()
        val last = values.last()
        return ((last - first) / values.size.toFloat()).coerceIn(-1f, 1f)
    }

    private fun computeTemporalStability(values: List<Float>): Float {
        if (values.size < 3) return 0f
        val mean = values.average().toFloat().coerceAtLeast(1e-4f)
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)
        return (1f - (stdDev / mean).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

enum class VerticalBand {
    LOWER,
    MIDDLE,
    UPPER
}

enum class VerticalCoveragePenalty(val humanReadable: String) {
    TOO_FEW_POINTS("Faltan puntos útiles para validar cobertura vertical."),
    MISSING_LOWER_BAND("Falta capturar mejor la base o límite con el suelo."),
    LOWER_BAND_IMPROVING("La base mejora, pero aún conviene capturar más puntos bajos."),
    MISSING_UPPER_BAND("Inclina el celular hacia la cima de la pila para capturar mejor la corona."),
    UPPER_BAND_IMPROVING("La corona mejora, pero aún conviene sostener el encuadre superior."),
    TOP_SURFACE_SPARSE("Aún faltan puntos en la parte más alta; da un paso atrás y centra la cima en la vista."),
    MIDDLE_DOMINANCE("La nube está concentrada en la franja media; varía ángulo y altura del celular."),
    INSUFFICIENT_VERTICAL_SPREAD("Cobertura vertical insuficiente entre base y corona."),
    SINGLE_HEIGHT_BAND_OBSERVER("Se detecta recorrido desde una sola franja de altura visual."),
    CONSTANT_DISTANCE_OBSERVER("Se detecta distancia casi constante; varía ángulo y distancia de observación.")
}

data class VerticalCoverageResult(
    val verticalCoverageScore: Float,
    val totalPoints: Int,
    val bandPointCounts: Map<VerticalBand, Int>,
    val bandRatios: Map<VerticalBand, Float>,
    val coveredBands: Set<VerticalBand>,
    val dominantBand: VerticalBand?,
    val weakBands: Set<VerticalBand>,
    val observerHeightRange: Float,
    val observerDistanceVariation: Float,
    val topCoverageScore: Float,
    val topPointCount: Int,
    val topBandDensity: Float,
    val topCoverageTemporalStability: Float,
    val topCoverageTrend: Float,
    val topCoverageState: TopCoverageState,
    val penaltyFlags: Set<VerticalCoveragePenalty>,
    val reasons: List<String>
) {
    val hasStrongMiddleConcentration: Boolean
        get() = VerticalCoveragePenalty.MIDDLE_DOMINANCE in penaltyFlags

    val supportsComplete: Boolean
        get() = verticalCoverageScore >= 0.72f &&
            topCoverageScore >= 0.60f &&
            VerticalCoveragePenalty.MISSING_LOWER_BAND !in penaltyFlags &&
            VerticalCoveragePenalty.MISSING_UPPER_BAND !in penaltyFlags &&
            weakBands.size <= 1

    val supportsAcceptable: Boolean
        get() = verticalCoverageScore >= 0.56f && weakBands.size <= 1
}

enum class TopCoverageState {
    TOP_MISSING,
    TOP_IMPROVING,
    TOP_OK
}

private data class TopCoverageMetrics(
    val score: Float,
    val pointCount: Int,
    val bandDensity: Float
)
