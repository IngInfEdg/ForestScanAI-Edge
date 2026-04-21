package com.forest.scanai.edge.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forest.scanai.edge.domain.model.BenchmarkMetadata

@Composable
fun BenchmarkMetadataDialog(
    onDismiss: () -> Unit,
    onConfirm: (BenchmarkMetadata) -> Unit
) {
    var metadata by remember { mutableStateOf(BenchmarkMetadata()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metadatos de Benchmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = metadata.pileName,
                    onValueChange = { metadata = metadata.copy(pileName = it) },
                    label = { Text("Nombre de la Pila") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = metadata.operatorName,
                    onValueChange = { metadata = metadata.copy(operatorName = it) },
                    label = { Text("Operador") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = metadata.sessionLabel,
                    onValueChange = { metadata = metadata.copy(sessionLabel = it) },
                    label = { Text("Etiqueta de Sesión (ej: Corrida 1)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = metadata.referenceSource,
                    onValueChange = { metadata = metadata.copy(referenceSource = it) },
                    label = { Text("Fuente de Referencia") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = metadata.referenceVolume?.toString() ?: "",
                    onValueChange = { metadata = metadata.copy(referenceVolume = it.toDoubleOrNull()) },
                    label = { Text("Volumen de Referencia (m³)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = metadata.notes,
                    onValueChange = { metadata = metadata.copy(notes = it) },
                    label = { Text("Notas") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(metadata) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
