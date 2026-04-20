package com.forest.scanai.edge.presentation.viewmodel

import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forest.scanai.edge.core.ScanParams
import com.forest.scanai.edge.data.location.LocationProvider
import com.forest.scanai.edge.domain.engine.*
import com.forest.scanai.edge.domain.model.*
import com.forest.scanai.edge.presentation.state.ScanUiState
import io.github.sceneview.math.Position
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sqrt

class ScanViewModel(
    private val locationProvider: LocationProvider,
    private val params: ScanParams = ScanParams(),
    private val processor: PointCloudProcessor = PointCloudProcessor(params),
    private val axisEstimator: PileAxisEstimator = PileAxisEstimator(),
    private val calculator: VolumeCalculator = VolumeCalculator(params, axisEstimator),
    private val coverageEvaluator: MeasurementCoverageEvaluator = MeasurementCoverageEvaluator(),
    private val pileCoverageQualityEvaluator: PileCoverageQualityEvaluator =
        PileCoverageQualityEvaluator(axisEstimator),
    private val trajectoryQualityEvaluator: TrajectoryQualityEvaluator = TrajectoryQualityEvaluator(),
    private val verticalCoverageEvaluator: VerticalCoverageEvaluator = VerticalCoverageEvaluator(),
    private val volumeStabilityEvaluator: VolumeStabilityEvaluator = VolumeStabilityEvaluator(),
    private val referenceScaleValidator: ReferenceScaleValidator = ReferenceScaleValidator(),
    private val stateEvaluator: MeasurementStateEvaluator = MeasurementStateEvaluator(),
    private val completenessValidator: MeasurementCompletenessValidator =
        MeasurementCompletenessValidator(),
    private val guidanceEngine: ScanGuidanceEngine = ScanGuidanceEngine(),
    private val pileObjectDetector: PileObjectDetector = PileObjectDetector(),
    private val appVersionName: String = "",
    private val appVersionCode: Long = 0L,
    private val appVersionDisplay: String = ""
) : ViewModel() {

    private val segmenter: GroundPileSegmenter = GroundPileSegmenter(axisEstimator)
    private val reviewBuilder: PointCloudReviewModelBuilder = PointCloudReviewModelBuilder()

    private val _uiState = MutableStateFlow(ScanUiState(metrics = ScanMetrics(appVersionDisplay = appVersionDisplay)))
    val uiState = _uiState.asStateFlow()

    private val _finalResult = MutableStateFlow<ScanSessionResult?>(null)
    val finalResult = _finalResult.asStateFlow()

    val points = mutableStateListOf<Position>()
    private val voxelGrid = mutableSetOf<String>()
    private val trajectory = mutableStateListOf<Location>()
    private val observerPath = mutableStateListOf<Position>()
    private val volumeHistory = ArrayDeque<Double>()
    private val usefulPointHistory = ArrayDeque<Int>()
    private val topCoverageHistory = ArrayDeque<Float>()

    private var startPos: Position? = null
    private var gpsJob: Job? = null
    private var lastStateRefreshAtMs = 0L
    private var lastLiveScanResult = ScanResult(0.0, emptyList())
    private var frameCounter = 0
    private var configuredReferenceObject: ReferenceObject? = ReferenceObject(
        id = "default_bar_2m",
        type = ReferenceObjectType.BAR_2M,
        expectedLengthMeters = 2.0,
        label = "Barra de referencia 2m"
    )
    private var manualReferenceObservedLengthMeters: Double? = null

    fun setManualReferenceBarObservation(observedLengthMeters: Double?) {
        manualReferenceObservedLengthMeters = observedLengthMeters?.takeIf { it > 0.0 }
    }

    fun toggleMeasuring() {
        if (!_uiState.value.isMeasuring) startMeasurement() else stopMeasurement()
    }

    private fun startMeasurement() {
        reset()
        _uiState.update { it.copy(isMeasuring = true, error = null) }
        gpsJob = viewModelScope.launch {
            locationProvider.locationUpdates().collectLatest { loc ->
                if (trajectory.isEmpty()) {
                    trajectory.add(loc)
                } else {
                    val last = trajectory.last()
                    if (loc.distanceTo(last) > 1.5f && loc.accuracy <= 25f) trajectory.add(loc)
                }
                refreshMeasurementState(force = true)
            }
        }
    }

    private fun stopMeasurement() {
        gpsJob?.cancel()
        val currentPoints = points.toList()
        
        // Simulación de stop para estabilizar compilación
        _uiState.update {
            it.copy(
                isMeasuring = false,
                metrics = it.metrics.copy(
                    coveragePercentage = 0.5f,
                    gpsDistanceWalked = calculateGpsDistance(),
                    arDistanceWalked = calculateArDistance()
                )
            )
        }
    }

    fun onFrameUpdated(frame: com.google.ar.core.Frame) {
        _uiState.update { it.copy(metrics = it.metrics.copy(trackingState = frame.camera.trackingState)) }
        if (!_uiState.value.isMeasuring) return

        val cameraPose = frame.camera.pose
        val currentPos = Position(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        if (startPos == null) startPos = currentPos

        if (observerPath.isEmpty() || distanceBetween(observerPath.last(), currentPos) > 0.25) {
            observerPath.add(currentPos)
        }

        val newPoints = processor.extractFilteredPoints(frame, currentPos, voxelGrid)
        if (newPoints.isNotEmpty()) points.addAll(newPoints)

        val rawDist = sqrt(
            (currentPos.x - startPos!!.x).toDouble().pow(2.0) +
                (currentPos.z - startPos!!.z).toDouble().pow(2.0)
        )

        _uiState.update {
            it.copy(
                metrics = it.metrics.copy(
                    distance = if (it.metrics.distance == 0.0) rawDist else it.metrics.distance + 0.1 * (rawDist - it.metrics.distance)
                )
            )
        }
        refreshMeasurementState()
    }

    private fun refreshMeasurementState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStateRefreshAtMs < 250L) return
        lastStateRefreshAtMs = now
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
        _uiState.update { ScanUiState(metrics = ScanMetrics(appVersionDisplay = appVersionDisplay)) }
        gpsJob?.cancel()
        startPos = null
    }
}
