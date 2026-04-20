package com.forest.scanai.edge.presentation.state

import com.forest.scanai.edge.domain.model.ScanMetrics

/**
 * Representa el estado de interacción de la pantalla de escaneo.
 * Contiene tanto el estado visual como las métricas técnicas actuales.
 */
data class ScanUiState(
    val isMeasuring: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val guidanceMessage: String = "Inicia el recorrido alrededor de la pila.",
    val shortGuidanceMessage: String = "Inicia el recorrido alrededor de la pila.",
    val canReviewMeasurement: Boolean = false,
    val canFinishMeasurement: Boolean = false,
    val metrics: ScanMetrics = ScanMetrics()
)
