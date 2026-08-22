package com.valhalla.loki.ui.theme

import androidx.compose.ui.graphics.Color

// The Asgardian palette, taken from Thor so the two apps read as siblings: pale-green primary,
// lavender secondary, violet tertiary, neutral greys. Used whenever Material You is off, which is
// the default — see ThemeSettings.dynamicColor.
//
// Everything down to LightOnErrorContainer is a verbatim copy of Thor's Color.kt and is kept that
// way on purpose, so the next palette change can be pasted across instead of merged. That is why
// twenty-two of these names are defined here and assigned to nothing — every *Fixed and *Dim role,
// greenLight/greenDark, and both OnErrorContainer values. Thor does not wire them either. So do not
// read an unassigned name here as evidence that the role is in use, and do not tidy them away.
//
// The two sections at the bottom are Loki's own, and there the opposite rule applies: they exist
// because a Loki screen needs them, so wire a new one up in the same commit that adds it.
//
// Contrast ratios in the comments are WCAG relative luminance against the surface named. Text roles
// need 4.5:1; `outline` and other non-text roles need 3:1.

val greenLight = Color(0xff4c662b)
val greenDark = Color(0xffb1d18a)

val OnTertiary = Color(0xff3f3386)
val OnPrimaryFixed = Color(0xff2d460f)
val TertiaryFixedDim = Color(0xffaca0fb)
val SurfaceContainerLow = Color(0xff131313)
val Secondary = Color(0xffc7c4dd)
val Background = Color(0xff0e0e0e)
val OnSecondaryFixedVariant = Color(0xff5e5d72)
val ErrorDim = Color(0xffc74c2f)
val OutlineVariant = Color(0xff484848)
val TertiaryFixed = Color(0xffbaafff)
val OnPrimaryFixedVariant = Color(0xff486329)
val Tertiary = Color(0xffc8bfff)
val OnBackground = Color(0xffe5e5e5)
val PrimaryFixed = Color(0xffcceda4)
val OnSecondary = Color(0xff3f3e52)
val InverseSurface = Color(0xfff9f9f9)
val OnPrimary = Color(0xff4c672c)
val PrimaryDim = Color(0xffcff0a6)
val OnTertiaryFixedVariant = Color(0xff3e3285)
val OnTertiaryFixed = Color(0xff1e0b66)
val OnTertiaryContainer = Color(0xff35287c)
val OnPrimaryContainer = Color(0xff445e25)
val TertiaryContainer = Color(0xffbaafff)
val SurfaceBright = Color(0xff2c2c2c)
val SurfaceTint = Color(0xfff0ffd7)
val SurfaceDim = Color(0xff0e0e0e)
val SurfaceContainerHigh = Color(0xff1f1f1f)
val SurfaceContainer = Color(0xff191919)
val Error = Color(0xfffe7453)
val SurfaceContainerLowest = Color(0xff000000)
val InverseOnSurface = Color(0xff555555)
val Outline = Color(0xff757575)
val SecondaryFixedDim = Color(0xffdad7f1)
val Primary = Color(0xfff0ffd7)
val SecondaryDim = Color(0xffb9b6ce)
val SecondaryFixed = Color(0xffe9e5ff)
val InversePrimary = Color(0xff4c672c)
val OnError = Color(0xff450900)
val Surface = Color(0xff0e0e0e)
val SecondaryContainer = Color(0xff242436)
val OnSecondaryContainer = Color(0xffa4a1b9)
val TertiaryDim = Color(0xffa195ef)
val OnSurfaceVariant = Color(0xffababab)
val OnSurface = Color(0xffe5e5e5)
val ErrorContainer = Color(0xff881f05)
val PrimaryFixedDim = Color(0xffbfdf97)
val SurfaceContainerHighest = Color(0xff262626)
val PrimaryContainer = Color(0xffd5f6ab)
val SurfaceVariant = Color(0xff262626)
val OnSecondaryFixed = Color(0xff424155)
val OnErrorContainer = Color(0xffff9b82)

