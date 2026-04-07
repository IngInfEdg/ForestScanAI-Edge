package com.forest.scanai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.presentation.ScanReviewViewModel
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReview3DScreen(
    viewModel: ScanReviewViewModel,
    onBack: () -> Unit
) {
    val result: ScanSessionResult? by viewModel.scanResult.collectAsState()

    var yaw by remember { mutableFloatStateOf(0.55f) }
    var pitch by remember { mutableFloatStateOf(-0.35f) }
    var zoom by remember { mutableFloatStateOf(1.0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Revision de medicion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF101418))
        ) {
            val res = result

            if (res == null || res.points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No hay puntos para revisar.",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            } else {
                val normalized = remember(res.points) {
                    normalizePoints(res.points.take(4000))
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                yaw += dragAmount.x * 0.005f
                                pitch = (pitch + dragAmount.y * 0.0035f).coerceIn(-1.2f, 1.2f)
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val scale = max(w, h) * 0.23f * zoom

                    normalized.forEach { p ->
                        val rotated = rotatePoint(p, yaw, pitch)

                        val screenX = w / 2f + rotated.x * scale
                        val screenY = h / 2f - rotated.y * scale

                        val depthShade = ((rotated.z + 1.5f) / 3f).coerceIn(0.15f, 1f)
                        val pointColor = Color(
                            red = 0.20f,
                            green = 0.85f,
                            blue = 0.45f,
                            alpha = depthShade
                        )

                        drawCircle(
                            color = pointColor,
                            radius = 2.2f + depthShade * 1.8f,
                            center = Offset(screenX, screenY)
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .widthIn(max = 280.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.72f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Estado de cobertura",
                            color = Color.Cyan,
                            style = MaterialTheme.typography.labelLarge
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = Color.Gray
                        )

                        MetricItem("Volumen", "${"%.2f".format(res.volume)} m3")
                        MetricItem("Cobertura", "${(res.coverage * 100).toInt()}%")
                        MetricItem("Estado", res.completeness.name)
                        MetricItem("Puntos", res.pointsCount.toString())
                        MetricItem("AR", "${"%.1f".format(res.arDistanceWalked)} m")
                        MetricItem("GPS", "${"%.1f".format(res.gpsDistanceWalked)} m")
                        MetricItem("Sectores", "${res.coveredSectors}/${res.totalSectors}")
                    }
                }

                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.72f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Rotacion y zoom", color = Color.White)
                        Text("Zoom", color = Color.LightGray)

                        Slider(
                            value = zoom,
                            onValueChange = { zoom = it },
                            valueRange = 0.6f..2.4f
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Arrastra para rotar",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Vec3(val x: Float, val y: Float, val z: Float)

private fun normalizePoints(points: List<Position>): List<Vec3> {
    if (points.isEmpty()) return emptyList()

    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val minZ = points.minOf { it.z }
    val maxZ = points.maxOf { it.z }

    val centerX = (minX + maxX) / 2f
    val centerY = (minY + maxY) / 2f
    val centerZ = (minZ + maxZ) / 2f

    val spanX = maxX - minX
    val spanY = maxY - minY
    val spanZ = maxZ - minZ
    val maxSpan = max(spanX, max(spanY, spanZ)).coerceAtLeast(0.001f)

    return points.map {
        Vec3(
            x = (it.x - centerX) / maxSpan,
            y = (it.y - centerY) / maxSpan,
            z = (it.z - centerZ) / maxSpan
        )
    }
}

private fun rotatePoint(point: Vec3, yaw: Float, pitch: Float): Vec3 {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)

    val x1 = point.x * cy - point.z * sy
    val z1 = point.x * sy + point.z * cy

    val y2 = point.y * cp - z1 * sp
    val z2 = point.y * sp + z1 * cp

    return Vec3(x = x1, y = y2, z = z2)
}

@Composable
private fun MetricItem(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            color = Color.LightGray,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}