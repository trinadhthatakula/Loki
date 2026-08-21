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

private val lightScheme = lightColorScheme(
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
    onErrorContainer = LightOnErrorContainer,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

private val darkScheme = darkColorScheme(
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
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = SurfaceTint,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
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
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1F1F1F),
    surfaceBright = Color(0xFF1F1F1F),
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
        darkTheme -> if (amoledMode) darkScheme.amoled() else darkScheme
        else -> lightScheme
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
