package com.valhalla.loki.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.valhalla.loki.ui.appList.AppListScreen
import com.valhalla.loki.ui.explorer.LogsExplorerScreen
import com.valhalla.loki.ui.navigation.LokiRoute
import com.valhalla.loki.ui.navigation.navItems
import com.valhalla.loki.ui.saved.LogViewerScreen
import com.valhalla.loki.ui.saved.SavedLogsScreen
import com.valhalla.loki.ui.settings.SettingsScreen
import org.koin.androidx.compose.koinViewModel
import java.io.File

/**
 * Pushes [route] unless it is already on top of the stack.
 *
 * A double-tap on a log row used to push the same key twice. The outgoing screen stays composed and
 * hit-testable for the whole ~500 ms slide — nav3 puts no pointer-blocking modifier over it — so the
 * second tap re-entered the same `onClick`. It did not crash, because both entries resolve to the
 * same `contentKey` and `NavDisplay` renders such a key in one scene only, so the symptom was just a
 * back press that appeared to do nothing: the first one popped the duplicate nobody could see.
 *
 * Comparing keys is enough to catch it — the routes are `@Serializable data class`es and objects, so
 * equality is structural — and there is no legitimate way to open the viewer for a file while
 * already viewing that same file.
 */
private fun MutableList<NavKey>.pushOnce(route: NavKey) {
    if (lastOrNull() != route) add(route)
}

