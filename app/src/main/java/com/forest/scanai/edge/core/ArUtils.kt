package com.forest.scanai.edge.core

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import io.github.sceneview.math.Position

/**
 * Proyecta un punto 3D a coordenadas 2D de pantalla.
 */
fun projectPointToScreen(
    pos: Position,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    width: Float,
    height: Float
): Offset? {
    val worldCoord = floatArrayOf(pos.x, pos.y, pos.z, 1.0f)
    val vpMatrix = FloatArray(16)
    Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    
    val clipCoord = FloatArray(4)
    Matrix.multiplyMV(clipCoord, 0, vpMatrix, 0, worldCoord, 0)
    
    if (clipCoord[3] <= 0) return null
    
    val x = (clipCoord[0] / clipCoord[3] + 1.0f) / 2.0f * width
    val y = (1.0f - clipCoord[1] / clipCoord[3]) / 2.0f * height
    
    return Offset(x, y)
}
