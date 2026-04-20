package com.forest.scanai.edge.domain.engine

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
        assertTrue("Top coverage debería ser alto en nube balanceada", result.topCoverageScore >= 0.60f)
        assertTrue("Estado de corona debería marcar TOP_OK", result.topCoverageState == TopCoverageState.TOP_OK)
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

    @Test
    fun sparseTopBand_shouldTriggerTopSurfaceSparsePenalty() {
        val points = buildBandPoints(lower = 140, middle = 140, upper = 20)

        val result = evaluator.evaluate(points)

        assertTrue(
            "Top muy disperso debe penalizarse",
            VerticalCoveragePenalty.TOP_SURFACE_SPARSE in result.penaltyFlags
        )
        assertTrue("Top coverage score debe reflejar falta de densidad", result.topCoverageScore < 0.55f)
        assertTrue("Estado debe indicar cima faltante", result.topCoverageState == TopCoverageState.TOP_MISSING)
    }

    @Test
    fun improvingRecentTopCoverage_shouldClassifyAsImproving() {
        val points = buildBandPoints(lower = 120, middle = 120, upper = 38)
        val recent = listOf(0.21f, 0.29f, 0.34f, 0.37f)

        val result = evaluator.evaluate(points, recentTopCoverageScores = recent)

        assertTrue("Debe detectar estado intermedio TOP_IMPROVING", result.topCoverageState == TopCoverageState.TOP_IMPROVING)
        assertTrue("La tendencia reciente debe ser positiva", result.topCoverageTrend > 0f)
    }


    @Test
    fun sustainedTopImprovement_shouldPromoteToTopOk() {
        val points = buildBandPoints(lower = 105, middle = 120, upper = 70)
        val recent = listOf(0.40f, 0.48f, 0.55f, 0.61f, 0.65f, 0.67f)

        val result = evaluator.evaluate(points, recentTopCoverageScores = recent)

        assertTrue("Con mejora sostenida la cima debe quedar en TOP_OK", result.topCoverageState == TopCoverageState.TOP_OK)
        assertTrue("Top coverage debería ser suficientemente alto", result.topCoverageScore >= 0.60f)
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
