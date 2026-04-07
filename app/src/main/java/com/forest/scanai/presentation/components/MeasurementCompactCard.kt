package com.forest.scanai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forest.scanai.domain.model.CompletenessLevel
import com.forest.scanai.domain.model.ScanUiState
import com.google.ar.core.TrackingState
import java.util.Locale

@Composable
fun MeasurementCompactCard(
    uiState: ScanUiState,
    modifier: Modifier = Modifier
) {
    val stateColor = when (uiState.completeness) {
        CompletenessLevel.COMPLETE -> Color(0xFF4CAF50)
        CompletenessLevel.ACCEPTABLE -> Color(0xFFFFC107)
        CompletenessLevel.PARTIAL -> Color(0xFFFF9800)
        CompletenessLevel.INSUFFICIENT -> Color(0xFFF44336)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Medición",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        if (uiState.trackingState != TrackingState.TRACKING) {
            Text(
                text = "AR: moviendo cámara, buscando superficie...",
                color = Color.Yellow,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricItem(
                label = "m³ Estéreo",
                value = formatVolume(uiState.stereoVolume)
            )

            MetricItem(
                label = "m³ Neto",
                value = formatVolume(uiState.netVolume)
            )
        }

        Text(
            text = "Cobertura: ${formatPercent(uiState.coveragePercentage)}%  •  Sectores: ${uiState.coveredSectors}/${uiState.totalSectors}",
            color = Color(0xFF00E5FF),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Recorrido AR: ${formatDistance(uiState.arDistanceWalked)} m",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = "Estado: ${completenessText(uiState.completeness)}",
            color = stateColor,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Guía: ${uiState.guidanceMessage.ifBlank { "Rodea la pila para iniciar la medición." }}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun completenessText(level: CompletenessLevel): String {
    return when (level) {
        CompletenessLevel.INSUFFICIENT -> "Insuficiente"
        CompletenessLevel.PARTIAL -> "Parcial"
        CompletenessLevel.ACCEPTABLE -> "Aceptable"
        CompletenessLevel.COMPLETE -> "Completa"
    }
}

private fun formatVolume(value: Double): String {
    return String.format(Locale.US, "%.2f", value)
}

private fun formatDistance(value: Double): String {
    return String.format(Locale.US, "%.1f", value)
}

private fun formatPercent(value: Float): String {
    return String.format(Locale.US, "%.0f", value * 100f)
}