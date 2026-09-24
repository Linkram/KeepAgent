package io.keepagent.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import io.keepagent.core.settings.SettingsStore
import kotlin.math.abs
import kotlin.random.Random

data class KeepPalette(
    val wall: Color, val brick: Color, val bar: Color, val dark: Color,
    val chip: Color, val tile: Color, val selected: Color, val tab: Color,
    val tabActive: Color, val glyph: Color, val agent: Color, val thinking: Color,
    val user: Color, val userText: Color, val input: Color, val amber: Color,
    val text: Color, val secondary: Color,
)

object ThemeState {
    const val KEEP = "keep"
    const val BLUE = "blue"
    const val OLED = "oled"
    const val SLATE = "slate"
    const val RANDOM = "random"
    val choices = listOf(KEEP to "Castle keep", BLUE to "Night blue", OLED to "OLED black", SLATE to "Slate blue", RANDOM to "Surprise me")
    var selected by mutableStateOf(KEEP)
        private set
    var palette by mutableStateOf(paletteFor(KEEP, 0))
        private set
    private var seed = 0
    fun preview(id: String): KeepPalette = paletteFor(id, seed)

    fun load(settings: SettingsStore) {
        seed = settings.getString(SettingsStore.NS_GENERAL, "themeSeed")?.toIntOrNull() ?: 0
        selected = settings.getString(SettingsStore.NS_GENERAL, "theme")?.takeIf { id -> choices.any { it.first == id } } ?: KEEP
        palette = paletteFor(selected, seed)
    }

    fun choose(id: String, settings: SettingsStore) {
        if (id !in choices.map { it.first }) return
        if (id == RANDOM) {
            val previous = seed
            do { seed = Random.nextInt(1, Int.MAX_VALUE) } while (seed == previous)
            settings.setString(SettingsStore.NS_GENERAL, "themeSeed", seed.toString())
        }
        selected = id
        palette = paletteFor(id, seed)
        settings.setString(SettingsStore.NS_GENERAL, "theme", id)
    }

    private fun c(value: Long) = Color(value)
    private fun paletteFor(id: String, seed: Int): KeepPalette = when (id) {
        BLUE -> KeepPalette(c(0xFF171C24), c(0xFF303B4A), c(0xFF202936), c(0xFF111925), c(0xFF273447), c(0xFF303B4B), c(0xFF3D536D), c(0xFF596C84), c(0xFF8AB8F2), c(0xFF111925), c(0xFF252E3B), c(0xFF2D394A), c(0xFF78AEEA), c(0xFF081B30), c(0xFF3B4A5C), c(0xFFE8BA65), c(0xFFF1F5FB), c(0xFFAFBED1))
        OLED -> KeepPalette(Color.Black, c(0xFF333333), Color.Black, Color.Black, c(0xFF161616), c(0xFF1B1B1B), c(0xFF353535), c(0xFF666666), Color.White, Color.Black, c(0xFF151515), c(0xFF202020), Color.White, Color.Black, c(0xFF252525), Color.White, Color.White, c(0xFFBFBFBF))
        SLATE -> KeepPalette(c(0xFF252B35), c(0xFF404957), c(0xFF303845), c(0xFF202630), c(0xFF394451), c(0xFF414C59), c(0xFF566778), c(0xFF677687), c(0xFFADBED0), c(0xFF1C2732), c(0xFF313B48), c(0xFF394553), c(0xFF9FB5CA), c(0xFF1A2B3C), c(0xFF4A5868), c(0xFFD6C19B), c(0xFFF0F3F6), c(0xFFBAC6D1))
        RANDOM -> {
            val r = Random(seed)
            val hue = r.nextFloat() * 360f
            val accent = hsl(hue, .42f + r.nextFloat() * .18f, .64f)
            val n = (hue + 25f) % 360f
            val darkText = c(0xFF101820)
            KeepPalette(hsl(n,.10f,.105f), hsl(n,.11f,.29f), hsl(n,.11f,.15f), hsl(n,.10f,.075f), hsl(n,.13f,.18f), hsl(n,.11f,.22f), hsl(n,.15f,.30f), hsl(n,.12f,.39f), accent, darkText, hsl(n,.09f,.17f), hsl(n,.11f,.21f), accent, darkText, hsl(n,.11f,.31f), accent, c(0xFFF4F3EF), c(0xFFB9BBB9))
        }
        else -> KeepPalette(c(0xFF191B1A), c(0xFF4C4942), c(0xFF252725), c(0xFF121513), c(0xFF30342F), c(0xFF373934), c(0xFF494D42), c(0xFF6B6251), c(0xFFD4BE82), c(0xFF202319), c(0xFF292B27), c(0xFF34372E), c(0xFF9FB676), c(0xFF17200E), c(0xFF4B4C43), c(0xFFD7B86F), c(0xFFF1F0E9), c(0xFFB6B8AA))
    }

