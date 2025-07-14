package com.valhalla.loki.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.loki.R
import com.valhalla.loki.model.AppInfo
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.getAppIcon
import com.valhalla.loki.services.LogcatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {

    val context = LocalContext.current
    val grabber = remember { AppInfoGrabber(context) }
    var userApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var systemApps by remember { mutableStateOf(emptyList<AppInfo>()) }

    // Observe the service's running state
    val isLoggerRunning by LogcatService.isRunning.collectAsState()

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        userApps = grabber.getUserApps()
        systemApps = grabber.getSystemApps()
    }

    // --- PERMISSION HANDLING ---
    var selectedAppForPermission by remember { mutableStateOf<AppInfo?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                selectedAppForPermission?.let { startLogService(context, it) }
            } else {
                Toast.makeText(context, "Notification permission denied.", Toast.LENGTH_SHORT)
                    .show()
            }
            selectedAppForPermission = null
        }
    )

    fun handleAppClick(appInfo: AppInfo) {
        if (isLoggerRunning) {
            Toast.makeText(context, "A logging session is already running.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startLogService(context, appInfo)
                }

                else -> {
                    selectedAppForPermission = appInfo
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No runtime permission needed for pre-Tiramisu
            startLogService(context, appInfo)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Loki") },
                actions = {
                    if (isLoggerRunning) {
                        IconButton(onClick = { showBottomSheet = true }) {
                            Icon(
                                painterResource(R.drawable.graphic_eq),
                                contentDescription = "Logger is running"
                            )
                        }
                    }
                }
            )
        },

    ) { paddingValues ->

        LazyColumn(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)) {
            items((userApps + systemApps).sortedBy { it.appName }) { app ->
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
                    modifier = Modifier.clickable { handleAppClick(app) }
                    // Your existing leadingContent...
                )
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState
            ) {
                LoggerBottomSheetContent(onStop = {
                    val intent = Intent(context, LogcatService::class.java).apply {
                        action = LogcatService.ACTION_STOP
                    }
                    context.startService(intent)
                    showBottomSheet = false
                })
            }
        }


    }
}


@Composable
private fun LoggerBottomSheetContent(onStop: () -> Unit) {
    val logFile by LogcatService.currentLogFile.collectAsState()
    var logLines by remember { mutableStateOf(listOf<String>()) }
    val listState = rememberLazyListState()

    // This effect will "tail" the log file for new lines
    LaunchedEffect(logFile) {
        if (logFile == null) {
            logLines = listOf("Waiting for log file...")
            return@LaunchedEffect
        }
        logLines = emptyList()

        launch(Dispatchers.IO) {
            try {
                // Wait a moment for the file to be created and written to
                delay(250)
                val reader = logFile!!.bufferedReader()
                while (isActive) {
                    val line = reader.readLine()
                    if (line != null) {
                        withContext(Dispatchers.Main) {
                            logLines = logLines + line
                        }
                    } else {
                        delay(300) // Wait for more content
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logLines = logLines + "Error reading log file: ${e.message}"
                }
            }
        }
    }

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
        Text("Live Logcat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
            Icon(painterResource(R.drawable.force_close), contentDescription = "Stop", modifier = Modifier.padding(end = 8.dp))
            Text("Stop Logging")
        }
    }
}


private fun startLogService(context: android.content.Context, appInfo: AppInfo) {
    val intent = Intent(context, LogcatService::class.java).apply {
        action = LogcatService.ACTION_START
        putExtra(LogcatService.EXTRA_APP_INFO, appInfo.asString())
    }
    context.startService(intent)
    Toast.makeText(context, "Starting logger for ${appInfo.appName}", Toast.LENGTH_SHORT).show()
}
