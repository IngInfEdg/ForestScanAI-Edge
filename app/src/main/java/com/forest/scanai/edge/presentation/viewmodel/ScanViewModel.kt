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

    private fun computeRecentUsefulPointGrowthRatio(window: Int = 6): Float {
        val recent = usefulPointHistory.takeLast(window)
        if (recent.size < 2) return 1f
        val first = recent.first().coerceAtLeast(1)
        val last = recent.last()
        return ((last - first).toFloat() / first.toFloat()).coerceAtLeast(0f)
    }

    private fun computeRecentVolumeDeltaRatio(window: Int = 6): Float {
        val recent = volumeHistory.takeLast(window)
        if (recent.size < 2) return 1f
        val median = recent.sorted().let { sorted ->
            if (sorted.size % 2 == 0) (sorted[sorted.size / 2] + sorted[sorted.size / 2 - 1]) / 2.0
            else sorted[sorted.size / 2]
        }.coerceAtLeast(1e-6)
        val delta = ((recent.maxOrNull() ?: median) - (recent.minOrNull() ?: median)).coerceAtLeast(0.0)
        return (delta / median).toFloat().coerceAtLeast(0f)
    }

    fun toggleMeasuring() {
        if (!_uiState.value.isMeasuring) startMeasurement() else stopMeasurement()
    }

    private fun startMeasurement() {
        reset()
        _uiState.update { it.copy(isMeasuring = true, error = null) }
        gpsJob = viewModelScope.launch {
            locationProvider.locationUpdates().collectLatest { loc ->
                if (trajectory.isEmpty() || loc.distanceTo(trajectory.last()) > 1.5f) {
                    trajectory.add(loc)
                }
                refreshMeasurementState(force = true)
            }
        }
    }

    private fun stopMeasurement() {
        gpsJob?.cancel()
        val currentPoints = points.toList()

        // 1. Detección y Segmentación final
        val detection = pileObjectDetector.detect(currentPoints, observerPath.toList())
        val primaryPilePoints = if (detection.pilePoints.size >= 120) detection.pilePoints else currentPoints

        val segmented = segmenter.segment(primaryPilePoints)
        val refinedPilePoints = segmented?.pilePoints?.takeIf { it.isNotEmpty() } ?: primaryPilePoints
        val refinedGroundPoints = segmented?.groundPoints ?: detection.groundPoints

        // 2. Evaluaciones de Calidad
        val coverageResult = coverageEvaluator.evaluateFromObserverPath(observerPath.toList(), refinedPilePoints)
        val verticalCoverage = verticalCoverageEvaluator.evaluate(refinedPilePoints, observerPath.toList(), topCoverageHistory.toList())
        val trajectoryQuality = trajectoryQualityEvaluator.evaluate(observerPath = observerPath.toList())
        val volumeStability = volumeStabilityEvaluator.evaluate(volumeHistory.toList())

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        // 3. Validación de completitud y estado
        val completeness = completenessValidator.validate(
            coverageResult.coverageRatio, coverageResult.coveredSectors, observerPath.size,
            refinedPilePoints.size, arDistance, gpsDistance
        )

        val stateDecision = stateEvaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = completeness,
                coverageRatio = coverageResult.coverageRatio,
                coveredSectors = coverageResult.coveredSectors,
                observerSamples = observerPath.size,
                usefulPointCount = refinedPilePoints.size,
                trajectoryQualityScore = trajectoryQuality.trajectoryQualityScore,
                hasTrajectoryInstability = TrajectoryPenalty.LOW_KINEMATIC_CONSISTENCY in trajectoryQuality.penaltyFlags,
                verticalCoverageScore = verticalCoverage.verticalCoverageScore,
                weakVerticalBands = verticalCoverage.weakBands.size,
                missingLowerBand = VerticalCoveragePenalty.MISSING_LOWER_BAND in verticalCoverage.penaltyFlags,
                missingUpperBand = VerticalCoveragePenalty.MISSING_UPPER_BAND in verticalCoverage.penaltyFlags,
                supportsAcceptableVertical = verticalCoverage.supportsAcceptable,
                hasStrongMiddleConcentration = verticalCoverage.hasStrongMiddleConcentration,
                isVolumeStable = volumeStability.isStable,
                topCoverageScore = verticalCoverage.topCoverageScore,
                topCoverageState = verticalCoverage.topCoverageState,
                recentUsefulPointGrowthRatio = computeRecentUsefulPointGrowthRatio(),
                recentVolumeDeltaRatio = computeRecentVolumeDeltaRatio(),
                hasUsableDetection = detection.isRobust || detection.pilePoints.size >= 120,
                hasReviewableModel = refinedPilePoints.size >= 600
            )
        )

        // 4. Cálculo final de volumen
        val result = calculator.calculate(refinedPilePoints)
        val reviewModel = segmented?.let { reviewBuilder.build(it) }

        // 5. Construir Resultado Final
        _finalResult.value = ScanSessionResult(
            volume = result.volume,
            length = detection.boundingBox?.length?.toDouble() ?: 0.0,
            maxHeight = segmented?.heightSummary?.maxHeight ?: 0.0,
            maxWidth = detection.boundingBox?.width?.toDouble() ?: 0.0,
            points = currentPoints,
            topPoints = result.topPoints,
            trajectory = trajectory.toList(),
            observerPath = observerPath.toList(),
            coverage = coverageResult.coverageRatio,
            completeness = stateDecision.completeness,
            confidence = detection.detectionConfidence,
            pointsCount = currentPoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance,
            gpsPointCount = trajectory.size,
            coveredSectors = coverageResult.coveredSectors,
            totalSectors = coverageResult.totalSectors,
            missingSectors = coverageResult.missingSectors,
            guidanceSummary = stateDecision.shortGuidance,
            groundPoints = refinedGroundPoints,
            pileOnlyPoints = refinedPilePoints,
            reviewModelPoints = reviewModel?.points ?: emptyList(),
            appVersionDisplay = appVersionDisplay
        )

        _uiState.update {
            it.copy(
                isMeasuring = false,
                canReviewMeasurement = true,
                canFinishMeasurement = stateDecision.canFinish,
                metrics = it.metrics.copy(
                    completeness = stateDecision.completeness,
                    coveragePercentage = coverageResult.coverageRatio
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
        if (newPoints.isNotEmpty()) {
            points.addAll(newPoints)

            // Cálculo de volumen en tiempo real
            frameCounter++
            if (frameCounter % 5 == 0) {
                val calcResult = calculator.calculate(points.toList())
                _uiState.update {
                    it.copy(
                        metrics = it.metrics.copy(
                            stereoVolume = calcResult.volume,
                            netVolume = calcResult.netVolumeEstimate,
                            topPoints = calcResult.topPoints
                        )
                    )
                }

                volumeHistory.addLast(calcResult.volume)
                if (volumeHistory.size > 20) volumeHistory.removeFirst()
                usefulPointHistory.addLast(points.size)
                if (usefulPointHistory.size > 20) usefulPointHistory.removeFirst()
            }
        }

        val rawDist = sqrt((currentPos.x - startPos!!.x).toDouble().pow(2.0) + (currentPos.z - startPos!!.z).toDouble().pow(2.0))
        _uiState.update {
            it.copy(metrics = it.metrics.copy(distance = rawDist))
        }
        refreshMeasurementState()
    }

    private fun refreshMeasurementState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStateRefreshAtMs < 500L) return
        lastStateRefreshAtMs = now

        val currentPoints = points.toList()
        val coverageResult = coverageEvaluator.evaluateFromObserverPath(observerPath.toList(), currentPoints)
        val verticalCoverage = verticalCoverageEvaluator.evaluate(currentPoints, observerPath.toList(), topCoverageHistory.toList())
        topCoverageHistory.addLast(verticalCoverage.topCoverageScore)
        if (topCoverageHistory.size > 24) topCoverageHistory.removeFirst()

        val arDistance = calculateArDistance()
        val gpsDistance = calculateGpsDistance()

        val completeness = completenessValidator.validate(
            coverageResult.coverageRatio, coverageResult.coveredSectors, observerPath.size,
            currentPoints.size, arDistance, gpsDistance
        )

        val guidance = guidanceEngine.buildMessage(
            completeness = completeness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = currentPoints.size,
            missingUpperBand = VerticalCoveragePenalty.MISSING_UPPER_BAND in verticalCoverage.penaltyFlags,
            lowTopCoverage = verticalCoverage.topCoverageScore < 0.55f,
            topCoverageState = verticalCoverage.topCoverageState,
            autoCompletionCandidate = false
        )

        _uiState.update {
            it.copy(
                guidanceMessage = guidance,
                metrics = it.metrics.copy(
                    coveragePercentage = coverageResult.coverageRatio,
                    coveredSectors = coverageResult.coveredSectors,
                    completeness = completeness,
                    arDistanceWalked = arDistance,
                    gpsDistanceWalked = gpsDistance
                )
            )
        }
    }

    private fun calculateGpsDistance(): Double = trajectory.zipWithNext { a, b -> a.distanceTo(b).toDouble() }.sum()
    private fun calculateArDistance(): Double = observerPath.zipWithNext { a, b -> distanceBetween(a, b) }.sum()
    private fun distanceBetween(a: Position, b: Position): Double = sqrt((a.x - b.x).toDouble().pow(2.0) + (a.z - b.z).toDouble().pow(2.0))

    fun reset() {
        points.clear()
        voxelGrid.clear()
        trajectory.clear()
        observerPath.clear()
        volumeHistory.clear()
        usefulPointHistory.clear()
        topCoverageHistory.clear()
        _uiState.update { ScanUiState(metrics = ScanMetrics(appVersionDisplay = appVersionDisplay)) }
        _finalResult.value = null
        gpsJob?.cancel()
        startPos = null
    }
}
