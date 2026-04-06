package com.forest.scanai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.forest.scanai.presentation.ScanReviewViewModel
import io.github.sceneview.SceneView
import io.github.sceneview.node.Node

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReview3DScreen(
    viewModel: ScanReviewViewModel,
    onBack: () -> Unit
) {
    val result by viewModel.scanResult.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Revisión 3D") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    FilterChip(
                        selected = viewMode == ScanReviewViewModel.ReviewMode.POINTS,
                        onClick = { viewModel.setViewMode(ScanReviewViewModel.ReviewMode.POINTS) },
                        label = { Text("Puntos") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = viewMode == ScanReviewViewModel.ReviewMode.MESH,
                        onClick = { viewModel.setViewMode(ScanReviewViewModel.ReviewMode.MESH) },
                        label = { Text("Malla") }
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            result?.let { res ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SceneView(ctx).apply {
                            // En una implementación real de SceneView 0.10.0, 
                            // para nubes de puntos grandes se usa PointCloud o se agregan nodos ligeros.
                            // Por ahora agregamos nodos para validar la posición espacial.
                            res.points.take(500).forEach { pos -> // Limitamos para fluidez en review
                                val node = Node(engine).apply {
                                    position = pos
                                    // Aquí se asignaría una geometría de esfera pequeña
                                }
                                addChild(node)
                            }
                        }
                    }
                )

                // Panel de Métricas
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(0.7f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        MetricText("Volumen", "${"%.2f".format(res.volume)} m³")
                        MetricText("Largo", "${"%.2f".format(res.length)} m")
                        MetricText("Ancho Máx", "${"%.2f".format(res.maxWidth)} m")
                        MetricText("Altura Máx", "${"%.2f".format(res.maxHeight)} m")
                        MetricText("Confianza", "${(res.confidence * 100).toInt()}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricText(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
