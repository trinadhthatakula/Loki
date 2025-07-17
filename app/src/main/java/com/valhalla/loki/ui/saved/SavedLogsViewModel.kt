package com.valhalla.loki.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.LoggedApp
import com.valhalla.loki.model.SavedLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SavedLogsUiState(
    val isLoading: Boolean = true,
    val loggedApps: List<LoggedApp> = emptyList()
)

class SavedLogsViewModel(
    private val filesDir: File,
    private val appInfoGrabber: AppInfoGrabber
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedLogsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSavedLogs()
    }

    fun loadSavedLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val logs = findAndOrganizeLogs()
            _uiState.value = _uiState.value.copy(isLoading = false, loggedApps = logs)
        }
    }

    private suspend fun findAndOrganizeLogs(): List<LoggedApp> = withContext(Dispatchers.IO) {
        val logsDir = File(filesDir, "logs")
        if (!logsDir.exists() || !logsDir.isDirectory) {
            return@withContext emptyList()
        }

        // Iterate through each subdirectory (which corresponds to a package name)
        logsDir.listFiles { file -> file.isDirectory }?.mapNotNull { appDir ->
            val packageName = appDir.name
            val appInfo = appInfoGrabber.getAppInfo(packageName) ?: return@mapNotNull null

            // Get all log files, sort them by timestamp (newest first)
            val savedLogs = appDir.listFiles { file -> file.isFile && file.extension == "log" }
                ?.mapNotNull { logFile ->
                    val timestamp = logFile.nameWithoutExtension.toLongOrNull()
                    if (timestamp != null) SavedLog(timestamp, logFile) else null
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()

            if (savedLogs.isNotEmpty()) {
                LoggedApp(appInfo, savedLogs)
            } else {
                null
            }
        }?.sortedBy { it.appInfo.appName } ?: emptyList()
    }
}