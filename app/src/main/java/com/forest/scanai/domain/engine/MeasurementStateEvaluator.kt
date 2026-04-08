package com.forest.scanai.domain.engine

import com.forest.scanai.domain.model.CompletenessLevel

class MeasurementStateEvaluator(
    private val completeTrajectoryThreshold: Float = 0.75f,
    private val acceptableTrajectoryThreshold: Float = 0.60f,
    private val completeVerticalThreshold: Float = 0.75f,
    private val acceptableVerticalThreshold: Float = 0.60f
) {

    fun evaluate(input: MeasurementStateInput): MeasurementStateDecision {
        val blockers = mutableListOf<String>()
        var level = input.baseCompleteness

        if (input.trajectoryQualityScore < acceptableTrajectoryThreshold) {
            blockers += "Trayectoria incompleta o con cierre deficiente; rodea mejor la pila antes de finalizar."
            level = level.capAt(CompletenessLevel.PARTIAL)
        }

        if (input.verticalCoverageScore < acceptableVerticalThreshold || input.weakVerticalBands >= 2) {
            blockers += "La cobertura vertical aún es insuficiente; falta capturar base y corona con más detalle."
            level = level.capAt(CompletenessLevel.PARTIAL)
        }

        if (input.hasStrongMiddleConcentration) {
            blockers += "La nube está concentrada en una franja media; recorre la pila variando el ángulo del celular."
            level = level.capAt(CompletenessLevel.PARTIAL)
        }

        if (input.baseCompleteness == CompletenessLevel.COMPLETE) {
            if (input.trajectoryQualityScore < completeTrajectoryThreshold) {
                blockers += "La trayectoria aún no cumple calidad completa (loop/ángulos)."
                level = level.capAt(CompletenessLevel.ACCEPTABLE)
            }

            if (input.verticalCoverageScore < completeVerticalThreshold || input.weakVerticalBands > 0) {
                blockers += "Cobertura vertical insuficiente para completar (base, mitad y corona)."
                level = level.capAt(CompletenessLevel.ACCEPTABLE)
            }

            if (!input.isVolumeStable) {
                blockers += "El volumen aún no se estabiliza; continúa escaneando para mejorar precisión."
                level = level.capAt(CompletenessLevel.ACCEPTABLE)
            }
        }

        if (level == CompletenessLevel.ACCEPTABLE && !input.supportsAcceptableVertical) {
            level = CompletenessLevel.PARTIAL
        }

        return MeasurementStateDecision(
            completeness = level,
            canFinish = level == CompletenessLevel.ACCEPTABLE || level == CompletenessLevel.COMPLETE,
            blockers = blockers.distinct()
        )
    }

    private fun CompletenessLevel.capAt(maximum: CompletenessLevel): CompletenessLevel {
        return if (this.ordinal > maximum.ordinal) maximum else this
    }
}

data class MeasurementStateInput(
    val baseCompleteness: CompletenessLevel,
    val trajectoryQualityScore: Float,
    val verticalCoverageScore: Float,
    val weakVerticalBands: Int,
    val supportsAcceptableVertical: Boolean,
    val hasStrongMiddleConcentration: Boolean,
    val isVolumeStable: Boolean
)

data class MeasurementStateDecision(
    val completeness: CompletenessLevel,
    val canFinish: Boolean,
    val blockers: List<String>
)

class VolumeStabilityEvaluator(
    private val windowSize: Int = 7,
    private val minSamples: Int = 5,
    private val maxRelativeVariation: Double = 0.12
) {

    fun evaluate(volumeSamples: List<Double>): VolumeStabilityResult {
        val window = volumeSamples.takeLast(windowSize)
        if (window.size < minSamples) {
            return VolumeStabilityResult(
                isStable = false,
                relativeVariation = 1.0,
                reasons = listOf("Aún no hay suficientes muestras para verificar estabilidad del volumen.")
            )
        }

        val median = window.sorted().let { sorted ->
            if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2.0
            } else {
                sorted[sorted.size / 2]
            }
        }.coerceAtLeast(1e-6)

        val min = window.minOrNull() ?: median
        val max = window.maxOrNull() ?: median
        val relativeVariation = ((max - min) / median).coerceAtLeast(0.0)
        val stable = relativeVariation <= maxRelativeVariation

        val reasons = if (stable) {
            emptyList()
        } else {
            listOf("El volumen aún varía demasiado entre muestras recientes.")
        }

        return VolumeStabilityResult(
            isStable = stable,
            relativeVariation = relativeVariation,
            reasons = reasons
        )
    }
}

data class VolumeStabilityResult(
    val isStable: Boolean,
    val relativeVariation: Double,
    val reasons: List<String>
)
