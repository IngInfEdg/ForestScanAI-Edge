package com.forest.scanai.presentation
import com.forest.scanai.domain.engine.GroundPileSegmenter
import com.forest.scanai.domain.engine.PointCloudReviewModelBuilder
import android.location.Location
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forest.scanai.core.ScanParams
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.domain.engine.MeasurementCompletenessValidator
import com.forest.scanai.domain.engine.MeasurementCoverageEvaluator
import com.forest.scanai.domain.engine.MeasurementStateEvaluator
import com.forest.scanai.domain.engine.MeasurementStateInput
import com.forest.scanai.domain.engine.PileAxisEstimator
import com.forest.scanai.domain.engine.PileCoverageQualityEvaluator
import com.forest.scanai.domain.engine.PileCoverageQualityLevel
import com.forest.scanai.domain.engine.PointCloudProcessor
import com.forest.scanai.domain.engine.ScanGuidanceEngine
import com.forest.scanai.domain.engine.TrajectoryQualityEvaluator
import com.forest.scanai.domain.engine.VerticalCoverageEvaluator
import com.forest.scanai.domain.engine.VolumeStabilityEvaluator
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
    private val axisEstimator: PileAxisEstimator = PileAxisEstimator(),
    private val calculator: VolumeCalculator = VolumeCalculator(params, axisEstimator),
    private val coverageEvaluator: MeasurementCoverageEvaluator = MeasurementCoverageEvaluator(),
    private val pileCoverageQualityEvaluator: PileCoverageQualityEvaluator =
        PileCoverageQualityEvaluator(axisEstimator),
    private val trajectoryQualityEvaluator: TrajectoryQualityEvaluator = TrajectoryQualityEvaluator(),
    private val verticalCoverageEvaluator: VerticalCoverageEvaluator = VerticalCoverageEvaluator(),
    private val volumeStabilityEvaluator: VolumeStabilityEvaluator = VolumeStabilityEvaluator(),
    private val stateEvaluator: MeasurementStateEvaluator = MeasurementStateEvaluator(),
    private val completenessValidator: MeasurementCompletenessValidator =
        MeasurementCompletenessValidator(),
    private val guidanceEngine: ScanGuidanceEngine = ScanGuidanceEngine()
) : ViewModel() {
    private val segmenter: GroundPileSegmenter = GroundPileSegmenter(axisEstimator)
    private val reviewBuilder: PointCloudReviewModelBuilder = PointCloudReviewModelBuilder()
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState = _uiState.asStateFlow()

    private val _finalResult = MutableStateFlow<ScanSessionResult?>(null)
    val finalResult = _finalResult.asStateFlow()

    val points = mutableStateListOf<Position>()

    private val voxelGrid = mutableSetOf<String>()
    private val trajectory = mutableStateListOf<Location>()
    private val observerPath = mutableStateListOf<Position>()
    private val volumeHistory = ArrayDeque<Double>()

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
                guidanceMessage = "Rodea la pila para iniciar la medición."
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

        val currentPoints = points.toList()

        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = currentPoints
        )
        val trajectoryQuality = trajectoryQualityEvaluator.evaluate(observerPath = observerPath.toList())
        val verticalCoverage = verticalCoverageEvaluator.evaluate(
            pilePoints = currentPoints,
            observerPath = observerPath.toList()
        )
        val volumeStability = volumeStabilityEvaluator.evaluate(volumeHistory.toList())

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val completeness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = currentPoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
        )
        val stateDecision = stateEvaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = completeness,
                trajectoryQualityScore = trajectoryQuality.trajectoryQualityScore,
                verticalCoverageScore = verticalCoverage.verticalCoverageScore,
                weakVerticalBands = verticalCoverage.weakBands.size,
                supportsAcceptableVertical = verticalCoverage.supportsAcceptable,
                hasStrongMiddleConcentration = verticalCoverage.hasStrongMiddleConcentration,
                isVolumeStable = volumeStability.isStable
            )
        )
        val gatedCompleteness = stateDecision.completeness

        val guidance = guidanceEngine.buildMessage(
            completeness = gatedCompleteness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = currentPoints.size
        )

        val gatedGuidance = buildString {
            append(guidance)
            verticalCoverage.reasons.forEach { append(" $it") }
            trajectoryQuality.penaltyReasons.forEach { append(" $it") }
            stateDecision.blockers.forEach { append(" $it") }
            volumeStability.reasons.forEach { append(" $it") }
        }.trim()

        if (gatedCompleteness == CompletenessLevel.INSUFFICIENT || gatedCompleteness == CompletenessLevel.PARTIAL) {
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
                    completeness = gatedCompleteness,
                    guidanceMessage = gatedGuidance,
                    canFinishMeasurement = false,
                    error = "Medición incompleta. $gatedGuidance"
                )
            }
            return
        }

        val result = calculator.calculate(currentPoints)
        val axisResult = axisEstimator.estimate(currentPoints)
        val cloudQuality = pileCoverageQualityEvaluator.evaluate(currentPoints)

