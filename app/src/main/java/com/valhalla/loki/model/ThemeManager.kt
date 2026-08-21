package com.valhalla.loki.model

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

// Top level, so there is exactly one DataStore instance per process. Anything else that wants an
// app preference must add a key here or go through ThemeManager — constructing a second DataStore
// over the same file throws at runtime.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Which theme the user picked.
 *
 * [token] is what gets PERSISTED and must never change; the label shown in Settings is the UI's
 * business. Storing the display string instead — as the contribution did — means renaming a menu
 * entry silently resets everybody's preference, and it cannot be localised.
 */
enum class ThemeMode(val token: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Unknown or absent tokens fall back to [SYSTEM] rather than throwing. */
        fun fromToken(token: String?): ThemeMode = entries.firstOrNull { it.token == token } ?: SYSTEM
    }
}

/**
 * The three theme settings as one value, so a reader cannot act on a torn combination of them —
 * [MainActivity][com.valhalla.loki.MainActivity] needs all three to build a single [ColorScheme]
 * [androidx.compose.material3.ColorScheme].
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Pure black in dark mode. */
    val amoled: Boolean = false,
    /** Material You, i.e. colours taken from the wallpaper. Android 12+ only. */
    val dynamicColor: Boolean = false,
)

/**
 * The persisted theme settings, backed by DataStore.
 *
 * Registered as a Koin singleton, so the flows below are all views onto one DataStore instance.
 */
class ThemeManager(private val context: Context) {

    val settings: Flow<ThemeSettings> = context.settingsDataStore.data
        .catch { cause ->
            // A corrupt or unreadable preferences file has to degrade to defaults, not throw.
            // MainActivity holds the splash screen up until this flow emits, so an exception
            // escaping here is an app that never draws.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            ThemeSettings(
                mode = ThemeMode.fromToken(prefs[KEY_THEME_MODE]),
                amoled = prefs[KEY_AMOLED] ?: false,
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            )
        }
        .distinctUntilChanged()

    /** Single-setting views, so Settings can bind one control at a time. */
    val themeMode: Flow<ThemeMode> = settings.map { it.mode }.distinctUntilChanged()
    val amoled: Flow<Boolean> = settings.map { it.amoled }.distinctUntilChanged()
    val dynamicColor: Flow<Boolean> = settings.map { it.dynamicColor }.distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.token }

    suspend fun setAmoled(enabled: Boolean) = edit { it[KEY_AMOLED] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }

    private companion object {
        // Names kept identical to the contribution's, so anyone who already ran that build keeps
        // their booleans. Only theme_mode's value SHAPE changed, and an unrecognised token falls
        // back to SYSTEM.
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_AMOLED = booleanPreferencesKey("amoled_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
