package com.forest.scanai.edge.core

data class ScanParams(
    val minDepth: Double = 0.5,
    val maxDepth: Double = 8.0, // Aumentado de 4.0 a 8.0 para pilas grandes
    val maxPileDepth: Double = 6.0,
    val voxelSize: Float = 0.05f,
    val confidenceThreshold: Float = 0.5f, // Ligeramente más bajo para capturar más puntos en madera oscura
    val sliceWidth: Float = 0.15f, // Slices un poco más anchos para mayor estabilidad
    val distanceCorrectionFactor: Double = 0.91,
    val emaAlpha: Double = 0.15,
    val groundMargin: Double = 0.25 // Ignorar puntos a menos de 25cm del suelo (maleza)
)
