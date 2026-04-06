package com.forest.scanai

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.Matrix
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forest.scanai.data.export.CsvExporter
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.presentation.ScanReviewViewModel
import com.forest.scanai.presentation.ScanViewModel
import com.forest.scanai.ui.ScanReview3DScreen
import com.forest.scanai.ui.ScanScreen
import com.forest.scanai.ui.theme.ForestScanAITheme
import io.github.sceneview.math.Position
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()
    private val reviewViewModel: ScanReviewViewModel by viewModels()
    private lateinit var csvExporter: CsvExporter
    private lateinit var locationProvider: LocationProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        csvExporter = CsvExporter(this)
        locationProvider = LocationProvider(this)

        setContent {
            ForestScanAITheme {
                var currentScreen by remember { mutableStateOf("scan") }
                val finalResult by viewModel.finalResult.collectAsState()

                LaunchedEffect(finalResult) {
                    finalResult?.let {
                        reviewViewModel.setScanResult(it)
                        currentScreen = "review"
                    }
                }

                MainScreen(
                    currentScreen = currentScreen,
                    viewModel = viewModel,
                    reviewViewModel = reviewViewModel,
                    onSaveReport = { uiState ->
                        lifecycleScope.launch {
                            val location = locationProvider.getCurrentLocation()
                            val path = csvExporter.saveReport(
                                uiState,
                                location?.latitude ?: 0.0,
                                location?.longitude ?: 0.0
                            )
                            if (path != null) {
                                Toast.makeText(this@MainActivity, "Reporte guardado", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onBackToScan = {
                        viewModel.reset()
                        currentScreen = "scan"
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
    onSaveReport: (com.forest.scanai.domain.model.ScanUiState) -> Unit,
    onBackToScan: () -> Unit
) {
    val context = LocalContext.current
    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    var permissionsGranted by remember {
        mutableStateOf(permissions.all { 
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        })
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
        when(currentScreen) {
            "scan" -> ScanScreen(viewModel, onSaveReport)
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

/**
 * Proyecta un punto 3D a coordenadas 2D de pantalla.
 */
fun projectPointToScreen(
    pos: Position,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    width: Float,
    height: Float
): Offset? {
    val worldCoord = floatArrayOf(pos.x, pos.y, pos.z, 1.0f)
    val vpMatrix = FloatArray(16)
    Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    
    val clipCoord = FloatArray(4)
    Matrix.multiplyMV(clipCoord, 0, vpMatrix, 0, worldCoord, 0)
    
    if (clipCoord[3] <= 0) return null
    
    val x = (clipCoord[0] / clipCoord[3] + 1.0f) / 2.0f * width
    val y = (1.0f - clipCoord[1] / clipCoord[3]) / 2.0f * height
    
    return Offset(x, y)
}
