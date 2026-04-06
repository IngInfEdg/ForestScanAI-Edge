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
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReview3DScreen(
    viewModel: ScanReviewViewModel,
    onBack: () -> Unit
) {
    val result by viewModel.scanResult.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Revisión de Medición") },
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
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            result?.let { res ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SceneView(ctx).apply {
                            // Configuración de iluminación para evitar pantalla negra
                            environment?.indirectLight?.let { it.intensity = 2.0f }
                            
                            // Posicionar cámara inicialmente
                            if (res.points.isNotEmpty()) {
                                val avgX = res.points.map { it.x }.average().toFloat()
                                val avgY = res.points.map { it.y }.average().toFloat()
                                val avgZ = res.points.map { it.z }.average().toFloat()
                                val center = Position(avgX, avgY, avgZ)
                                cameraNode.position = Position(center.x, center.y + 2f, center.z + 6f)
                                cameraNode.lookAt(center)
                            }
                        }
                    },
                    update = { sceneView ->
                        // Limpiar nodos para evitar duplicados en recomposición
                        sceneView.children.filterIsInstance<Node>().forEach { sceneView.removeChild(it) }
                        
                        // Renderizar una muestra de puntos (Máximo 1000)
                        res.points.take(1000).forEach { pos ->
                            val pointNode = Node(sceneView.engine).apply {
                                position = pos
                            }
                            sceneView.addChild(pointNode)
                        }
                    }
                )

                // Panel de Calidad y Completitud
                Card(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(0.7f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Estado de Cobertura", color = Color.Cyan, style = MaterialTheme.typography.labelLarge)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Gray)
                        MetricItem("Volumen", "${"%.2f".format(res.volume)} m³")
                        MetricItem("Cobertura", "${(res.coverage * 100).toInt()}%")
                        MetricItem("Estado", res.completeness.name)
                        MetricItem("Trayectoria GPS", "${res.trajectory.size} pts")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