/**
 * The app shell: a bottom bar over one [NavDisplay].
 *
 * Each tab owns its own back stack, so opening a capture from Saved and then wandering into
 * Settings and back lands you on the capture again rather than on the list. That is the standard
 * bottom-navigation contract, and it is also what makes a full-screen child *behave* like one —
 * this used to be a `HorizontalPager`, where the viewer and the explorer had to render themselves
 * in place of a tab's content and the pager would happily swipe out from under them mid-read.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    onExitConfirmed: () -> Unit,
    // Shizuku's grant flow needs an Activity to bind the permission callback to, which a
    // composable does not have, so the Activity hands the trigger down rather than the screen
    // reaching for it. It covers root as well: which channel actually runs the grant is the
    // Activity's decision, not something a settings row should be picking.
    onRequestPrivilege: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    // In rememberSaveable rather than the ViewModel because the back stacks below are saveable
    // state too. A ViewModel with no SavedStateHandle survives rotation but not process death, so
    // after a restore the tab would be back on Apps while the stack it indexes into had restored —
    // two halves of one piece of navigation state, disagreeing.
    var activeTab by rememberSaveable { mutableIntStateOf(0) }

    val appsBackStack = rememberNavBackStack(LokiRoute.Apps)
    val savedBackStack = rememberNavBackStack(LokiRoute.Saved)
    val settingsBackStack = rememberNavBackStack(LokiRoute.Settings)

    val backStacks = remember(appsBackStack, savedBackStack, settingsBackStack) {
        listOf(appsBackStack, savedBackStack, settingsBackStack)
    }
    // Coerced because `activeTab` is restored from a Bundle that an older build wrote, and a build
    // with more tabs than this one would hand back an index past the end.
    val tab = activeTab.coerceIn(backStacks.indices)
    val currentBackStack = backStacks[tab]

    // The viewer and the explorer are full-screen and draw their own headers, so the bar would be
    // a second, competing navigation affordance over content that has nothing to switch between.
    val showBottomBar = currentBackStack.lastOrNull() in navItems.map { it.route }

    // Three handlers with mutually exclusive `enabled` flags rather than one with a `when`: only
    // the innermost *enabled* handler fires, so making the conditions disjoint is what guarantees
    // exactly one of them runs. The order below is the order back should be answered in.
    //
    // 1. A child of the active tab is open — close it.
    BackHandler(enabled = currentBackStack.size > 1) {
        currentBackStack.removeLastOrNull()
    }
    // 2. At the root of a tab that is not the first one — go to the first tab, as before.
    BackHandler(enabled = currentBackStack.size == 1 && tab != 0) {
        activeTab = 0
    }
    // 3. At the root of the first tab — the only place back means "leave Loki".
    BackHandler(enabled = currentBackStack.size == 1 && tab == 0) {
        viewModel.showExitDialog()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    navItems.forEachIndexed { index, navItem ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { activeTab = index },
                            icon = { Icon(painterResource(navItem.icon), navItem.title) },
                            label = { Text(navItem.title) },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        // One provider for every route in the app. Which stack an entry lands on is decided by the
        // push site, not here, so the viewer needs describing exactly once even though two screens
        // open it.
        val entryProvider = entryProvider<NavKey> {
            entry<LokiRoute.Apps> {
                AppListScreen(modifier = modifier)
            }
            // The two tab roots name their own stack rather than `currentBackStack`, because they
            // can only ever push onto that one — no need to ask which tab is active to find out.
            entry<LokiRoute.Saved> {
                SavedLogsScreen(
                    modifier = modifier,
                    onOpenLog = { file ->
                        savedBackStack.pushOnce(LokiRoute.LogViewer(file.absolutePath))
                    },
                )
            }
            entry<LokiRoute.Settings> {
                SettingsScreen(
                    modifier = modifier,
                    onRequestPrivilege = onRequestPrivilege,
                    onBrowseLogs = { settingsBackStack.pushOnce(LokiRoute.LogsExplorer) },
                )
            }
            // These two are reachable from more than one tab — the viewer opens from Saved and from
            // the explorer — so they go through the active stack, which is the one they are on
            // whenever they are the thing being drawn.
            entry<LokiRoute.LogsExplorer> {
                LogsExplorerScreen(
                    modifier = modifier,
                    onClose = { currentBackStack.removeLastOrNull() },
                    onOpenLog = { file ->
                        currentBackStack.pushOnce(LokiRoute.LogViewer(file.absolutePath))
                    },
                )
            }
            entry<LokiRoute.LogViewer> { route ->
                LogViewerScreen(
                    logFile = File(route.path),
                    onBack = { currentBackStack.removeLastOrNull() },
                    modifier = modifier,
                )
            }
        }

        // One decorated entry list per stack, so switching tabs does not tear down the other tabs'
        // ViewModels or scroll positions — that is the whole reason for `rememberDecoratedNavEntries`
        // here instead of handing `NavDisplay` a back stack directly.
        val appsDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            rememberViewModelStoreNavEntryDecorator(),
        )
        val appsEntries = rememberDecoratedNavEntries(
            backStack = appsBackStack,
            entryDecorators = appsDecorators,
            entryProvider = entryProvider,
        )

        val savedDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            rememberViewModelStoreNavEntryDecorator(),
        )
        val savedEntries = rememberDecoratedNavEntries(
            backStack = savedBackStack,
            entryDecorators = savedDecorators,
            entryProvider = entryProvider,
        )

        val settingsDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            rememberViewModelStoreNavEntryDecorator(),
        )
        val settingsEntries = rememberDecoratedNavEntries(
            backStack = settingsBackStack,
            entryDecorators = settingsDecorators,
            entryProvider = entryProvider,
        )

        val entries = remember(tab, appsEntries, savedEntries, settingsEntries) {
            when (tab) {
                1 -> savedEntries
                2 -> settingsEntries
                else -> appsEntries
            }
        }

        val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
        val effectsSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()

        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            NavDisplay(
                entries = entries,
                onBack = { currentBackStack.removeLastOrNull() },
                transitionSpec = {
                    (fadeIn(effectsSpec) + slideInHorizontally(animationSpec = spatialSpec) { it }) togetherWith
                            (fadeOut(effectsSpec) + slideOutHorizontally(animationSpec = spatialSpec) { -it })
                },
                popTransitionSpec = {
                    (fadeIn(effectsSpec) + slideInHorizontally(animationSpec = spatialSpec) { -it }) togetherWith
                            (fadeOut(effectsSpec) + slideOutHorizontally(animationSpec = spatialSpec) { it })
                },
                predictivePopTransitionSpec = {
                    (fadeIn(effectsSpec) + slideInHorizontally(animationSpec = spatialSpec) { -it }) togetherWith
                            (fadeOut(effectsSpec) + slideOutHorizontally(animationSpec = spatialSpec) { it })
                },
            )
        }

        if (uiState.showExitDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.hideExitDialog() },
                title = { Text("Exit Application?") },
                text = { Text("Are you sure you want to exit Loki?") },
                confirmButton = {
                    TextButton(onClick = onExitConfirmed) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.hideExitDialog() }) { Text("No") }
                },
            )
        }
    }
}
