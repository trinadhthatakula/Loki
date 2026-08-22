package com.valhalla.loki.model

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * The one preferences DataStore in the process.
 *
 * `preferencesDataStore` hands out a single instance per delegate, and building a *second* one over
 * the same file throws at runtime — so this is shared rather than duplicated. It used to be
 * `private` inside `ThemeManager.kt`, whose comment already said that anything else wanting a
 * preference had to add a key there or go through `ThemeManager`. [SelfGrantStore] is the second
 * thing that wanted one, and it is not a theme setting, so the delegate moved out here instead of
 * its keys moving in.
 *
 * `internal`, so the file cannot be opened from outside `:app`, and each owner keeps its own keys
 * private to itself.
 */
internal val Context.settingsDataStore by preferencesDataStore(name = "settings")
