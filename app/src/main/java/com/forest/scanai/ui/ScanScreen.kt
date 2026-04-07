package com.forest.scanai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.forest.scanai.core.projectPointToScreen
import com.forest.scanai.domain.model.ScanUiState
import com.forest.scanai.presentation.ScanViewModel
import com.forest.scanai.presentation.components.MeasurementCompactCard
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (uiState.trackingState == TrackingState.TRACKING) {
                points.forEach { pos ->
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

                if (uiState.topPoints.size > 1) {
                    val screenPoints: List<Offset> = uiState.topPoints.mapNotNull { pos ->
                        projectPointToScreen(
                            pos,
                            viewMatrix,
                            projMatrix,
                            size.width,
                            size.height
                        )
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

        MeasurementCompactCard(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )

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
                    Text(
                        text = uiState.error!!,
                        color = Color.White,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp
                    )
                }
            }

            Button(
                onClick = { viewModel.toggleMeasuring() },
                enabled = uiState.isMeasuring || uiState.canFinishMeasurement,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isMeasuring) Color.Red else Color(0xFF4CAF50)
                )
            ) {
                Text(if (uiState.isMeasuring) "Detener escaneo" else "Finalizar")
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