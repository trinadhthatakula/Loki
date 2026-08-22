package com.valhalla.loki.model

import android.content.Context
import android.graphics.drawable.Drawable
import java.io.File

/** The single directory name saved captures live under, relative to `filesDir`. */
const val LOGS_DIR_NAME = "logs"

/**
 * Where saved captures live: `filesDir/logs/<package name>/<epoch millis>.log`.
 *
 * One definition, because four files used to spell this layout out themselves — the writer
 * ([com.valhalla.loki.services.LogcatService]), both readers and
 * [com.valhalla.loki.services.LokiDocumentsProvider] — and a disagreement between any two of them
 * is a capture that saves into a directory nothing lists.
 *
 * `filesDir` and not external storage: it is private on every API level, which is the resolution
 * `docs/review-anon-contribution.md` §1.1 reached. Reaching the logs from a file manager is the
 * DocumentsProvider's job, not a reason to move them somewhere world-readable.
 */
val Context.logsDir: File get() = File(filesDir, LOGS_DIR_NAME)

/**
 * The single directory name share bundles are staged under, relative to `cacheDir`.
 *
 * It is named here rather than at the one call site because `res/xml/provider_paths.xml` declares
 * exactly this path and nothing wider — a `<cache-path>` covering all of `cacheDir` would make every
 * scratch file Loki ever writes reachable through the FileProvider. The two spellings have to agree
 * or a share fails with `IllegalArgumentException` at the moment the user taps it.
 */
const val SHARE_CACHE_DIR_NAME = "log_shares"

/** Where a zip built for a share is staged: `cacheDir/log_shares/<epoch millis>/<name>.zip`. */
val Context.shareCacheDir: File get() = File(cacheDir, SHARE_CACHE_DIR_NAME)

/**
 * One saved log file.
 *
 * [sizeBytes] is measured when the listing is built rather than read from [file] where it is drawn:
 * `File.length()` inside a `LazyColumn` row is a `stat` on the main thread, once per row per
 * recomposition.
 */
data class SavedLog(
    val timestamp: Long,
    val file: File,
    val sizeBytes: Long,
)

/**
 * An app that has at least one saved log.
 *
 * [appInfo] is synthesised from the directory name when the app is no longer installed, so its logs
 * stay listed, readable and deletable. Dropping the directory instead — as the contribution did —
 * hides captures at exactly the moment they matter most, after the app they came from is gone.
 *
 * [icon] is loaded off the main thread and cached per package by the ViewModel, so the same
 * `Drawable` instance comes back on every reload. A fresh instance each time would make every
 * `LoggedApp` unequal to its predecessor and recompose the whole list on any change.
 */
data class LoggedApp(
    val appInfo: AppInfo,
    val logs: List<SavedLog>,
    val icon: Drawable? = null,
) {
    val totalBytes: Long get() = logs.sumOf { it.sizeBytes }
}
