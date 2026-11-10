// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lumen.R
import app.lumen.data.ThemeMode

/** Warm monochrome: near-black ink on paper, inverted at night. The music's artwork brings the colour. */
@Immutable
data class LumenColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val isDark: Boolean,
)

val LightColors = LumenColors(
    bg = Color(0xFFFAF9F5),
    surface = Color(0xFFF1EFE8),
    surfaceHigh = Color(0xFFE7E4DA),
    line = Color(0xFFE2DFD4),
    text = Color(0xFF141413),
    textDim = Color(0xFF6C6A63),
    textFaint = Color(0xFF9F9D95),
    isDark = false,
)

val DarkColors = LumenColors(
    bg = Color(0xFF0E0E0D),
    surface = Color(0xFF191917),
    surfaceHigh = Color(0xFF242321),
    line = Color(0xFF2A2926),
    text = Color(0xFFF5F4EE),
    textDim = Color(0xFFA4A39C),
    textFaint = Color(0xFF6E6D67),
    isDark = true,
)

/** Colour themes. "classic" is Lumen's paper-and-ink; the others tint the same design with one hue. */
object Palettes {
    /** id → name shown in Settings. */
    val all = listOf(
        "classic" to "Classic", "indigo" to "Indigo", "ocean" to "Ocean", "forest" to "Forest",
        "sunset" to "Sunset", "rose" to "Rose", "lavender" to "Lavender", "black" to "Pure black",
    )
    private val hues = mapOf("indigo" to 232f, "ocean" to 200f, "forest" to 150f, "sunset" to 24f, "rose" to 345f, "lavender" to 275f)

    fun colors(id: String, dark: Boolean): LumenColors {
        if (id == "black" && dark) return DarkColors.copy(bg = Color.Black, surface = Color(0xFF0E0E0E), surfaceHigh = Color(0xFF191919), line = Color(0xFF1F1F1F))
        val h = hues[id] ?: return if (dark) DarkColors else LightColors
        return if (dark) LumenColors(
            bg = Color.hsl(h, 0.30f, 0.055f), surface = Color.hsl(h, 0.26f, 0.10f), surfaceHigh = Color.hsl(h, 0.24f, 0.15f),
            line = Color.hsl(h, 0.20f, 0.18f), text = Color.hsl(h, 0.65f, 0.92f), textDim = Color.hsl(h, 0.18f, 0.68f),
            textFaint = Color.hsl(h, 0.12f, 0.45f), isDark = true,
        ) else LumenColors(
            bg = Color.hsl(h, 0.40f, 0.97f), surface = Color.hsl(h, 0.34f, 0.93f), surfaceHigh = Color.hsl(h, 0.30f, 0.885f),
            line = Color.hsl(h, 0.24f, 0.86f), text = Color.hsl(h, 0.60f, 0.17f), textDim = Color.hsl(h, 0.16f, 0.40f),
            textFaint = Color.hsl(h, 0.12f, 0.60f), isDark = false,
        )
    }

    /** A swatch for the picker. */
    fun swatch(id: String): Color = when (id) {
        "classic" -> Color(0xFF8A877C)
        "black" -> Color.Black
        else -> Color.hsl(hues[id] ?: 0f, 0.6f, 0.55f)
    }
}

/** Typefaces. Inter and Source Serif ship with the app; the rest are the phone's own fonts. */
object Fonts {
    val all = listOf("inter" to "Inter", "system" to "Phone font", "serif" to "Serif", "mono" to "Mono", "condensed" to "Condensed", "casual" to "Handwritten")

    /** (text family, headline family) */
    fun families(id: String): Pair<FontFamily, FontFamily> = when (id) {
        "system" -> FontFamily.Default to Serif
        "serif" -> Serif to Serif
        "mono" -> FontFamily.Monospace to FontFamily.Monospace
        "condensed" -> device("sans-serif-condensed").let { it to it }
        "casual" -> FontFamily.Cursive to FontFamily.Cursive
        else -> Inter to Serif
    }

    private fun device(name: String) = FontFamily(
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName(name), FontWeight.Normal),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName(name), FontWeight.Medium),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName(name), FontWeight.SemiBold),
        Font(androidx.compose.ui.text.font.DeviceFontFamilyName(name), FontWeight.Bold),
    )

    fun type(id: String, scale: Float): LumenType {
        val (body, head) = families(id)
        val s = scale.coerceIn(0.85f, 1.3f)
        fun TextStyle.with(family: FontFamily) = copy(fontFamily = family, fontSize = fontSize * s, lineHeight = lineHeight * s)
        val base = LumenType()
        return LumenType(
            display = base.display.with(head), title = base.title.with(head), heading = base.heading.with(body),
            body = base.body.with(body), bodyStrong = base.bodyStrong.with(body), small = base.small.with(body),
            label = base.label.with(body), micro = base.micro.with(body),
        )
    }
}

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

val Serif = FontFamily(
    variable(R.font.source_serif, 400),
    variable(R.font.source_serif, 500),
    variable(R.font.source_serif, 600),
)

@Immutable
data class LumenType(
    val display: TextStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    val title: TextStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight(500), fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    val heading: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(650), fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    val body: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    val bodyStrong: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
    val small: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(450), fontSize = 13.sp, lineHeight = 17.sp),
    val label: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    val micro: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

val LocalColors = staticCompositionLocalOf { DarkColors }
val LocalType = staticCompositionLocalOf { LumenType() }

object Lumen {
    val colors: LumenColors @Composable get() = LocalColors.current
    val type: LumenType @Composable get() = LocalType.current
}

@Composable
fun LumenTheme(mode: ThemeMode, palette: String = "classic", font: String = "inter", textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = androidx.compose.runtime.remember(palette, dark) { Palettes.colors(palette, dark) }
    val scheme = if (dark) darkColorScheme(
        primary = c.text, onPrimary = c.bg, secondary = c.textDim, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textDim,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh,
        surfaceContainerLow = c.surface, outline = c.line, outlineVariant = c.line, secondaryContainer = c.surfaceHigh,
        onSecondaryContainer = c.text, primaryContainer = c.surfaceHigh, onPrimaryContainer = c.text,
    ) else lightColorScheme(
        primary = c.text, onPrimary = c.bg, secondary = c.textDim, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textDim,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh,
        surfaceContainerLow = c.surface, outline = c.line, outlineVariant = c.line, secondaryContainer = c.surfaceHigh,
        onSecondaryContainer = c.text, primaryContainer = c.surfaceHigh, onPrimaryContainer = c.text,
    )
    val type = androidx.compose.runtime.remember(font, textScale) { Fonts.type(font, textScale) }
    CompositionLocalProvider(LocalColors provides c, LocalType provides type) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = type.body, bodyMedium = type.small, titleLarge = type.heading, labelLarge = type.label),
            content = content,
        )
    }
}
