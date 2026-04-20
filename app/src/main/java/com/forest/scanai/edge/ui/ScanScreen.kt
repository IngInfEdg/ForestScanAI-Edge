package com.forest.scanai.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forest.scanai.edge.core.projectPointToScreen
import com.forest.scanai.edge.domain.model.ScanSessionResult
import com.forest.scanai.edge.domain.model.ScanUiState
import androidx.compose.ui.text.font.FontWeight
import com.forest.scanai.edge.presentation.viewmodel.ScanViewModel
import com.forest.scanai.edge.presentation.components.MeasurementCompactCard
import com.forest.scanai.edge.presentation.components.SegmentedPile3DView
import com.google.ar.core.Config
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ArSceneView
import java.util.Locale

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onRequestSaveCsv: (ScanUiState, ScanSessionResult?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val finalResult by viewModel.finalResult.collectAsState()
    val points = viewModel.points
    val visualPoints = remember(points.size) {
        val maxVisualPoints = 2200
        if (points.size <= maxVisualPoints) points.toList() else points.takeLast(maxVisualPoints)
    }
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (uiState.trackingState == TrackingState.TRACKING && finalResult == null) {
                visualPoints.forEach { pos ->
                    projectPointToScreen(
                        pos,
                        viewMatrix,
                        projMatrix,
                        size.width,
                        size.height
                    )?.let { offset ->
                        drawCircle(
                            color = Color.Green.copy(alpha = 0.40f),
                            radius = 1.5.dp.toPx(),
                            center = offset
                        )
                    }
                }

            }
        }

        if (finalResult == null) {
            MeasurementCompactCard(
                uiState = uiState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        } else {
            ResultOverlay(
                result = finalResult,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = uiState.appVersionDisplay,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (uiState.error != null && finalResult == null) {
                Surface(
                    color = Color.Red.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = uiState.error!!,
                        color = Color.White,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp
                    )
                }
            }

            if (finalResult == null) {
                if (!uiState.isMeasuring) {
                    Button(
                        onClick = { viewModel.toggleMeasuring() },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2)
                        )
                    ) {
                        Text("Iniciar medición")
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
                            onClick = { onRequestSaveCsv(uiState, finalResult) },
                            enabled = uiState.stereoVolume > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                        ) {
                            Text("Guardar CSV")
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.toggleMeasuring() },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.canFinishMeasurement) {
                                Color(0xFF2E7D32)
                            } else {
                                Color.Red
                            }
                        )
                    ) {
                        Text(
                            if (uiState.canFinishMeasurement) {
                                "Finalizar medición"
                            } else {
                                "Detener escaneo"
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (uiState.canReviewMeasurement && !uiState.canFinishMeasurement) {
                            Button(
                                onClick = { viewModel.toggleMeasuring() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                            ) {
                                Text("Detener para revisión")
                            }
                        }

                        Button(
                            onClick = { viewModel.reset() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("Cancelar")
                        }
                    }
                }
            } else {
                if (!uiState.canFinishMeasurement) {
                    Text(
                        text = "Lista para revisión. Requiere validación antes de finalizar.",
                        color = Color(0xFFFFF59D),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (uiState.canFinishMeasurement) {
                        Button(
                            onClick = { viewModel.reset() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("Finalizar")
                        }
                    } else {
                        Button(
                            onClick = { onRequestSaveCsv(uiState, finalResult) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                        ) {
                            Text("Guardar revisión")
                        }
                    }
                    Button(
                        onClick = { onRequestSaveCsv(uiState, finalResult) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                    ) {
                        Text("Guardar CSV")
                    }

                    Button(
                        onClick = { viewModel.reset() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Repetir medición")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultOverlay(
    result: ScanSessionResult?,
    modifier: Modifier = Modifier
) {
    if (result == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Resultado de medición",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "m³ Estéreo: ${formatDouble(result.volume)}  •  m³ Neto: ${formatDouble(result.volume * 0.45)}",
            color = Color(0xFF35D07F),
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Largo: ${formatDouble(result.length)} m  •  Ancho: ${formatDouble(result.maxWidth)} m",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Altura máxima: ${formatDouble(result.maxHeight)} m",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Altura P95: ${formatDouble(result.p95PileHeight)} m  •  Media: ${formatDouble(result.meanPileHeight)} m",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Suelo ref.: ${formatDouble(result.groundReference)} m  •  Base pila: ${formatDouble(result.pileBaseReference)} m",
            color = Color(0xFFD7CCC8),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Modelo 3D: ${formatDouble(result.reviewModelWidth.toDouble())} x ${formatDouble(result.reviewModelHeight.toDouble())} x ${formatDouble(result.reviewModelDepth.toDouble())} m",
            color = Color(0xFFB3E5FC),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Cobertura: ${(result.coverage * 100f).toInt()}%  •  Sectores: ${result.coveredSectors}/${result.totalSectors}",
            color = Color(0xFF00E5FF),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Estado: ${result.completeness}",
            color = when (result.completeness.name) {
                "COMPLETE" -> Color(0xFF4CAF50)
                "ACCEPTABLE" -> Color(0xFFFFC107)
                "PARTIAL" -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = result.guidanceSummary,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )

        SegmentedPile3DView(
            points = result.reviewModelPoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        )
    }
}

private fun formatDouble(value: Double): String {
    return String.format(Locale.US, "%.2f", value)
}
