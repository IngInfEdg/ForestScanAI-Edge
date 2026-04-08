package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Evalúa la calidad geométrica de la trayectoria de medición (AR/GPS ya proyectada a XZ).
 */
class TrajectoryQualityEvaluator(
    private val totalSectors: Int = 12
) {

    fun evaluate(
        observerPath: List<Position> = emptyList(),
        trajectory: List<TrajectorySample> = emptyList()
    ): TrajectoryQualityResult {
        val samples = when {
            observerPath.isNotEmpty() -> observerPath.map { it.toSample() }
            trajectory.isNotEmpty() -> trajectory
            else -> emptyList()
        }

        if (samples.size < 3) {
            return TrajectoryQualityResult(
                trajectoryQualityScore = 0f,
                sampleCount = samples.size,
                loopClosure = LoopClosureAnalysis(
                    errorDistance = 0.0,
                    pathLength = 0.0,
                    normalizedError = 1.0,
                    score = 0f
                ),
                angularUniformity = AngularUniformityAnalysis(
                    sectorCounts = emptyList(),
                    coveredSectors = 0,
                    coverageRatio = 0f,
                    normalizedEntropy = 0f,
                    score = 0f
                ),
                kinematicConsistency = KinematicConsistencyAnalysis(
                    meanStep = 0.0,
                    stdStep = 0.0,
                    coefficientOfVariation = 1.0,
                    maxStep = 0.0,
                    jumpRatio = 0.0,
                    score = 0f
                ),
                penaltyFlags = setOf(TrajectoryPenalty.INSUFFICIENT_SAMPLES),
                penaltyReasons = listOf("Muestras insuficientes para evaluar trayectoria.")
            )
        }

        val loopClosure = analyzeLoopClosure(samples)
        val angular = analyzeAngularUniformity(samples)
        val kinematic = analyzeKinematicConsistency(samples)

        val trajectoryQualityScore = (
            loopClosure.score * 0.40f +
                angular.score * 0.35f +
                kinematic.score * 0.25f
            ).coerceIn(0f, 1f)

        val penalties = mutableSetOf<TrajectoryPenalty>()
        if (loopClosure.normalizedError > 0.25) penalties.add(TrajectoryPenalty.HIGH_LOOP_CLOSURE_ERROR)
        if (angular.coverageRatio < 0.60f || angular.normalizedEntropy < 0.65f) {
            penalties.add(TrajectoryPenalty.POOR_ANGULAR_UNIFORMITY)
        }
        if (kinematic.coefficientOfVariation > 1.0 || kinematic.jumpRatio > 3.0) {
            penalties.add(TrajectoryPenalty.LOW_KINEMATIC_CONSISTENCY)
        }

        val reasons = penalties.map { it.humanReadable }

        return TrajectoryQualityResult(
            trajectoryQualityScore = trajectoryQualityScore,
            sampleCount = samples.size,
            loopClosure = loopClosure,
            angularUniformity = angular,
            kinematicConsistency = kinematic,
            penaltyFlags = penalties,
            penaltyReasons = reasons
        )
    }

    private fun analyzeLoopClosure(samples: List<TrajectorySample>): LoopClosureAnalysis {
        val pathLength = samples.zipWithNext { a, b -> distance(a, b) }.sum()
        val closureError = distance(samples.first(), samples.last())

        val normalizedError = when {
            pathLength <= 1e-6 -> 1.0
            else -> (closureError / pathLength).coerceIn(0.0, 1.0)
        }

        val score = (1.0 - normalizedError).coerceIn(0.0, 1.0).toFloat()

        return LoopClosureAnalysis(
            errorDistance = closureError,
            pathLength = pathLength,
            normalizedError = normalizedError,
            score = score
        )
    }

    private fun analyzeAngularUniformity(samples: List<TrajectorySample>): AngularUniformityAnalysis {
        val centerX = samples.map { it.x }.average()
        val centerZ = samples.map { it.z }.average()

        val sectorCounts = IntArray(totalSectors)
        val sectorSize = (2.0 * PI) / totalSectors

        samples.forEach { sample ->
            var angle = atan2(sample.z - centerZ, sample.x - centerX)
            if (angle < 0.0) angle += 2.0 * PI
            val sector = (angle / sectorSize).toInt().coerceIn(0, totalSectors - 1)
            sectorCounts[sector] += 1
        }

        val coveredSectors = sectorCounts.count { it > 0 }
        val coverageRatio = (coveredSectors.toFloat() / totalSectors.toFloat()).coerceIn(0f, 1f)

        val n = samples.size.toDouble()
        val entropy = sectorCounts
            .filter { it > 0 }
            .map { it / n }
            .sumOf { p -> -p * ln(p) }

        val maxEntropy = ln(totalSectors.toDouble()).takeIf { it > 0.0 } ?: 1.0
        val normalizedEntropy = (entropy / maxEntropy).coerceIn(0.0, 1.0).toFloat()

        val score = (coverageRatio * 0.60f + normalizedEntropy * 0.40f).coerceIn(0f, 1f)

        return AngularUniformityAnalysis(
            sectorCounts = sectorCounts.toList(),
            coveredSectors = coveredSectors,
            coverageRatio = coverageRatio,
            normalizedEntropy = normalizedEntropy,
            score = score
        )
    }

    private fun analyzeKinematicConsistency(samples: List<TrajectorySample>): KinematicConsistencyAnalysis {
        val steps = samples.zipWithNext { a, b -> distance(a, b) }
        if (steps.isEmpty()) {
            return KinematicConsistencyAnalysis(
                meanStep = 0.0,
                stdStep = 0.0,
                coefficientOfVariation = 1.0,
                maxStep = 0.0,
                jumpRatio = 0.0,
                score = 0f
            )
        }

        val mean = steps.average()
        val variance = steps.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        val cv = if (mean <= 1e-6) 1.0 else std / mean
        val maxStep = steps.maxOrNull() ?: 0.0
        val jumpRatio = if (mean <= 1e-6) 0.0 else maxStep / mean

        val cvPenalty = (cv / 1.5).coerceIn(0.0, 1.0)
        val jumpPenalty = ((jumpRatio - 1.0) / 3.0).coerceIn(0.0, 1.0)

        val score = (1.0 - (cvPenalty * 0.7 + jumpPenalty * 0.3)).coerceIn(0.0, 1.0).toFloat()

        return KinematicConsistencyAnalysis(
            meanStep = mean,
            stdStep = std,
            coefficientOfVariation = cv,
            maxStep = maxStep,
            jumpRatio = jumpRatio,
            score = score
        )
    }

    private fun distance(a: TrajectorySample, b: TrajectorySample): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun Position.toSample(): TrajectorySample =
        TrajectorySample(x = x.toDouble(), z = z.toDouble(), source = TrajectorySource.AR_OBSERVER)
}

