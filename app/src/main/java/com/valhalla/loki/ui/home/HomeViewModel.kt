package com.valhalla.loki.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val navIndex: Int = 0,
    val canExit: Boolean = true,
    val showExitDialog : Boolean = false
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun showExitDialog(){
        _uiState.update {
            it.copy(
                showExitDialog = true
            )
        }
    }

    fun hideExitDialog(){
        _uiState.update {
            it.copy(
                showExitDialog = false
            )
        }
    }

    fun setNavIndex(index: Int) {
        _uiState.update {
            it.copy(
                navIndex = index,
                canExit = index == 0
            )
        }
    }

}
