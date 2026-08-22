package com.valhalla.loki.ui.theme

import androidx.compose.ui.graphics.Color

// Loki's explicit brand scheme: green primary, gold secondary, violet tertiary. Used whenever
// Material You is off, which is the default — see ThemeSettings.dynamicColor.
//
// Every value here is assigned to a ColorScheme slot in Theme.kt. If you add one, wire it up in the
// same commit: an unassigned colour looks like it is in use and silently isn't, which is how the
// contribution ended up with four defined-but-ignored roles and sixteen dead ones.
//
// Contrast ratios in the comments are WCAG relative luminance against the surface named. Text roles
// need 4.5:1; `outline` and other non-text roles need 3:1.

// --- DARK ---
// M3 takes a dark scheme's ACCENT roles from the light tonal steps and its CONTAINERS from the dark
// steps. The contribution had it the other way round, which put primary at 2.14:1, tertiary at
// 1.94:1 and error at 2.69:1 on its own surface — invisible, and `primary` is the content colour for
// TextButton, OutlinedButton, Switch, Slider, RadioButton, a selected NavigationBarItem, a focused
// TextField's label and indicator, and every progress indicator.
val Primary = Color(0xFF7BDBA6)              // 10.38:1 on Surface, 12.53:1 on AMOLED black
val OnPrimary = Color(0xFF003920)            // 7.80:1 on Primary
val PrimaryContainer = Color(0xFF0B6623)
val OnPrimaryContainer = Color(0xFFD4F5E0)   // 6.10:1 on PrimaryContainer
val Secondary = Color(0xFFC9B037)            // 8.08:1 on Surface
val OnSecondary = Color(0xFF2A1F00)
val SecondaryContainer = Color(0xFF3D2E00)
val OnSecondaryContainer = Color(0xFFFFF0B3)
val Tertiary = Color(0xFFD3BBF0)             // 10.06:1 on Surface
val OnTertiary = Color(0xFF2E1B5C)
val TertiaryContainer = Color(0xFF2E1B5C)
val OnTertiaryContainer = Color(0xFFE8D5FF)  // 10.78:1 on TertiaryContainer
val Error = Color(0xFFFFB4AB)                // 10.25:1 on Surface
val OnError = Color(0xFF690005)              // 7.72:1 on Error
val ErrorContainer = Color(0xFF410002)
val OnErrorContainer = Color(0xFFFFDAD6)     // 13.26:1 on ErrorContainer
val Background = Color(0xFF1A1A1A)
val OnBackground = Color(0xFFF9F9FB)
val Surface = Color(0xFF1A1A1A)
val OnSurface = Color(0xFFF9F9FB)
val SurfaceVariant = Color(0xFF2E2E2E)
val OnSurfaceVariant = Color(0xFFC5C5C9)
val SurfaceTint = Color(0xFF005C32)
val SurfaceDim = Color(0xFF000000)
val SurfaceBright = Color(0xFF2E2E2E)
val SurfaceContainerLowest = Color(0xFF000000)
val SurfaceContainerLow = Color(0xFF1A1A1A)
val SurfaceContainer = Color(0xFF1A1A1A)
val SurfaceContainerHigh = Color(0xFF2E2E2E)
val SurfaceContainerHighest = Color(0xFF383838)
val Outline = Color(0xFF909094)              // 5.47:1 on Surface
val OutlineVariant = Color(0xFF555555)
val InverseSurface = Color(0xFFF5F5F5)
val InverseOnSurface = Color(0xFF000000)
val InversePrimary = Color(0xFF1E8449)

