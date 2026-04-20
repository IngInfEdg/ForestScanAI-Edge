package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.CompletenessLevel

class MeasurementStateEvaluator(
    private val completeTrajectoryThreshold: Float = 0.75f,
    private val acceptableTrajectoryThreshold: Float = 0.60f,
    private val completeVerticalThreshold: Float = 0.75f,
    private val acceptableVerticalThreshold: Float = 0.60f,
    private val completeTopCoverageThreshold: Float = 0.60f
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
        if (input.topCoverageScore < 0.50f && input.topCoverageState != TopCoverageState.TOP_IMPROVING) {
            blockers += "Aún faltan puntos de corona; inclina el celular hacia la cima y mantén la parte alta al centro."
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
            if (input.topCoverageScore < completeTopCoverageThreshold && input.topCoverageState != TopCoverageState.TOP_OK) {
                blockers += "La corona aún no está bien muestreada para marcar medición completa."
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

        val canFinish = level == CompletenessLevel.ACCEPTABLE || level == CompletenessLevel.COMPLETE
        val autoCompletionCandidate =
            input.coverageRatio >= 0.95f &&
                input.coveredSectors >= 11 &&
                input.verticalCoverageScore >= 0.72f &&
                input.topCoverageScore >= 0.60f &&
                input.trajectoryQualityScore >= 0.72f &&
                input.isVolumeStable &&
                input.recentUsefulPointGrowthRatio <= 0.035f &&
                input.recentVolumeDeltaRatio <= 0.03f &&
                input.hasUsableDetection

        val hasCoverageForReview = input.coverageRatio >= 0.85f || input.coveredSectors >= 10
        val hasSamplingForReview = input.observerSamples >= 18 && input.usefulPointCount >= 600
        val hasModelForReview = input.hasReviewableModel || input.usefulPointCount >= 900
        val canReview = canFinish || (
            hasCoverageForReview &&
                hasSamplingForReview &&
                hasModelForReview &&
                input.hasUsableDetection
            )

        val shortGuidance = when {
            autoCompletionCandidate -> "Medición completa detectada. Ya puedes finalizar."
            input.missingLowerBand -> "Falta capturar mejor la base."
            input.topCoverageState == TopCoverageState.TOP_MISSING && input.missingUpperBand ->
                "Inclina el celular hacia la cima de la pila."
            input.topCoverageState == TopCoverageState.TOP_IMPROVING && input.topCoverageScore >= 0.56f ->
                "La cima ya es usable; mantén unos segundos para consolidar."
            input.topCoverageState == TopCoverageState.TOP_IMPROVING ->
                "La cima está mejorando; mantén el encuadre unos segundos."
            input.topCoverageScore < 0.55f -> "Da un paso atrás para capturar la corona completa."
            input.hasTrajectoryInstability -> "Recorrido con saltos; mueve el celular más parejo."
            canFinish -> "Medición completa. Ya puedes finalizar."
            canReview -> "Medición lista para revisión."
            input.coverageRatio >= 0.85f && input.weakVerticalBands > 0 -> "Cobertura suficiente, pero falta altura."
            else -> "Aún falta cobertura para revisión."
        }

        return MeasurementStateDecision(
            completeness = level,
            canReview = canReview,
            canFinish = canFinish,
            autoCompletionCandidate = autoCompletionCandidate,
            shortGuidance = shortGuidance,
            blockers = blockers.distinct()
        )
    }

    private fun CompletenessLevel.capAt(maximum: CompletenessLevel): CompletenessLevel {
        return if (this.ordinal > maximum.ordinal) maximum else this
    }
}

data class MeasurementStateInput(
    val baseCompleteness: CompletenessLevel,
    val coverageRatio: Float,
    val coveredSectors: Int,
    val observerSamples: Int,
    val usefulPointCount: Int,
    val trajectoryQualityScore: Float,
    val hasTrajectoryInstability: Boolean,
    val verticalCoverageScore: Float,
    val weakVerticalBands: Int,
    val missingLowerBand: Boolean,
    val missingUpperBand: Boolean,
    val supportsAcceptableVertical: Boolean,
    val hasStrongMiddleConcentration: Boolean,
    val isVolumeStable: Boolean,
    val topCoverageScore: Float,
    val topCoverageState: TopCoverageState = TopCoverageState.TOP_MISSING,
    val recentUsefulPointGrowthRatio: Float,
    val recentVolumeDeltaRatio: Float,
    val hasUsableDetection: Boolean,
    val hasReviewableModel: Boolean
)

data class MeasurementStateDecision(
    val completeness: CompletenessLevel,
    val canReview: Boolean,
    val canFinish: Boolean,
    val autoCompletionCandidate: Boolean,
    val shortGuidance: String,
    val blockers: List<String>
)

class VolumeStabilityEvaluator(
    private val windowSize: Int = 7,
    private val minSamples: Int = 5,
    private val maxIqrVariation: Double = 0.14,
    private val maxMadVariation: Double = 0.10,
    private val maxDriftRatio: Double = 0.08
) {

    fun evaluate(volumeSamples: List<Double>): VolumeStabilityResult {
        val window = volumeSamples.takeLast(windowSize)
        if (window.size < minSamples) {
            return VolumeStabilityResult(
                isStable = false,
                relativeVariation = 1.0,
                relativeIqr = 1.0,
                relativeMad = 1.0,
                driftRatio = 1.0,
                stabilityScore = 0.0,
                reasons = listOf("Aún no hay suficientes muestras para verificar estabilidad del volumen.")
            )
        }

        val sorted = window.sorted()
        val median = sorted.let {
            if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2.0
            } else {
                sorted[sorted.size / 2]
            }
        }.coerceAtLeast(1e-6)

        val q1 = sorted[(sorted.size - 1) / 4]
        val q3 = sorted[((sorted.size - 1) * 3) / 4]
        val iqr = (q3 - q1).coerceAtLeast(0.0)
        val mad = window.map { kotlin.math.abs(it - median) }.sorted().let { deviations ->
            if (deviations.size % 2 == 0) {
                (deviations[deviations.size / 2] + deviations[deviations.size / 2 - 1]) / 2.0
            } else deviations[deviations.size / 2]
        }

        val firstHalfMean = window.take(window.size / 2).average().takeIf { !it.isNaN() } ?: median
        val secondHalfMean = window.drop(window.size / 2).average().takeIf { !it.isNaN() } ?: median
        val driftRatio = kotlin.math.abs(secondHalfMean - firstHalfMean) / median

        val lowerFence = q1 - 1.5 * iqr
        val upperFence = q3 + 1.5 * iqr
        val trimmed = window.filter { it in lowerFence..upperFence }
        val min = trimmed.minOrNull() ?: q1
        val max = trimmed.maxOrNull() ?: q3
        val relativeVariation = ((max - min) / median).coerceAtLeast(0.0)
        val relativeIqr = (iqr / median).coerceAtLeast(0.0)
        val relativeMad = ((mad * 1.4826) / median).coerceAtLeast(0.0)
        val stabilityScore = (
            1.0 -
                (relativeIqr / maxIqrVariation) * 0.45 -
                (relativeMad / maxMadVariation) * 0.35 -
                (driftRatio / maxDriftRatio) * 0.20
            ).coerceIn(0.0, 1.0)
        val stable = relativeIqr <= maxIqrVariation &&
            relativeMad <= maxMadVariation &&
            driftRatio <= maxDriftRatio &&
            stabilityScore >= 0.70

        val reasons = if (stable) {
            emptyList()
        } else {
            listOf("El volumen aún varía demasiado entre muestras recientes.")
        }

        return VolumeStabilityResult(
            isStable = stable,
            relativeVariation = relativeVariation,
            relativeIqr = relativeIqr,
            relativeMad = relativeMad,
            driftRatio = driftRatio,
            stabilityScore = stabilityScore,
            reasons = reasons
        )
    }
}

data class VolumeStabilityResult(
    val isStable: Boolean,
    val relativeVariation: Double,
    val relativeIqr: Double,
    val relativeMad: Double,
    val driftRatio: Double,
    val stabilityScore: Double,
    val reasons: List<String>
)
