package com.reactivenotch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

enum class IconKind { PLAY, PAUSE, NEXT, PREV, HEADPHONES }

@Composable
fun GlyphIcon(kind: IconKind, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // 밑변이 x0, 꼭짓점이 x1 인 삼각형 (x1 > x0 이면 오른쪽을 향함)
        fun tri(x0: Float, x1: Float) = Path().apply {
            moveTo(w * x0, h * 0.18f); lineTo(w * x1, h * 0.5f); lineTo(w * x0, h * 0.82f)
            close()
        }
        when (kind) {
            IconKind.PLAY -> drawPath(tri(0.22f, 0.86f), tint)
            IconKind.PAUSE -> {
                drawRoundRect(tint, Offset(w * 0.2f, h * 0.15f), Size(w * 0.2f, h * 0.7f), CornerRadius(w * 0.05f))
                drawRoundRect(tint, Offset(w * 0.6f, h * 0.15f), Size(w * 0.2f, h * 0.7f), CornerRadius(w * 0.05f))
            }
            IconKind.NEXT -> {
                drawPath(tri(0.12f, 0.72f), tint)
                drawRoundRect(tint, Offset(w * 0.76f, h * 0.18f), Size(w * 0.13f, h * 0.64f), CornerRadius(w * 0.04f))
            }
            IconKind.PREV -> {
                drawPath(tri(0.88f, 0.28f), tint)
                drawRoundRect(tint, Offset(w * 0.11f, h * 0.18f), Size(w * 0.13f, h * 0.64f), CornerRadius(w * 0.04f))
            }
            IconKind.HEADPHONES -> {
                val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.17f, h * 0.12f), Size(w * 0.66f, h * 0.66f), style = stroke)
                drawRoundRect(tint, Offset(w * 0.1f, h * 0.5f), Size(w * 0.2f, h * 0.38f), CornerRadius(w * 0.08f))
                drawRoundRect(tint, Offset(w * 0.7f, h * 0.5f), Size(w * 0.2f, h * 0.38f), CornerRadius(w * 0.08f))
            }
        }
    }
}
