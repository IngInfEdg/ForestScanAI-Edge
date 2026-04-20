package com.forest.scanai.edge

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.forest.scanai.edge.core.AppVersionProvider
import com.forest.scanai.edge.data.export.BenchmarkExporter
import com.forest.scanai.edge.data.export.CsvExporter
import com.forest.scanai.edge.data.export.ReportSaver
import com.forest.scanai.edge.data.location.LocationProvider
import com.forest.scanai.edge.domain.model.createBenchmarkRecord
import com.forest.scanai.edge.presentation.screen.MainRoute
import com.forest.scanai.edge.presentation.state.ScanUiState
import com.forest.scanai.edge.presentation.viewmodel.ScanViewModel
import com.forest.scanai.edge.presentation.viewmodel.ScanViewModelFactory
import com.forest.scanai.edge.ui.theme.ForestScanAITheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val locationProvider by lazy { LocationProvider(applicationContext) }
    private val csvExporter by lazy { CsvExporter(applicationContext) }
    private val appVersionProvider by lazy { AppVersionProvider(applicationContext) }
    private val benchmarkExporter by lazy { BenchmarkExporter(applicationContext) }
    
    private val reportSaver by lazy {
        ReportSaver(locationProvider, csvExporter, appVersionProvider)
    }

    private val viewModel: ScanViewModel by viewModels {
        ScanViewModelFactory(
            locationProvider = locationProvider,
            appVersionName = appVersionProvider.versionName,
            appVersionCode = appVersionProvider.versionCode,
            appVersionDisplay = appVersionProvider.displayVersion
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ForestScanAITheme {
                MainRoute(
                    viewModel = viewModel,
                    onSaveReportToUri = { destinationUri, uiState: ScanUiState, result ->
                        lifecycleScope.launch {
                            val saved = reportSaver.saveReport(destinationUri, uiState.metrics, result)
                            val message = if (saved) "Reporte guardado" else "No se pudo guardar el CSV"
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onSaveBenchmarkToUri = { uri, result ->
                        val record = createBenchmarkRecord(result)
                        val success = benchmarkExporter.saveBenchmarkToUri(uri, record)
                        Toast.makeText(this@MainActivity, if(success) "Benchmark registrado" else "Error al registrar benchmark", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
