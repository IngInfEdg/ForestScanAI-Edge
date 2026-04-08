package com.forest.scanai.domain.engine

import com.forest.scanai.core.ScanParams
import com.google.ar.core.Frame
import android.util.Log
import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.sqrt

class PointCloudProcessor(private val params: ScanParams) {

    fun extractFilteredPoints(
        frame: Frame,
        cameraPos: Position,
        voxelGrid: MutableSet<String>
    ): List<Position> {
        val filteredPoints = mutableListOf<Position>()
        var pointCloud: com.google.ar.core.PointCloud? = null

        try {
            pointCloud = frame.acquirePointCloud()
            val buffer = pointCloud.points
            val count = buffer.remaining() / 4

            if (count <= 0) {
                return emptyList()
            }

            val dynamicStep = when {
                count > 4000 -> 6
                count > 2500 -> 4
                count > 1200 -> 3
                else -> 2
            }

            val effectiveVoxel = params.voxelSize.coerceAtMost(0.08f)
            val minDistance = params.minDepth.coerceAtLeast(0.45)
            val maxDistance = params.maxDepth.coerceAtMost(5.5)

            for (i in 0 until count step dynamicStep) {
                val base = i * 4
                if (base + 3 >= buffer.limit()) break

                val x = buffer.get(base)
                val y = buffer.get(base + 1)
                val z = buffer.get(base + 2)
                val confidence = buffer.get(base + 3)

                if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !confidence.isFinite()) {
                    continue
                }

                if (confidence < params.confidenceThreshold) {
                    continue
                }

                val dx = x - cameraPos.x
                val dy = y - cameraPos.y
                val dz = z - cameraPos.z

                // 🔧 CORREGIDO: usar Double en sqrt
                val horizontalDistance = sqrt((dx * dx + dz * dz).toDouble()).toFloat()
                val spatialDistance = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                val verticalDelta = y - cameraPos.y
                val absVerticalDelta = abs(verticalDelta)

                if (horizontalDistance < minDistance.toFloat() || horizontalDistance > maxDistance.toFloat()) {
                    continue
                }
                if (spatialDistance < minDistance.toFloat() || spatialDistance > (maxDistance + 1.0).toFloat()) {
                    continue
                }

                if (absVerticalDelta > 3.5f) {
                    continue
                }

                if (horizontalDistance > 3.8f && confidence < 0.5f) {
                    continue
                }

                if (absVerticalDelta < 0.03f && horizontalDistance > 1.2f) {
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
        } catch (e: Exception) {
            Log.w("PointCloudProcessor", "Failed to extract AR points: ${e.message}")
        } finally {
            pointCloud?.release()
        }

        return filteredPoints
    }
}
