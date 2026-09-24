package io.keepagent.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeHarmonyTest {
    @Test
    fun randomizerUsesEveryHarmonyAndKeepsAccentsReadable() {
        val names = (0..200).map(ThemeState::harmonyName).toSet()
        assertEquals(setOf("Complementary", "Analogous", "Triadic",
            "Split complementary", "Tetradic"), names)

        for (seed in 0..200) {
            val palette = ThemeState.randomPalette(seed)
            val accents = listOf(palette.user, palette.amber, palette.accent3)
            assertEquals(3, accents.distinct().size, "Seed $seed repeats an accent")
            accents.forEach { accent ->
                assertTrue(contrast(accent, palette.userText) >= 4.5,
                    "Seed $seed has an unreadable accent")
            }
            assertTrue(contrast(palette.wall, palette.text) >= 7.0,
                "Seed $seed has an unreadable background")
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val light = max(luminance(a), luminance(b))
        val dark = min(luminance(a), luminance(b))
        return (light + .05) / (dark + .05)
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double {
            val channel = value.toDouble()
            return if (channel <= .04045) channel / 12.92
                else Math.pow((channel + .055) / 1.055, 2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) +
            .0722 * linear(color.blue)
    }
}
