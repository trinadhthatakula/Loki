package com.valhalla.loki.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.valhalla.loki.model.navItems
import com.valhalla.loki.ui.appList.AppListScreen
import com.valhalla.loki.ui.saved.SavedScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    onExitConfirmed: () -> Unit // Callback to handle app exit
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(pageCount = { navItems.size })
    val scope = rememberCoroutineScope()

    // Sync pager state with navIndex from ViewModel
    LaunchedEffect(uiState.navIndex) {
        pagerState.animateScrollToPage(uiState.navIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setNavIndex(page)
        }
    }

    // Handle back press
    BackHandler(enabled = true) {
        if (uiState.canExit.not()) {
            viewModel.setNavIndex(0)
        }else {
            viewModel.showExitDialog()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                navItems.forEachIndexed { index, navItem ->
                    NavigationBarItem(
                        selected = uiState.navIndex == index,
                        onClick = {
                            viewModel.setNavIndex(index)
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = {
                            Icon(
                                painterResource(navItem.icon),
                                navItem.title
                            )
                        },
                        label = {
                            Text(navItem.title)
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(pagerState) { page ->
            when (page) {
                0 -> AppListScreen(
                    modifier.padding(paddingValues)
                )
                1 -> SavedScreen(
                    modifier.padding(paddingValues)
                )
                else -> { /* Other screens will go here */ }
            }
        }

        // Exit confirmation dialog
        if (uiState.canExit && uiState.showExitDialog ) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.hideExitDialog()
                },
                title = { Text("Exit Application?") },
                text = { Text("Are you sure you want to exit Loki?") },
                confirmButton = {
                    TextButton(onClick = onExitConfirmed) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.hideExitDialog()
                    }) {
                        Text("No")
                    }
                }
            )
        }

    }
}