// --- LIGHT ---
val LightPrimary = Color(0xFF005C32)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD4F5E0)
val LightOnPrimaryContainer = Color(0xFF0B6623)
val LightSecondary = Color(0xFFC9B037)
val LightOnSecondary = Color(0xFF2A1F00)
val LightSecondaryContainer = Color(0xFFFFF0B3)
val LightOnSecondaryContainer = Color(0xFF3D2E00)
val LightTertiary = Color(0xFF5C3A7A)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE8D5FF)
val LightOnTertiaryContainer = Color(0xFF2E1B5C)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightSurface = Color(0xFFF9F9FB)
val LightOnSurface = Color(0xFF1A1A1A)
// 0xFF757575 in the contribution, which is 3.95:1 on LightSurfaceContainer — under 4.5:1, and
// `onSurfaceVariant` is a body-text role (supporting text on every ListItem, for one).
val LightOnSurfaceVariant = Color(0xFF46464A) // 8.05:1 on LightSurfaceContainer, 8.93:1 on LightSurface
val LightOutline = Color(0xFF757575)          // 4.38:1 on LightSurface; non-text role, needs 3:1
val LightOutlineVariant = Color(0xFFC5C5C9)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF3F3F7)
val LightSurfaceContainer = Color(0xFFEDEDF1)
val LightSurfaceContainerHigh = Color(0xFFE7E7EB)
val LightSurfaceContainerHighest = Color(0xFFE1E1E5)

// --- SEMANTIC ---
// "Granted / healthy". Not a Material role: ColorScheme has no success slot, and the Settings
// permission card needs a colour that reads in light, dark AND AMOLED rather than a hardcoded
// Color(0xFF4CAF50). Read it through ColorScheme.success in Theme.kt, not directly.
//
// Failure has no counterpart here on purpose — `colorScheme.error` is legible in dark mode now,
// so it covers the other half.
val SuccessLight = Color(0xFF005C32)         // 7.75:1 on LightSurface
val SuccessDark = Color(0xFF7BDBA6)          // 10.38:1 on Surface, 12.53:1 on AMOLED black

// --- LOG LEVELS ---
// One pair per logcat priority, for the log viewer. Read them through ColorScheme.logLevelColor()
// in Theme.kt, never directly, so AMOLED and light both get the right half.
//
// The contribution hardcoded a single set — Color(0xFFD32F2F) for error, Color(0xFF1976D2) for
// debug and so on — which are Material *500* swatches picked for a light background. On Loki's dark
// surface D32F2F is 3.62:1 and 1976D2 is 3.08:1: both fail AA for body text, on the one screen in
// the app that is nothing but body text.
//
// Every value below clears 4.5:1 on every surface it can land on, and the band is kept deliberately
// narrow (roughly 6:1 to 12:1) so that no priority reads as louder than another purely because it
// happens to be brighter. Verbose is the dimmest of the set on purpose — it is the noise level.
// Info, Error and Fatal reuse the scheme's own primary/error/tertiary values rather than
// introducing near-duplicates of them.
val LogVerboseDark = Color(0xFF9E9E9E)       //  6.50:1 on Surface,  7.84:1 on AMOLED black
val LogDebugDark = Color(0xFF82B1FF)         //  8.02:1 on Surface,  9.68:1 on AMOLED black
val LogInfoDark = Color(0xFF7BDBA6)          // 10.38:1 on Surface, 12.53:1 on AMOLED black
val LogWarnDark = Color(0xFFFFC947)          // 11.35:1 on Surface, 13.70:1 on AMOLED black
val LogErrorDark = Color(0xFFFFB4AB)         // 10.25:1 on Surface, 12.37:1 on AMOLED black
val LogFatalDark = Color(0xFFD3BBF0)         // 10.06:1 on Surface, 12.14:1 on AMOLED black

val LogVerboseLight = Color(0xFF5F5F63)      //  6.05:1 on LightSurface
val LogDebugLight = Color(0xFF0B4F91)        //  7.86:1 on LightSurface
val LogInfoLight = Color(0xFF005C32)         //  7.75:1 on LightSurface
val LogWarnLight = Color(0xFF6E4600)         //  7.87:1 on LightSurface
val LogErrorLight = Color(0xFFBA1A1A)        //  6.14:1 on LightSurface
val LogFatalLight = Color(0xFF5C3A7A)        //  8.53:1 on LightSurface
