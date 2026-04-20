package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.CoverageResult
import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.atan2

class MeasurementCoverageEvaluator(
    private val totalSectors: Int = 12
) {

    fun evaluateFromObserverPath(
        observerPath: List<Position>,
        pilePoints: List<Position>
    ): CoverageResult {
        if (observerPath.isEmpty() || pilePoints.isEmpty()) {
            return CoverageResult(
                coverageRatio = 0f,
                coveredSectors = 0,
                totalSectors = totalSectors,
                missingSectors = (0 until totalSectors).toList(),
                coveredSectorIndexes = emptySet()
            )
        }

        val centerX = pilePoints.map { it.x }.average().toFloat()
        val centerZ = pilePoints.map { it.z }.average().toFloat()

        val covered = mutableSetOf<Int>()
        val sectorSize = (2.0 * PI) / totalSectors.toDouble()

        observerPath.forEach { obs ->
            val dx = (obs.x - centerX).toDouble()
            val dz = (obs.z - centerZ).toDouble()

            var angle = atan2(dz, dx)
            if (angle < 0) angle += 2.0 * PI

            val sector = (angle / sectorSize).toInt().coerceIn(0, totalSectors - 1)
            covered.add(sector)
        }

        val missing = (0 until totalSectors).filterNot { covered.contains(it) }
        val ratio = covered.size.toFloat() / totalSectors.toFloat()

        return CoverageResult(
            coverageRatio = ratio,
            coveredSectors = covered.size,
            totalSectors = totalSectors,
            missingSectors = missing,
            coveredSectorIndexes = covered
        )
    }
}
