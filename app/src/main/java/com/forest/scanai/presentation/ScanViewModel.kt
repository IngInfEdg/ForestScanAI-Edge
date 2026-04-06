package com.forest.scanai.presentation

import android.location.Location
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forest.scanai.core.ScanParams
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.domain.engine.PointCloudProcessor
import com.forest.scanai.domain.engine.VolumeCalculator
import com.forest.scanai.domain.model.CompletenessLevel
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.domain.model.ScanUiState
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.math.Position
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class ScanViewModel(
    private val locationProvider: LocationProvider,
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

    // Trayectoria GPS
    private val _trajectory = mutableStateListOf<Location>()
    private var gpsJob: Job? = null

    fun toggleMeasuring() {
        val isMeasuring = _uiState.value.isMeasuring
        if (!isMeasuring) {
            startMeasurement()
        } else {
            stopMeasurement()
        }
    }

    private fun startMeasurement() {
        reset()
        _uiState.update { it.copy(isMeasuring = true, error = null) }
        startGpsTracking()
    }

    private fun stopMeasurement() {
        val coverage = calculateCoverage()
        val completeness = evaluateCompleteness(coverage)
        
        if (completeness == CompletenessLevel.INSUFFICIENT) {
            _uiState.update { it.copy(error = "Recorrido insuficiente. Debe rodear más la pila.") }
            // No detenemos isMeasuring para forzar a que siga caminando, 
            // o bien detenemos y mostramos el error. 
            // Por requerimiento de "bloqueo", impediremos el cierre exitoso.
            return
        }

        _uiState.update { it.copy(isMeasuring = false) }
        stopGpsTracking()
        prepareFinalResult(coverage, completeness)
    }

    private fun startGpsTracking() {
        gpsJob = viewModelScope.launch {
            while (true) {
                locationProvider.getCurrentLocation()?.let { location ->
                    if (_trajectory.isEmpty() || location.distanceTo(_trajectory.last()) > 1.0) {
                        _trajectory.add(location)
                    }
                }
                delay(2000)
            }
        }
    }

    private fun stopGpsTracking() {
        gpsJob?.cancel()
        gpsJob = null
    }

    private fun calculateCoverage(): Float {
        if (points.isEmpty()) return 0f
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minZ = points.minOf { it.z }
        val maxZ = points.maxOf { it.z }
        
        // Área proyectada en el suelo
        val area = (maxX - minX) * (maxZ - minZ)
        // Normalización experimental: 15m2 de base para una pila estándar
        return (area / 15f).coerceIn(0f, 1f)
    }

    private fun evaluateCompleteness(coverage: Float): CompletenessLevel {
        val gpsDist = calculateGpsDistance()
        return when {
            coverage > 0.7f && gpsDist > 12.0 -> CompletenessLevel.COMPLETE
            coverage > 0.4f && gpsDist > 6.0 -> CompletenessLevel.ACCEPTABLE
            coverage > 0.1f -> CompletenessLevel.PARTIAL
            else -> CompletenessLevel.INSUFFICIENT
        }
    }

    private fun calculateGpsDistance(): Float {
        var total = 0f
        for (i in 0 until _trajectory.size - 1) {
            total += _trajectory[i].distanceTo(_trajectory[i+1])
        }
        return total
    }

    private fun prepareFinalResult(coverage: Float, completeness: CompletenessLevel) {
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
            trajectory = _trajectory.toList(),
            coverage = coverage,
            completeness = completeness,
            confidence = 0.85f,
            pointsCount = points.size
        )
    }

    fun onFrameUpdated(frame: Frame) {
        _uiState.update { it.copy(trackingState = frame.camera.trackingState) }
        
        if (!_uiState.value.isMeasuring) return

        val cameraPose = frame.camera.pose
        val currentPos = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        
        if (startPos == null) startPos = currentPos

        val newPoints = processor.extractFilteredPoints(frame, currentPos, voxelGrid)
        if (newPoints.isNotEmpty()) {
            points.addAll(newPoints)
            updateMetrics(currentPos)
        }
    }

    private fun updateMetrics(currentPos: Position) {
        val result = calculator.calculate(points.toList())
        
        val dx = currentPos.x - startPos!!.x
        val dz = currentPos.z - startPos!!.z
        val rawDist = sqrt((dx * dx + dz * dz).toDouble()) * params.distanceCorrectionFactor
        
        val currentDistance = _uiState.value.distance
        val smoothDist = if (currentDistance == 0.0) rawDist else currentDistance + params.emaAlpha * (rawDist - currentDistance)

        _uiState.update { it.copy(
            stereoVolume = result.volume,
            netVolume = result.volume * 0.45,
            distance = smoothDist,
            topPoints = result.topPoints,
            coveragePercentage = calculateCoverage()
        ) }
    }

    fun reset() {
        points.clear()
        voxelGrid.clear()
        _trajectory.clear()
        startPos = null
        _uiState.value = ScanUiState()
        _finalResult.value = null
        stopGpsTracking()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
