package com.forest.scanai

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.forest.scanai.core.AppVersionProvider
import com.forest.scanai.data.export.CsvExporter
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.domain.model.ScanUiState
import com.forest.scanai.presentation.ScanReviewViewModel
import com.forest.scanai.presentation.ScanViewModel
import com.forest.scanai.ui.ScanReview3DScreen
import com.forest.scanai.ui.ScanScreen
import com.forest.scanai.ui.theme.ForestScanAITheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val locationProvider by lazy { LocationProvider(applicationContext) }
    private val csvExporter by lazy { CsvExporter(applicationContext) }
    private val appVersionProvider by lazy { AppVersionProvider(applicationContext) }

    private val viewModel: ScanViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ScanViewModel(
                    locationProvider = locationProvider,
                    appVersionName = appVersionProvider.versionName,
                    appVersionCode = appVersionProvider.versionCode,
                    appVersionDisplay = appVersionProvider.displayVersion
                ) as T
            }
        }
    }

    private val reviewViewModel: ScanReviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ForestScanAITheme {
                var currentScreen by remember { mutableStateOf("scan") }

                MainScreen(
                    currentScreen = currentScreen,
                    viewModel = viewModel,
                    reviewViewModel = reviewViewModel,
                    onSaveReport = { uiState, result ->
                        lifecycleScope.launch {
                            val location = locationProvider.getCurrentLocation()
                            val path = csvExporter.saveReport(
                                uiState = uiState,
                                result = result,
                                appVersionDisplay = appVersionProvider.displayVersion,
                                lat = location?.latitude ?: 0.0,
                                lon = location?.longitude ?: 0.0
                            )
                            if (path != null) {
                                Toast.makeText(this@MainActivity, "Reporte guardado", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onBackToScan = {
                        viewModel.reset()
                        currentScreen = "scan"
                    },
                    onOpenReview = {
                        reviewViewModel.setScanResult(it)
                        currentScreen = "review"
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    currentScreen: String,
    viewModel: ScanViewModel,
    reviewViewModel: ScanReviewViewModel,
    onSaveReport: (ScanUiState, ScanSessionResult?) -> Unit,
    onBackToScan: () -> Unit,
    onOpenReview: (ScanSessionResult) -> Unit
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
        when (currentScreen) {
            "scan" -> ScanScreen(viewModel, onSaveReport, onOpenReview)
            "review" -> ScanReview3DScreen(reviewViewModel, onBack = onBackToScan)
        }
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