// --- LIGHT THEME (Asgardian Technical Alchemist) ---
val LightSurface = Color(0xfff8faf3)
val LightSurfaceContainer = Color(0xffedefe8)
val LightSurfaceContainerHighest = Color(0xffe1e3dd)
val LightSurfaceContainerHigh = Color(0xffe7e9e2)
val LightSurfaceContainerLow = Color(0xfff2f4ed)
val LightSurfaceContainerLowest = Color(0xffffffff)
val LightPrimary = Color(0xff354e15)
val LightOnPrimary = Color(0xffffffff)
val LightPrimaryContainer = Color(0xff4c662b)
val LightOnPrimaryContainer = Color(0xfff0ffd7)
val LightSecondary = Color(0xff55624c)
val LightOnSecondary = Color(0xffffffff)
val LightSecondaryContainer = Color(0xffd9e7cb)
val LightOnSecondaryContainer = Color(0xff131f0d)
val LightTertiary = Color(0xff66355d)
val LightOnTertiary = Color(0xffffffff)
val LightTertiaryContainer = Color(0xfff8d8ee)
val LightOnTertiaryContainer = Color(0xff2d112b)
val LightOnSurface = Color(0xff191c18)
val LightOnSurfaceVariant = Color(0xff43493e)
val LightOutline = Color(0xff74796d)
val LightOutlineVariant = Color(0xffc3c8bc)
val LightError = Color(0xffba1a1a)
val LightOnError = Color(0xffffffff)
val LightErrorContainer = Color(0xffffdad6)
val LightOnErrorContainer = Color(0xff410002)


// --- SEMANTIC ---
// "Granted / healthy". Not a Material role: ColorScheme has no success slot, and the Settings
// permission card needs a colour that reads in light, dark AND AMOLED rather than a hardcoded
// Color(0xFF4CAF50). Read it through ColorScheme.success in Theme.kt, not directly.
//
// Failure has no counterpart here on purpose — `colorScheme.error` is legible in dark mode now,
// so it covers the other half.
val SuccessLight = Color(0xFF005C32)         //  7.75:1 on LightSurface
val SuccessDark = Color(0xFF7BDBA6)          // 11.51:1 on Surface, 12.53:1 on AMOLED black

// --- LOG LEVELS ---
// One pair per logcat priority, for the log viewer. Read them through ColorScheme.logLevelColor()
// in Theme.kt, never directly, so AMOLED and light both get the right half.
//
// The contribution hardcoded a single set — Color(0xFFD32F2F) for error, Color(0xFF1976D2) for
// debug and so on — which are Material *500* swatches picked for a light background. On Loki's dark
// surface D32F2F is 3.88:1 and 1976D2 is 4.19:1: both fail AA for body text, on the one screen in
// the app that is nothing but body text.
//
// Every value below clears 4.5:1 on every surface it can land on. The tightest case is not
// `Surface` but `surfaceBright`/`surfaceContainerHighest`, where Verbose — the dimmest of the set,
// on purpose, because it is the noise level — still holds 5.21:1. The band is kept deliberately
// narrow (roughly 7:1 to 13:1 on Surface) so that no priority reads as louder than another purely
// because it happens to be brighter.
//
// These twelve were tuned against Loki's previous palette and deliberately left alone when the
// Asgardian palette landed, because they answer to the log viewer rather than to the scheme. So the
// old note that Info, Error and Fatal "reuse the scheme's own primary/error/tertiary" no longer
// holds — only LogErrorLight still coincides with LightError. They are independent values now, and
// the ratios below are what keeps them honest.
val LogVerboseDark = Color(0xFF9E9E9E)       //  7.21:1 on Surface,  7.84:1 on AMOLED black
val LogDebugDark = Color(0xFF82B1FF)         //  8.90:1 on Surface,  9.68:1 on AMOLED black
val LogInfoDark = Color(0xFF7BDBA6)          // 11.51:1 on Surface, 12.53:1 on AMOLED black
val LogWarnDark = Color(0xFFFFC947)          // 12.59:1 on Surface, 13.70:1 on AMOLED black
val LogErrorDark = Color(0xFFFFB4AB)         // 11.37:1 on Surface, 12.37:1 on AMOLED black
val LogFatalDark = Color(0xFFD3BBF0)         // 11.16:1 on Surface, 12.14:1 on AMOLED black

val LogVerboseLight = Color(0xFF5F5F63)      //  6.05:1 on LightSurface
val LogDebugLight = Color(0xFF0B4F91)        //  7.86:1 on LightSurface
val LogInfoLight = Color(0xFF005C32)         //  7.75:1 on LightSurface
val LogWarnLight = Color(0xFF6E4600)         //  7.87:1 on LightSurface
val LogErrorLight = Color(0xFFBA1A1A)        //  6.14:1 on LightSurface
val LogFatalLight = Color(0xFF5C3A7A)        //  8.53:1 on LightSurface
