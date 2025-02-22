package com.valhalla.loki.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.loki.BuildConfig
import com.valhalla.loki.R
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.exportLogs
import com.valhalla.loki.model.getAppIcon
import com.valhalla.loki.ui.widgets.TermLoggerDialog
import com.valhalla.loki.model.AppInfo
import java.io.File
import java.net.URLConnection

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {}
) {

    val context = LocalContext.current

    val grabber = AppInfoGrabber(context)

    var userApps by remember { mutableStateOf(grabber.getUserApps()) }
    var systemApps by remember { mutableStateOf(grabber.getSystemApps()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var canExit by remember { mutableStateOf(false) }
    var logObserver by remember { mutableStateOf(emptyList<String>()) }
    var termLoggerTitle by remember { mutableStateOf("") }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            userApps = grabber.getUserApps()
            systemApps = grabber.getSystemApps()
            isRefreshing = false
        }
    }

    if (userApps.isEmpty() && systemApps.isEmpty()) {
        isRefreshing = true
    }

    var selectedApp: AppInfo? by remember { mutableStateOf(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.launcher_foreground),
                "App Icon",
                modifier = Modifier
                    .padding(5.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .padding(8.dp)
            )
            Text(
                "Loki",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Start
            )
        }

        LazyColumn {
            items(userApps + systemApps) {
                ListItem(
                    leadingContent = {
                        Box {
                            Image(
                                painter = rememberDrawablePainter(
                                    getAppIcon(
                                        it.packageName,
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
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                it.appName ?: "Unknown",
                                maxLines = 1
                            )
                            if (it.splitPublicSourceDirs.isNotEmpty()) {
                                Text(
                                    text = "${it.splitPublicSourceDirs.size} Splits",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            RoundedCornerShape(50)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.5.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    },
                    supportingContent = {
                        Text(
                            it.packageName,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.Companion
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            selectedApp = it
                        }

                )
            }
        }

        BackHandler {
            if (selectedApp == null) {
                onExit()
            } else {
                Toast.makeText(
                    context, "Please wait exporting logs for ${selectedApp?.appName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (selectedApp != null) {

            Dialog(
                onDismissRequest = {
                    selectedApp = null
                },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {

                var tryExit = {
                    logObserver += "Logs exported"
                    canExit = true
                }
                logObserver = emptyList()
                termLoggerTitle = "Loki's Logger"
                val folder = File(context.filesDir, "logs")
                val logFile = File(folder, "${selectedApp?.appName}.log")
                if (folder.exists() || folder.mkdirs()) {
                    if (logFile.exists().not() || logFile.delete()) {
                        if (logFile.createNewFile()) {
                            exportLogs(selectedApp!!, logFile, observer = {
                                logObserver += it
                            }, exit = { result ->
                                if(result.isSuccess){
                                    ShareCompat.IntentBuilder(context)
                                        .setStream(FileProvider.getUriForFile(
                                            context,
                                            BuildConfig.APPLICATION_ID + ".provider",
                                            logFile
                                        ))
                                        .setType(URLConnection.guessContentTypeFromName(logFile.getName()))
                                        .startChooser()
                                    tryExit.invoke()
                                }else{
                                    result.exceptionOrNull()?.printStackTrace()
                                    logObserver += "Failed to export logs"
                                    canExit = true
                                }
                            })
                        }
                    }
                }

                TermLoggerDialog(
                    title = termLoggerTitle,
                    canExit = canExit,
                    logObserver = logObserver,
                    done = {
                        selectedApp = null
                        canExit = false
                    }
                )
            }

        }


    }

}