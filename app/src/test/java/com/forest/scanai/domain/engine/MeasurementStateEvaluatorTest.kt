package com.forest.scanai.domain.engine

import com.forest.scanai.domain.model.CompletenessLevel
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
                trajectoryQualityScore = 0.82f,
                verticalCoverageScore = 0.52f,
                weakVerticalBands = 2,
                supportsAcceptableVertical = false,
                hasStrongMiddleConcentration = true,
                isVolumeStable = true
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
                trajectoryQualityScore = 0.9f,
                verticalCoverageScore = 0.85f,
                weakVerticalBands = 0,
                supportsAcceptableVertical = true,
                hasStrongMiddleConcentration = false,
                isVolumeStable = stability.isStable
            )
        )

        assertEquals("Volumen inestable no permite COMPLETE", CompletenessLevel.ACCEPTABLE, decision.completeness)
        assertTrue("Debe incluir mensaje de bloqueo", decision.blockers.any { it.contains("volumen", ignoreCase = true) })
    }
}
