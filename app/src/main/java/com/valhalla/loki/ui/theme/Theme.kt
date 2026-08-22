package com.valhalla.loki.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.valhalla.loki.model.LogLevel

/**
 * The resolved **in-app** dark flag.
 *
 * Not the same thing as the device night mode: a user can run Loki dark on a light system, or the
 * reverse. Read this — never `isSystemInDarkTheme()` — anywhere a widget has to follow the app's own
 * theme. Note that resources the platform resolves for us cannot follow it:
 * `res/drawable-night/loki_animated.xml` and the splash icon key off the device configuration, and
 * there is nothing Compose can do about that.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Semantic "granted / healthy", for the permission cards in Settings and onboarding.
 *
 * [ColorScheme] has no success role, so this is the one place that decides which of the two
 * [SuccessLight]/[SuccessDark] values applies. Failure is `colorScheme.error`.
 */
val ColorScheme.success: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) SuccessDark else SuccessLight

/**
 * The colour a logcat priority draws in.
 *
 * Same reasoning as [success]: [ColorScheme] has no slot for this, and the one decision — which half
 * of each light/dark pair applies — belongs here rather than at every call site.
 *
 * [LogLevel.UNKNOWN] returns `onSurface`, so separators and blank lines look like ordinary text
 * instead of being assigned a priority they do not have. Dynamic colour is deliberately *not*
 * honoured: a wallpaper-derived palette has no error/warning axis, and remapping priorities onto it
 * would mean a warning and an error could come out the same hue.
 */
@Composable
@ReadOnlyComposable
fun ColorScheme.logLevelColor(level: LogLevel): Color = if (LocalDarkTheme.current) {
    when (level) {
        LogLevel.VERBOSE -> LogVerboseDark
        LogLevel.DEBUG -> LogDebugDark
        LogLevel.INFO -> LogInfoDark
        LogLevel.WARN -> LogWarnDark
        LogLevel.ERROR -> LogErrorDark
        LogLevel.FATAL -> LogFatalDark
        LogLevel.UNKNOWN -> onSurface
    }
} else {
    when (level) {
        LogLevel.VERBOSE -> LogVerboseLight
        LogLevel.DEBUG -> LogDebugLight
        LogLevel.INFO -> LogInfoLight
        LogLevel.WARN -> LogWarnLight
        LogLevel.ERROR -> LogErrorLight
        LogLevel.FATAL -> LogFatalLight
        LogLevel.UNKNOWN -> onSurface
    }
}


private val AsgardianLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    background = LightSurface,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

// Token-for-token Thor's dark scheme. Three slots Loki used to assign are left unset here because
// Thor leaves them unset, so all three fall back to the Material 3 baseline: `onErrorContainer` (in
// the light scheme above too) plus `surfaceDim` and `surfaceBright`. Nothing in Loki reads any of
// them directly today. Note the asymmetry that leaves behind — amoled() below still overrides
// surfaceDim and surfaceBright, so those two are themed in AMOLED dark and baseline in ordinary
// dark. The values are still in Color.kt if anything ever needs them wired back up.
private val AsgardianDarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
    surfaceTint = SurfaceTint,
    background = Background,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
)

/**
 * Pure-black override, applied only in dark mode.
 *
 * The whole surface ladder has to move, not just `background`/`surface`/`surfaceVariant`: leave
 * `surfaceContainer*` at its greys and every card, sheet and bottom bar floats *lighter* than the
 * page behind it, which is backwards. Flattened toward black but not all the way to it — absolute
 * black everywhere erases every elevation cue and cards stop having edges.
 *
 * **`surfaceContainerLow` is the one that must not be black.** It is `AsgardSectionCard`'s default
 * container, and the card draws no border and no shadow, so setting it to `Color.Black` on a
 * `Color.Black` page made the Settings cards disappear outright — the four groups read as one
 * undivided list held together only by their titles. That contradicted the paragraph above, which is
 * the whole reason `surfaceContainer` was already held off black.
 *
 * The steps are ~8% apart rather than the 4% they used to be, because on an OLED panel `#0A0A0A`
 * against `#000000` is not reliably an edge — it is a guess at one. And the ladder is strictly
 * monotonic: lifting `surfaceContainerLow` alone would have put it *above* `surfaceContainer`,
 * making a bottom bar darker than a card floating over it, which is the same inversion in a new
 * place. `surfaceContainerLowest` stays absolute black; it sits conceptually beneath the page, and
 * it is the one slot where that is the right answer.
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF171717),
    surfaceVariant = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF292929),
    surfaceBright = Color(0xFF292929),
)

@Composable
fun LokiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamic && darkTheme ->
            dynamicDarkColorScheme(context).let { if (amoledMode) it.amoled() else it }

        useDynamic -> dynamicLightColorScheme(context)
        darkTheme -> if (amoledMode) AsgardianDarkColorScheme.amoled() else AsgardianDarkColorScheme
        else -> AsgardianLightColorScheme
    }

    // Edge-to-edge is on (MainActivity), so the app draws behind the system bars and they are
    // transparent. `android:statusBarColor` is a no-op at targetSdk 36, which is why there is no
    // themes.xml here — the only thing left to control is the icon tint, and that has to follow the
    // IN-APP theme, because it can disagree with the device night mode. enableEdgeToEdge() sets the
    // tint from the system mode during onCreate; this runs afterwards and wins.
    val view = LocalView.current
    val activity = LocalActivity.current
    if (!view.isInEditMode) {
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    // The contribution set only the status bar, so on a three-button device the
                    // navigation icons stayed dark on a dark background.
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
    ) {
        CompositionLocalProvider(LocalDarkTheme provides darkTheme, content = content)
    }
}
