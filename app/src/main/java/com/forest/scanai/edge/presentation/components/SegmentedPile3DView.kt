package com.forest.scanai.edge.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.forest.scanai.edge.domain.engine.PointCloudReviewModelBuilder
import com.forest.scanai.edge.domain.engine.ReviewPoint3D
import kotlin.math.max

@Composable
fun SegmentedPile3DView(
    points: List<ReviewPoint3D>,
    modifier: Modifier = Modifier,
    initialYaw: Float = 0.65f,
    initialPitch: Float = -0.25f,
    initialZoom: Float = 1.15f
) {
    var yaw by remember { mutableFloatStateOf(initialYaw) }
    var pitch by remember { mutableFloatStateOf(initialPitch) }
    var zoom by remember { mutableFloatStateOf(initialZoom) }

    val builder = remember { PointCloudReviewModelBuilder() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .pointerInput(points) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    yaw += pan.x * 0.01f
                    pitch = (pitch + pan.y * 0.0045f).coerceIn(-1.2f, 1.2f)
                    zoom = (zoom * gestureZoom).coerceIn(0.6f, 3.0f)
                }
            }
    ) {
        if (points.isEmpty()) {
            drawEmptyState()
            return@Canvas
        }

        val rotated = points
            .map { builder.rotate(it, yaw, pitch) }
            .sortedBy { it.z }

        val maxRange = max(
            1f,
            max(
                rotated.maxOf { kotlin.math.abs(it.x) },
                max(
                    rotated.maxOf { kotlin.math.abs(it.y) },
                    rotated.maxOf { kotlin.math.abs(it.z) }
                )
            )
        )

        val scale = (size.minDimension / (maxRange * 2.5f)) * zoom
        val center = Offset(size.width / 2f, size.height * 0.72f)

        rotated.forEach { point ->
            val screenX = center.x + point.x * scale
            val screenY = center.y - point.y * scale + point.z * scale * 0.18f

            val isVisible =
                screenX in -20f..(size.width + 20f) &&
                        screenY in -20f..(size.height + 20f)

            if (isVisible) {
                drawCircle(
                    color = if (point.isPile) Color(0xFF35D07F) else Color(0xFF7A5A3A),
                    radius = if (point.isPile) 2.6f else 1.7f,
                    center = Offset(screenX, screenY),
                    alpha = if (point.isPile) 0.95f else 0.45f
                )
            }
        }
    }
}

private fun DrawScope.drawEmptyState() {
    val center = Offset(size.width / 2f, size.height / 2f)

    drawCircle(
        color = Color.Gray.copy(alpha = 0.25f),
        radius = 90f,
        center = center
    )

    drawCircle(
        color = Color.Gray.copy(alpha = 0.50f),
        radius = 3.5f,
        center = center
    )
}