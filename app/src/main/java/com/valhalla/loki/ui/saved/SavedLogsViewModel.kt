package com.valhalla.loki.ui.saved

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.AppInfo
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.LoggedApp
import com.valhalla.loki.model.SavedLog
import com.valhalla.loki.model.directoryChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Extensions a file in the logs tree is listed under. A `Set`, so the check does not allocate per file. */
private val LOG_EXTENSIONS = setOf("log", "txt")

/** How long the filesystem has to stay quiet before a change is turned into a reload. */
private const val DEBOUNCE_MS = 500L

/**
 * [isLoading] is the first load only — the one with nothing to show yet. [isRefreshing] drives the
 * pull-to-refresh indicator. A reload triggered by the filesystem sets neither: the list simply
 * changes, because a spinner appearing on its own reads as a fault.
 */
data class SavedLogsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val loggedApps: List<LoggedApp> = emptyList(),
)

class SavedLogsViewModel(
    private val logsDir: File,
    private val appInfoGrabber: AppInfoGrabber,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedLogsUiState())
    val uiState: StateFlow<SavedLogsUiState> = _uiState.asStateFlow()

    /** One-shot user-facing messages, same shape and reasoning as `SettingsViewModel.messages`. */
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Icons by package name, `null` included so a package without one is not asked about again.
     *
     * A plain map with no synchronisation: it is only ever touched from the single reload coroutine
     * below, and each reload is sequenced after the last.
     */
    private val iconCache = mutableMapOf<String, Drawable?>()

    private enum class Reload { NOW, DEBOUNCED }

    /**
     * Reload requests, conflated to one slot.
     *
     * A `Channel` rather than a `SharedFlow` so the request queued below survives until the loop
     * starts collecting, and conflated so a burst of filesystem events cannot queue up a burst of
     * reloads. The loop is sequential, which is the whole point: the contribution launched a fresh
     * coroutine per `FileObserver` event, each with its own `delay(500)`, so fifty new files meant
     * fifty concurrent directory walks all racing to write the same state (§1.4).
     */
    private val reloads = Channel<Reload>(capacity = Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (request in reloads) {
                if (request == Reload.DEBOUNCED) delay(DEBOUNCE_MS)
                load()
            }
        }
        reloads.trySend(Reload.NOW)
        viewModelScope.launch {
            // Stops watching on its own when the scope dies: the flow's awaitClose unregisters
            // every observer, so there is nothing for onCleared() to do.
            logsDir.directoryChanges().collect { reloads.trySend(Reload.DEBOUNCED) }
        }
    }

    /** Pull-to-refresh. The indicator is raised here so it appears on the gesture, not after the walk. */
    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        reloads.trySend(Reload.NOW)
    }

    /**
     * Deletes one saved log.
     *
     * Says what happened either way. `File.delete()` returns false rather than throwing — the
     * contribution ignored that and simply did nothing visible when a delete failed, which is
     * indistinguishable from the tap not registering.
     */
    fun deleteLog(savedLog: SavedLog) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val file = savedLog.file
                // Already gone counts as deleted: the listing was stale, and the user's intent is
                // satisfied either way.
                val gone = !file.exists() || file.delete()
                if (gone) pruneEmptyPackageDir(file.parentFile)
                gone
            }
            _messages.tryEmit(if (deleted) "Log deleted." else "Could not delete that log.")
            reloads.trySend(Reload.NOW)
        }
    }

    /**
     * Removes a package directory that has nothing left in it.
     *
     * Only reachable for a directory whose own parent is [logsDir], and `delete()` refuses a
     * non-empty directory, so the worst case is that nothing happens. Without this, deleting an
     * app's last log leaves an empty folder that the list hides but a file manager still shows
     * through the DocumentsProvider.
     */
    private fun pruneEmptyPackageDir(dir: File?) {
        if (dir == null) return
        if (dir.parentFile?.canonicalPath != logsDir.canonicalPath) return
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    private suspend fun load() {
        val apps = withContext(Dispatchers.IO) { readSavedLogs() }
        _uiState.update {
            it.copy(isLoading = false, isRefreshing = false, loggedApps = apps)
        }
    }

    /**
     * Walks `logs/<package>/` and builds the listing, icons and file sizes included.
     *
     * All of it is I/O — the directory listings, `File.length()` per log and the icon load — and all
     * of it used to be spread between here, a `remember` in each row and the row's own draw pass.
     */
    private fun readSavedLogs(): List<LoggedApp> {
        val packageDirs = logsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return packageDirs.mapNotNull { dir ->
            val logs = dir.listFiles { file -> file.isFile && file.extension in LOG_EXTENSIONS }
                ?.map { file ->
                    SavedLog(
                        // The name is the capture's epoch millis. Anything else in here did not
                        // come from Loki, so fall back to the mtime rather than hiding the file.
                        timestamp = file.nameWithoutExtension.toLongOrNull() ?: file.lastModified(),
                        file = file,
                        sizeBytes = file.length(),
                    )
                }
                ?.sortedByDescending { it.timestamp }
                .orEmpty()
            if (logs.isEmpty()) return@mapNotNull null
            LoggedApp(appInfo = appInfoFor(dir.name), logs = logs, icon = iconFor(dir.name))
        }.sortedBy { it.appInfo.appName?.lowercase() ?: it.appInfo.packageName }
    }

    /** The installed app, or a stand-in built from the directory name once it has been uninstalled. */
    private fun appInfoFor(packageName: String): AppInfo =
        appInfoGrabber.getAppInfo(packageName)
            ?: AppInfo(appName = packageName, packageName = packageName)

    private fun iconFor(packageName: String): Drawable? =
        if (packageName in iconCache) {
            iconCache[packageName]
        } else {
            appInfoGrabber.getAppIcon(packageName).also { iconCache[packageName] = it }
        }
}
