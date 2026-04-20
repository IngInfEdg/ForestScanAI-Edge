package com.forest.scanai.edge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forest.scanai.edge.core.AppVersionProvider
import com.forest.scanai.edge.data.export.CsvExporter
import com.forest.scanai.edge.data.location.LocationProvider
import com.forest.scanai.edge.domain.model.ScanSessionResult
import com.forest.scanai.edge.domain.model.ScanUiState
import com.forest.scanai.edge.presentation.ScanViewModel
import com.forest.scanai.edge.presentation.viewmodel.ScanViewModelFactory
import com.forest.scanai.edge.ui.ScanScreen
import com.forest.scanai.edge.ui.theme.ForestScanAITheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val locationProvider by lazy { LocationProvider(applicationContext) }
    private val csvExporter by lazy { CsvExporter(applicationContext) }
    private val appVersionProvider by lazy { AppVersionProvider(applicationContext) }

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
                MainScreen(
                    viewModel = viewModel,
                    onSaveReportToUri = { destinationUri, uiState, result ->
                        lifecycleScope.launch {
                            val location = locationProvider.getCurrentLocation()
                            val saved = csvExporter.saveReportToUri(
                                destinationUri = destinationUri,
                                uiState = uiState,
                                result = result,
                                appVersionDisplay = appVersionProvider.displayVersion,
                                lat = location?.latitude ?: 0.0,
                                lon = location?.longitude ?: 0.0
                            )
                            if (saved) {
                                Toast.makeText(this@MainActivity, "Reporte guardado", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, "No se pudo guardar el CSV", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: ScanViewModel,
    onSaveReportToUri: (Uri, ScanUiState, ScanSessionResult?) -> Unit
) {
    val context = LocalContext.current
    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    var permissionsGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val csvExporter = remember(context) { CsvExporter(context) }
    var pendingCsvRequest by remember { mutableStateOf<Pair<ScanUiState, ScanSessionResult?>?>(null) }
    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val pending = pendingCsvRequest
        if (uri != null && pending != null) {
            onSaveReportToUri(uri, pending.first, pending.second)
        }
        pendingCsvRequest = null
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { res -> permissionsGranted = res.values.all { it } }
    )

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            launcher.launch(permissions)
        }
    }

    if (permissionsGranted) {
        ScanScreen(
            viewModel = viewModel,
            onRequestSaveCsv = { uiState, result ->
                pendingCsvRequest = uiState to result
                createCsvLauncher.launch(csvExporter.buildSuggestedFileName())
            }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Se requieren permisos de cámara y ubicación.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(permissions) }) {
                    Text("Conceder Permisos")
                }
            }
        }
    }
}