// 🔥 NUEVO — segmentación suelo vs pila
        val segmented = segmenter.segment(currentPoints)

// 🔥 NUEVO — modelo 3D
        val reviewModel = segmented?.let { reviewBuilder.build(it) }

// 🔥 NUEVO — alturas reales
        val heightSummary = segmented?.heightSummary

        val groundPoints = segmented?.groundPoints ?: emptyList()
        val pilePoints = segmented?.pilePoints ?: currentPoints

        val maxHeight = heightSummary?.maxHeight
            ?: result.topPoints.maxOfOrNull { it.y }?.toDouble() ?: 0.0

        val adjustedCompleteness = when {
            cloudQuality == null -> completeness

            completeness == CompletenessLevel.COMPLETE &&
                    cloudQuality.qualityLevel != PileCoverageQualityLevel.COMPLETE -> {
                CompletenessLevel.ACCEPTABLE
            }

            completeness == CompletenessLevel.ACCEPTABLE &&
                    cloudQuality.qualityLevel == PileCoverageQualityLevel.POOR -> {
                CompletenessLevel.PARTIAL
            }

            completeness == CompletenessLevel.COMPLETE &&
                    cloudQuality.qualityLevel == PileCoverageQualityLevel.FAIR -> {
                CompletenessLevel.PARTIAL
            }

            else -> completeness
        }
        val finalCompleteness = stateDecision.completeness.capAt(adjustedCompleteness)

        val adjustedGuidance = buildString {
            append(gatedGuidance)

            if (cloudQuality != null) {
                if (!cloudQuality.edgeCoverageStart || !cloudQuality.edgeCoverageEnd) {
                    append(" Falta cubrir uno o ambos extremos de la pila.")
                }

                if (cloudQuality.verticalCoverageRatio < 0.50f) {
                    append(" Falta capturar mejor la altura del material.")
                }

                if (cloudQuality.longitudinalCoverageRatio < 0.70f) {
                    append(" La nube aún no cubre bien todo el largo de la pila.")
                }
            }
        }

        val length = axisResult?.length
            ?: if (currentPoints.isNotEmpty()) {
                (currentPoints.maxOf { it.x } - currentPoints.minOf { it.x }).toDouble()
            } else {
                0.0
            }

        val maxWidth = axisResult?.width
            ?: if (currentPoints.isNotEmpty()) {
                (currentPoints.maxOf { it.z } - currentPoints.minOf { it.z }).toDouble()
            } else {
                0.0
            }

        val finalConfidence = (
                when (finalCompleteness) {
                    CompletenessLevel.COMPLETE -> 0.95f
                    CompletenessLevel.ACCEPTABLE -> 0.80f
                    CompletenessLevel.PARTIAL -> 0.60f
                    CompletenessLevel.INSUFFICIENT -> 0.40f
                } - (cloudQuality?.confidencePenalty ?: 0f)
                ).coerceIn(0.20f, 0.95f)

        _finalResult.value = ScanSessionResult(
            volume = result.volume,
            length = length,
            maxHeight = maxHeight,
            maxWidth = maxWidth,

            points = currentPoints,
            topPoints = result.topPoints,

            trajectory = trajectory.toList(),
            observerPath = observerPath.toList(),

            coverage = coverageResult.coverageRatio,
            completeness = finalCompleteness,
            confidence = finalConfidence,

            pointsCount = currentPoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance,
            gpsPointCount = trajectory.size,

            coveredSectors = coverageResult.coveredSectors,
            totalSectors = coverageResult.totalSectors,
            missingSectors = coverageResult.missingSectors,

            guidanceSummary = adjustedGuidance,

            // 🔥 NUEVO
            groundPoints = groundPoints,
            pileOnlyPoints = pilePoints,

            groundReference = heightSummary?.groundReference ?: 0.0,
            pileBaseReference = heightSummary?.pileBaseReference ?: 0.0,
            meanPileHeight = heightSummary?.meanHeight ?: 0.0,
            p95PileHeight = heightSummary?.p95Height ?: 0.0,

            reviewModelPoints = reviewModel?.points ?: emptyList(),
            reviewModelWidth = reviewModel?.width ?: 0f,
            reviewModelHeight = reviewModel?.height ?: 0f,
            reviewModelDepth = reviewModel?.depth ?: 0f
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
                completeness = finalCompleteness,
                guidanceMessage = adjustedGuidance,
                canFinishMeasurement = stateDecision.canFinish,
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
        volumeHistory.addLast(calcResult.volume)
        if (volumeHistory.size > 20) {
            volumeHistory.removeFirst()
        }

        refreshMeasurementState()
    }

    private fun refreshMeasurementState() {
        val currentPoints = points.toList()

        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = currentPoints
        )

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val baseCompleteness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = currentPoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
        )
        val trajectoryQuality = trajectoryQualityEvaluator.evaluate(observerPath = observerPath.toList())
        val verticalCoverage = verticalCoverageEvaluator.evaluate(
            pilePoints = currentPoints,
            observerPath = observerPath.toList()
        )
        val volumeStability = volumeStabilityEvaluator.evaluate(volumeHistory.toList())

        val cloudQuality = pileCoverageQualityEvaluator.evaluate(currentPoints)
        val stateDecision = stateEvaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = baseCompleteness,
                trajectoryQualityScore = trajectoryQuality.trajectoryQualityScore,
                verticalCoverageScore = verticalCoverage.verticalCoverageScore,
                weakVerticalBands = verticalCoverage.weakBands.size,
                supportsAcceptableVertical = verticalCoverage.supportsAcceptable,
                hasStrongMiddleConcentration = verticalCoverage.hasStrongMiddleConcentration,
                isVolumeStable = volumeStability.isStable
            )
        )

        val adjustedCompleteness = when {
            cloudQuality == null -> stateDecision.completeness

            stateDecision.completeness == CompletenessLevel.COMPLETE &&
                    cloudQuality.qualityLevel != PileCoverageQualityLevel.COMPLETE -> {
                CompletenessLevel.ACCEPTABLE
            }

            stateDecision.completeness == CompletenessLevel.ACCEPTABLE &&
                    cloudQuality.qualityLevel == PileCoverageQualityLevel.POOR -> {
                CompletenessLevel.PARTIAL
            }

            stateDecision.completeness == CompletenessLevel.COMPLETE &&
                    cloudQuality.qualityLevel == PileCoverageQualityLevel.FAIR -> {
                CompletenessLevel.PARTIAL
            }

            else -> stateDecision.completeness
        }

        val guidance = guidanceEngine.buildMessage(
            completeness = adjustedCompleteness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = currentPoints.size
        )

        val adjustedGuidance = buildString {
            append(guidance)
            verticalCoverage.reasons.forEach { append(" $it") }
            trajectoryQuality.penaltyReasons.forEach { append(" $it") }
            stateDecision.blockers.forEach { append(" $it") }
            volumeStability.reasons.forEach { append(" $it") }

            if (cloudQuality != null) {
                if (!cloudQuality.edgeCoverageStart || !cloudQuality.edgeCoverageEnd) {
                    append(" Falta cubrir uno o ambos extremos de la pila.")
                }

                if (cloudQuality.verticalCoverageRatio < 0.50f) {
                    append(" Falta capturar mejor la altura del material.")
                }

                if (cloudQuality.longitudinalCoverageRatio < 0.70f) {
                    append(" La nube aún no cubre bien todo el largo de la pila.")
                }
            }
        }

        _uiState.update {
            it.copy(
                coveragePercentage = coverageResult.coverageRatio,
                coveredSectors = coverageResult.coveredSectors,
                totalSectors = coverageResult.totalSectors,
                gpsPointCount = trajectory.size,
                observerSampleCount = observerPath.size,
                gpsDistanceWalked = gpsDistance,
                arDistanceWalked = arDistance,
                completeness = adjustedCompleteness,
                guidanceMessage = adjustedGuidance,
                canFinishMeasurement = stateDecision.canFinish &&
                    (adjustedCompleteness == CompletenessLevel.ACCEPTABLE || adjustedCompleteness == CompletenessLevel.COMPLETE)
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
        volumeHistory.clear()
        startPos = null
        gpsJob?.cancel()
        _uiState.value = ScanUiState()
        _finalResult.value = null
    }

    private fun CompletenessLevel.capAt(maximum: CompletenessLevel): CompletenessLevel {
        return if (this.ordinal > maximum.ordinal) maximum else this
    }
}
