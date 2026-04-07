package com.forest.scanai.presentation

import android.location.Location
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forest.scanai.core.ScanParams
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.domain.engine.MeasurementCompletenessValidator
import com.forest.scanai.domain.engine.MeasurementCoverageEvaluator
import com.forest.scanai.domain.engine.PointCloudProcessor
import com.forest.scanai.domain.engine.ScanGuidanceEngine
import com.forest.scanai.domain.engine.VolumeCalculator
import com.forest.scanai.domain.model.CompletenessLevel
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.domain.model.ScanUiState
import io.github.sceneview.math.Position
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sqrt

class ScanViewModel(
    private val locationProvider: LocationProvider,
    private val params: ScanParams = ScanParams(),
    private val processor: PointCloudProcessor = PointCloudProcessor(params),
    private val calculator: VolumeCalculator = VolumeCalculator(params),
    private val coverageEvaluator: MeasurementCoverageEvaluator = MeasurementCoverageEvaluator(),
    private val completenessValidator: MeasurementCompletenessValidator = MeasurementCompletenessValidator(),
    private val guidanceEngine: ScanGuidanceEngine = ScanGuidanceEngine()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState = _uiState.asStateFlow()

    private val _finalResult = MutableStateFlow<ScanSessionResult?>(null)
    val finalResult = _finalResult.asStateFlow()

    val points = mutableStateListOf<Position>()

    private val voxelGrid = mutableSetOf<String>()
    private val trajectory = mutableStateListOf<Location>()
    private val observerPath = mutableStateListOf<Position>()

    private var startPos: Position? = null
    private var gpsJob: Job? = null

    fun toggleMeasuring() {
        if (!_uiState.value.isMeasuring) {
            startMeasurement()
        } else {
            stopMeasurement()
        }
    }

    private fun startMeasurement() {
        reset()
        _uiState.update {
            it.copy(
                isMeasuring = true,
                error = null,
                guidanceMessage = "Comienza a rodear la pila manteniendo la cámara apuntando al material."
            )
        }

        gpsJob = viewModelScope.launch {
            locationProvider.locationUpdates().collectLatest { loc ->
                if (trajectory.isEmpty()) {
                    trajectory.add(loc)
                } else {
                    val last = trajectory.last()
                    val movedEnough = loc.distanceTo(last) > 1.5f
                    val betterAccuracy = loc.accuracy <= 25f
                    if (movedEnough && betterAccuracy) {
                        trajectory.add(loc)
                    }
                }
                refreshMeasurementState()
            }
        }
    }

    private fun stopMeasurement() {
        gpsJob?.cancel()

        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = points.toList()
        )

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val completeness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = points.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
        )

        val guidance = guidanceEngine.buildMessage(
            completeness = completeness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = points.size
        )

        if (completeness == CompletenessLevel.INSUFFICIENT || completeness == CompletenessLevel.PARTIAL) {
            _uiState.update {
                it.copy(
                    isMeasuring = false,
                    coveragePercentage = coverageResult.coverageRatio,
                    coveredSectors = coverageResult.coveredSectors,
                    totalSectors = coverageResult.totalSectors,
                    gpsPointCount = trajectory.size,
                    observerSampleCount = observerPath.size,
                    gpsDistanceWalked = gpsDistance,
                    arDistanceWalked = arDistance,
                    completeness = completeness,
                    guidanceMessage = guidance,
                    canFinishMeasurement = false,
                    error = "Medición incompleta. $guidance"
                )
            }
            return
        }

        val result = calculator.calculate(points.toList())

        val length = if (points.isNotEmpty()) {
            (points.maxOf { it.x } - points.minOf { it.x }).toDouble()
        } else 0.0

        val maxWidth = if (points.isNotEmpty()) {
            (points.maxOf { it.z } - points.minOf { it.z }).toDouble()
        } else 0.0

        _finalResult.value = ScanSessionResult(
            volume = result.volume,
            length = length,
            maxHeight = result.topPoints.maxOfOrNull { it.y }?.toDouble() ?: 0.0,
            maxWidth = maxWidth,
            points = points.toList(),
            topPoints = result.topPoints,
            trajectory = trajectory.toList(),
            observerPath = observerPath.toList(),
            coverage = coverageResult.coverageRatio,
            completeness = completeness,
            confidence = when (completeness) {
                CompletenessLevel.COMPLETE -> 0.95f
                CompletenessLevel.ACCEPTABLE -> 0.80f
                else -> 0.50f
            },
            pointsCount = points.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance,
            gpsPointCount = trajectory.size,
            coveredSectors = coverageResult.coveredSectors,
            totalSectors = coverageResult.totalSectors,
            missingSectors = coverageResult.missingSectors,
            guidanceSummary = guidance
        )

        _uiState.update {
            it.copy(
                isMeasuring = false,
                coveragePercentage = coverageResult.coverageRatio,
                coveredSectors = coverageResult.coveredSectors,
                totalSectors = coverageResult.totalSectors,
                gpsPointCount = trajectory.size,
                observerSampleCount = observerPath.size,
                gpsDistanceWalked = gpsDistance,
                arDistanceWalked = arDistance,
                completeness = completeness,
                guidanceMessage = guidance,
                canFinishMeasurement = true,
                error = null
            )
        }
    }

    fun onFrameUpdated(frame: com.google.ar.core.Frame) {
        _uiState.update { it.copy(trackingState = frame.camera.trackingState) }
        if (!_uiState.value.isMeasuring) return

        val cameraPose = frame.camera.pose
        val currentPos = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        if (startPos == null) startPos = currentPos

        if (observerPath.isEmpty() || distanceBetween(observerPath.last(), currentPos) > 0.25) {
            observerPath.add(currentPos)
        }

        val newPoints = processor.extractFilteredPoints(frame, currentPos, voxelGrid)
        if (newPoints.isNotEmpty()) {
            points.addAll(newPoints)
        }

        val calcResult = calculator.calculate(points.toList())

        val rawDist = sqrt(
            (currentPos.x - startPos!!.x).toDouble().pow(2.0) +
                    (currentPos.z - startPos!!.z).toDouble().pow(2.0)
        ) * params.distanceCorrectionFactor

        _uiState.update {
            it.copy(
                stereoVolume = calcResult.volume,
                netVolume = calcResult.volume * 0.45,
                distance = if (it.distance == 0.0) {
                    rawDist
                } else {
                    it.distance + params.emaAlpha * (rawDist - it.distance)
                },
                topPoints = calcResult.topPoints
            )
        }

        refreshMeasurementState()
    }

    private fun refreshMeasurementState() {
        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = points.toList()
        )

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val completeness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = points.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
        )

        val guidance = guidanceEngine.buildMessage(
            completeness = completeness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = points.size
        )

        _uiState.update {
            it.copy(
                coveragePercentage = coverageResult.coverageRatio,
                coveredSectors = coverageResult.coveredSectors,
                totalSectors = coverageResult.totalSectors,
                gpsPointCount = trajectory.size,
                observerSampleCount = observerPath.size,
                gpsDistanceWalked = gpsDistance,
                arDistanceWalked = arDistance,
                completeness = completeness,
                guidanceMessage = guidance,
                canFinishMeasurement = completeness == CompletenessLevel.ACCEPTABLE ||
                        completeness == CompletenessLevel.COMPLETE
            )
        }
    }

    private fun calculateGpsDistance(): Double {
        if (trajectory.size < 2) return 0.0
        return trajectory.zipWithNext { a, b -> a.distanceTo(b).toDouble() }.sum()
    }

    private fun calculateArDistance(): Double {
        if (observerPath.size < 2) return 0.0
        return observerPath.zipWithNext { a, b -> distanceBetween(a, b) }.sum()
    }

    private fun distanceBetween(a: Position, b: Position): Double {
        return sqrt(
            (a.x - b.x).toDouble().pow(2.0) +
                    (a.z - b.z).toDouble().pow(2.0)
        )
    }

    fun reset() {
        points.clear()
        voxelGrid.clear()
        trajectory.clear()
        observerPath.clear()
        startPos = null
        gpsJob?.cancel()
        _uiState.value = ScanUiState()
        _finalResult.value = null
    }
}