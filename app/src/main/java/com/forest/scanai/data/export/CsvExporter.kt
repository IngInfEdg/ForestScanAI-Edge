package com.forest.scanai.data.export

import android.content.Context
import android.os.Environment
import android.util.Log
import com.forest.scanai.domain.model.ScanUiState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CsvExporter(private val context: Context) {
    fun saveReport(uiState: ScanUiState, lat: Double, lon: Double): String? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ForestScan_$ts.csv"
        return try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileWriter(file).use { writer ->
                writer.append("Timestamp,Lat,Lon,Distance,StereoVol,NetVol\n")
                writer.append("$ts,$lat,$lon,${uiState.distance},${uiState.stereoVolume},${uiState.netVolume}\n")
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CsvExporter", "Error saving CSV: ${e.message}")
            null
        }
    }
}
