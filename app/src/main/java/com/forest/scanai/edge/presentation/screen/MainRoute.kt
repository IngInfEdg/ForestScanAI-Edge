package com.forest.scanai.edge.presentation.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.forest.scanai.edge.data.export.CsvExporter
import com.forest.scanai.edge.domain.model.ScanSessionResult
import com.forest.scanai.edge.domain.model.ScanUiState
import com.forest.scanai.edge.presentation.ScanViewModel
import com.forest.scanai.edge.ui.ScanScreen

@Composable
fun MainRoute(
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
