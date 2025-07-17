package com.valhalla.loki.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.PermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class OnboardingUiState(
    val isRootAvailable: Boolean = false,
    val isShizukuAvailable: Boolean = false,
    val grantViaShizukuInProgress: Boolean = false,
    val grantViaShizukuSuccess: Boolean? = null // null = idle, true = success, false = fail
)

class OnboardingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState = _uiState.asStateFlow()

    init {
        checkPermissions()
    }

    fun checkPermissions() {
        _uiState.value = _uiState.value.copy(
            isRootAvailable = PermissionManager.isRootAvailable(),
            isShizukuAvailable = PermissionManager.isShizukuAvailable()
        )
    }

    fun grantPermissionViaShizuku(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(grantViaShizukuInProgress = true)
            val success = PermissionManager.grantReadLogsViaShizuku(context)
            _uiState.value = _uiState.value.copy(
                grantViaShizukuInProgress = false,
                grantViaShizukuSuccess = success
            )
        }
    }
}
