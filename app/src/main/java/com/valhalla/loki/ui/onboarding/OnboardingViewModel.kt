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
    val grantViaShizukuSuccess: Boolean? = null, // null = idle, true = success, false = fail
    val checkRootInProgress: Boolean = false,
    val continueWithRootResult: Boolean? = null // null = idle, true = root confirmed, false = no root
)

class OnboardingViewModel(
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState = _uiState.asStateFlow()

    init {
        checkPermissions()
    }

    fun checkPermissions() {
        viewModelScope.launch {
            // isRootAvailable() suspends: probing root spawns a `su` process, which must not
            // happen on the main thread.
            val root = permissionManager.isRootAvailable()
            _uiState.value = _uiState.value.copy(
                isRootAvailable = root,
                isShizukuAvailable = permissionManager.isShizukuAvailable()
            )
        }
    }

    /**
     * Re-probes root on demand, so a user who grants access in their root manager while this
     * screen is open can retry without restarting the app.
     */
    fun continueWithRoot() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checkRootInProgress = true)
            val root = permissionManager.isRootAvailable()
            _uiState.value = _uiState.value.copy(
                isRootAvailable = root,
                checkRootInProgress = false,
                continueWithRootResult = root
            )
        }
    }

    /** Clears the one-shot result so the button can be pressed again. */
    fun consumeContinueWithRootResult() {
        _uiState.value = _uiState.value.copy(continueWithRootResult = null)
    }

    fun grantPermissionViaShizuku(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(grantViaShizukuInProgress = true)
            val success = permissionManager.grantReadLogsViaShizuku(context)
            _uiState.value = _uiState.value.copy(
                grantViaShizukuInProgress = false,
                grantViaShizukuSuccess = success
            )
        }
    }
}
