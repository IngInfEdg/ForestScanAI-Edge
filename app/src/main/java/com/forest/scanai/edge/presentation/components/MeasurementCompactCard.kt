package com.forest.scanai.edge.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forest.scanai.edge.domain.model.CompletenessLevel
import com.forest.scanai.edge.presentation.state.ScanUiState
import com.google.ar.core.TrackingState
import java.util.Locale

@Composable
fun MeasurementCompactCard(
    uiState: ScanUiState,
    modifier: Modifier = Modifier
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val metrics = uiState.metrics
    val stateColor = when (metrics.completeness) {
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

        if (metrics.trackingState != TrackingState.TRACKING) {
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
                value = formatVolume(metrics.stereoVolume)
            )

            MetricItem(
                label = "m³ Neto",
                value = formatVolume(metrics.netVolume)
            )
        }

        Text(
            text = "Cobertura: ${formatPercent(metrics.coveragePercentage)}%",
            color = Color(0xFF00E5FF),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Estado: ${completenessText(metrics.completeness)}",
            color = stateColor,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = uiState.shortGuidanceMessage.ifBlank { "Rodea la pila para iniciar la medición." },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider(color = Color.Gray.copy(alpha = 0.45f))
        Text(
            text = if (showDiagnostics) "Ocultar diagnóstico" else "Ver diagnóstico",
            color = Color(0xFF90CAF9),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable { showDiagnostics = !showDiagnostics }
        )

        if (showDiagnostics) {
            metrics.diagnostics.forEach { detail ->
                Text(
                    text = "• $detail",
                    color = Color(0xFFE0E0E0),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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
        CompletenessLevel.ACCEPTABLE -> "Revisable"
        CompletenessLevel.COMPLETE -> "Completa"
    }
}

private fun formatVolume(value: Double): String {
    return String.format(Locale.US, "%.2f", value)
}

private fun formatPercent(value: Float): String {
    return String.format(Locale.US, "%.0f", value * 100f)
}
