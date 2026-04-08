package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.sqrt

class VerticalCoverageEvaluator(
    private val minPointsForEvaluation: Int = 180,
    private val middleDominanceThreshold: Float = 0.72f,
    private val bandWeakThreshold: Float = 0.12f
) {

    fun evaluate(
        pilePoints: List<Position>,
        observerPath: List<Position> = emptyList()
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
        val weakBands = ratios.filterValues { it in 0f..bandWeakThreshold }.keys
        val dominant = ratios.maxByOrNull { it.value }?.key

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
        val spreadScore = ((heightRange - 0.35f) / 0.85f).coerceIn(0f, 1f)
        val observerHeightScore = if (observerPath.size < 4) 0.6f else (observerHeightRange / 0.9f).coerceIn(0f, 1f)
        val observerDistanceScore = if (observerPath.size < 4) 0.6f else (observerDistanceVariation / 0.16f).coerceIn(0f, 1f)
        val observerDiversityScore = ((observerHeightScore + observerDistanceScore) / 2f).coerceIn(0f, 1f)

        var score = (
            coveredScore * 0.45f +
                balanceScore * 0.30f +
                spreadScore * 0.15f +
                observerDiversityScore * 0.10f
            ).coerceIn(0f, 1f)

        val penalties = mutableSetOf<VerticalCoveragePenalty>()

        if (ratios.getValue(VerticalBand.LOWER) <= bandWeakThreshold) {
            penalties += VerticalCoveragePenalty.MISSING_LOWER_BAND
            score -= 0.22f
        }

        if (ratios.getValue(VerticalBand.UPPER) <= bandWeakThreshold) {
            penalties += VerticalCoveragePenalty.MISSING_UPPER_BAND
            score -= 0.24f
        }

        if (ratios.getValue(VerticalBand.MIDDLE) >= middleDominanceThreshold) {
            penalties += VerticalCoveragePenalty.MIDDLE_DOMINANCE
            score -= 0.30f
        }

        if (heightRange < 0.45f) {
            penalties += VerticalCoveragePenalty.INSUFFICIENT_VERTICAL_SPREAD
            score -= 0.15f
        }

        if (observerPath.size >= 4 && observerHeightRange < 0.22f) {
            penalties += VerticalCoveragePenalty.SINGLE_HEIGHT_BAND_OBSERVER
            score -= 0.10f
        }

        if (observerPath.size >= 4 && observerDistanceVariation < 0.05f) {
            penalties += VerticalCoveragePenalty.CONSTANT_DISTANCE_OBSERVER
            score -= 0.08f
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
            penaltyFlags = penalties,
            reasons = reasons
        )
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
    MISSING_UPPER_BAND("Falta capturar mejor la parte superior de la pila."),
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
    val penaltyFlags: Set<VerticalCoveragePenalty>,
    val reasons: List<String>
) {
    val hasStrongMiddleConcentration: Boolean
        get() = VerticalCoveragePenalty.MIDDLE_DOMINANCE in penaltyFlags

    val supportsComplete: Boolean
        get() = verticalCoverageScore >= 0.75f &&
            VerticalCoveragePenalty.MISSING_LOWER_BAND !in penaltyFlags &&
            VerticalCoveragePenalty.MISSING_UPPER_BAND !in penaltyFlags &&
            weakBands.size <= 1

    val supportsAcceptable: Boolean
        get() = verticalCoverageScore >= 0.60f && weakBands.size <= 1
}
