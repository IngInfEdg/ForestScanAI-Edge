package com.forest.scanai.edge.domain.engine

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class PileDetectionQuality {
    HIGH,
    MEDIUM,
    LOW,
    FALLBACK
}

data class GroundPlaneEstimate(
    val a: Float,
    val b: Float,
    val c: Float,
    val tolerance: Float,
    val fitError: Float,
    val method: String
) {
    fun expectedY(x: Float, z: Float): Float = a * x + b * z + c
    fun signedDistance(point: Position): Float = point.y - expectedY(point.x, point.z)
}

data class PileBoundingInfo(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val centroid: Position
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0f)
    val length: Float get() = (maxZ - minZ).coerceAtLeast(0f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0f)
}

data class PileCluster(
    val id: Int,
    val points: List<Position>,
    val centroid: Position,
    val boundingInfo: PileBoundingInfo,
    val score: Float,
    val estimatedVolumeProxy: Float
)

data class DetectionConfidenceBreakdown(
    val dominanceScore: Float,
    val heightScore: Float,
    val compactnessScore: Float,
    val observerConsistencyScore: Float,
    val groundSeparationScore: Float,
    val confidence: Float
)

data class PileDetectionResult(
    val groundPoints: List<Position>,
    val nonGroundPoints: List<Position>,
    val pilePoints: List<Position>,
    val groundPlane: GroundPlaneEstimate?,
    val clusters: List<PileCluster>,
    val primaryClusterId: Int?,
    val boundingBox: PileBoundingInfo?,
    val heightRange: ClosedFloatingPointRange<Float>?,
    val lengthRange: ClosedFloatingPointRange<Float>?,
    val widthRange: ClosedFloatingPointRange<Float>?,
    val detectionConfidence: Float,
    val quality: PileDetectionQuality,
    val reasons: List<String>,
    val debugInfo: Map<String, String>
) {
    val isRobust: Boolean
        get() = quality == PileDetectionQuality.HIGH || quality == PileDetectionQuality.MEDIUM
}

