package com.valhalla.loki.model

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The two things a self-grant sweep has to remember across process death.
 *
 * A process-memory latch was the original design and the class KDoc on [SelfPermissionGrabber] used
 * to argue that it was enough, because the re-read that matters happens at the top of the next
 * launch. That argument holds for *success* and fails for the two cases where the right answer is
 * "do nothing again":
 *
 *  - **A grant the user later undid.** [grantedOnce] is what separates "never asked" from "denied on
 *    purpose", which [planSelfGrant] cannot tell from a `PackageManager` read alone. Without it,
 *    turning Loki's notifications off in Settings lasted exactly until the next cold start.
 *  - **A gateway that is not there.** [rootUnavailable] keeps the sweep from spending a `su` spawn —
 *    and, on a device whose root manager asks, a prompt — on every launch of an app that has no root
 *    and never will. A user who grants root later gets it back with one tap; see
 *    [SelfPermissionGrabber.recheck].
 *
 * Both are markers rather than a mirror of the permission state. The authority on "is it granted
 * now" is still [PermissionManager.isHeld] — nothing here is ever consulted for that.
 */
class SelfGrantStore(private val context: Context) {

    /**
     * The survivable permissions a sweep has taken at least once.
     *
     * Only ever grows. A name in here that Loki does *not* currently hold was revoked after we
     * granted it, and the only actor who can revoke a `dangerous` permission through the UI is the
     * user — so the sweep leaves it alone from then on.
     */
    suspend fun grantedOnce(): Set<String> = preferences()[KEY_GRANTED_ONCE].orEmpty()

    /** Adds [permissions] to [grantedOnce]. A no-op for an empty collection, including no write. */
    suspend fun recordGranted(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        edit { prefs ->
            prefs[KEY_GRANTED_ONCE] = prefs[KEY_GRANTED_ONCE].orEmpty() + permissions
        }
    }

    /** Whether the last root probe found no root. Absent — a fresh install — reads as `false`. */
    suspend fun rootUnavailable(): Boolean = preferences()[KEY_ROOT_UNAVAILABLE] == true

    /**
     * Records what a root probe found.
     *
     * Guarded by a read, so the overwhelmingly common call — "still no root, same as last time" —
     * costs no disk write at all.
     */
    suspend fun setRootUnavailable(unavailable: Boolean) {
        if (rootUnavailable() == unavailable) return
        edit { it[KEY_ROOT_UNAVAILABLE] = unavailable }
    }

    /**
     * One snapshot of the store, degrading to defaults on [IOException].
     *
     * The same treatment [ThemeManager] gives its read path, and for a sharper reason here: this is
     * consulted on the path that decides whether to issue a privileged command, so an unreadable
     * preferences file must fall back to "nothing remembered" rather than throw into the sweep.
     */
    private suspend fun preferences(): Preferences = context.settingsDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .first()

    /** Applies [block], treating a failed write as "the marker did not stick". */
    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        try {
            context.settingsDataStore.edit { block(it) }
        } catch (e: IOException) {
            Log.w(TAG, "could not persist a self-grant marker", e)
        }
    }

    private companion object {
        val KEY_GRANTED_ONCE = stringSetPreferencesKey("self_grant_granted_once")
        val KEY_ROOT_UNAVAILABLE = booleanPreferencesKey("self_grant_root_unavailable")

        const val TAG = "SelfGrantStore"
    }
}
