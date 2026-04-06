package com.forest.scanai.domain.engine

import com.forest.scanai.core.ScanParams
import com.google.ar.core.Frame
import io.github.sceneview.math.Position
import kotlin.math.sqrt

class PointCloudProcessor(private val params: ScanParams) {
    fun extractFilteredPoints(
        frame: Frame,
        cameraPos: Position,
        voxelGrid: MutableSet<String>
    ): List<Position> {
        val filteredPoints = mutableListOf<Position>()
        try {
            val pointCloud = frame.acquirePointCloud()
            val buffer = pointCloud.points
            val count = buffer.remaining() / 4

            for (i in 0 until count step 20) {
                val x = buffer.get(i * 4)
                val y = buffer.get(i * 4 + 1)
                val z = buffer.get(i * 4 + 2)
                val confidence = buffer.get(i * 4 + 3)

                val dx = x - cameraPos.x
                val dy = y - cameraPos.y
                val dz = z - cameraPos.z
                val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble())

                // strict filter: ignore background vegetation
                if (confidence > params.confidenceThreshold && dist in params.minDepth..params.maxDepth) {
                    val key = "${(x / params.voxelSize).toInt()},${(y / params.voxelSize).toInt()},${(z / params.voxelSize).toInt()}"
                    if (voxelGrid.add(key)) {
                        filteredPoints.add(Position(x, y, z))
                    }
                }
            }
            pointCloud.release()
        } catch (e: Exception) {
            // Log error
        }
        return filteredPoints
    }
}
