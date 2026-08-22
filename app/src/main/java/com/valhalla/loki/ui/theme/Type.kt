package com.valhalla.loki.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.valhalla.loki.R

import androidx.compose.ui.text.font.Font as ResFont

/**
 * One [Font] entry for one weight of a **variable** font.
 *
 * Fira Code is the only variable font left — Outfit ships as nine static instances below — so this
 * has exactly one caller. The trap it works around is worth keeping written down anyway.
 *
 * `variationSettings` is passed explicitly and that is not optional. `firacode_variable.ttf` has
 * `fvar` `wght` 300..700 with a default of **300** and no Regular default instance (its `name` ID 1
 * is "Fira Code Light"). A `Font(resId, weight)` call resolves to the four-argument overload, which
 * passes an EMPTY `FontVariation.Settings()`, so the declared [FontWeight] is used for *matching* but
 * never reaches the `wght` axis: every mono style in the app would draw at 300. Naming
 * `variationSettings` is what selects the five-argument overload.
 *
 * Only `wght` is set. `FontVariation.Settings(weight, style)` would also write `ital`, and the font
 * has no italic axis.
 */
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/**
 * Outfit, as nine static instances — one file per weight, no `wght` axis on any of them.
 *
 * This was a single variable `outfit_variable.ttf` until the palette and typography were brought
 * into line with Thor. It is now the nine files Thor ships, byte-identical to Thor's copies, which
 * costs about 284 KiB of uncompressed APK over the variable file and buys the two apps one
 * typography source instead of two. Declaring every step is still what makes a `FontWeight.Light`
 * request render at 300 rather than degrade to whichever weight happens to be nearest.
 *
 * Eighteen entries, not nine: nine weights times two [FontStyle]s. The italic half is not what it
 * looks like — see the note above [AppTypography].
 */
val bodyFontFamily = FontFamily(
    ResFont(resId = R.font.outfit_regular, weight = FontWeight.Normal, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_black, weight = FontWeight.Black, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_bold, weight = FontWeight.Bold, style = FontStyle.Normal),
    ResFont(
        resId = R.font.outfit_extrabold,
        weight = FontWeight.ExtraBold,
        style = FontStyle.Normal
    ),
    ResFont(
        resId = R.font.outfit_extralight,
        weight = FontWeight.ExtraLight,
        style = FontStyle.Normal
    ),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Normal),
    ResFont(resId = R.font.outfit_regular, weight = FontWeight.Normal, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_black, weight = FontWeight.Black, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_bold, weight = FontWeight.Bold, style = FontStyle.Italic),
    ResFont(
        resId = R.font.outfit_extrabold,
        weight = FontWeight.ExtraBold,
        style = FontStyle.Italic
    ),
    ResFont(
        resId = R.font.outfit_extralight,
        weight = FontWeight.ExtraLight,
        style = FontStyle.Italic
    ),
    ResFont(resId = R.font.outfit_light, weight = FontWeight.Light, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_medium, weight = FontWeight.Medium, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_semibold, weight = FontWeight.SemiBold, style = FontStyle.Italic),
    ResFont(resId = R.font.outfit_thin, weight = FontWeight.Thin, style = FontStyle.Italic),
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

// About bodyFontFamily's nine FontStyle.Italic entries: every one of them names the UPRIGHT file.
// No bundled face is italic — all nine outfit_*.ttf have fsSelection bit 0 clear, macStyle italic
// clear and post.italicAngle 0.0, and firacode_variable.ttf has no `ital` axis — so what those
// entries do is tell Compose's matcher that a real italic exists, which SUPPRESSES the synthetic
// oblique it would otherwise apply. Italic text renders upright rather than slanted.
//
// They are here for parity with Thor, whose Type.kt carries the identical block, and the cost today
// is zero: nothing in Loki asks for FontStyle.Italic. Delete the nine Italic entries the moment
// something does, and synthesis comes back. Do not instead go looking for an italic Outfit file —
// there isn't one upstream.
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
