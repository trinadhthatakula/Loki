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
import com.valhalla.loki.model.rootAvailable
import com.valhalla.loki.services.LogcatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val isRefreshing: Boolean = false // Added for pull-to-refresh
)

class AppListViewModel(
    private val grabber: AppInfoGrabber
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    init {
        loadApps()
        observeLogcatServiceState()
        observeLogFile()
        checkRootAccess ()
    }

    fun checkRootAccess() {
        viewModelScope.launch { 
            _uiState.update { 
                it.copy(
                    hasRootAccess = rootAvailable()
                )
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val userApps = grabber.getUserApps()
            val systemApps = grabber.getSystemApps()
            _uiState.value = _uiState.value.copy(
                userApps = userApps,
                systemApps = systemApps,
                isLoading = false
            )
        }
    }

    fun refreshApps() {
        loadApps()
    }

    private fun observeLogcatServiceState() {
        viewModelScope.launch {
            LogcatService.isRunning.collectLatest { isRunning ->
                _uiState.value = _uiState.value.copy(isLoggerRunning = isRunning)
            }
        }
    }

    private fun observeLogFile() {
        viewModelScope.launch {
            LogcatService.currentLogFile.collectLatest { file ->
                _uiState.value = _uiState.value.copy(currentLogFile = file)
                if (file != null) {
                    tailLogFile(file)
                } else {
                    _uiState.value = _uiState.value.copy(logLines = listOf("Waiting for log file..."))
                }
            }
        }
    }

    private fun tailLogFile(logFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(logLines = emptyList())
            try {
                // Wait a moment for the file to be created and written to
                delay(250)
                val reader = logFile.bufferedReader()
                while (viewModelScope.isActive) {
                    val line = reader.readLine()
                    if (line != null) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(logLines = _uiState.value.logLines + line)
                        }
                    } else {
                        delay(300) // Wait for more content
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(logLines = _uiState.value.logLines + "Error reading log file: ${e.message}")
                }
            }
        }
    }

    fun handleAppClick(context: Context, appInfo: AppInfo, requestPermission: (String) -> Unit) {
        if(_uiState.value.hasRootAccess) {
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
                        _uiState.value = _uiState.value.copy(selectedAppForPermission = appInfo)
                        requestPermission(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            } else {
                // No runtime permission needed for pre-Tiramisu
                startLogService(appInfo, context)
            }
        }else {
            _uiState.update { 
                it.copy(showRootRestricted = true)
            }
        }
    }

    fun onPermissionResult(isGranted: Boolean, context: Context) {
        if (isGranted) {
            _uiState.value.selectedAppForPermission?.let { startLogService(it,context) }
        } else {
            // Permission denied, show toast (handled in UI)
        }
        _uiState.value = _uiState.value.copy(selectedAppForPermission = null)
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
        _uiState.value = _uiState.value.copy(showBottomSheet = isVisible)
    }

    fun hideRootRestrictedDialog() {
        _uiState.update { it.copy(showRootRestricted = false) }
    }
}
