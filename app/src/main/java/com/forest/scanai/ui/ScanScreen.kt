package com.forest.scanai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forest.scanai.domain.model.ScanUiState
import com.forest.scanai.presentation.ScanViewModel
import com.forest.scanai.core.projectPointToScreen
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ArSceneView

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onSaveReport: (ScanUiState) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val points = viewModel.points
    var viewMatrix by remember { mutableStateOf(FloatArray(16)) }
    var projMatrix by remember { mutableStateOf(FloatArray(16)) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ArSceneView(ctx).apply {
                    onArFrame = { arFrame ->
                        val session = arSession
                        if (session != null && session.config.depthMode != Config.DepthMode.AUTOMATIC) {
                            val config = session.config
                            config.depthMode = Config.DepthMode.AUTOMATIC
                            config.focusMode = Config.FocusMode.AUTO
                            session.configure(config)
                        }

                        if (arFrame.frame.camera.trackingState == TrackingState.TRACKING) {
                            val vMat = FloatArray(16)
                            arFrame.frame.camera.getViewMatrix(vMat, 0)
                            viewMatrix = vMat

                            val pMat = FloatArray(16)
                            arFrame.frame.camera.getProjectionMatrix(pMat, 0, 0.1f, 100f)
                            projMatrix = pMat

                            viewModel.onFrameUpdated(arFrame.frame)
                        }
                    }
                }
            }
        )

        // Visualización de puntos y contorno
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (uiState.trackingState == TrackingState.TRACKING) {
                points.forEach { pos ->
                    projectPointToScreen(pos, viewMatrix, projMatrix, size.width, size.height)?.let { offset ->
                        drawCircle(
                            color = Color.Green.copy(alpha = 0.4f),
                            radius = 1.5.dp.toPx(),
                            center = offset
                        )
                    }
                }

                if (uiState.topPoints.size > 1) {
                    val screenPoints: List<Offset> = uiState.topPoints.mapNotNull { pos ->
                        projectPointToScreen(pos, viewMatrix, projMatrix, size.width, size.height)
                    }

                    if (screenPoints.size > 1) {
                        val path = Path().apply {
                            moveTo(screenPoints[0].x, screenPoints[0].y)
                            for (i in 1 until screenPoints.size) {
                                lineTo(screenPoints[i].x, screenPoints[i].y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = Color.Red,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
        }

        // HUD Overlay con los nuevos parámetros
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.medium)
                .padding(12.dp)
        ) {
            Text("Volumen Neto: ${"%.2f".format(uiState.netVolume)} m³", color = Color.White, fontSize = 18.sp)
            Text("Cobertura: ${(uiState.coveragePercentage * 100).toInt()}%", color = Color.Cyan)
            Text("Sectores: ${uiState.coveredSectors}/${uiState.totalSectors}", color = Color.Cyan)
            Text("Recorrido AR: ${"%.1f".format(uiState.arDistanceWalked)} m", color = Color.LightGray, fontSize = 12.sp)
            Text("Recorrido GPS: ${"%.1f".format(uiState.gpsDistanceWalked)} m", color = Color.LightGray, fontSize = 12.sp)
            Text("GPS válidos: ${uiState.gpsPointCount}", color = Color.LightGray, fontSize = 12.sp)
            Text("Muestras AR: ${uiState.observerSampleCount}", color = Color.LightGray, fontSize = 12.sp)
            Text("Cobertura: ${(uiState.coveragePercentage * 100).toInt()}%")
            Text("Sectores: ${uiState.coveredSectors}/${uiState.totalSectors}")
            Text("Recorrido AR: ${"%.1f".format(uiState.arDistanceWalked)} m")
            Text("Recorrido GPS: ${"%.1f".format(uiState.gpsDistanceWalked)} m")
            Text("GPS válidos: ${uiState.gpsPointCount}")
            Text("Muestras AR: ${uiState.observerSampleCount}")
            Text("Estado: ${uiState.completeness}")
            Text("Guía: ${uiState.guidanceMessage}")
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("Estado: ${uiState.completeness}", color = if(uiState.canFinishMeasurement) Color.Green else Color.Yellow, fontSize = 14.sp)
            Text("Guía: ${uiState.guidanceMessage}", color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
        }

        // Controles de Medición
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.error != null) {
                Surface(
                    color = Color.Red.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(uiState.error!!, color = Color.White, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
                }
            }

            Button(
                onClick = { viewModel.toggleMeasuring() },
                enabled = uiState.isMeasuring || uiState.canFinishMeasurement,
                modifier = Modifier.fillMaxWidth(0.7f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMeasuring) Color.Red else Color(0xFF4CAF50)
                )
            ) {
                Text(if (uiState.isMeasuring) "DETENER ESCANEO" else "FINALIZAR")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { viewModel.reset() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Reset")
                }
                
                Button(
                    onClick = { if (!uiState.isMeasuring) onSaveReport(uiState) },
                    enabled = !uiState.isMeasuring && uiState.stereoVolume > 0
                ) {
                    Text("Guardar CSV")
                }
            }
        }
    }
}
