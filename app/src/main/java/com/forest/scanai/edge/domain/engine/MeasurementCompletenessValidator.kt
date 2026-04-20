package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.CompletenessLevel

class MeasurementCompletenessValidator {

    fun validate(
        angularCoverage: Float,
        coveredSectors: Int,
        observerSamples: Int,
        usefulPointCount: Int,
        arDistanceWalked: Double,
        gpsDistanceWalked: Double
    ): CompletenessLevel {

        val effectiveDistance = maxOf(arDistanceWalked, gpsDistanceWalked)

        return when {
            observerSamples < 20 -> CompletenessLevel.INSUFFICIENT
            usefulPointCount < 800 -> CompletenessLevel.INSUFFICIENT
            angularCoverage < 0.40f -> CompletenessLevel.INSUFFICIENT
            effectiveDistance < 6.0 -> CompletenessLevel.INSUFFICIENT

            angularCoverage < 0.60f || coveredSectors < 6 -> CompletenessLevel.PARTIAL

            angularCoverage >= 0.80f &&
                coveredSectors >= 9 &&
                observerSamples >= 40 &&
                usefulPointCount >= 1500 &&
                effectiveDistance >= 10.0 -> CompletenessLevel.COMPLETE

            else -> CompletenessLevel.ACCEPTABLE
        }
    }
}
