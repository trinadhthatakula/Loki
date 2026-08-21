package com.valhalla.loki.ui.widgets

import java.util.Locale

/**
 * A byte count as something a person can read: `512 B`, `1.4 KB`, `18.7 MB`.
 *
 * Binary units, because that is what a filesystem reports. Shared rather than private to Settings —
 * the saved-logs list shows the same numbers for the same files, and two copies of this drifting
 * apart would mean one screen calling a capture 1.4 KB and the other 1.5 KB.
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1_024.0 * 1_024.0))
}
