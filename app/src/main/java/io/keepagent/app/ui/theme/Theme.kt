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
val WallBase = Color(0xFF171B20)
val WallBrick = Color(0xFF545454)        // brick face
val BarStone = Color(0xFF20262D)
val BarDark = Color(0xFF111827)          // night-sky strip above the stone tabs
val BarChip = Color(0xFF202838)          // header status chips
val TileStone = Color(0xFF383838)        // general chips / fields
val TileStoneSelected = Color(0xFF4A4A4A)
val BevelLight = Color(0x29FFFFFF)
val TabBrown = Color(0xFF666A70)         // gray stone tab (unselected)
val TabBrownActive = Color(0xFFC5C7CA)   // selected tab, pale stone
val TabGlyph = Color(0xFF181B20)         // dark glyph on stone
val TabLabelOn = Color(0xFF202328)        // label on the pale tile
val AgentBubble = Color(0xFF262626)
val ThinkingInset = Color(0xFF303238)
val UserBubble = Color(0xFF85A95C)       // sage
val UserBubbleText = Color(0xFF14200B)
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
val KeepFont = FontFamily.SansSerif

private val DarkColors = darkColorScheme(
    primary = UserBubble,
    onPrimary = UserBubbleText,
    primaryContainer = Color(0xFF3F5630),
    onPrimaryContainer = TextPrimary,
    secondary = UserBubble,
    secondaryContainer = TileStoneSelected,
    onSecondaryContainer = TextPrimary,
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
