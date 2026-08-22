package com.valhalla.loki.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * `navIndex` and `canExit` used to live here. Navigation 3 owns both now: the selected tab is
 * `rememberSaveable` state next to the back stacks it indexes into, and "can this back press exit
 * the app" is a question about stack depth that only the composable can answer.
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val showExitDialog: Boolean = false,
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun showExitDialog() {
        _uiState.update { it.copy(showExitDialog = true) }
    }

    fun hideExitDialog() {
        _uiState.update { it.copy(showExitDialog = false) }
    }
}