enum class TrajectorySource {
    AR_OBSERVER,
    GPS_PROJECTED
}

data class TrajectorySample(
    val x: Double,
    val z: Double,
    val source: TrajectorySource = TrajectorySource.AR_OBSERVER
)

enum class TrajectoryPenalty(val humanReadable: String) {
    INSUFFICIENT_SAMPLES("Muestras insuficientes en la trayectoria."),
    HIGH_LOOP_CLOSURE_ERROR("Error alto de cierre de la trayectoria (loop closure)."),
    POOR_ANGULAR_UNIFORMITY("Cobertura/uniformidad angular insuficiente alrededor de la pila."),
    LOW_KINEMATIC_CONSISTENCY("Movimiento con saltos o velocidad poco consistente.")
}

data class TrajectoryQualityResult(
    val trajectoryQualityScore: Float,
    val sampleCount: Int,
    val loopClosure: LoopClosureAnalysis,
    val angularUniformity: AngularUniformityAnalysis,
    val kinematicConsistency: KinematicConsistencyAnalysis,
    val penaltyFlags: Set<TrajectoryPenalty>,
    val penaltyReasons: List<String>
)

data class LoopClosureAnalysis(
    val errorDistance: Double,
    val pathLength: Double,
    val normalizedError: Double,
    val score: Float
)

data class AngularUniformityAnalysis(
    val sectorCounts: List<Int>,
    val coveredSectors: Int,
    val coverageRatio: Float,
    val normalizedEntropy: Float,
    val score: Float
)

data class KinematicConsistencyAnalysis(
    val meanStep: Double,
    val stdStep: Double,
    val coefficientOfVariation: Double,
    val maxStep: Double,
    val jumpRatio: Double,
    val score: Float
)
