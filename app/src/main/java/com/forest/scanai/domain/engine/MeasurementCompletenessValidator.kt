package com.forest.scanai.domain.engine

import android.location.Location
import com.forest.scanai.domain.model.CompletenessLevel
import io.github.sceneview.math.Position
import kotlin.math.atan2

class MeasurementCompletenessValidator {
    
    fun evaluate(
        points: List<Position>,
        trajectory: List<Location>,
        distanceWalked: Double
    ): Pair<CompletenessLevel, String> {
        if (points.size < 500) return CompletenessLevel.INSUFFICIENT to "Capture más puntos de la pila"
        
        // Cobertura Angular basada en la trayectoria GPS
        val angularCoverage = calculateAngularCoverage(trajectory)
        
        return when {
            angularCoverage >= 180 && distanceWalked > 10.0 -> 
                CompletenessLevel.COMPLETE to "Medición Completa. Puede finalizar."
            angularCoverage >= 90 && distanceWalked > 5.0 -> 
                CompletenessLevel.ACCEPTABLE to "Cobertura aceptable. Rodée un poco más."
            distanceWalked > 2.0 -> 
                CompletenessLevel.PARTIAL to "Recorra los costados de la pila."
            else -> 
                CompletenessLevel.INSUFFICIENT to "Camine lateralmente para cubrir la pila."
        }
    }

    private fun calculateAngularCoverage(trajectory: List<Location>): Double {
        if (trajectory.size < 3) return 0.0
        val centerLat = trajectory.map { it.latitude }.average()
        val centerLon = trajectory.map { it.longitude }.average()
        
        val angles = trajectory.map { 
            atan2(it.latitude - centerLat, it.longitude - centerLon) 
        }.sorted()
        
        return Math.toDegrees(angles.last() - angles.first())
    }
}
