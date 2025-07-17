package com.valhalla.loki.ui.appList

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.loki.R
import com.valhalla.loki.model.getAppIcon
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    modifier: Modifier = Modifier,
    appListViewModel: AppListViewModel = koinViewModel()
) {

    val context = LocalContext.current
    val uiState by appListViewModel.uiState.collectAsState()

    val sheetState = rememberModalBottomSheetState()

    // --- PERMISSION HANDLING ---
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            appListViewModel.onPermissionResult(isGranted, context)
            if (isGranted) {
                Toast.makeText(context, "Notification permission granted.", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Toast.makeText(context, "Notification permission denied.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    )

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {

                }
            ) {
                Image(
                    painter = painterResource(R.drawable.loki_black),
                    "Logo",
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                    contentScale = ContentScale.Crop
                )
            }

            Text(
                "Loki",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
            )
            if (uiState.isLoggerRunning) {
                IconButton(onClick = { appListViewModel.setBottomSheetVisibility(true) }) {
                    Icon(
                        painterResource(R.drawable.graphic_eq),
                        contentDescription = "Logger is running"
                    )
                }
            }
        }
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
                Text("Loading apps...", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { appListViewModel.refreshApps() },
                modifier = Modifier
                    .fillMaxSize()
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items((uiState.userApps + uiState.systemApps).sortedBy { it.appName }) { app ->
                        ListItem(
                            leadingContent = {
                                Box {
                                    Image(
                                        painter = rememberDrawablePainter(
                                            getAppIcon(
                                                app.packageName,
                                                context
                                            )
                                        ),
                                        "App Icon",
                                        modifier = Modifier.Companion
                                            .padding(5.dp)
                                            .size(50.dp)
                                    )
                                }
                            },
                            headlineContent = { Text(app.appName ?: "Unknown") },
                            supportingContent = { Text(app.packageName) },
                            modifier = Modifier.clickable {
                                if (uiState.isLoggerRunning) {
                                    Toast.makeText(
                                        context,
                                        "A logging session is already running.",
                                        Toast.LENGTH_SHORT
                                    )
                                        .show()
                                    return@clickable
                                }
                                appListViewModel.handleAppClick(context, app) { permission ->
                                    notificationPermissionLauncher.launch(permission)
                                }
                            }
                        )
                    }
                }
            }
        }
    }



    if (uiState.showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { appListViewModel.setBottomSheetVisibility(false) },
            sheetState = sheetState
        ) {
            LoggerBottomSheetContent(
                logLines = uiState.logLines,
                currentLogFile = uiState.currentLogFile,
                onStop = {
                    appListViewModel.stopLogService(context)
                }
            )
        }
    }

    // Root access restricted dialog
    if (uiState.showRootRestricted) {
        AlertDialog(
            onDismissRequest = { appListViewModel.hideRootRestrictedDialog() },
            title = { Text("Root Access Required") },
            text = { Text("Loki requires root access to log app activity. Please ensure your device is rooted and grant root access to Loki in your root management application (e.g., Magisk).") },
            confirmButton = {
                TextButton(onClick = {
                    appListViewModel.hideRootRestrictedDialog()
                    appListViewModel.checkRootAccess() // Re-check root access
                }) {
                    Text("Check Again")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { appListViewModel.hideRootRestrictedDialog() }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }
}


@Composable
private fun LoggerBottomSheetContent(
    logLines: List<String>,
    currentLogFile: File?,
    onStop: () -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Live Logcat",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(max = 300.dp)
                .padding(vertical = 8.dp)
        ) {
            items(logLines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Icon(
                painterResource(R.drawable.force_close),
                contentDescription = "Stop",
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("Stop Logging")
        }
    }
}