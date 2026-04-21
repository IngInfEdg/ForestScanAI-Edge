package com.forest.scanai.edge.domain.model

import android.os.Build

data class BenchmarkRecord(
    val id: String,
    val timestamp: Long,
    val metadata: BenchmarkMetadata,
    val volume: Double,
    val netVolume: Double,
    val distance: Double,
    val coveragePercentage: Float,
    val gpsPointCount: Int,
    val coveredSectors: Int,
    val totalSectors: Int,
    val completeness: String,
    val appVersionDisplay: String,
    val source: String = "ForestScanAI Edge",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val scanDurationSec: Long? = null
)

/**
 * Mapper simple para convertir el resultado actual en un registro de benchmark.
 */
fun createBenchmarkRecord(
    result: ScanSessionResult,
    metadata: BenchmarkMetadata
): BenchmarkRecord {
    return BenchmarkRecord(
        id = java.util.UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        metadata = metadata,
        volume = result.volume,
        netVolume = result.netVolumeEstimate,
        distance = result.arDistanceWalked,
        coveragePercentage = result.coverage,
        gpsPointCount = result.gpsPointCount,
        coveredSectors = result.coveredSectors,
        totalSectors = result.totalSectors,
        completeness = result.completeness.name,
        appVersionDisplay = result.appVersionDisplay
    )
}
