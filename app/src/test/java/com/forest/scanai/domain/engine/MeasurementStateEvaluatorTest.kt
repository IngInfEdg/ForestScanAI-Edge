package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.CompletenessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementStateEvaluatorTest {

    private val evaluator = MeasurementStateEvaluator()
    private val volumeStabilityEvaluator = VolumeStabilityEvaluator()

    @Test
    fun acceptableTrajectoryButPoorVertical_shouldNotBeComplete() {
        val decision = evaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = CompletenessLevel.COMPLETE,
                coverageRatio = 0.93f,
                coveredSectors = 11,
                observerSamples = 42,
                usefulPointCount = 2100,
                trajectoryQualityScore = 0.82f,
                hasTrajectoryInstability = false,
                verticalCoverageScore = 0.52f,
                weakVerticalBands = 2,
                missingLowerBand = false,
                missingUpperBand = true,
                supportsAcceptableVertical = false,
                hasStrongMiddleConcentration = true,
                isVolumeStable = true,
                topCoverageScore = 0.40f,
                recentUsefulPointGrowthRatio = 0.12f,
                recentVolumeDeltaRatio = 0.09f,
                hasUsableDetection = true,
                hasReviewableModel = true
            )
        )

        assertTrue("No debe quedar en COMPLETE con vertical pobre", decision.completeness != CompletenessLevel.COMPLETE)
        assertEquals("Debe degradar a PARTIAL cuando cobertura vertical es mala", CompletenessLevel.PARTIAL, decision.completeness)
    }

    @Test
    fun unstableVolume_shouldBlockComplete() {
        val stability = volumeStabilityEvaluator.evaluate(
            listOf(10.0, 12.8, 8.9, 13.1, 9.2, 12.5, 8.7)
        )

        assertFalse("La serie debe considerarse inestable", stability.isStable)

        val decision = evaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = CompletenessLevel.COMPLETE,
                coverageRatio = 0.95f,
                coveredSectors = 12,
                observerSamples = 48,
                usefulPointCount = 2500,
                trajectoryQualityScore = 0.9f,
                hasTrajectoryInstability = false,
                verticalCoverageScore = 0.85f,
                weakVerticalBands = 0,
                missingLowerBand = false,
                missingUpperBand = false,
                supportsAcceptableVertical = true,
                hasStrongMiddleConcentration = false,
                isVolumeStable = stability.isStable,
                topCoverageScore = 0.78f,
                recentUsefulPointGrowthRatio = 0.05f,
                recentVolumeDeltaRatio = 0.06f,
                hasUsableDetection = true,
                hasReviewableModel = true
            )
        )

        assertEquals("Volumen inestable no permite COMPLETE", CompletenessLevel.ACCEPTABLE, decision.completeness)
        assertTrue("Debe incluir mensaje de bloqueo", decision.blockers.any { it.contains("volumen", ignoreCase = true) })
    }

    @Test
    fun stableAndFullyCoveredMeasurement_shouldEnableAutoCompletionCandidate() {
        val decision = evaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = CompletenessLevel.COMPLETE,
                coverageRatio = 0.97f,
                coveredSectors = 12,
                observerSamples = 52,
                usefulPointCount = 2800,
                trajectoryQualityScore = 0.88f,
                hasTrajectoryInstability = false,
                verticalCoverageScore = 0.84f,
                weakVerticalBands = 0,
                missingLowerBand = false,
                missingUpperBand = false,
                supportsAcceptableVertical = true,
                hasStrongMiddleConcentration = false,
                isVolumeStable = true,
                topCoverageScore = 0.81f,
                recentUsefulPointGrowthRatio = 0.02f,
                recentVolumeDeltaRatio = 0.015f,
                hasUsableDetection = true,
                hasReviewableModel = true
            )
        )

        assertTrue("Debe sugerir finalización automática cuando ya no hay mejoras relevantes", decision.autoCompletionCandidate)
    }
}
