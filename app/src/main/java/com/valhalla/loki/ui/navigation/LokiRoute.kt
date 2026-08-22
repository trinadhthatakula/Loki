package com.valhalla.loki.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every place Loki can navigate to.
 *
 * The three tabs are the roots of three independent back stacks — see `HomeScreen` — and the rest
 * are pushed on top of whichever tab opened them. That is why [LogViewer] is one route rather than
 * one per tab: the same viewer opens from the saved-logs list and from the explorer, and which tab
 * it came from is already recorded by *which stack it was pushed onto*.
 */
@Serializable
sealed interface LokiRoute : NavKey {

    @Serializable
    data object Apps : LokiRoute

    @Serializable
    data object Saved : LokiRoute

    @Serializable
    data object Settings : LokiRoute

    /**
     * A capture, open for reading.
     *
     * @param path the absolute path, as a `String` rather than a [java.io.File], because
     *   `rememberNavBackStack` persists the stack for task restoration and a `File` is not
     *   serialisable by kotlinx.serialization. It also means a restored stack can name a file that
     *   has since been deleted — from the explorer, or from Settings → "Clear all saved logs" —
     *   which is fine: `LogViewerViewModel` already reports a failed read as `uiState.error` and
     *   the viewer renders that, so the worst case is an explained empty screen rather than a
     *   crash on restore.
     */
    @Serializable
    data class LogViewer(val path: String) : LokiRoute

    @Serializable
    data object LogsExplorer : LokiRoute
}
