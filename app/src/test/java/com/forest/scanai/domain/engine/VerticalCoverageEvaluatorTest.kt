package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalCoverageEvaluatorTest {

    private val evaluator = VerticalCoverageEvaluator(minPointsForEvaluation = 60)

    @Test
    fun balancedCloudAcrossThreeBands_shouldReturnHighScore() {
        val points = buildBandPoints(lower = 80, middle = 80, upper = 80)

        val result = evaluator.evaluate(points)

        assertTrue("Score vertical alto esperado para nube balanceada", result.verticalCoverageScore >= 0.75f)
        assertTrue("Debe cubrir 3 bandas", result.coveredBands.size == 3)
        assertFalse("No debería penalizar dominancia media", VerticalCoveragePenalty.MIDDLE_DOMINANCE in result.penaltyFlags)
    }

    @Test
    fun middleDominatedCloud_shouldBePenalized() {
        val points = buildBandPoints(lower = 15, middle = 220, upper = 15)

        val result = evaluator.evaluate(points)

        assertTrue("Debe penalizar dominancia de franja media", VerticalCoveragePenalty.MIDDLE_DOMINANCE in result.penaltyFlags)
        assertTrue("Score debe caer por concentración", result.verticalCoverageScore < 0.60f)
    }

    @Test
    fun cloudWithoutUpperBand_shouldNotSupportComplete() {
        val points = buildBandPoints(lower = 120, middle = 120, upper = 0)

        val result = evaluator.evaluate(points)

        assertTrue("Debe reportar banda superior faltante", VerticalCoveragePenalty.MISSING_UPPER_BAND in result.penaltyFlags)
        assertFalse("Sin banda superior no puede soportar COMPLETE", result.supportsComplete)
    }

    private fun buildBandPoints(lower: Int, middle: Int, upper: Int): List<Position> {
        val points = mutableListOf<Position>()

        repeat(lower) { idx ->
            points += Position(x = (idx % 10) * 0.05f, y = 0.05f + (idx % 6) * 0.03f, z = (idx % 12) * 0.04f)
        }
        repeat(middle) { idx ->
            points += Position(x = (idx % 10) * 0.05f, y = 0.45f + (idx % 6) * 0.04f, z = (idx % 12) * 0.04f)
        }
        repeat(upper) { idx ->
            points += Position(x = (idx % 10) * 0.05f, y = 0.82f + (idx % 6) * 0.03f, z = (idx % 12) * 0.04f)
        }

        return points
    }
}
