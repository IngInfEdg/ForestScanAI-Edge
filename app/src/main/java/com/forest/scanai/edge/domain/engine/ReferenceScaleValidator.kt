package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.ReferenceBarMeasurement
import com.forest.scanai.edge.domain.model.ReferenceMeasurementStatus
import com.forest.scanai.edge.domain.model.ReferenceObject

class ReferenceScaleValidator(
    private val warningRelativeError: Double = 0.12,
    private val maxRelativeError: Double = 0.30
) {

    fun validate(
        referenceObject: ReferenceObject?,
        observedLengthMeters: Double?
    ): ReferenceBarMeasurement? {
        val reference = referenceObject ?: return null
        val observed = observedLengthMeters?.takeIf { it > 0.0 }

        if (observed == null) {
            return ReferenceBarMeasurement(
                referenceObject = reference,
                observedLengthMeters = null,
                absoluteErrorMeters = 0.0,
                relativeError = 0.0,
                scaleRatio = 1.0,
                scaleValidationScore = 0f,
                status = ReferenceMeasurementStatus.NOT_PROVIDED,
                notes = listOf(
                    "Referencia configurada pero no observada todavía.",
                    "Estado NOT_PROVIDED: falta registrar longitud observada de la barra de 2m.",
                    "Siguiente paso sugerido: capturar una observación manual para habilitar validación de escala."
                )
            )
        }

        val expected = reference.expectedLengthMeters.coerceAtLeast(1e-6)
        val absError = kotlin.math.abs(observed - expected)
        val relativeError = absError / expected
        val scaleRatio = observed / expected
        val score = (1.0 - (relativeError / maxRelativeError)).coerceIn(0.0, 1.0).toFloat()
        val status = if (relativeError <= warningRelativeError) {
            ReferenceMeasurementStatus.VALID
        } else {
            ReferenceMeasurementStatus.WARNING
        }

        val notes = buildList {
            if (status == ReferenceMeasurementStatus.WARNING) {
                add("Escala potencialmente inestable según la barra de referencia.")
            }
            if (scaleRatio < 0.92) add("Posible subestimación de escala/altura.")
            if (scaleRatio > 1.08) add("Posible sobreestimación de escala/altura.")
        }

        return ReferenceBarMeasurement(
            referenceObject = reference,
            observedLengthMeters = observed,
            absoluteErrorMeters = absError,
            relativeError = relativeError,
            scaleRatio = scaleRatio,
            scaleValidationScore = score,
            status = status,
            notes = notes
        )
    }
}
