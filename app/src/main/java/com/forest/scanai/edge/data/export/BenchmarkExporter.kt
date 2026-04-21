package com.forest.scanai.edge.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.forest.scanai.edge.domain.model.BenchmarkRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BenchmarkExporter(private val context: Context) {

    fun buildSuggestedFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        return "Benchmark_$ts.csv"
    }

    /**
     * Guarda un registro de benchmark en el URI proporcionado.
     */
    fun saveBenchmarkToUri(destinationUri: Uri, record: BenchmarkRecord): Boolean {
        return try {
            context.contentResolver.openOutputStream(destinationUri, "wa")?.bufferedWriter()?.use { writer ->
                val header = "ID,Timestamp,SessionLabel,PileName,Operator,ReferenceSource,RefVolume,Volume,NetVolume,Distance,Coverage,GPSPoints,Sectors,Completeness,AppVersion,Device,Notes"
                
                val m = record.metadata
                val row = listOf(
                    record.id,
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp)),
                    m.sessionLabel,
                    m.pileName,
                    m.operatorName,
                    m.referenceSource,
                    m.referenceVolume?.toString() ?: "",
                    record.volume.toString(),
                    record.netVolume.toString(),
                    record.distance.toString(),
                    record.coveragePercentage.toString(),
                    record.gpsPointCount.toString(),
                    "${record.coveredSectors}/${record.totalSectors}",
                    record.completeness,
                    record.appVersionDisplay,
                    record.deviceModel,
                    m.notes
                ).joinToString(",") { "\"$it\"" }

                writer.write(header)
                writer.newLine()
                writer.write(row)
                writer.newLine()
            } != null
        } catch (e: Exception) {
            Log.e("BenchmarkExporter", "Error saving benchmark: ${e.message}")
            false
        }
    }
}
