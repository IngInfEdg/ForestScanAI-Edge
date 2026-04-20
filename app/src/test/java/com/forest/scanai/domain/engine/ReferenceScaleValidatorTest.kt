package com.forest.scanai.edge.domain.engine

import com.forest.scanai.edge.domain.model.ReferenceMeasurementStatus
import com.forest.scanai.edge.domain.model.ReferenceObject
import com.forest.scanai.edge.domain.model.ReferenceObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceScaleValidatorTest {

    private val validator = ReferenceScaleValidator()
    private val reference = ReferenceObject(
        id = "bar-2m",
        type = ReferenceObjectType.BAR_2M,
        expectedLengthMeters = 2.0,
        label = "Barra 2m"
    )

    @Test
    fun nullObservedLength_shouldReturnNotProvided() {
        val result = validator.validate(reference, null)

        assertEquals(ReferenceMeasurementStatus.NOT_PROVIDED, result?.status)
        assertEquals(0f, result?.scaleValidationScore)
    }

    @Test
    fun closeObservedLength_shouldReturnValidScore() {
        val result = validator.validate(reference, 2.04)

        assertEquals(ReferenceMeasurementStatus.VALID, result?.status)
        assertTrue((result?.scaleValidationScore ?: 0f) > 0.85f)
    }

    @Test
    fun farObservedLength_shouldReturnWarning() {
        val result = validator.validate(reference, 1.55)

        assertEquals(ReferenceMeasurementStatus.WARNING, result?.status)
        assertTrue((result?.relativeError ?: 0.0) > 0.12)
    }
}
