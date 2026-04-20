package com.forest.scanai.edge.data.export

import android.net.Uri
import com.forest.scanai.edge.core.AppVersionProvider
import com.forest.scanai.edge.data.location.LocationProvider
import com.forest.scanai.edge.domain.model.ScanMetrics
import com.forest.scanai.edge.domain.model.ScanSessionResult

class ReportSaver(
    private val locationProvider: LocationProvider,
    private val csvExporter: CsvExporter,
    private val appVersionProvider: AppVersionProvider
) {
    suspend fun saveReport(
        destinationUri: Uri,
        metrics: ScanMetrics,
        result: ScanSessionResult?
    ): Boolean {
        val location = locationProvider.getCurrentLocation()
        return csvExporter.saveReportToUri(
            destinationUri = destinationUri,
            metrics = metrics,
            result = result,
            appVersionDisplay = appVersionProvider.displayVersion,
            lat = location?.latitude ?: 0.0,
            lon = location?.longitude ?: 0.0
        )
    }
}
