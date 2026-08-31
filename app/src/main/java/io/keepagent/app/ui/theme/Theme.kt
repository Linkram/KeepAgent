package io.keepagent.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Stone / iron palette, tuned to the mockup. Visual theme only (F-014):
 * no themed naming in code, logs, or settings — colors are named for what
 * they are, not for what they evoke.
 */
val WallBase = Color(0xFF212121)
val WallBrick = Color(0xFF2C2C2C)
val BarStone = Color(0xFF2F2F2F)
val TileStone = Color(0xFF383838)
val TileStoneSelected = Color(0xFF4A4A4A)
val BevelLight = Color(0x29FFFFFF)
val AgentBubble = Color(0xFF262626)
val UserBubble = Color(0xFF7CB342)
val UserBubbleText = Color(0xFF181C12)
val AmberStatus = Color(0xFFE0B84C)
val LinkRead = Color(0xFFE57373)
val LinkSearch = Color(0xFFE0B84C)
val LinkWrite = Color(0xFF64B5F6)
val TextPrimary = Color(0xFFEDEDED)
val TextSecondary = Color(0xFF9E9E9E)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8A8A8),
    onPrimary = Color(0xFF1A1A1A),
    secondary = UserBubble,
    background = WallBase,
    surface = BarStone,
    onSurface = TextPrimary,
    onBackground = TextPrimary,
)

@Composable
fun KeepAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
