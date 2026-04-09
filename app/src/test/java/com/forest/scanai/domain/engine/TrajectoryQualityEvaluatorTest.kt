package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TrajectoryQualityEvaluatorTest {

    private val evaluator = TrajectoryQualityEvaluator(totalSectors = 12)

    @Test
    fun closedStablePath_shouldPassWithHighScore() {
        val path = circularPath(radius = 2.0f, samples = 72, closeLoop = true)

        val result = evaluator.evaluate(observerPath = path)

        assertTrue("Score esperado alto para trayectoria cerrada estable", result.trajectoryQualityScore >= 0.75f)
        assertTrue("No debería tener penalización de loop closure", TrajectoryPenalty.HIGH_LOOP_CLOSURE_ERROR !in result.penaltyFlags)
        assertTrue("No debería tener penalización angular", TrajectoryPenalty.POOR_ANGULAR_UNIFORMITY !in result.penaltyFlags)
    }

    @Test
    fun semicircularIncompletePath_shouldBePenalized() {
        val path = arcPath(radius = 2.0f, samples = 36, startAngle = 0.0, endAngle = PI)

        val result = evaluator.evaluate(observerPath = path)

        assertTrue("Trayectoria semicircular debe penalizarse en angular", TrajectoryPenalty.POOR_ANGULAR_UNIFORMITY in result.penaltyFlags)
        assertTrue("Score esperado medio/bajo para trayectoria incompleta", result.trajectoryQualityScore < 0.70f)
    }

    @Test
    fun closedPathWithStrongDrift_shouldBePenalized() {
        val mostlyCircle = circularPath(radius = 2.0f, samples = 60, closeLoop = false).toMutableList()
        val last = mostlyCircle.last()
        // drift fuerte: fin muy lejos del inicio
        mostlyCircle[mostlyCircle.lastIndex] = Position(last.x + 2.5f, last.y, last.z + 2.5f)

        val result = evaluator.evaluate(observerPath = mostlyCircle)

        assertTrue("Debe detectar loop closure alto", TrajectoryPenalty.HIGH_LOOP_CLOSURE_ERROR in result.penaltyFlags)
        assertTrue("Score debe caer por drift", result.trajectoryQualityScore < 0.75f)
        assertFalse("No debe aprobar como trayectoria ideal", result.penaltyFlags.isEmpty())
    }

    private fun circularPath(radius: Float, samples: Int, closeLoop: Boolean): List<Position> {
        val points = mutableListOf<Position>()
        val total = if (closeLoop) samples else samples - 1
        for (i in 0 until total) {
            val t = (2.0 * PI * i) / samples
            points.add(Position((radius * cos(t)).toFloat(), 0f, (radius * sin(t)).toFloat()))
        }
        if (closeLoop && points.isNotEmpty()) {
            points.add(points.first())
        }
        return points
    }

    private fun arcPath(radius: Float, samples: Int, startAngle: Double, endAngle: Double): List<Position> {
        val points = mutableListOf<Position>()
        for (i in 0 until samples) {
            val t = startAngle + (endAngle - startAngle) * i / (samples - 1).coerceAtLeast(1)
            points.add(Position((radius * cos(t)).toFloat(), 0f, (radius * sin(t)).toFloat()))
        }
        return points
    }
}
