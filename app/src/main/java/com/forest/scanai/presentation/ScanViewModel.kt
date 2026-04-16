package com.forest.scanai.presentation

import android.location.Location
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forest.scanai.core.ScanParams
import com.forest.scanai.data.location.LocationProvider
import com.forest.scanai.domain.engine.GroundPileSegmenter
import com.forest.scanai.domain.engine.MeasurementCompletenessValidator
import com.forest.scanai.domain.engine.MeasurementCoverageEvaluator
import com.forest.scanai.domain.engine.MeasurementStateEvaluator
import com.forest.scanai.domain.engine.MeasurementStateInput
import com.forest.scanai.domain.engine.PileAxisEstimator
import com.forest.scanai.domain.engine.PileCoverageQualityEvaluator
import com.forest.scanai.domain.engine.PileCoverageQualityLevel
import com.forest.scanai.domain.engine.PileObjectDetector
import com.forest.scanai.domain.engine.PointCloudProcessor
import com.forest.scanai.domain.engine.PointCloudReviewModelBuilder
import com.forest.scanai.domain.engine.ReferenceScaleValidator
import com.forest.scanai.domain.engine.ScanGuidanceEngine
import com.forest.scanai.domain.engine.TopCoverageState
import com.forest.scanai.domain.engine.TrajectoryQualityEvaluator
import com.forest.scanai.domain.engine.VerticalCoverageEvaluator
import com.forest.scanai.domain.engine.VerticalCoveragePenalty
import com.forest.scanai.domain.engine.VolumeCalculator
import com.forest.scanai.domain.engine.VolumeStabilityEvaluator
import com.forest.scanai.domain.engine.TrajectoryPenalty
import com.forest.scanai.domain.model.CompletenessLevel
import com.forest.scanai.domain.model.ReferenceObject
import com.forest.scanai.domain.model.ReferenceObjectType
import com.forest.scanai.domain.model.ScanSessionResult
import com.forest.scanai.domain.model.ScanUiState
import io.github.sceneview.math.Position
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.os.SystemClock
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

    private val _uiState = MutableStateFlow(ScanUiState(appVersionDisplay = appVersionDisplay))
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
    private var lastDetectionDebug: Map<String, String> = emptyMap()
    private var lastDetection = pileObjectDetector.detect(emptyList())
    private var lastDetectionPointCount = 0
    private var lastDetectionObserverCount = 0
    private var lastStateRefreshAtMs = 0L
    private var lastLiveScanResult = com.forest.scanai.domain.model.ScanResult(0.0, emptyList())
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

    private fun selectPilePreferredPoints(
        detection: com.forest.scanai.domain.engine.PileDetectionResult,
        fallbackPoints: List<Position>,
        minPilePoints: Int
    ): List<Position> {
        return if (detection.pilePoints.size >= minPilePoints) {
            detection.pilePoints
        } else {
            fallbackPoints
        }
    }

    fun toggleMeasuring() {
        if (!_uiState.value.isMeasuring) startMeasurement() else stopMeasurement()
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
                    if (loc.distanceTo(last) > 1.5f && loc.accuracy <= 25f) trajectory.add(loc)
                }
                refreshMeasurementState(force = true)
            }
        }
    }

    private fun stopMeasurement() {
        gpsJob?.cancel()

        val currentPoints = points.toList()
        val detection = getDetectionSnapshot(currentPoints, observerPath.toList(), force = true)
        val primaryPilePoints = selectPilePreferredPoints(
            detection = detection,
            fallbackPoints = currentPoints,
            minPilePoints = 120
        )

        // Refinador opcional: GroundPileSegmenter queda como fallback para mejorar visualización 3D.
        val segmented = segmenter.segment(primaryPilePoints)
        val refinedPilePoints = segmented?.pilePoints?.takeIf { it.isNotEmpty() } ?: primaryPilePoints
        val refinedGroundPoints = segmented?.groundPoints ?: detection.groundPoints

        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = refinedPilePoints
        )
        val trajectoryQuality = trajectoryQualityEvaluator.evaluate(observerPath = observerPath.toList())
        val verticalCoverage = verticalCoverageEvaluator.evaluate(
            pilePoints = refinedPilePoints,
            observerPath = observerPath.toList(),
            recentTopCoverageScores = topCoverageHistory.toList()
        )
        val volumeStability = volumeStabilityEvaluator.evaluate(volumeHistory.toList())
        val referenceMeasurement = referenceScaleValidator.validate(
            referenceObject = configuredReferenceObject,
            observedLengthMeters = manualReferenceObservedLengthMeters
        )

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val completeness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = refinedPilePoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
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

        val guidance = guidanceEngine.buildMessage(
            completeness = stateDecision.completeness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = refinedPilePoints.size,
            missingUpperBand = VerticalCoveragePenalty.MISSING_UPPER_BAND in verticalCoverage.penaltyFlags,
            lowTopCoverage = verticalCoverage.topCoverageScore < 0.55f,
            topCoverageState = verticalCoverage.topCoverageState,
            topNeedsPerspective = verticalCoverage.topCoverageState == TopCoverageState.TOP_MISSING &&
                verticalCoverage.topPointCount >= 12 &&
                verticalCoverage.topBandDensity < 14f,
            autoCompletionCandidate = stateDecision.autoCompletionCandidate
        )

        val gatedGuidance = buildString {
            append(guidance)
            verticalCoverage.reasons.forEach { append(" $it") }
            trajectoryQuality.penaltyReasons.forEach { append(" $it") }
            stateDecision.blockers.forEach { append(" $it") }
            volumeStability.reasons.forEach { append(" $it") }
            detection.reasons.forEach { append(" $it") }
        }.trim()

        val diagnostics = buildDiagnostics(
            coverageResult = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            totalSectors = coverageResult.totalSectors,
            trajectoryQuality = trajectoryQuality.trajectoryQualityScore,
            gpsDistance = gpsDistance,
            arDistance = arDistance,
            verticalCoverage = verticalCoverage.verticalCoverageScore,
            topCoverage = verticalCoverage.topCoverageScore,
            topCoverageState = verticalCoverage.topCoverageState,
            volumeStable = volumeStability.isStable,
            autoCompletionCandidate = stateDecision.autoCompletionCandidate,
            usefulPointGrowthRatio = computeRecentUsefulPointGrowthRatio(),
            volumeDeltaRatio = computeRecentVolumeDeltaRatio(),
            blockers = stateDecision.blockers,
            detection = detection
        )

        if (!stateDecision.canReview) {
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
                    completeness = stateDecision.completeness,
                    guidanceMessage = gatedGuidance,
                    shortGuidanceMessage = stateDecision.shortGuidance,
                    diagnostics = diagnostics,
                    canReviewMeasurement = false,
                    canFinishMeasurement = false,
                    error = "Medición incompleta. $gatedGuidance"
                )
            }
            return
        }

        val result = calculator.calculate(refinedPilePoints)
        val axisResult = axisEstimator.estimate(refinedPilePoints)
        val cloudQuality = pileCoverageQualityEvaluator.evaluate(refinedPilePoints)

        val reviewModel = segmented?.let { reviewBuilder.build(it) }
        val heightSummary = segmented?.heightSummary

        val maxHeight = heightSummary?.maxHeight
            ?: detection.boundingBox?.height?.toDouble()
            ?: result.topPoints.maxOfOrNull { it.y }?.toDouble() ?: 0.0

        val adjustedCompleteness = when {
            cloudQuality == null -> completeness
            completeness == CompletenessLevel.COMPLETE &&
                cloudQuality.qualityLevel != PileCoverageQualityLevel.COMPLETE -> CompletenessLevel.ACCEPTABLE
            completeness == CompletenessLevel.ACCEPTABLE &&
                cloudQuality.qualityLevel == PileCoverageQualityLevel.POOR -> CompletenessLevel.PARTIAL
            completeness == CompletenessLevel.COMPLETE &&
                cloudQuality.qualityLevel == PileCoverageQualityLevel.FAIR -> CompletenessLevel.PARTIAL
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

        val length = detection.boundingBox?.length?.toDouble()
            ?: axisResult?.length
            ?: if (refinedPilePoints.isNotEmpty()) {
                (refinedPilePoints.maxOf { it.x } - refinedPilePoints.minOf { it.x }).toDouble()
            } else 0.0

        val maxWidth = detection.boundingBox?.width?.toDouble()
            ?: axisResult?.width
            ?: if (refinedPilePoints.isNotEmpty()) {
                (refinedPilePoints.maxOf { it.z } - refinedPilePoints.minOf { it.z }).toDouble()
            } else 0.0

        val finalConfidence = (
            when (finalCompleteness) {
                CompletenessLevel.COMPLETE -> 0.95f
                CompletenessLevel.ACCEPTABLE -> 0.80f
                CompletenessLevel.PARTIAL -> 0.60f
                CompletenessLevel.INSUFFICIENT -> 0.40f
            } - (cloudQuality?.confidencePenalty ?: 0f)
        ).coerceIn(0.20f, 0.95f) * detection.detectionConfidence.coerceIn(0.45f, 1f)

        val finalBoundingBox = computeBounds(refinedPilePoints)
        val calibrationDebugInfo = detection.debugInfo + mapOf(
            "raw_points" to currentPoints.size.toString(),
            "accepted_points" to currentPoints.size.toString(),
            "pile_points" to refinedPilePoints.size.toString(),
            "ground_points" to refinedGroundPoints.size.toString(),
            "bounding_box_final" to formatBounds(finalBoundingBox),
            "max_height" to String.format("%.3f", maxHeight),
            "p95_height" to String.format("%.3f", heightSummary?.p95Height ?: maxHeight),
            "mean_height" to String.format("%.3f", heightSummary?.meanHeight ?: 0.0),
            "volume_before_correction" to String.format("%.3f", result.volumeBeforeCorrection),
            "volume_after_correction" to String.format("%.3f", result.volumeAfterCorrection),
            "detection_confidence" to String.format("%.3f", detection.detectionConfidence),
            "vertical_coverage_score" to String.format("%.3f", verticalCoverage.verticalCoverageScore),
            "top_coverage_score" to String.format("%.3f", verticalCoverage.topCoverageScore),
            "top_point_count" to verticalCoverage.topPointCount.toString(),
            "top_band_density" to String.format("%.3f", verticalCoverage.topBandDensity),
            "top_coverage_state" to verticalCoverage.topCoverageState.name,
            "top_coverage_trend" to String.format("%.3f", verticalCoverage.topCoverageTrend),
            "top_coverage_temporal_stability" to String.format("%.3f", verticalCoverage.topCoverageTemporalStability),
            "trajectory_quality_score" to String.format("%.3f", trajectoryQuality.trajectoryQualityScore),
            "volume_stability_score" to String.format("%.3f", volumeStability.stabilityScore),
            "volume_variation_ratio" to String.format("%.3f", volumeStability.relativeVariation),
            "volume_iqr_ratio" to String.format("%.3f", volumeStability.relativeIqr),
            "volume_mad_ratio" to String.format("%.3f", volumeStability.relativeMad),
            "volume_drift_ratio" to String.format("%.3f", volumeStability.driftRatio),
            "scale_validation_score" to String.format("%.3f", referenceMeasurement?.scaleValidationScore ?: 0f),
            "reference_expected_m" to String.format("%.3f", referenceMeasurement?.referenceObject?.expectedLengthMeters ?: 0.0),
            "reference_observed_m" to String.format("%.3f", referenceMeasurement?.observedLengthMeters ?: 0.0),
            "reference_relative_error" to String.format("%.3f", referenceMeasurement?.relativeError ?: 0.0),
            "reference_status" to (referenceMeasurement?.status?.name ?: "NOT_CONFIGURED"),
            "auto_completion_candidate" to stateDecision.autoCompletionCandidate.toString()
        ) + result.debugInfo

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
            groundPoints = refinedGroundPoints,
            pileOnlyPoints = refinedPilePoints,
            groundReference = heightSummary?.groundReference ?: 0.0,
            pileBaseReference = heightSummary?.pileBaseReference ?: 0.0,
            meanPileHeight = heightSummary?.meanHeight ?: 0.0,
            p95PileHeight = heightSummary?.p95Height ?: maxHeight,
            reviewModelPoints = reviewModel?.points ?: emptyList(),
            reviewModelWidth = reviewModel?.width ?: 0f,
            reviewModelHeight = reviewModel?.height ?: 0f,
            reviewModelDepth = reviewModel?.depth ?: 0f,
            pileDetectionConfidence = detection.detectionConfidence,
            pileDetectionQuality = detection.quality.name,
            pileDetectionReasons = detection.reasons,
            detectionDebugInfo = calibrationDebugInfo,
            volumeBeforeCorrection = result.volumeBeforeCorrection,
            volumeAfterCorrection = result.volumeAfterCorrection,
            referenceBarMeasurement = referenceMeasurement,
            scaleValidationScore = referenceMeasurement?.scaleValidationScore ?: 0f,
            volumeStabilityScore = volumeStability.stabilityScore.toFloat(),
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            appVersionDisplay = appVersionDisplay
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
                shortGuidanceMessage = stateDecision.shortGuidance,
                diagnostics = diagnostics,
                canReviewMeasurement = true,
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
        if (newPoints.isNotEmpty()) points.addAll(newPoints)

        val currentPoints = points.toList()
        val detection = getDetectionSnapshot(currentPoints, observerPath.toList())
        val measurementPoints = selectPilePreferredPoints(
            detection = detection,
            fallbackPoints = currentPoints,
            minPilePoints = 80
        )

        frameCounter++
        val calcResult = if (frameCounter % 2 == 0) {
            calculator.calculate(measurementPoints).also { lastLiveScanResult = it }
        } else {
            lastLiveScanResult
        }
        lastDetectionDebug = detection.debugInfo + mapOf(
            "raw_points" to processor.lastStats.rawPoints.toString(),
            "sampled_points" to processor.lastStats.sampledPoints.toString(),
            "accepted_points_live" to processor.lastStats.acceptedPoints.toString(),
            "measurement_points" to measurementPoints.size.toString()
        )

        val rawDist = sqrt(
            (currentPos.x - startPos!!.x).toDouble().pow(2.0) +
                (currentPos.z - startPos!!.z).toDouble().pow(2.0)
        ) * params.distanceCorrectionFactor

        _uiState.update {
            it.copy(
                stereoVolume = calcResult.volume,
                netVolume = calcResult.volume * 0.45,
                distance = if (it.distance == 0.0) rawDist else it.distance + params.emaAlpha * (rawDist - it.distance),
                topPoints = calcResult.topPoints
            )
        }

        volumeHistory.addLast(calcResult.volume)
        if (volumeHistory.size > 20) volumeHistory.removeFirst()
        usefulPointHistory.addLast(measurementPoints.size)
        if (usefulPointHistory.size > 20) usefulPointHistory.removeFirst()

        refreshMeasurementState()
    }

    private fun refreshMeasurementState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStateRefreshAtMs < 250L) return
        lastStateRefreshAtMs = now

        val currentPoints = points.toList()
        val detection = getDetectionSnapshot(currentPoints, observerPath.toList())
        val evaluationPoints = selectPilePreferredPoints(
            detection = detection,
            fallbackPoints = currentPoints,
            minPilePoints = 80
        )

        val coverageResult = coverageEvaluator.evaluateFromObserverPath(
            observerPath = observerPath.toList(),
            pilePoints = evaluationPoints
        )

        val gpsDistance = calculateGpsDistance()
        val arDistance = calculateArDistance()

        val baseCompleteness = completenessValidator.validate(
            angularCoverage = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            observerSamples = observerPath.size,
            usefulPointCount = evaluationPoints.size,
            arDistanceWalked = arDistance,
            gpsDistanceWalked = gpsDistance
        )

        val trajectoryQuality = trajectoryQualityEvaluator.evaluate(observerPath = observerPath.toList())
        val verticalCoverage = verticalCoverageEvaluator.evaluate(
            pilePoints = evaluationPoints,
            observerPath = observerPath.toList(),
            recentTopCoverageScores = topCoverageHistory.toList()
        )
        topCoverageHistory.addLast(verticalCoverage.topCoverageScore)
        if (topCoverageHistory.size > 24) topCoverageHistory.removeFirst()
        val volumeStability = volumeStabilityEvaluator.evaluate(volumeHistory.toList())

        val cloudQuality = pileCoverageQualityEvaluator.evaluate(evaluationPoints)
        val stateDecision = stateEvaluator.evaluate(
            MeasurementStateInput(
                baseCompleteness = baseCompleteness,
                coverageRatio = coverageResult.coverageRatio,
                coveredSectors = coverageResult.coveredSectors,
                observerSamples = observerPath.size,
                usefulPointCount = evaluationPoints.size,
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
                hasReviewableModel = evaluationPoints.size >= 600
            )
        )

        val adjustedCompleteness = when {
            cloudQuality == null -> stateDecision.completeness
            stateDecision.completeness == CompletenessLevel.COMPLETE &&
                cloudQuality.qualityLevel != PileCoverageQualityLevel.COMPLETE -> CompletenessLevel.ACCEPTABLE
            stateDecision.completeness == CompletenessLevel.ACCEPTABLE &&
                cloudQuality.qualityLevel == PileCoverageQualityLevel.POOR -> CompletenessLevel.PARTIAL
            stateDecision.completeness == CompletenessLevel.COMPLETE &&
                cloudQuality.qualityLevel == PileCoverageQualityLevel.FAIR -> CompletenessLevel.PARTIAL
            else -> stateDecision.completeness
        }

        val guidance = guidanceEngine.buildMessage(
            completeness = adjustedCompleteness,
            missingSectors = coverageResult.missingSectors,
            observerSamples = observerPath.size,
            usefulPoints = evaluationPoints.size,
            missingUpperBand = VerticalCoveragePenalty.MISSING_UPPER_BAND in verticalCoverage.penaltyFlags,
            lowTopCoverage = verticalCoverage.topCoverageScore < 0.55f,
            topCoverageState = verticalCoverage.topCoverageState,
            topNeedsPerspective = verticalCoverage.topCoverageState == TopCoverageState.TOP_MISSING &&
                verticalCoverage.topPointCount >= 12 &&
                verticalCoverage.topBandDensity < 14f,
            autoCompletionCandidate = stateDecision.autoCompletionCandidate
        )

        val adjustedGuidance = buildString {
            append(guidance)
            verticalCoverage.reasons.forEach { append(" $it") }
            trajectoryQuality.penaltyReasons.forEach { append(" $it") }
            stateDecision.blockers.forEach { append(" $it") }
            volumeStability.reasons.forEach { append(" $it") }
            detection.reasons.forEach { append(" $it") }
        }
        val diagnostics = buildDiagnostics(
            coverageResult = coverageResult.coverageRatio,
            coveredSectors = coverageResult.coveredSectors,
            totalSectors = coverageResult.totalSectors,
            trajectoryQuality = trajectoryQuality.trajectoryQualityScore,
            gpsDistance = gpsDistance,
            arDistance = arDistance,
            verticalCoverage = verticalCoverage.verticalCoverageScore,
            topCoverage = verticalCoverage.topCoverageScore,
            topCoverageState = verticalCoverage.topCoverageState,
            volumeStable = volumeStability.isStable,
            autoCompletionCandidate = stateDecision.autoCompletionCandidate,
            usefulPointGrowthRatio = computeRecentUsefulPointGrowthRatio(),
            volumeDeltaRatio = computeRecentVolumeDeltaRatio(),
            blockers = stateDecision.blockers,
            detection = detection
        )

        if (!detection.isRobust) {
            Log.d("PileObjectDetector", "Fallback activo: ${detection.reasons.joinToString()} | debug=$lastDetectionDebug")
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
                shortGuidanceMessage = stateDecision.shortGuidance,
                diagnostics = diagnostics,
                canReviewMeasurement = stateDecision.canReview,
                canFinishMeasurement = (stateDecision.canFinish || stateDecision.autoCompletionCandidate) &&
                    (adjustedCompleteness == CompletenessLevel.ACCEPTABLE || adjustedCompleteness == CompletenessLevel.COMPLETE)
            )
        }
    }

    private fun buildDiagnostics(
        coverageResult: Float,
        coveredSectors: Int,
        totalSectors: Int,
        trajectoryQuality: Float,
        gpsDistance: Double,
        arDistance: Double,
        verticalCoverage: Float,
        topCoverage: Float,
        topCoverageState: TopCoverageState,
        volumeStable: Boolean,
        autoCompletionCandidate: Boolean,
        usefulPointGrowthRatio: Float,
        volumeDeltaRatio: Float,
        blockers: List<String>,
        detection: com.forest.scanai.domain.engine.PileDetectionResult
    ): List<String> {
        val header = listOf(
            "Sectores cubiertos: $coveredSectors/$totalSectors",
            "Recorrido AR: ${"%.1f".format(arDistance)} m",
            "Distancia GPS: ${"%.1f".format(gpsDistance)} m",
            "Quality score trayectoria: ${"%.2f".format(trajectoryQuality)}",
            "Cobertura vertical: ${(verticalCoverage * 100).toInt()}%",
            "Cobertura de cima/corona: ${(topCoverage * 100).toInt()}%",
            "Estado de corona: ${topCoverageState.name}",
            "Cobertura total: ${(coverageResult * 100).toInt()}%",
            "Volumen estable: ${if (volumeStable) "Sí" else "No"}",
            "Crecimiento puntos útiles (reciente): ${"%.2f".format(usefulPointGrowthRatio * 100f)}%",
            "Delta volumen (reciente): ${"%.2f".format(volumeDeltaRatio * 100f)}%",
            "Auto-finalización: ${if (autoCompletionCandidate) "Lista" else "Aún no"}",
            "Detection confidence: ${(detection.detectionConfidence * 100).toInt()}%",
            "Detector: ${detection.quality.name}"
        )
        return (header + blockers + detection.reasons).distinct()
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

    private data class Bounds3D(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val minZ: Float,
        val maxZ: Float
    )

    private fun computeBounds(points: List<Position>): Bounds3D? {
        if (points.isEmpty()) return null
        return Bounds3D(
            minX = points.minOf { it.x },
            maxX = points.maxOf { it.x },
            minY = points.minOf { it.y },
            maxY = points.maxOf { it.y },
            minZ = points.minOf { it.z },
            maxZ = points.maxOf { it.z }
        )
    }

    private fun formatBounds(bounds: Bounds3D?): String {
        if (bounds == null) return "n/a"
        return "x:[${"%.2f".format(bounds.minX)},${"%.2f".format(bounds.maxX)}] " +
            "y:[${"%.2f".format(bounds.minY)},${"%.2f".format(bounds.maxY)}] " +
            "z:[${"%.2f".format(bounds.minZ)},${"%.2f".format(bounds.maxZ)}]"
    }

    fun reset() {
        points.clear()
        voxelGrid.clear()
        trajectory.clear()
        observerPath.clear()
        volumeHistory.clear()
        usefulPointHistory.clear()
        topCoverageHistory.clear()
        startPos = null
        gpsJob?.cancel()
        _uiState.value = ScanUiState(appVersionDisplay = appVersionDisplay)
        _finalResult.value = null
        lastDetectionDebug = emptyMap()
        lastDetection = pileObjectDetector.detect(emptyList())
        lastDetectionPointCount = 0
        lastDetectionObserverCount = 0
        lastStateRefreshAtMs = 0L
        lastLiveScanResult = com.forest.scanai.domain.model.ScanResult(0.0, emptyList())
        frameCounter = 0
    }

    private fun getDetectionSnapshot(
        currentPoints: List<Position>,
        currentObserverPath: List<Position>,
        force: Boolean = false
    ) = if (
        force ||
        lastDetectionPointCount == 0 ||
        currentPoints.size - lastDetectionPointCount >= 90 ||
        currentObserverPath.size - lastDetectionObserverCount >= 3
    ) {
        pileObjectDetector.detect(currentPoints, currentObserverPath).also {
            lastDetection = it
            lastDetectionPointCount = currentPoints.size
            lastDetectionObserverCount = currentObserverPath.size
        }
    } else {
        lastDetection
    }

    private fun CompletenessLevel.capAt(maximum: CompletenessLevel): CompletenessLevel {
        return if (ordinal > maximum.ordinal) maximum else this
    }
}
