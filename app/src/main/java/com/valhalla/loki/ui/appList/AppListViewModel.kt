package com.valhalla.loki.ui.appList

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.AppInfo
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.services.LogcatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * How many lines of a live capture the sheet keeps.
 *
 * The live view is a tail, not an archive — the whole capture is on disk and the viewer opens it
 * properly. Keeping every line meant an hour-long capture held a few hundred thousand strings in a
 * `StateFlow` that Compose re-reads on every emission.
 */
private const val MAX_LIVE_LINES = 2_000

/** How long to idle before re-checking a file that has stopped growing. */
private const val TAIL_POLL_MS = 300L

/** How long to wait for the capture to create its file before giving up on tailing it. */
private const val TAIL_WAIT_MS = 10_000L

data class AppListUiState(
    val isLoading: Boolean = true,
    val hasRootAccess: Boolean = false,
    val showRootRestricted: Boolean = false,
    val userApps: List<AppInfo> = emptyList(),
    val systemApps: List<AppInfo> = emptyList(),
    val showBottomSheet: Boolean = false,
    val isLoggerRunning: Boolean = false,
    val selectedAppForPermission: AppInfo? = null,
    val logLines: List<String> = emptyList(),
    val currentLogFile: File? = null,
    val isRefreshing: Boolean = false,
    val searchQuery: String = "", // Added for search functionality
    val filteredApps: List<AppInfo> = emptyList() // Added for search functionality
)

