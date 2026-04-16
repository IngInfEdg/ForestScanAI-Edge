package com.forest.scanai.domain.engine

import com.forest.scanai.domain.model.CompletenessLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGuidanceEngineTest {

    private val engine = ScanGuidanceEngine()

    @Test
    fun missingTop_shouldAskToTiltTowardsTop() {
        val message = engine.buildMessage(
            completeness = CompletenessLevel.INSUFFICIENT,
            missingSectors = emptyList(),
            observerSamples = 22,
            usefulPoints = 1200,
            missingUpperBand = true,
            lowTopCoverage = true,
            topCoverageState = TopCoverageState.TOP_MISSING
        )

        assertTrue(message.contains("cima", ignoreCase = true))
    }

    @Test
    fun improvingTop_shouldEncourageHoldAngle() {
        val message = engine.buildMessage(
            completeness = CompletenessLevel.PARTIAL,
            missingSectors = emptyList(),
            observerSamples = 26,
            usefulPoints = 1500,
            topCoverageState = TopCoverageState.TOP_IMPROVING
        )

        assertTrue(message.contains("mejorando", ignoreCase = true))
    }
}
