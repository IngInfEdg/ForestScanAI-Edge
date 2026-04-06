package com.forest.scanai.domain.engine

import com.forest.scanai.core.ScanParams
import com.forest.scanai.domain.model.ScanResult
import io.github.sceneview.math.Position

class VolumeCalculator(private val params: ScanParams) {
    fun calculate(points: List<Position>): ScanResult {
        if (points.size < 200) return ScanResult(0.0, emptyList())
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val length = maxX - minX
        if (length < 0.2) return ScanResult(0.0, emptyList())

        val sliceWidth = params.sliceWidth
        val numSlices = (length / sliceWidth).toInt()
        val sliceAreas = mutableListOf<Double>()
        val rawTopPoints = mutableListOf<Position>()

        for (i in 0 until numSlices) {
            val startX = minX + i * sliceWidth
            val pointsInSlice = points.filter { it.x in startX..(startX + sliceWidth) }
            
            if (pointsInSlice.size >= 10) {
                // Robust Base: Percentile 10 to ignore dirt/debris holes
                val sortedY = pointsInSlice.map { it.y }.sorted()
                val groundLevel = sortedY[sortedY.size / 10]
                
                // Zenith Filter: Percentile 95 to ignore isolated floating points (noise)
                val topLevel = sortedY[(sortedY.size * 0.95).toInt()]
                
                // Zenith Point for visualization
                val topPoint = pointsInSlice.filter { it.y >= topLevel }.maxByOrNull { it.y } ?: pointsInSlice.maxBy { it.y }
                
                // Only consider points clearly above ground margin (brush/grass)
                if (topLevel - groundLevel > params.groundMargin) {
                    rawTopPoints.add(topPoint)
                    val sliceHeight = (topLevel - groundLevel).toDouble()
                    val sliceDepth = (pointsInSlice.maxOf { it.z } - pointsInSlice.minOf { it.z })
                        .toDouble().coerceAtMost(params.maxPileDepth)
                    
                    sliceAreas.add(sliceHeight * sliceDepth)
                } else {
                    sliceAreas.add(0.0)
                    rawTopPoints.add(Position(startX + sliceWidth / 2, groundLevel, 0f))
                }
            } else {
                sliceAreas.add(0.0)
                rawTopPoints.add(Position(startX + sliceWidth / 2, 0f, 0f))
            }
        }

        // Smoother contour visualization
        val smoothedTopPoints = rawTopPoints.mapIndexed { index, pos ->
            if (index > 1 && index < rawTopPoints.size - 2) {
                val avgY = (rawTopPoints[index - 2].y + rawTopPoints[index - 1].y + pos.y + 
                            rawTopPoints[index + 1].y + rawTopPoints[index + 2].y) / 5f
                Position(pos.x, avgY, pos.z)
            } else pos
        }

        var totalVolume = 0.0
        for (i in 0 until sliceAreas.size - 1) {
            totalVolume += ((sliceAreas[i] + sliceAreas[i + 1]) / 2.0) * sliceWidth
        }
        return ScanResult(totalVolume, smoothedTopPoints)
    }
}
