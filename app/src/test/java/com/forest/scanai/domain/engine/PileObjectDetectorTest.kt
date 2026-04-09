package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PileObjectDetectorTest {

    private val detector = PileObjectDetector(
        minPointCount = 80,
        minClusterSize = 25,
        dbscanEps = 0.32f,
        dbscanMinPts = 8
    )

    @Test
    fun `estima suelo simple con plano cercano`() {
        val points = syntheticGroundAndPile(
            groundCount = 180,
            pileCenter = Position(0.8f, 0.6f, 0.8f),
            pileSpread = 0.5f,
            pileCount = 260
        )

        val result = detector.detect(points)
        val plane = result.groundPlane

        assertNotNull(plane)
        assertTrue(result.groundPoints.size > 120)
        assertTrue((plane?.fitError ?: 1f) < 0.08f)
    }

    @Test
    fun `separa suelo y no suelo`() {
        val points = syntheticGroundAndPile(220, Position(1.2f, 0.7f, 1.0f), 0.55f, 320)
        val result = detector.detect(points)

        assertTrue(result.groundPoints.isNotEmpty())
        assertTrue(result.nonGroundPoints.isNotEmpty())
        assertTrue(result.nonGroundPoints.size > result.groundPoints.size / 2)
    }

    @Test
    fun `identifica cluster principal`() {
        val points = syntheticGroundAndPile(200, Position(0.2f, 0.7f, 0.1f), 0.6f, 360)
        val result = detector.detect(points, observerPath = listOf(Position(0f, 1.3f, 0f)))

        assertNotNull(result.primaryClusterId)
        assertTrue(result.pilePoints.size >= 200)
        assertTrue(result.detectionConfidence >= 0.5f)
    }

    @Test
    fun `con varios clusters gana el principal`() {
        val base = syntheticGround(180)
        val mainCluster = syntheticPile(Position(0.4f, 0.7f, 0.2f), 0.55f, 340)
        val smallCluster = syntheticPile(Position(3.2f, 0.5f, 3.0f), 0.35f, 70)

        val result = detector.detect(base + mainCluster + smallCluster, observerPath = listOf(Position(0f, 1.4f, 0f)))

        assertTrue(result.clusters.size >= 2)
        assertTrue(result.pilePoints.size > 250)
        assertTrue(result.boundingBox!!.centroid.x < 1.5f)
    }

    @Test
    fun `con pocos puntos usa fallback`() {
        val sparse = syntheticGroundAndPile(20, Position(0.2f, 0.4f, 0.2f), 0.25f, 30)
        val result = detector.detect(sparse)

        assertEquals(PileDetectionQuality.FALLBACK, result.quality)
        assertFalse(result.isRobust)
        assertTrue(result.reasons.any { it.contains("Pocos puntos") })
    }

    @Test
    fun `nube sesgada no debe tener alta confianza`() {
        val skewedGround = (0 until 240).map { i ->
            val x = i / 120f
            Position(x, 0.02f * x, 0.05f)
        }
        val narrowPile = (0 until 120).map { i ->
            val t = i / 120f
            Position(0.7f + 0.06f * t, 0.35f + 0.15f * t, 0.06f)
        }

        val result = detector.detect(skewedGround + narrowPile)

        assertTrue(result.detectionConfidence < 0.75f)
        assertFalse(result.quality == PileDetectionQuality.HIGH)
    }

    private fun syntheticGroundAndPile(
        groundCount: Int,
        pileCenter: Position,
        pileSpread: Float,
        pileCount: Int
    ): List<Position> {
        return syntheticGround(groundCount) + syntheticPile(pileCenter, pileSpread, pileCount)
    }

    private fun syntheticGround(count: Int): List<Position> {
        return (0 until count).map { i ->
            val x = (i % 20) * 0.15f
            val z = (i / 20) * 0.15f
            val y = 0.015f * x + 0.01f * z + jitter(i, 0.012f)
            Position(x, y, z)
        }
    }

    private fun syntheticPile(center: Position, spread: Float, count: Int): List<Position> {
        return (0 until count).map { i ->
            val angle = (i % 36) * (Math.PI / 18.0)
            val radius = (0.1f + (i % 17) / 17f * spread)
            val x = center.x + kotlin.math.cos(angle).toFloat() * radius + jitter(i, 0.03f)
            val z = center.z + kotlin.math.sin(angle).toFloat() * radius + jitter(i + 3, 0.03f)
            val heightFactor = 1f - (radius / spread).coerceIn(0f, 1f)
            val y = center.y + heightFactor * 0.9f + jitter(i + 5, 0.04f)
            Position(x, y, z)
        }
    }

    private fun jitter(seed: Int, scale: Float): Float {
        val normalized = ((seed * 1103515245 + 12345) and 0x7fffffff) / Int.MAX_VALUE.toFloat()
        return (normalized - 0.5f) * 2f * scale
    }
}
