package com.forest.scanai.data.export

import android.content.Context
import android.os.Environment
import android.util.Log
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.domain.model.ScanUiState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvExporter(private val context: Context) {
    fun saveReport(
        uiState: ScanUiState,
        result: ScanSessionResult?,
        appVersionDisplay: String,
        lat: Double,
        lon: Double
    ): String? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ForestScan_$ts.csv"

        return try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileWriter(file).use { writer ->
                writer.append(
                    "Timestamp,AppVersion,Lat,Lon,Distance,StereoVol,NetVol,TotalPoints,RawPoints,AcceptedPoints,GroundPoints,NonGroundPoints,PilePoints,Clusters,SelectedCluster,DetectionQuality,DetectionConfidence,EstimatedHeight,BoundingBox,BoundingBoxFinal,MaxHeight,P95Height,MeanHeight,VolumeBeforeCorrection,VolumeAfterCorrection,VerticalCoverageScore,TopCoverageScore,TopPointCount,TopBandDensity,TopCoverageState,TopCoverageTrend,TopCoverageTemporalStability,TrajectoryQualityScore,VolumeStabilityScore,VolumeVariationRatio,VolumeIqrRatio,VolumeMadRatio,VolumeDriftRatio,ScaleValidationScore,ReferenceExpectedM,ReferenceObservedM,ReferenceRelativeError,ReferenceStatus,AutoCompletionCandidate,FallbackReasons\n"
                )

                val debug = result?.detectionDebugInfo.orEmpty()
                val fallbackReasons = result?.pileDetectionReasons?.joinToString(" | ") ?: ""
                val boundingBox = debug["bounding_box"] ?: ""

                writer.append(
                    listOf(
                        ts,
                        appVersionDisplay,
                        lat.toString(),
                        lon.toString(),
                        uiState.distance.toString(),
                        uiState.stereoVolume.toString(),
                        uiState.netVolume.toString(),
                        result?.pointsCount?.toString() ?: "",
                        debug["raw_points"] ?: "",
                        debug["accepted_points"] ?: "",
                        debug["ground_points"] ?: "",
                        debug["non_ground_points"] ?: "",
                        debug["pile_points"] ?: "",
                        debug["clusters"] ?: "",
                        debug["selected_cluster"] ?: "",
                        result?.pileDetectionQuality ?: "",
                        result?.pileDetectionConfidence?.toString() ?: "",
                        debug["estimated_height"] ?: "",
                        boundingBox,
                        debug["bounding_box_final"] ?: "",
                        debug["max_height"] ?: "",
                        debug["p95_height"] ?: "",
                        debug["mean_height"] ?: "",
                        debug["volume_before_correction"] ?: "",
                        debug["volume_after_correction"] ?: "",
                        debug["vertical_coverage_score"] ?: "",
                        debug["top_coverage_score"] ?: "",
                        debug["top_point_count"] ?: "",
                        debug["top_band_density"] ?: "",
                        debug["top_coverage_state"] ?: "",
                        debug["top_coverage_trend"] ?: "",
                        debug["top_coverage_temporal_stability"] ?: "",
                        debug["trajectory_quality_score"] ?: "",
                        debug["volume_stability_score"] ?: "",
                        debug["volume_variation_ratio"] ?: "",
                        debug["volume_iqr_ratio"] ?: "",
                        debug["volume_mad_ratio"] ?: "",
                        debug["volume_drift_ratio"] ?: "",
                        debug["scale_validation_score"] ?: "",
                        debug["reference_expected_m"] ?: "",
                        debug["reference_observed_m"] ?: "",
                        debug["reference_relative_error"] ?: "",
                        debug["reference_status"] ?: "",
                        debug["auto_completion_candidate"] ?: "",
                        fallbackReasons
                    ).joinToString(",") { csvSafe(it) } + "\n"
                )
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CsvExporter", "Error saving CSV: ${e.message}")
            null
        }
    }

    private fun csvSafe(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('\n')) "\"$escaped\"" else escaped
    }
}
