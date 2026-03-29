package com.forest.scanai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.Image
import android.opengl.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.forest.scanai.ui.theme.ForestScanAITheme
import com.google.android.gms.location.LocationServices
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.arcore.ArFrame
import io.github.sceneview.math.Position
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

data class ScanResult(val volume: Double, val topPoints: List<Position>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForestScanAITheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
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
        ForestScanHUD()
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

@Composable
fun ForestScanHUD() {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    
    val points = remember { mutableStateListOf<Position>() }
    val voxelGrid = remember { mutableSetOf<String>() }
    var startPosition by remember { mutableStateOf<Position?>(null) }
    var currentPosition by remember { mutableStateOf<Position?>(null) }
    var trackingState by remember { mutableStateOf(TrackingState.PAUSED) }
    var packingFactor by remember { mutableStateOf(0.45f) }

    // Filtro de paso bajo para suavizado de distancia (EMA - Exponential Moving Average)
    var smoothedDistance by remember { mutableStateOf(0.0) }
    val alpha = 0.15 // Factor de suavizado (0 a 1)

    // Matrices para proyección de línea de contorno
    var viewMatrix by remember { mutableStateOf(FloatArray(16)) }
    var projMatrix by remember { mutableStateOf(FloatArray(16)) }

    val scanResult = remember(points.size) { calculateIrregularVolume(points.toList()) }
    val stereoVolume = scanResult.volume
    val netVolume = stereoVolume * packingFactor
    
    // Cálculo de Distancia Horizontal (XZ) con Factor de Corrección 0.91 y Suavizado
    LaunchedEffect(startPosition, currentPosition) {
        if (startPosition != null && currentPosition != null) {
            val dx = currentPosition!!.x - startPosition!!.x
            val dz = currentPosition!!.z - startPosition!!.z
            val rawDist = sqrt((dx * dx + dz * dz).toDouble()) * 0.91
            // Filtro de paso bajo: New = Old + alpha * (Target - Old)
            smoothedDistance = smoothedDistance + alpha * (rawDist - smoothedDistance)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ArSceneView(ctx).apply {
                    onArFrame = { arFrame: ArFrame ->
                        val frame = arFrame.frame
                        val session = arSession
                        
                        if (session != null && session.config.depthMode != Config.DepthMode.AUTOMATIC) {
                            val config = session.config
                            config.depthMode = Config.DepthMode.AUTOMATIC
                            config.focusMode = Config.FocusMode.AUTO
                            session.configure(config)
                        }

                        trackingState = frame.camera.trackingState
                        
                        if (trackingState == TrackingState.TRACKING) {
                            val vMat = FloatArray(16)
                            frame.camera.getViewMatrix(vMat, 0)
                            viewMatrix = vMat
                            
                            val pMat = FloatArray(16)
                            frame.camera.getProjectionMatrix(pMat, 0, 0.1f, 100f)
                            projMatrix = pMat

                            val cameraPose = frame.camera.pose
                            val camPos = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
                            
                            if (startPosition == null) startPosition = camPos
                            currentPosition = camPos
                            
                            // Filtro de Profundidad Estricto Estilo LiDAR (0.8m - 4.0m)
                            extractPointCloudFiltered(frame, camPos, voxelGrid) { newPoints ->
                                points.addAll(newPoints)
                            }

                            try {
                                val image = frame.acquireCameraImage()
                                packingFactor = estimatePackingFactor(image)
                                image.close()
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
        )

        // Dibujo de Línea de Contorno Suave (Canvas Overlay)
        if (trackingState == TrackingState.TRACKING && scanResult.topPoints.size > 1) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val screenPoints = scanResult.topPoints.mapNotNull {
                    projectPointToScreen(it, viewMatrix, projMatrix, size.width, size.height)
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

        // UI Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.medium)
                .padding(16.dp)
        ) {
            HUDMetricItem("Distancia", "${"%.2f".format(smoothedDistance)} m")
            HUDMetricItem("Volumen Estéreo", "${"%.2f".format(stereoVolume)} m³")
            HUDMetricItem("Volumen Neto", "${"%.2f".format(netVolume)} m³")
            
            if (trackingState != TrackingState.TRACKING) {
                Text("⚠️ Tracking limitado. Sensor Ocluido?", color = Color.Yellow, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { 
                points.clear()
                voxelGrid.clear()
                startPosition = null
                currentPosition = null
                smoothedDistance = 0.0
            }) { Text("Reset") }
            
            Button(onClick = { 
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        saveReportToCSV(context, smoothedDistance, stereoVolume, netVolume, location?.latitude ?: 0.0, location?.longitude ?: 0.0)
                    }
                }
            }) { Text("Guardar CSV") }
        }
    }
}

@Composable
fun HUDMetricItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 24.sp)
    }
}

/**
 * Algoritmo de Volumen por Secciones con Suavizado de Contorno
 */
fun calculateIrregularVolume(points: List<Position>): ScanResult {
    if (points.size < 200) return ScanResult(0.0, emptyList())
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val length = maxX - minX
    if (length < 0.2) return ScanResult(0.0, emptyList())

    val sliceWidth = 0.1f
    val numSlices = (length / sliceWidth).toInt()
    val sliceAreas = mutableListOf<Double>()
    val rawTopPoints = mutableListOf<Position>()

    for (i in 0 until numSlices) {
        val startX = minX + i * sliceWidth
        val pointsInSlice = points.filter { it.x in startX..(startX + sliceWidth) }
        
        if (pointsInSlice.size >= 5) {
            val topPoint = pointsInSlice.maxByOrNull { it.y }!!
            rawTopPoints.add(topPoint)

            val avgMinY = pointsInSlice.map { it.y }.sorted().take(5).average()
            val maxY = topPoint.y
            val sliceHeight = (maxY - avgMinY).coerceAtLeast(0.0)
            
            // Calculamos ancho (Z) buscando diferencia min/max, limitado a 4.0m
            val sliceDepth = (pointsInSlice.maxOf { it.z } - pointsInSlice.minOf { it.z }).toDouble().coerceAtMost(4.0)
            
            sliceAreas.add(sliceHeight * sliceDepth)
        } else {
            sliceAreas.add(0.0)
            // Mantener consistencia de índices para el suavizado aunque el slice esté vacío
            rawTopPoints.add(Position(startX + sliceWidth/2, 0f, 0f))
        }
    }

    // Suavizado de Contorno (Media Móvil de 3 puntos para evitar línea "dentada")
    val smoothedTopPoints = rawTopPoints.mapIndexed { index, pos ->
        if (index > 0 && index < rawTopPoints.size - 1) {
            val avgY = (rawTopPoints[index - 1].y + pos.y + rawTopPoints[index + 1].y) / 3f
            Position(pos.x, avgY, pos.z)
        } else pos
    }

    var totalVolume = 0.0
    for (i in 0 until sliceAreas.size - 1) {
        totalVolume += ((sliceAreas[i] + sliceAreas[i + 1]) / 2.0) * sliceWidth
    }
    return ScanResult(totalVolume, smoothedTopPoints)
}

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

/**
 * Filtro de Oclusión Estilo LiDAR (0.8m - 4.0m)
 */
private fun extractPointCloudFiltered(
    frame: Frame, 
    cameraPos: Position,
    voxelGrid: MutableSet<String>, 
    onNewPoints: (List<Position>) -> Unit
) {
    try {
        val pointCloud = frame.acquirePointCloud()
        val buffer = pointCloud.points
        val count = buffer.remaining() / 4
        val filteredPoints = mutableListOf<Position>()
        for (i in 0 until count step 20) {
            val x = buffer.get(i * 4)
            val y = buffer.get(i * 4 + 1)
            val z = buffer.get(i * 4 + 2)
            
            val dx = x - cameraPos.x
            val dy = y - cameraPos.y
            val dz = z - cameraPos.z
            val dist = sqrt((dx * dx + dy * dy + dz * dz).toDouble())

            // Filtro estricto: 0.8m a 4.0m para ignorar vegetación de fondo
            if (buffer.get(i * 4 + 3) > 0.6f && dist in 0.8..4.0) {
                val key = "${(x/0.05f).toInt()},${(y/0.05f).toInt()},${(z/0.05f).toInt()}"
                if (voxelGrid.add(key)) filteredPoints.add(Position(x, y, z))
            }
        }
        pointCloud.release()
        if (filteredPoints.isNotEmpty()) onNewPoints(filteredPoints)
    } catch (e: Exception) {}
}

fun saveReportToCSV(context: Context, distance: Double, stereoVol: Double, netVol: Double, lat: Double, lon: Double) {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    try {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ForestScan_$ts.csv")
        FileWriter(file).use { writer ->
            writer.append("Timestamp,Lat,Lon,Distance,StereoVol,NetVol\n")
            writer.append("$ts,$lat,$lon,$distance,$stereoVol,$netVol\n")
        }
        Toast.makeText(context, "Guardado en Documentos", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { Log.e("CSV", e.message ?: "Error") }
}

fun estimatePackingFactor(image: Image?): Float = 0.45f
