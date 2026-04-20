package com.forest.scanai.edge.domain.engine

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class ReviewPoint3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val isPile: Boolean
)

data class ReviewModel3D(
    val points: List<ReviewPoint3D>,
    val width: Float,
    val height: Float,
    val depth: Float
)

class PointCloudReviewModelBuilder {

    fun build(segmented: SegmentedPileResult): ReviewModel3D {
        val reviewPoints = segmented.classifiedPoints.map { point ->
            ReviewPoint3D(
                x = point.along,
                y = point.relativeHeight,
                z = point.cross,
                isPile = point.pointClass == PointClass.PILE
            )
        }

        if (reviewPoints.isEmpty()) {
            return ReviewModel3D(
                points = emptyList(),
                width = 0f,
                height = 0f,
                depth = 0f
            )
        }

        val xs = reviewPoints.map { it.x }
        val ys = reviewPoints.map { it.y }
        val zs = reviewPoints.map { it.z }

        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 0f
        val minZ = zs.minOrNull() ?: 0f
        val maxZ = zs.maxOrNull() ?: 0f

        val centeredPoints = reviewPoints.map { point ->
            ReviewPoint3D(
                x = point.x - minX - ((maxX - minX) / 2f),
                y = point.y - minY,
                z = point.z - minZ - ((maxZ - minZ) / 2f),
                isPile = point.isPile
            )
        }

        return ReviewModel3D(
            points = centeredPoints,
            width = maxX - minX,
            height = maxY - minY,
            depth = maxZ - minZ
        )
    }

    fun rotate(point: ReviewPoint3D, yaw: Float, pitch: Float): ReviewPoint3D {
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)

        val x1 = point.x * cosYaw - point.z * sinYaw
        val z1 = point.x * sinYaw + point.z * cosYaw

        val y2 = point.y * cosPitch - z1 * sinPitch
        val z2 = point.y * sinPitch + z1 * cosPitch

        return ReviewPoint3D(
            x = x1,
            y = y2,
            z = z2,
            isPile = point.isPile
        )
    }

    fun maxRange(points: List<ReviewPoint3D>): Float {
        if (points.isEmpty()) return 1f

        return max(
            1f,
            max(
                points.maxOf { kotlin.math.abs(it.x) },
                max(
                    points.maxOf { kotlin.math.abs(it.y) },
                    points.maxOf { kotlin.math.abs(it.z) }
                )
            )
        )
    }
}