class AppListViewModel(
    private val grabber: AppInfoGrabber,
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    init {
        loadApps()
        observeLogcatServiceState()
        observeLogFile()
        checkRootAccess()
        observeSearchQuery() // Observe search query changes
    }

    /** Just the three fields the filtered list is a function of. */
    private data class FilterInputs(
        val userApps: List<AppInfo>,
        val systemApps: List<AppInfo>,
        val searchQuery: String
    )

    /**
     * Keeps `filteredApps` in step with the app list and the query.
     *
     * The `map`/`distinctUntilChanged` pair is load-bearing rather than tidying. This collects
     * `_uiState` and writes back into it, so without narrowing to the inputs first, *every* state
     * change re-ran the sort — including the one this block had just made. It did not spin forever,
     * because `StateFlow` conflates a structurally-equal value and the filtered list usually came
     * out identical, but it did mean a full sort of every installed app on each emission, and a live
     * capture emits once per batch of log lines. Narrowing first means the work happens when the
     * apps or the query actually change, and the write-back cannot retrigger it at all.
     *
     * `withContext` also gets the sort off the main thread, which matters because `collectLatest`
     * can only abandon superseded work at a suspension point — with the old body there was none, so
     * every keystroke's sort ran to completion before the next one started.
     */
    private fun observeSearchQuery() {
        viewModelScope.launch {
            _uiState
                .map { FilterInputs(it.userApps, it.systemApps, it.searchQuery) }
                .distinctUntilChanged()
                .collectLatest { inputs ->
                    val filtered = withContext(Dispatchers.Default) {
                        val allApps =
                            (inputs.userApps + inputs.systemApps).sortedBy { it.appName }
                        val query = inputs.searchQuery
                        if (query.isBlank()) allApps
                        else allApps.filter { app ->
                            app.appName?.contains(query, ignoreCase = true) == true ||
                                    app.packageName.contains(query, ignoreCase = true)
                        }
                    }
                    _uiState.update { it.copy(filteredApps = filtered) }
                }
        }
    }

    fun checkRootAccess() {
        viewModelScope.launch {
            // Resolve before update {}: isRootAvailable() suspends, and the update block must
            // stay a pure, non-suspending transform.
            val hasRoot = permissionManager.isRootAvailable()
            _uiState.update { it.copy(hasRootAccess = hasRoot) }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            // A pull-to-refresh keeps the list on screen and spins its own indicator; only the first
            // load replaces the list with a spinner. Raising isLoading either way meant the pull
            // gesture threw the list away and PullToRefreshBox's indicator was never shown at all,
            // because nothing set isRefreshing.
            _uiState.update { it.copy(isLoading = !it.isRefreshing) }
            // One package sweep on Dispatchers.IO. This used to be two sweeps — getUserApps() then
            // getSystemApps() — run straight on the main thread, and each of them loaded a label per
            // installed app, which is a resource-APK open apiece.
            val (systemApps, userApps) = withContext(Dispatchers.IO) {
                grabber.allApps.partition { it.isSystem }
            }
            _uiState.update {
                it.copy(
                    userApps = userApps,
                    systemApps = systemApps,
                    isLoading = false,
                    isRefreshing = false
                )
            }
        }
    }

    fun refreshApps() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadApps()
    }

    private fun observeLogcatServiceState() {
        viewModelScope.launch {
            LogcatService.isRunning.collectLatest { isRunning ->
                _uiState.update { it.copy(isLoggerRunning = isRunning) }
            }
        }
    }

    private fun observeLogFile() {
        viewModelScope.launch {
            LogcatService.currentLogFile.collectLatest { file ->
                _uiState.update { it.copy(currentLogFile = file) }
                if (file != null) {
                    // Called directly, *not* launched into viewModelScope. As its own coroutine it
                    // outlived the emission that started it: collectLatest cancels the block it is
                    // running, and the tail was not in that block, so every capture left another
                    // reader behind polling a file forever — each one holding an open fd and
                    // pushing lines from an old capture into the current view.
                    tailLogFile(file)
                } else {
                    _uiState.update { it.copy(logLines = listOf("Waiting for log file...")) }
                }
            }
        }
    }

    /**
     * Follows a capture as it is written, until the collector that called it is cancelled.
     *
     * Suspends for as long as the tail runs, which is the point: cancellation now comes from the
     * structure rather than from a flag. The old loop tested `viewModelScope.isActive`, which is
     * false only once the whole ViewModel is gone — so it could not stop for a *new* capture, and
     * `catch (e: Exception)` swallowed the `CancellationException` that would otherwise have ended
     * it anyway.
     */
    private suspend fun tailLogFile(logFile: File) {
        _uiState.update { it.copy(logLines = emptyList()) }
        withContext(Dispatchers.IO) {
            // The service publishes the path when it starts the capture; the file itself appears
            // when the capture opens its writer, a moment later. Waiting for it beats the fixed
            // 250 ms sleep this replaces, which was either too long or — under root, where a `su`
            // prompt can sit in front of the first write — far too short, and then the open threw
            // FileNotFoundException and the view showed an error for a capture that was fine.
            var waited = 0L
            while (!logFile.exists() && waited < TAIL_WAIT_MS) {
                delay(TAIL_POLL_MS)
                waited += TAIL_POLL_MS
            }
            if (!logFile.exists()) {
                _uiState.update { it.copy(logLines = listOf("Waiting for log file...")) }
                return@withContext
            }

            try {
                logFile.bufferedReader().use { reader ->
                    while (true) {
                        // Drain what is there, then publish once. Appending per line meant a
                        // main-thread hop and a full copy of the list for every line — quadratic in
                        // the length of the capture, and logcat can deliver thousands of lines a
                        // second. `update` is atomic, so there is no need to be on the main thread
                        // at all; Compose collects on it regardless.
                        val batch = ArrayList<String>()
                        while (batch.size < MAX_LIVE_LINES) {
                            val line = reader.readLine() ?: break
                            batch += line
                        }
                        if (batch.isEmpty()) {
                            delay(TAIL_POLL_MS)
                            continue
                        }
                        _uiState.update { state ->
                            state.copy(
                                logLines = (state.logLines + batch).takeLast(MAX_LIVE_LINES)
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // A new capture started, or the sheet went away. Not a failure — and it must be
                // rethrown, or the coroutine machinery is left believing this scope is still live.
                throw e
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(logLines = it.logLines + "Error reading log file: ${e.message}")
                }
            }
        }
    }

    fun handleAppClick(context: Context, appInfo: AppInfo, requestPermission: (String) -> Unit) {
        if (_uiState.value.hasRootAccess || permissionManager.hasReadLogsPermission()) {
            if (_uiState.value.isLoggerRunning) {
                // Already logging, show toast (handled in UI)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                when {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED -> {
                        startLogService(appInfo, context)
                    }

                    else -> {
                        _uiState.update { it.copy(selectedAppForPermission = appInfo) }
                        requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } else {
                // No runtime permission needed for pre-Tiramisu
                startLogService(appInfo, context)
            }
        } else {
            _uiState.update {
                it.copy(showRootRestricted = true)
            }
        }
    }

    fun onPermissionResult(isGranted: Boolean, context: Context) {
        if (isGranted) {
            _uiState.value.selectedAppForPermission?.let { startLogService(it, context) }
        } else {
            // Permission denied, show toast (handled in UI)
        }
        _uiState.update { it.copy(selectedAppForPermission = null) }
    }

    private fun startLogService(appInfo: AppInfo, context: Context) {
        val intent = Intent(context, LogcatService::class.java).apply {
            action = LogcatService.ACTION_START
            putExtra(LogcatService.EXTRA_APP_INFO, appInfo.asString())
        }
        context.startService(intent)
        // Toast message will be shown in UI
    }

    fun stopLogService(context: Context) {
        val intent = Intent(context, LogcatService::class.java).apply {
            action = LogcatService.ACTION_STOP
        }
        context.startService(intent)
        setBottomSheetVisibility(false) // Close bottom sheet on stop
    }

    fun setBottomSheetVisibility(isVisible: Boolean) {
        _uiState.update { it.copy(showBottomSheet = isVisible) }
    }

    fun hideRootRestrictedDialog() {
        _uiState.update { it.copy(showRootRestricted = false) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}