class PileObjectDetector(
    private val minPointCount: Int = 180,
    private val minClusterSize: Int = 40,
    private val dbscanEps: Float = 0.35f,
    private val dbscanMinPts: Int = 12
) {

    fun detect(points: List<Position>, observerPath: List<Position> = emptyList()): PileDetectionResult {
        if (points.size < minPointCount) {
            val bounds = buildBounds(points)
            return PileDetectionResult(
                groundPoints = emptyList(),
                nonGroundPoints = points,
                pilePoints = points,
                groundPlane = null,
                clusters = emptyList(),
                primaryClusterId = null,
                boundingBox = bounds,
                heightRange = bounds?.let { it.minY..it.maxY },
                lengthRange = bounds?.let { it.minX..it.maxX },
                widthRange = bounds?.let { it.minZ..it.maxZ },
                detectionConfidence = 0.25f,
                quality = PileDetectionQuality.FALLBACK,
                reasons = listOf("Pocos puntos para detectar la pila principal con robustez."),
                debugInfo = mapOf(
                    "total_points" to points.size.toString(),
                    "accepted_points" to points.size.toString(),
                    "ground_points" to "0",
                    "non_ground_points" to points.size.toString(),
                    "pile_points" to points.size.toString(),
                    "clusters" to "0",
                    "selected_cluster" to "none"
                )
            )
        }

        val plane = estimateGroundPlane(points)
        val ground = mutableListOf<Position>()
        val nonGround = mutableListOf<Position>()

        points.forEach { point ->
            val signedDistance = plane.signedDistance(point)
            if (abs(signedDistance) <= plane.tolerance || signedDistance < 0f) {
                ground += point
            } else {
                nonGround += point
            }
        }

        val clusters = dbscan(nonGround)
        val clusterScores = clusters.mapIndexed { index, clusterPoints ->
            val bounds = buildBounds(clusterPoints)!!
            val centroid = bounds.centroid
            val volumeProxy = bounds.width * bounds.length * bounds.height
            val score = scoreCluster(
                size = clusterPoints.size,
                centroid = centroid,
                bounds = bounds,
                observerPath = observerPath,
                nonGroundPoints = nonGround.size
            )
            PileCluster(
                id = index,
                points = clusterPoints,
                centroid = centroid,
                boundingInfo = bounds,
                score = score,
                estimatedVolumeProxy = volumeProxy
            )
        }.sortedByDescending { it.score }

        val selected = clusterScores.firstOrNull()
        val reasons = mutableListOf<String>()
        val confidenceBreakdown = computeConfidenceBreakdown(
            selected = selected,
            allClusters = clusterScores,
            totalPoints = points.size,
            nonGroundPoints = nonGround.size,
            groundPoints = ground.size,
            observerPath = observerPath
        )

        if (selected == null) {
            reasons += "No se detectó cluster dominante en puntos no-suelo."
        } else {
            if (selected.points.size < minClusterSize) {
                reasons += "Cluster principal pequeño (${selected.points.size} puntos)."
            }
            if (selected.boundingInfo.height < 0.35f) {
                reasons += "Rango vertical bajo para una pila robusta."
            }
            if (confidenceBreakdown.dominanceScore < 0.52f) {
                reasons += "La pila dominante no se separa lo suficiente de otros clusters."
            }
            if (confidenceBreakdown.groundSeparationScore < 0.45f) {
                reasons += "Separación suelo/no-suelo incierta para esta nube."
            }
            if (confidenceBreakdown.observerConsistencyScore < 0.45f && observerPath.size >= 4) {
                reasons += "La observación del cluster dominante es poco consistente respecto al recorrido."
            }
        }

        val confidence = confidenceBreakdown.confidence
        val quality = when {
            selected == null -> PileDetectionQuality.FALLBACK
            confidence >= 0.76f && selected.points.size >= minClusterSize -> PileDetectionQuality.HIGH
            confidence >= 0.58f -> PileDetectionQuality.MEDIUM
            confidence >= 0.40f -> PileDetectionQuality.LOW
            else -> PileDetectionQuality.FALLBACK
        }

        val initialPilePoints = selected?.points ?: nonGround.ifEmpty { points }
        val pilePoints = preserveTopCrownPoints(
            selectedCluster = selected,
            nonGroundPoints = nonGround,
            fallbackPoints = initialPilePoints
        )
        val bounds = buildBounds(pilePoints)

        if (quality == PileDetectionQuality.FALLBACK) {
            reasons += "Se aplicará fallback a puntos crudos para no cortar el flujo de medición."
        }

        val debugInfo = mapOf(
            "total_points" to points.size.toString(),
            "accepted_points" to points.size.toString(),
            "ground_points" to ground.size.toString(),
            "non_ground_points" to nonGround.size.toString(),
            "pile_points" to pilePoints.size.toString(),
            "clusters" to clusterScores.size.toString(),
            "selected_cluster" to (selected?.id?.toString() ?: "none"),
            "estimated_height" to String.format("%.3f", bounds?.height ?: 0f),
            "bounding_box" to formatBounds(bounds),
            "confidence" to String.format("%.3f", confidence),
            "dominance_score" to String.format("%.3f", confidenceBreakdown.dominanceScore),
            "height_score" to String.format("%.3f", confidenceBreakdown.heightScore),
            "compactness_score" to String.format("%.3f", confidenceBreakdown.compactnessScore),
            "observer_consistency_score" to String.format("%.3f", confidenceBreakdown.observerConsistencyScore),
            "ground_separation_score" to String.format("%.3f", confidenceBreakdown.groundSeparationScore),
            "ground_plane_fit_error" to String.format("%.4f", plane.fitError),
            "ground_method" to plane.method,
            "top_band_points" to countTopBandPoints(pilePoints).toString()
        )

        return PileDetectionResult(
            groundPoints = ground,
            nonGroundPoints = nonGround,
            pilePoints = pilePoints,
            groundPlane = plane,
            clusters = clusterScores,
            primaryClusterId = selected?.id,
            boundingBox = bounds,
            heightRange = bounds?.let { it.minY..it.maxY },
            lengthRange = bounds?.let { it.minX..it.maxX },
            widthRange = bounds?.let { it.minZ..it.maxZ },
            detectionConfidence = confidence,
            quality = quality,
            reasons = reasons.distinct(),
            debugInfo = debugInfo
        )
    }

    private fun estimateGroundPlane(points: List<Position>): GroundPlaneEstimate {
        val lowCandidates = points
            .sortedBy { it.y }
            .take(max(48, min(450, points.size / 3)))

        // Nota de diseño: usamos un ajuste lineal robusto sobre puntos bajos para mantener costo O(n)
        // en dispositivo móvil. Esta interfaz deja espacio para evolucionar a RANSAC real en el futuro.
        val sums = lowCandidates.fold(DoubleArray(9)) { acc, p ->
            val x = p.x.toDouble()
            val z = p.z.toDouble()
            val y = p.y.toDouble()
            acc[0] += x * x // xx
            acc[1] += x * z // xz
            acc[2] += x     // x
            acc[3] += z * z // zz
            acc[4] += z     // z
            acc[5] += x * y // xy
            acc[6] += z * y // zy
            acc[7] += y     // y
            acc[8] += 1.0   // n
            acc
        }

        val matrix = arrayOf(
            doubleArrayOf(sums[0], sums[1], sums[2]),
            doubleArrayOf(sums[1], sums[3], sums[4]),
            doubleArrayOf(sums[2], sums[4], sums[8])
        )
        val vector = doubleArrayOf(sums[5], sums[6], sums[7])

        val solution = solve3x3(matrix, vector)
        val a = solution?.getOrNull(0)?.toFloat() ?: 0f
        val b = solution?.getOrNull(1)?.toFloat() ?: 0f
        val c = solution?.getOrNull(2)?.toFloat() ?: (lowCandidates.map { it.y }.average().toFloat())

        val residuals = lowCandidates.map { point -> point.y - (a * point.x + b * point.z + c) }
        val mad = median(residuals.map { abs(it) })
        val tolerance = (0.06f + mad * 2.6f).coerceIn(0.08f, 0.24f)
        val rmse = sqrt(residuals.map { it * it }.average()).toFloat()

        return GroundPlaneEstimate(
            a = a,
            b = b,
            c = c,
            tolerance = tolerance,
            fitError = rmse,
            method = "low-point-linear-fit"
        )
    }

    private fun scoreCluster(
        size: Int,
        centroid: Position,
        bounds: PileBoundingInfo,
        observerPath: List<Position>,
        nonGroundPoints: Int
    ): Float {
        val sizeScore = (size / max(nonGroundPoints, 1).toFloat()).coerceIn(0f, 1f)
        val centerScore = if (observerPath.isEmpty()) {
            0.55f
        } else {
            val observerCenter = centroid(observerPath)
            val d = distance2d(observerCenter, centroid)
            exp((-d / 2.2f).toDouble()).toFloat().coerceIn(0f, 1f)
        }

        val geometryScore = ((bounds.height / 1.8f) * 0.6f + ((bounds.width + bounds.length) / 5.0f) * 0.4f)
            .coerceIn(0f, 1f)

        return (sizeScore * 0.50f + centerScore * 0.25f + geometryScore * 0.25f).coerceIn(0f, 1f)
    }

    private fun preserveTopCrownPoints(
        selectedCluster: PileCluster?,
        nonGroundPoints: List<Position>,
        fallbackPoints: List<Position>
    ): List<Position> {
        val bounds = selectedCluster?.boundingInfo ?: return fallbackPoints
        val base = selectedCluster.points.toMutableList()
        val topThreshold = bounds.maxY - bounds.height * 0.18f
        val lowerRetentionThreshold = bounds.minY + bounds.height * 0.06f
        val extraTopPoints = nonGroundPoints.filter { point ->
            point.y >= topThreshold &&
                point.x in (bounds.minX - 0.35f)..(bounds.maxX + 0.35f) &&
                point.z in (bounds.minZ - 0.35f)..(bounds.maxZ + 0.35f)
        }
        val extraEdgePoints = nonGroundPoints.filter { point ->
            point.y >= lowerRetentionThreshold &&
                point.x in (bounds.minX - 0.45f)..(bounds.maxX + 0.45f) &&
                point.z in (bounds.minZ - 0.45f)..(bounds.maxZ + 0.45f) &&
                (
                    point.x <= bounds.minX + 0.25f ||
                        point.x >= bounds.maxX - 0.25f ||
                        point.z <= bounds.minZ + 0.25f ||
                        point.z >= bounds.maxZ - 0.25f
                    )
        }
        base += extraTopPoints
        base += extraEdgePoints
        return base.distinct()
    }

    private fun countTopBandPoints(points: List<Position>): Int {
        if (points.isEmpty()) return 0
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val threshold = maxY - (maxY - minY).coerceAtLeast(1e-4f) * 0.20f
        return points.count { it.y >= threshold }
    }

    private fun dbscan(points: List<Position>): List<List<Position>> {
        if (points.size < dbscanMinPts) return emptyList()

        val cell = dbscanEps
        val grid = mutableMapOf<Triple<Int, Int, Int>, MutableList<Int>>()
        points.forEachIndexed { index, point ->
            val key = Triple(
                kotlin.math.floor(point.x / cell).toInt(),
                kotlin.math.floor(point.y / cell).toInt(),
                kotlin.math.floor(point.z / cell).toInt()
            )
            grid.getOrPut(key) { mutableListOf() }.add(index)
        }

        val visited = BooleanArray(points.size)
        val assignedCluster = IntArray(points.size) { -1 }
        val clusters = mutableListOf<MutableList<Int>>()

        for (i in points.indices) {
            if (visited[i]) continue
            visited[i] = true

            val neighbors = regionQuery(i, points, grid, cell)
            if (neighbors.size < dbscanMinPts) continue

            val clusterId = clusters.size
            val cluster = mutableListOf<Int>()
            clusters += cluster
            expandCluster(
                seedIndex = i,
                initialNeighbors = neighbors,
                clusterId = clusterId,
                points = points,
                grid = grid,
                cell = cell,
                visited = visited,
                assignedCluster = assignedCluster,
                cluster = cluster
            )
        }

        return clusters
            .map { idx -> idx.map { points[it] } }
            .filter { it.size >= minClusterSize }
    }

    private fun expandCluster(
        seedIndex: Int,
        initialNeighbors: MutableList<Int>,
        clusterId: Int,
        points: List<Position>,
        grid: Map<Triple<Int, Int, Int>, List<Int>>,
        cell: Float,
        visited: BooleanArray,
        assignedCluster: IntArray,
        cluster: MutableList<Int>
    ) {
        val queue = ArrayDeque<Int>()
        queue.add(seedIndex)
        initialNeighbors.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (assignedCluster[current] == clusterId) continue

            if (assignedCluster[current] == -1) {
                assignedCluster[current] = clusterId
                cluster.add(current)
            }

            if (!visited[current]) {
                visited[current] = true
            }

            val neighbors = regionQuery(current, points, grid, cell)
            if (neighbors.size >= dbscanMinPts) {
                neighbors.forEach { neighbor ->
                    if (assignedCluster[neighbor] == -1) {
                        queue.add(neighbor)
                    }
                }
            }
        }
    }

    private fun regionQuery(
        index: Int,
        points: List<Position>,
        grid: Map<Triple<Int, Int, Int>, List<Int>>,
        cell: Float
    ): MutableList<Int> {
        val point = points[index]
        val key = Triple(
            kotlin.math.floor(point.x / cell).toInt(),
            kotlin.math.floor(point.y / cell).toInt(),
            kotlin.math.floor(point.z / cell).toInt()
        )

        val neighbors = mutableListOf<Int>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val bucket = grid[Triple(key.first + dx, key.second + dy, key.third + dz)] ?: continue
                    bucket.forEach { candidate ->
                        if (distance3d(point, points[candidate]) <= dbscanEps) {
                            neighbors += candidate
                        }
                    }
                }
            }
        }
        return neighbors
    }

    private fun computeConfidenceBreakdown(
        selected: PileCluster?,
        allClusters: List<PileCluster>,
        totalPoints: Int,
        nonGroundPoints: Int,
        groundPoints: Int,
        observerPath: List<Position>
    ): DetectionConfidenceBreakdown {
        if (selected == null) {
            return DetectionConfidenceBreakdown(
                dominanceScore = 0f,
                heightScore = 0f,
                compactnessScore = 0f,
                observerConsistencyScore = 0f,
                groundSeparationScore = 0f,
                confidence = 0.20f
            )
        }

        val sizeRatioTotal = selected.points.size / totalPoints.toFloat().coerceAtLeast(1f)
        val sizeRatioNonGround = selected.points.size / nonGroundPoints.toFloat().coerceAtLeast(1f)
        val nextClusterSize = allClusters.getOrNull(1)?.points?.size?.toFloat() ?: 0f
        val dominanceMargin = (selected.points.size - nextClusterSize) / selected.points.size.toFloat().coerceAtLeast(1f)
        val dominanceScore = (
            sizeRatioNonGround.coerceIn(0f, 1f) * 0.65f +
                dominanceMargin.coerceIn(0f, 1f) * 0.35f
            ).coerceIn(0f, 1f)

        val height = selected.boundingInfo.height
        val heightScore = (height / 1.65f).coerceIn(0f, 1f)
        val footprint = (selected.boundingInfo.width * selected.boundingInfo.length).coerceAtLeast(1e-4f)
        val compactnessScore = (selected.points.size / footprint / 260f).coerceIn(0f, 1f)

        val observerConsistencyScore = if (observerPath.isEmpty()) {
            0.62f
        } else {
            val observerCenter = centroid(observerPath)
            val d = distance2d(observerCenter, selected.centroid)
            exp((-d / 2.8f).toDouble()).toFloat().coerceIn(0f, 1f)
        }

        val groundRatio = groundPoints / totalPoints.toFloat().coerceAtLeast(1f)
        val nonGroundRatio = nonGroundPoints / totalPoints.toFloat().coerceAtLeast(1f)
        val groundSeparationScore = when {
            groundRatio in 0.22f..0.72f && nonGroundRatio in 0.20f..0.78f -> 1.0f
            groundRatio in 0.15f..0.85f -> 0.7f
            else -> 0.3f
        }

        val confidence = (
            dominanceScore * 0.34f +
                heightScore * 0.20f +
                compactnessScore * 0.15f +
                observerConsistencyScore * 0.11f +
                groundSeparationScore * 0.20f +
                sizeRatioTotal.coerceIn(0f, 1f) * 0.08f
            ).coerceIn(0.12f, 0.95f)

        return DetectionConfidenceBreakdown(
            dominanceScore = dominanceScore,
            heightScore = heightScore,
            compactnessScore = compactnessScore,
            observerConsistencyScore = observerConsistencyScore,
            groundSeparationScore = groundSeparationScore,
            confidence = confidence
        )
    }

    private fun solve3x3(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val det = determinant3x3(matrix)
        if (abs(det) < 1e-8) return null

        val mx = arrayOf(
            doubleArrayOf(vector[0], matrix[0][1], matrix[0][2]),
            doubleArrayOf(vector[1], matrix[1][1], matrix[1][2]),
            doubleArrayOf(vector[2], matrix[2][1], matrix[2][2])
        )
        val my = arrayOf(
            doubleArrayOf(matrix[0][0], vector[0], matrix[0][2]),
            doubleArrayOf(matrix[1][0], vector[1], matrix[1][2]),
            doubleArrayOf(matrix[2][0], vector[2], matrix[2][2])
        )
        val mz = arrayOf(
            doubleArrayOf(matrix[0][0], matrix[0][1], vector[0]),
            doubleArrayOf(matrix[1][0], matrix[1][1], vector[1]),
            doubleArrayOf(matrix[2][0], matrix[2][1], vector[2])
        )

        return doubleArrayOf(
            determinant3x3(mx) / det,
            determinant3x3(my) / det,
            determinant3x3(mz) / det
        )
    }

    private fun determinant3x3(m: Array<DoubleArray>): Double {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    }

    private fun buildBounds(points: List<Position>): PileBoundingInfo? {
        if (points.isEmpty()) return null

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val minZ = points.minOf { it.z }
        val maxZ = points.maxOf { it.z }

        return PileBoundingInfo(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            minZ = minZ,
            maxZ = maxZ,
            centroid = Position((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
        )
    }

    private fun formatBounds(bounds: PileBoundingInfo?): String {
        if (bounds == null) return "none"
        return "x=[${"%.2f".format(bounds.minX)}, ${"%.2f".format(bounds.maxX)}], " +
            "y=[${"%.2f".format(bounds.minY)}, ${"%.2f".format(bounds.maxY)}], " +
            "z=[${"%.2f".format(bounds.minZ)}, ${"%.2f".format(bounds.maxZ)}]"
    }

    private fun centroid(points: List<Position>): Position {
        val n = points.size.coerceAtLeast(1)
        return Position(
            points.sumOf { it.x.toDouble() }.toFloat() / n,
            points.sumOf { it.y.toDouble() }.toFloat() / n,
            points.sumOf { it.z.toDouble() }.toFloat() / n
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun distance3d(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun distance2d(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }
}
