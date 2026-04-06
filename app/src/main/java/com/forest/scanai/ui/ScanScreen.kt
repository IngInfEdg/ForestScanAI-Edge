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

        // Overlay de Visualización: Pintado de Cobertura + Línea de Contorno
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (uiState.trackingState == TrackingState.TRACKING) {
                
                // 1. DIBUJAR "PINTURA" DE COBERTURA (Puntos Verdes)
                points.forEach { pos ->
                    projectPointToScreen(pos, viewMatrix, projMatrix, size.width, size.height)?.let { offset ->
                        drawCircle(
                            color = Color.Green.copy(alpha = 0.4f),
                            radius = 1.5.dp.toPx(),
                            center = offset
                        )
                    }
                }

                // 2. DIBUJAR LÍNEA DE CONTORNO (Roja)
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

        // HUD Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.medium)
                .padding(16.dp)
        ) {
            HUDMetricItem("Distancia", "${"%.2f".format(uiState.distance)} m")
            HUDMetricItem("Volumen Estéreo", "${"%.2f".format(uiState.stereoVolume)} m³")
            HUDMetricItem("Volumen Neto", "${"%.2f".format(uiState.netVolume)} m³")
            
            if (uiState.trackingState != TrackingState.TRACKING) {
                Text("⚠️ Buscando superficie...", color = Color.Yellow, fontSize = 12.sp)
            }
        }

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Botón de Inicio / Detener Medición
            Button(
                onClick = { viewModel.toggleMeasuring() },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMeasuring) Color.Red else Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = if (uiState.isMeasuring) "DETENER ESCANEO" else "INICIAR ESCANEO",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
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

@Composable
private fun HUDMetricItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 26.sp, style = MaterialTheme.typography.headlineMedium)
    }
}
