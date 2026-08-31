package io.keepagent.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import io.keepagent.app.ui.theme.WallBase
import io.keepagent.app.ui.theme.WallBrick

/**
 * Subtle wall backdrop (F-014): low-contrast brick joints over the wall
 * base color, matching the mockup. Brick tints are deterministic per
 * position so the texture is stable across redraws.
 */
@Composable
fun CastleWallBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = WallBase)
        val brickW = 72f
        val brickH = 26f
        val gap = 1.5f
        var row = 0
        var y = 0f
        while (y < size.height) {
            val rowOffset = if (row % 2 == 0) 0f else brickW / 2f
            var x = -rowOffset
            var col = 0
            while (x < size.width) {
                val tint = ((row * 31 + col * 17) % 5) // 0..4
                val brick = lerp(WallBase, WallBrick, 0.45f + tint * 0.05f)
                drawRoundRect(
                    topLeft = Offset(x + gap / 2f, y + gap / 2f),
                    size = Size(brickW - gap, brickH - gap),
                    cornerRadius = CornerRadius(2f, 2f),
                    color = brick,
                )
                x += brickW
                col++
            }
            y += brickH
            row++
        }
    }
}
