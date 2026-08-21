package com.valhalla.loki.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.loki.R

/**
 * One [Font] entry for one weight of a **variable** font.
 *
 * `variationSettings` is passed explicitly and that is not optional. Both bundled fonts are
 * variable, and neither has a Regular default instance — `outfit_variable.ttf` has `fvar` `wght`
 * 100..900 with a default of **100** (its `name` ID 1 is literally "Outfit Thin"), and
 * `firacode_variable.ttf` is 300..700 with a default of **300**. A `Font(resId, weight)` call
 * resolves to the four-argument overload, which passes an EMPTY `FontVariation.Settings()`, so the
 * declared [FontWeight] is used for *matching* but never reaches the `wght` axis: every style in the
 * app would draw at the file's default instance. Naming `variationSettings` is what selects the
 * five-argument overload.
 *
 * Only `wght` is set. `FontVariation.Settings(weight, style)` would also write `ital`, and neither
 * font has an italic axis.
 */
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Outfit, across its full `wght` axis.
 *
 * Declaring all nine steps is free here — it is one file, not nine — so a `FontWeight.Light`
 * request renders at 300 instead of silently degrading to the nearest declared weight.
 */
val bodyFontFamily = FontFamily(
    variableFont(R.font.outfit_variable, FontWeight.Thin),
    variableFont(R.font.outfit_variable, FontWeight.ExtraLight),
    variableFont(R.font.outfit_variable, FontWeight.Light),
    variableFont(R.font.outfit_variable, FontWeight.Normal),
    variableFont(R.font.outfit_variable, FontWeight.Medium),
    variableFont(R.font.outfit_variable, FontWeight.SemiBold),
    variableFont(R.font.outfit_variable, FontWeight.Bold),
    variableFont(R.font.outfit_variable, FontWeight.ExtraBold),
    variableFont(R.font.outfit_variable, FontWeight.Black),
)

val displayFontFamily = bodyFontFamily

/**
 * Fira Code, across its `wght` axis (300..700 — it has no ExtraBold or Black).
 *
 * Named for what it is rather than who made it, so swapping the mono face is one line here. Drives
 * the `label*` styles below, and log surfaces should ask for it explicitly.
 */
val monoFontFamily = FontFamily(
    variableFont(R.font.firacode_variable, FontWeight.Light),
    variableFont(R.font.firacode_variable, FontWeight.Normal),
    variableFont(R.font.firacode_variable, FontWeight.Medium),
    variableFont(R.font.firacode_variable, FontWeight.SemiBold),
    variableFont(R.font.firacode_variable, FontWeight.Bold),
)

// Material 3's defaults, kept only as the source of the sizes, line heights and letter spacing that
// each style below copies. Private: it is scaffolding, not app API.
private val baseline = Typography()

// No italic entries. Neither font ships an italic face or an `ital` axis, and declaring the upright
// file as FontStyle.Italic — as the contribution did, nine times — tells Compose's matcher that a
// real italic exists, which SUPPRESSES the synthetic oblique it would otherwise apply. Italic text
// then renders upright. Omitting them keeps synthesis.
//
// `label*` is mono, which means every Button, TextButton, Chip and NavigationBarItem in the app
// draws in Fira Code, because M3 labels buttons with labelLarge. That is deliberate for a logcat
// reader. To undo it, point the three `label*` styles at bodyFontFamily; monoFontFamily stays
// available for the log surfaces either way.
val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Black
    ),
    displayMedium = baseline.displayMedium.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.ExtraBold
    ),
    displaySmall = baseline.displaySmall.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = baseline.headlineLarge.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = baseline.headlineMedium.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Bold
    ),
    headlineSmall = baseline.headlineSmall.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Medium
    ),
    titleLarge = baseline.titleLarge.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = baseline.titleMedium.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Medium
    ),
    titleSmall = baseline.titleSmall.copy(
        fontFamily = displayFontFamily,
        fontWeight = FontWeight.Normal
    ),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = bodyFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = bodyFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = bodyFontFamily),
    labelLarge = baseline.labelLarge.copy(
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = baseline.labelMedium.copy(
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Normal
    ),
    labelSmall = baseline.labelSmall.copy(
        fontFamily = monoFontFamily,
        fontWeight = FontWeight.Normal
    ),
)
