package io.keepagent.app.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/*
 * Stone / brick palette + pixel typeface, tuned to the KeepAgent mockup.
 * Visual theme only (F-014): colors are named for what they are, not for
 * what they evoke.
 */
val WallBase = Color(0xFF3A3A3A)         // mortar between bricks
val WallBrick = Color(0xFF545454)        // brick face
val BarStone = Color(0xFF2F2F2F)         // general dark surface
val BarDark = Color(0xFF1B1B21)          // top header bar
val BarChip = Color(0xFF141419)          // header status chips
val TileStone = Color(0xFF383838)        // general chips / fields
val TileStoneSelected = Color(0xFF4A4A4A)
val BevelLight = Color(0x29FFFFFF)
val TabBrown = Color(0xFF8A5A33)         // tab tile (unselected)
val TabBrownActive = Color(0xFFCDC7BE)   // tab tile (selected, pale)
val TabGlyph = Color(0xFF1D1A16)         // dark glyph on tab tiles
val TabLabelOn = Color(0xFF241F19)       // label on the pale tile
val AgentBubble = Color(0xFF262626)
val UserBubble = Color(0xFF85A95C)       // sage
val UserBubbleText = Color(0xFFF2F1E6)   // light text on sage
val InputStone = Color(0xFF5C5C5C)       // input strip
val AmberStatus = Color(0xFFE0B84C)
val LinkRead = Color(0xFFE57373)
val LinkSearch = Color(0xFFE0B84C)
val LinkWrite = Color(0xFF64B5F6)
val TextPrimary = Color(0xFFEDEDED)
val TextSecondary = Color(0xFF9E9E9E)

/**
 * Pixel typeface (Pixelify Sans, OFL) — the mockup's type. It's a variable
 * font; Compose maps each requested FontWeight onto its wght axis, so
 * SemiBold/Bold requests still render chunkier.
 */
val KeepFont = FontFamily(Font(resId = io.keepagent.app.R.font.pixelify_sans))

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
    MaterialTheme(colorScheme = DarkColors) {
        // Pixel face as the app-wide default. Text that pins an explicit
        // fontFamily (monospace for code) keeps it.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = KeepFont),
        ) {
            content()
        }
    }
}
