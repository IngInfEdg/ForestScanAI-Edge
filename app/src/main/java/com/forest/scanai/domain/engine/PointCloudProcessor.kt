package com.forest.scanai.domain.engine

import android.util.Log
import com.forest.scanai.core.ScanParams
import com.google.ar.core.Frame
import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.sqrt

data class PointCloudExtractionStats(
    val rawPoints: Int,
    val sampledPoints: Int,
    val acceptedPoints: Int,
    val rejectedByConfidence: Int,
    val rejectedByDistance: Int,
    val rejectedByVerticalGate: Int,
    val rejectedByNoiseGate: Int
)

class PointCloudProcessor(private val params: ScanParams) {

    var lastStats: PointCloudExtractionStats = PointCloudExtractionStats(0, 0, 0, 0, 0, 0, 0)
        private set

    fun extractFilteredPoints(
        frame: Frame,
        cameraPos: Position,
        voxelGrid: MutableSet<String>
    ): List<Position> {
        val filteredPoints = mutableListOf<Position>()
        var pointCloud: com.google.ar.core.PointCloud? = null

        var sampled = 0
        var rejectedConfidence = 0
        var rejectedDistance = 0
        var rejectedVertical = 0
        var rejectedNoise = 0

        try {
            pointCloud = frame.acquirePointCloud()
            val buffer = pointCloud.points
            val count = buffer.remaining() / 4

            if (count <= 0) {
                lastStats = PointCloudExtractionStats(0, 0, 0, 0, 0, 0, 0)
                return emptyList()
            }

            val dynamicStep = when {
                count > 6000 -> 6
                count > 3500 -> 4
                count > 1800 -> 3
                else -> 2
            }

            val effectiveVoxel = params.voxelSize.coerceAtMost(0.10f)
            val minDistance = params.minDepth.coerceAtLeast(0.35)
            val maxDistance = params.maxDepth.coerceAtMost(6.5)

            for (i in 0 until count step dynamicStep) {
                sampled++
                val base = i * 4
                if (base + 3 >= buffer.limit()) break

                val x = buffer.get(base)
                val y = buffer.get(base + 1)
                val z = buffer.get(base + 2)
                val confidence = buffer.get(base + 3)

                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !confidence.isFinite()) {
                    continue
                }

                if (confidence < (params.confidenceThreshold * 0.85f)) {
                    rejectedConfidence++
                    continue
                }

                val dx = x - cameraPos.x
                val dy = y - cameraPos.y
                val dz = z - cameraPos.z

                val horizontalDistance = sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                val spatialDistance = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                val verticalDelta = y - cameraPos.y
                val absVerticalDelta = abs(verticalDelta)

                if (horizontalDistance < minDistance.toFloat() || horizontalDistance > maxDistance.toFloat()) {
                    rejectedDistance++
                    continue
                }
                if (spatialDistance < minDistance.toFloat() || spatialDistance > (maxDistance + 1.5).toFloat()) {
                    rejectedDistance++
                    continue
                }

                // Menos agresivo para conservar corona/base y delegar limpieza a la segmentación.
                if (absVerticalDelta > 4.5f) {
                    rejectedVertical++
                    continue
                }

                if (horizontalDistance > 5.0f && confidence < 0.45f) {
                    rejectedNoise++
                    continue
                }

                val voxelX = (x / effectiveVoxel).toInt()
                val voxelY = (y / effectiveVoxel).toInt()
                val voxelZ = (z / effectiveVoxel).toInt()
                val key = "$voxelX,$voxelY,$voxelZ"

                if (voxelGrid.add(key)) {
                    filteredPoints.add(Position(x, y, z))
                }
            }

            lastStats = PointCloudExtractionStats(
                rawPoints = count,
                sampledPoints = sampled,
                acceptedPoints = filteredPoints.size,
                rejectedByConfidence = rejectedConfidence,
                rejectedByDistance = rejectedDistance,
                rejectedByVerticalGate = rejectedVertical,
                rejectedByNoiseGate = rejectedNoise
            )
        } catch (e: Exception) {
            Log.w("PointCloudProcessor", "Failed to extract AR points: ${e.message}")
        } finally {
            pointCloud?.release()
        }

        return filteredPoints
    }
}