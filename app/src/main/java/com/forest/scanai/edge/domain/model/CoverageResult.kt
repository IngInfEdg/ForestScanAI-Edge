package com.forest.scanai.edge.domain.model

data class CoverageResult(
    val coverageRatio: Float,
    val coveredSectors: Int,
    val totalSectors: Int,
    val missingSectors: List<Int>,
    val coveredSectorIndexes: Set<Int>
)
