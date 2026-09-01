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
 * Brick wall backdrop (F-014): low-contrast brick joints over the mortar
 * base, matching the mockup. Brick tints are deterministic per position so
 * the texture is stable across redraws. Brick size is relative to the view
 * so it reads the same on any screen.
 */
@Composable
fun CastleWallBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = WallBase)
        val brickW = size.width / 5f
        val brickH = brickW / 3.2f
        val gap = maxOf(2f, brickW * 0.03f)
        var row = 0
        var y = 0f
        while (y < size.height) {
            val rowOffset = if (row % 2 == 0) 0f else brickW / 2f
            var x = -rowOffset
            var col = 0
            while (x < size.width) {
                val tint = ((row * 31 + col * 17) % 5) // 0..4
                val brick = lerp(WallBase, WallBrick, 0.55f + tint * 0.09f)
                drawRoundRect(
                    topLeft = Offset(x + gap / 2f, y + gap / 2f),
                    size = Size(brickW - gap, brickH - gap),
                    cornerRadius = CornerRadius(3f, 3f),
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
