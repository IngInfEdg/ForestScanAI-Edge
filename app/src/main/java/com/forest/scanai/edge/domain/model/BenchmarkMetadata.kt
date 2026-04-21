package com.forest.scanai.edge.domain.model

data class BenchmarkMetadata(
    val pileName: String = "",
    val operatorName: String = "",
    val notes: String = "",
    val referenceSource: String = "Sin referencia", // Metashape, Pix4D, ForestScanAI, Terreno, etc.
    val referenceVolume: Double? = null,
    val sessionLabel: String = "Corrida 1"
)
