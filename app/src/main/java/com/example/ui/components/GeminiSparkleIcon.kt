package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAuraColors

@Composable
fun GeminiSparkleIcon(
    size: Dp = 24.dp,
    animate: Boolean = false,
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "gemini_sparkle")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle_spin"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_pulse"
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val factor = if (animate) scale else 1f
        val rx = (w * 0.46f) * factor
        val ry = (h * 0.46f) * factor
        val curve = rx * 0.24f

        val path = Path().apply {
            moveTo(cx, cy - ry)
            cubicTo(cx + curve * 0.4f, cy - curve * 0.4f, cx + curve * 0.4f, cy - curve * 0.4f, cx + rx, cy)
            cubicTo(cx + curve * 0.4f, cy + curve * 0.4f, cx + curve * 0.4f, cy + curve * 0.4f, cx, cy + ry)
            cubicTo(cx - curve * 0.4f, cy + curve * 0.4f, cx - curve * 0.4f, cy + curve * 0.4f, cx - rx, cy)
            cubicTo(cx - curve * 0.4f, cy - curve * 0.4f, cx - curve * 0.4f, cy - curve * 0.4f, cx, cy - ry)
            close()
        }

        val brush = Brush.linearGradient(
            colors = listOf(
                auraColors.sparkleStart,
                auraColors.sparkleMid,
                auraColors.sparkleEnd
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        )

        rotate(degrees = if (animate) rotation else 0f, pivot = Offset(cx, cy)) {
            drawPath(path = path, brush = brush)
        }
    }
}
