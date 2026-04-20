package com.forest.scanai.edge.data.export

import android.net.Uri
import com.forest.scanai.edge.core.AppVersionProvider
import com.forest.scanai.edge.data.location.LocationProvider
import com.forest.scanai.edge.domain.model.ScanSessionResult
import com.forest.scanai.edge.domain.model.ScanUiState

class ReportSaver(
    private val locationProvider: LocationProvider,
    private val csvExporter: CsvExporter,
    private val appVersionProvider: AppVersionProvider
) {
    suspend fun saveReport(
        destinationUri: Uri,
        uiState: ScanUiState,
        result: ScanSessionResult?
    ): Boolean {
        val location = locationProvider.getCurrentLocation()
        return csvExporter.saveReportToUri(
            destinationUri = destinationUri,
            uiState = uiState,
            result = result,
            appVersionDisplay = appVersionProvider.displayVersion,
            lat = location?.latitude ?: 0.0,
            lon = location?.longitude ?: 0.0
        )
    }
}