    private fun hsl(h: Float, s: Float, l: Float): Color {
        val chroma = (1f - abs(2f*l-1f))*s
        val x = chroma*(1f-abs((h/60f)%2f-1f))
        val m = l-chroma/2f
        val (red, green, blue) = when ((h/60f).toInt()%6) {
            0 -> Triple(chroma,x,0f); 1 -> Triple(x,chroma,0f)
            2 -> Triple(0f,chroma,x); 3 -> Triple(0f,x,chroma)
            4 -> Triple(x,0f,chroma); else -> Triple(chroma,0f,x)
        }
        return Color(red+m,green+m,blue+m)
    }
}

val WallBase get() = ThemeState.palette.wall
val WallBrick get() = ThemeState.palette.brick
val BarStone get() = ThemeState.palette.bar
val BarDark get() = ThemeState.palette.dark
val BarChip get() = ThemeState.palette.chip
val TileStone get() = ThemeState.palette.tile
val TileStoneSelected get() = ThemeState.palette.selected
val BevelLight get() = Color.White.copy(alpha = .16f)
val TabBrown get() = ThemeState.palette.tab
val TabBrownActive get() = ThemeState.palette.tabActive
val TabGlyph get() = ThemeState.palette.glyph
val TabLabelOn get() = ThemeState.palette.glyph
val AgentBubble get() = ThemeState.palette.agent
val ThinkingInset get() = ThemeState.palette.thinking
val UserBubble get() = ThemeState.palette.user
val UserBubbleText get() = ThemeState.palette.userText
val InputStone get() = ThemeState.palette.input
val AmberStatus get() = ThemeState.palette.amber
val LinkRead get() = if (ThemeState.selected == ThemeState.OLED) Color.White else Color(0xFFE57373)
val LinkSearch get() = ThemeState.palette.amber
val LinkWrite get() = if (ThemeState.selected == ThemeState.OLED) Color.White else Color(0xFF64B5F6)
val TextPrimary get() = ThemeState.palette.text
val TextSecondary get() = ThemeState.palette.secondary
val KeepFont = FontFamily.SansSerif

@Composable
fun KeepAgentTheme(content: @Composable () -> Unit) {
    val p = ThemeState.palette
    MaterialTheme(colorScheme = darkColorScheme(
        primary = p.user, onPrimary = p.userText,
        primaryContainer = p.selected, onPrimaryContainer = p.text,
        secondary = p.user, secondaryContainer = p.selected,
        onSecondaryContainer = p.text, background = p.wall,
        surface = p.bar, surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.wall, surfaceContainerLow = p.bar,
        surfaceContainer = p.tile, surfaceContainerHigh = p.tile,
        surfaceContainerHighest = p.selected, surfaceVariant = p.tile,
        outline = p.secondary,
        onSurface = p.text, onBackground = p.text,
    )) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = KeepFont),
            LocalContentColor provides p.text,
        ) { content() }
    }
}
