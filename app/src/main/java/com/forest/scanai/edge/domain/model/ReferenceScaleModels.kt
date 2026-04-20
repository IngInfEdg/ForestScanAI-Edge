package com.forest.scanai.edge.domain.model

enum class ReferenceObjectType {
    BAR_2M,
    CUSTOM_LINEAR
}

data class ReferenceObject(
    val id: String,
    val type: ReferenceObjectType,
    val expectedLengthMeters: Double,
    val label: String
)

enum class ReferenceMeasurementStatus {
    NOT_PROVIDED,
    VALID,
    WARNING
}

data class ReferenceBarMeasurement(
    val referenceObject: ReferenceObject,
    val observedLengthMeters: Double?,
    val absoluteErrorMeters: Double,
    val relativeError: Double,
    val scaleRatio: Double,
    val scaleValidationScore: Float,
    val status: ReferenceMeasurementStatus,
    val notes: List<String> = emptyList()
)
