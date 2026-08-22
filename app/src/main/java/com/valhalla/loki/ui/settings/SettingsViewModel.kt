package com.valhalla.loki.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.LogcatCapture
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.model.SelfPermissionGrabber
import com.valhalla.loki.model.ThemeManager
import com.valhalla.loki.model.ThemeMode
import com.valhalla.loki.model.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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

/**
 * What privilege Loki actually holds, as three independent facts rather than one enum.
 *
 * They are genuinely independent, and the contribution's single "granted via X" string collapsed
 * them wrongly: Shizuku being connected does **not** mean `READ_LOGS` is granted — Shizuku is the
 * mechanism that *can* grant it. So [shizukuReady] drives whether offering the grant is worth the
 * user's time, while [canCapture] answers the only question the banner cares about.
 */
data class PrivilegeStatus(
    val readLogsGranted: Boolean = false,
    val rootAvailable: Boolean = false,
    val shizukuReady: Boolean = false,
) {
    /** True when a capture would actually produce another app's logs. */
    val canCapture: Boolean get() = readLogsGranted || rootAvailable
}

/** How much is on disk, so "Clear all saved logs" can say what it is about to destroy. */
data class SavedLogsStats(
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
)

/**
 * `null` for [privilege] and [stats] means "not measured yet", which the UI shows as a placeholder.
 * Neither can be computed synchronously: the root probe suspends and the directory walk is I/O.
 */
data class SettingsUiState(
    val privilege: PrivilegeStatus? = null,
    val stats: SavedLogsStats? = null,
    val theme: ThemeSettings = ThemeSettings(),
    val isClearing: Boolean = false,
)

class SettingsViewModel(
    private val permissionManager: PermissionManager,
    private val logcatCapture: LogcatCapture,
    private val themeManager: ThemeManager,
    private val selfPermissionGrabber: SelfPermissionGrabber,
    private val logsDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * One-shot user-facing messages.
     *
     * `DROP_OLDEST` with no subscribers is deliberate: a message raised while the Settings page is
     * off-screen is stale by the time anyone could read it, and replaying it later would toast out
     * of nowhere.
     */
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            themeManager.settings.collect { settings ->
                _uiState.update { it.copy(theme = settings) }
            }
        }
        refresh()
    }

    /**
     * Re-measures privilege and disk usage.
     *
     * Both live here rather than in the composable because both are blocking work:
     * `isRootAvailable()` spawns or reuses a `su` process and `logsDir` is a recursive walk.
     * Reading either during composition — as the contribution did — runs them on the main thread
     * on every recomposition.
     */
    fun refresh() {
        viewModelScope.launch {
            val status = PrivilegeStatus(
                readLogsGranted = permissionManager.hasReadLogsPermission(),
                rootAvailable = permissionManager.isRootAvailable(),
                shizukuReady = permissionManager.isShizukuAvailable(),
            )
            _uiState.update { it.copy(privilege = status) }
        }
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { measureLogs() }
            _uiState.update { it.copy(stats = stats) }
        }
    }

    /**
     * What the "Tap to re-check" row does: re-measures, and re-runs the self-grant sweep.
     *
     * Separate from [refresh] because the two callers want different things. `init` and
     * `clearAllLogs` want the numbers on screen brought up to date; a tap on that row is a user
     * saying "look again", which is the one signal that justifies re-probing root after a previous
     * launch found none — see [SelfPermissionGrabber.recheck]. It is also what makes the sweep's own
     * "the user may grant root in a minute and something brings us back" comment true; before this
     * existed, nothing did except a Shizuku binder delivery.
     */
    fun recheckPrivilege() {
        refresh()
        selfPermissionGrabber.recheck()
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { themeManager.setThemeMode(mode) }

    fun setAmoled(enabled: Boolean) = viewModelScope.launch { themeManager.setAmoled(enabled) }

    fun setDynamicColor(enabled: Boolean) =
        viewModelScope.launch { themeManager.setDynamicColor(enabled) }

    /**
     * Deletes every saved log.
     *
     * Three things the contribution got wrong, all fixed here: it ran the delete on the main
     * thread, it ignored the return value, and it toasted "All logs deleted!" unconditionally —
     * so a partial failure looked like a success. It also raced an in-flight capture, which holds
     * an open writer into this very directory; that is now refused up front rather than left to
     * produce a half-deleted tree and a capture writing into a directory that no longer exists.
     *
     * It empties the directory rather than replacing it. Deleting the root and calling `mkdirs()`
     * leaves the same *path* holding a different inode, and every `FileObserver` in
     * [directoryChanges][com.valhalla.loki.model.directoryChanges] is bound to the inode — so the
     * live watch behind Saved logs and the explorer died here and those screens stopped updating
     * until they were reopened. `ShallowTreeObserver` now also repairs a watch it is told has gone,
     * but not creating the problem is the cheaper half of the fix.
     */
    fun clearAllLogs() {
        if (logcatCapture.isCapturing) {
            _messages.tryEmit("A capture is still running. Stop it first.")
            return
        }
        if (_uiState.value.isClearing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true) }
            val result = withContext(Dispatchers.IO) {
                if (!logsDir.exists()) return@withContext ClearResult.NOTHING_TO_DO
                val children = logsDir.listFiles() ?: return@withContext ClearResult.FAILED
                if (children.isEmpty()) return@withContext ClearResult.NOTHING_TO_DO
                // deleteRecursively() reports false on partial failure, which is exactly the case
                // the unconditional toast used to hide. Per child, and `fold` rather than `all`, so
                // one stubborn file does not short-circuit the delete of everything after it.
                val deleted = children.fold(true) { ok, child -> child.deleteRecursively() && ok }
                if (!deleted) ClearResult.FAILED else ClearResult.CLEARED
            }
            _uiState.update { it.copy(isClearing = false) }
            _messages.tryEmit(
                when (result) {
                    ClearResult.CLEARED -> "Saved logs deleted."
                    ClearResult.NOTHING_TO_DO -> "There are no saved logs."
                    ClearResult.FAILED -> "Could not delete every log. Some files remain."
                }
            )
            refresh()
        }
    }

    private enum class ClearResult { CLEARED, NOTHING_TO_DO, FAILED }

    private fun measureLogs(): SavedLogsStats {
        if (!logsDir.isDirectory) return SavedLogsStats()
        var count = 0
        var bytes = 0L
        logsDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                count++
                bytes += file.length()
            }
        }
        return SavedLogsStats(fileCount = count, totalBytes = bytes)
    }
}
