package com.forest.scanai.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.atan2
import kotlin.math.sqrt

data class PileAxisResult(
    val centroidX: Float,
    val centroidZ: Float,
    val axisX: Float,
    val axisZ: Float,
    val perpX: Float,
    val perpZ: Float,
    val minAlong: Float,
    val maxAlong: Float,
    val minCross: Float,
    val maxCross: Float,
    val length: Double,
    val width: Double,
    val confidence: Float
)

class PileAxisEstimator {

    fun estimate(points: List<Position>): PileAxisResult? {
        if (points.size < 20) return null

        val centroidX = points.map { it.x.toDouble() }.average().toFloat()
        val centroidZ = points.map { it.z.toDouble() }.average().toFloat()

        var sxx = 0.0
        var szz = 0.0
        var sxz = 0.0

        for (p in points) {
            val dx = p.x - centroidX
            val dz = p.z - centroidZ
            sxx += dx * dx
            szz += dz * dz
            sxz += dx * dz
        }

        if (points.size > 1) {
            val denom = (points.size - 1).toDouble()
            sxx /= denom
            szz /= denom
            sxz /= denom
        }

        val angle = 0.5 * atan2(2.0 * sxz, sxx - szz)
        val axisX = kotlin.math.cos(angle).toFloat()
        val axisZ = kotlin.math.sin(angle).toFloat()

        val norm = sqrt(axisX * axisX + axisZ * axisZ)
        if (norm <= 1e-6f) return null

        val ux = axisX / norm
        val uz = axisZ / norm

        val vx = -uz
        val vz = ux

        var minAlong = Float.POSITIVE_INFINITY
        var maxAlong = Float.NEGATIVE_INFINITY
        var minCross = Float.POSITIVE_INFINITY
        var maxCross = Float.NEGATIVE_INFINITY

        for (p in points) {
            val dx = p.x - centroidX
            val dz = p.z - centroidZ

            val along = dx * ux + dz * uz
            val cross = dx * vx + dz * vz

            if (along < minAlong) minAlong = along
            if (along > maxAlong) maxAlong = along
            if (cross < minCross) minCross = cross
            if (cross > maxCross) maxCross = cross
        }

        val trace = sxx + szz
        val detTerm = kotlin.math.sqrt(((sxx - szz) * (sxx - szz)) + 4.0 * sxz * sxz)
        val lambda1 = (trace + detTerm) / 2.0
        val lambda2 = (trace - detTerm) / 2.0
        val confidence = if (lambda1 > 1e-6) {
            (lambda1 / (lambda1 + kotlin.math.max(lambda2, 1e-6))).toFloat()
        } else {
            0f
        }

        return PileAxisResult(
            centroidX = centroidX,
            centroidZ = centroidZ,
            axisX = ux,
            axisZ = uz,
            perpX = vx,
            perpZ = vz,
            minAlong = minAlong,
            maxAlong = maxAlong,
            minCross = minCross,
            maxCross = maxCross,
            length = (maxAlong - minAlong).toDouble(),
            width = (maxCross - minCross).toDouble(),
            confidence = confidence
        )
    }

    fun projectAlong(point: Position, axis: PileAxisResult): Float {
        val dx = point.x - axis.centroidX
        val dz = point.z - axis.centroidZ
        return dx * axis.axisX + dz * axis.axisZ
    }

    fun projectCross(point: Position, axis: PileAxisResult): Float {
        val dx = point.x - axis.centroidX
        val dz = point.z - axis.centroidZ
        return dx * axis.perpX + dz * axis.perpZ
    }

    fun pointOnAxis(axis: PileAxisResult, along: Float, y: Float): Position {
        val x = axis.centroidX + axis.axisX * along
        val z = axis.centroidZ + axis.axisZ * along
        return Position(x, y, z)
    }
}