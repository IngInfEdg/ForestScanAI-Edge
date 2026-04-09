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
                    "Timestamp,AppVersion,Lat,Lon,Distance,StereoVol,NetVol,TotalPoints,GroundPoints,NonGroundPoints,PilePoints,Clusters,SelectedCluster,DetectionQuality,DetectionConfidence,EstimatedHeight,BoundingBox,FallbackReasons\n"
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
                        debug["ground_points"] ?: "",
                        debug["non_ground_points"] ?: "",
                        debug["pile_points"] ?: "",
                        debug["clusters"] ?: "",
                        debug["selected_cluster"] ?: "",
                        result?.pileDetectionQuality ?: "",
                        result?.pileDetectionConfidence?.toString() ?: "",
                        debug["estimated_height"] ?: "",
                        boundingBox,
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
