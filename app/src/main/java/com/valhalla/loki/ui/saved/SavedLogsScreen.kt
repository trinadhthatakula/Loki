package com.valhalla.loki.ui.saved

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.loki.R
import com.valhalla.loki.model.LoggedApp
import com.valhalla.loki.model.SavedLog
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface SavedLogActions {
    data class ViewMore(val loggedApp: LoggedApp) : SavedLogActions
    data class OnLogClick(val savedLog: SavedLog) : SavedLogActions
}

@Composable
fun SavedLogsScreen(
    modifier: Modifier = Modifier,
    viewModel: SavedLogsViewModel = koinViewModel(),
    onAction: (SavedLogActions) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier.fillMaxSize()) {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else if (uiState.loggedApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved logs found.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(uiState.loggedApps) { loggedApp ->
                    LoggedAppItem(
                        loggedApp = loggedApp,
                        onViewMoreClicked = {
                            onAction(SavedLogActions.ViewMore(loggedApp))
                        },
                        onLogFileClicked = {
                            onAction(SavedLogActions.OnLogClick(it))
                        }
                    )
                }
            }
        }

    }

}

@Composable
private fun LoggedAppItem(
    loggedApp: LoggedApp,
    onViewMoreClicked: (String) -> Unit,
    onLogFileClicked: (SavedLog) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val logsToShow = loggedApp.logs.take(3)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            ListItem(
                headlineContent = { Text(loggedApp.appInfo.appName ?: "Unknown") },
                supportingContent = { Text("${loggedApp.logs.size} logs saved") },
                trailingContent = {
                    Icon(
                        painterResource( if (isExpanded) R.drawable.unfold_less else R.drawable.unfold_more),
                        contentDescription = "Expand or collapse"
                    )
                },
                modifier = Modifier.clickable { isExpanded = !isExpanded }
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    logsToShow.forEach { savedLog ->
                        LogFileRow(savedLog = savedLog, onClick = { onLogFileClicked(savedLog) })
                    }

                    if (loggedApp.logs.size > 3) {
                        TextButton(
                            onClick = { onViewMoreClicked(loggedApp.appInfo.packageName) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("View all ${loggedApp.logs.size} logs")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFileRow(savedLog: SavedLog, onClick: () -> Unit) {
    val dateFormat = remember {
        SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.note_alt),
            contentDescription = "Log file",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = dateFormat.format(Date(savedLog.timestamp)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
