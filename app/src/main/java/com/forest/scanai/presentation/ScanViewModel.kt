package com.forest.scanai.presentation

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.forest.scanai.core.ScanParams
import com.forest.scanai.domain.engine.PointCloudProcessor
import com.forest.scanai.domain.engine.VolumeCalculator
import com.forest.scanai.domain.model.ScanUiState
import com.forest.scanai.domain.model.ScanSessionResult
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class ScanViewModel(
    private val params: ScanParams = ScanParams(),
    private val processor: PointCloudProcessor = PointCloudProcessor(params),
    private val calculator: VolumeCalculator = VolumeCalculator(params)
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState = _uiState.asStateFlow()

    private val _finalResult = MutableStateFlow<ScanSessionResult?>(null)
    val finalResult = _finalResult.asStateFlow()

    val points = mutableStateListOf<Position>()
    private val voxelGrid = mutableSetOf<String>()
    private var startPos: Position? = null

    fun toggleMeasuring() {
        val currentState = _uiState.value.isMeasuring
        if (!currentState) {
            // Iniciar nueva medición: limpiar datos previos
            reset()
            _uiState.value = _uiState.value.copy(isMeasuring = true)
        } else {
            // Detener medición: preparar datos para reporte y revisión
            _uiState.value = _uiState.value.copy(isMeasuring = false)
            prepareFinalResult()
        }
    }

    private fun prepareFinalResult() {
        if (points.isEmpty()) return
        
        val result = calculator.calculate(points.toList())
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minZ = points.minOf { it.z }
        val maxZ = points.maxOf { it.z }
        
        _finalResult.value = ScanSessionResult(
            volume = result.volume,
            length = (maxX - minX).toDouble(),
            maxHeight = result.topPoints.maxOfOrNull { it.y.toDouble() } ?: 0.0,
            maxWidth = (maxZ - minZ).toDouble(),
            points = points.toList(),
            topPoints = result.topPoints,
            confidence = 0.85f // Simulación de confianza
        )
    }

    fun onFrameUpdated(frame: Frame) {
        _uiState.value = _uiState.value.copy(trackingState = frame.camera.trackingState)
        
        if (!_uiState.value.isMeasuring) return

        val cameraPose = frame.camera.pose
        val currentPos = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        
        if (startPos == null) startPos = currentPos

        // Process point cloud
        val newPoints = processor.extractFilteredPoints(frame, currentPos, voxelGrid)
        if (newPoints.isNotEmpty()) {
            points.addAll(newPoints)
            updateMetrics(currentPos)
        }
    }

    private fun updateMetrics(currentPos: Position) {
        val result = calculator.calculate(points.toList())
        
        // Corrected Horizontal Distance with EMA
        val dx = currentPos.x - startPos!!.x
        val dz = currentPos.z - startPos!!.z
        val rawDist = sqrt((dx * dx + dz * dz).toDouble()) * params.distanceCorrectionFactor
        
        val currentDistance = _uiState.value.distance
        val smoothDist = if (currentDistance == 0.0) rawDist else currentDistance + params.emaAlpha * (rawDist - currentDistance)

        _uiState.value = _uiState.value.copy(
            stereoVolume = result.volume,
            netVolume = result.volume * 0.45,
            distance = smoothDist,
            topPoints = result.topPoints
        )
    }

    fun reset() {
        points.clear()
        voxelGrid.clear()
        startPos = null
        _uiState.value = ScanUiState(isMeasuring = false)
        _finalResult.value = null
    }
}
