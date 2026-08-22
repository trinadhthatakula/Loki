package com.valhalla.loki.ui.saved

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.asgard.components.AsgardDialogScaffold
import com.valhalla.asgard.components.AsgardEmptyState
import com.valhalla.asgard.components.AsgardHeader
import com.valhalla.asgard.components.AsgardListRow
import com.valhalla.asgard.components.AsgardLoadingState
import com.valhalla.asgard.components.AsgardSearchBar
import com.valhalla.loki.model.LoggedApp
import com.valhalla.loki.model.SavedLog
import com.valhalla.loki.ui.widgets.formatBytes
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved captures, grouped by the app they came from.
 *
 * Everything shown here — sizes, icons, the listing itself — is computed by [SavedLogsViewModel] on
 * a background dispatcher and arrives as state. No row touches the filesystem or `PackageManager`;
 * `docs/review-anon-contribution.md` §5.5 is the long version of why.
 */
@Composable
fun SavedLogsScreen(
    modifier: Modifier = Modifier,
    viewModel: SavedLogsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    // A Set of package names rather than a flag per row: the expansion has to survive the row
    // leaving composition, which is exactly what happens when the list is scrolled or reloaded.
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<SavedLog?>(null) }
    // The path rather than the File, because rememberSaveable's default saver takes a String and
    // survives process death, which a File does not.
    var openLogPath by rememberSaveable { mutableStateOf<String?>(null) }

    // The viewer takes over the tab rather than opening as its own destination, because Loki still
    // navigates by pager. Step 9 makes it a route; nothing here has to change for that beyond
    // deleting these four lines.
    val openPath = openLogPath
    if (openPath != null) {
        LogViewerScreen(
            logFile = File(openPath),
            onBack = { openLogPath = null },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val visibleApps = remember(uiState.loggedApps, query) {
        if (query.isBlank()) {
            uiState.loggedApps
        } else {
            uiState.loggedApps.filter { app ->
                app.appInfo.appName?.contains(query, ignoreCase = true) == true ||
                    app.appInfo.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        AsgardHeader(title = "Saved logs", icon = Icons.Filled.Folder)

        when {
            uiState.isLoading -> AsgardLoadingState(
                text = "Reading saved logs…",
                modifier = Modifier.fillMaxSize(),
            )

            uiState.loggedApps.isEmpty() -> AsgardEmptyState(
                text = "No saved logs yet",
                description = "Pick an app on the Apps tab to start a capture. Stopping it leaves " +
                    "the log here.",
                icon = Icons.AutoMirrored.Filled.Article,
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                AsgardSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search apps",
                    leadingIcon = Icons.Filled.Search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (visibleApps.isEmpty()) {
                        AsgardEmptyState(
                            text = "Nothing matches “$query”",
                            icon = Icons.Filled.Search,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        SavedLogsList(
                            apps = visibleApps,
                            expanded = expanded,
                            onToggle = { pkg ->
                                expanded = if (pkg in expanded) expanded - pkg else expanded + pkg
                            },
                            onView = { openLogPath = it.file.absolutePath },
                            onOpen = { context.openLog(it.file) },
                            onShare = { context.shareLog(it.file) },
                            onDelete = { pendingDelete = it },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { savedLog ->
        AsgardDialogScaffold(
            onDismissRequest = { pendingDelete = null },
            title = "Delete this log?",
            text = "${savedLog.file.name} (${formatBytes(savedLog.sizeBytes)}) will be removed " +
                "permanently. It cannot be undone.",
            icon = Icons.Filled.DeleteForever,
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                viewModel.deleteLog(savedLog)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun SavedLogsList(
    apps: List<LoggedApp>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onView: (SavedLog) -> Unit,
    onOpen: (SavedLog) -> Unit,
    onShare: (SavedLog) -> Unit,
    onDelete: (SavedLog) -> Unit,
) {
    // The date is formatted once for the whole list, not once per row: SimpleDateFormat is
    // expensive to build and is only safe to share because composition is single-threaded.
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        apps.forEach { app ->
            val packageName = app.appInfo.packageName
            val isExpanded = packageName in expanded

            // Sibling items rather than a nested Column inside one item: a package with two hundred
            // captures then composes the rows that are on screen instead of all of them. The
            // contribution capped each app at three logs and put the rest behind a "view all"
            // button that navigated nowhere.
            item(key = "app:$packageName") {
                AsgardListRow(
                    title = app.appInfo.appName ?: packageName,
                    subtitle = packageName,
                    caption = "${app.logs.size} ${if (app.logs.size == 1) "log" else "logs"} · " +
                        formatBytes(app.totalBytes),
                    leading = { AppIcon(app) },
                    trailing = {
                        Icon(
                            imageVector = if (isExpanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                        )
                    },
                    onClick = { onToggle(packageName) },
                )
            }

            if (isExpanded) {
                items(app.logs, key = { "log:${it.file.absolutePath}" }) { savedLog ->
                    LogRow(
                        savedLog = savedLog,
                        label = dateFormat.format(Date(savedLog.timestamp)),
                        onView = { onView(savedLog) },
                        onOpen = { onOpen(savedLog) },
                        onShare = { onShare(savedLog) },
                        onDelete = { onDelete(savedLog) },
                    )
                }
                item(key = "divider:$packageName") {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    savedLog: SavedLog,
    label: String,
    onView: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    AsgardListRow(
        title = label,
        subtitle = formatBytes(savedLog.sizeBytes),
        icon = Icons.AutoMirrored.Filled.Article,
        // Tapping the row reads the log in Loki; "Open with…" hands it to another app. Both used to
        // be the system chooser, because there was nothing in Loki that could read a log.
        onClick = onView,
        // Indented under its app, and left of the overflow button rather than under it.
        contentPadding = PaddingValues(start = 32.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        titleStyle = MaterialTheme.typography.bodyMedium,
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open with…") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

/** The app's launcher icon, or a folder when it has none — an uninstalled app has no icon to load. */
@Composable
private fun AppIcon(app: LoggedApp) {
    val icon = app.icon
    if (icon == null) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Image(
            painter = rememberDrawablePainter(icon),
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

private val ICON_SIZE = 40.dp

/**
 * A read grant on one file, for one app the user picked, for as long as that app lives.
 *
 * The `logs` entry in `res/xml/provider_paths.xml` is what makes `filesDir/logs/…` shareable at
 * all, and it is scoped to that subdirectory — a log is the only thing in `filesDir` a URI can be
 * built for. Read only, and never write: the contribution set
 * `FLAG_GRANT_WRITE_URI_PERMISSION` on a *view* intent, which handed the chosen app the ability to
 * rewrite the capture it was asked to display.
 */
private fun Context.logUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.provider", file)

/**
 * Hands the log to whichever app the user picks.
 *
 * `text/plain`, because that is what a logcat dump is. The contribution fell back to
 * `application/octet-stream` when it could not guess, which resolves to a download manager or
 * nothing at all rather than to a text viewer.
 */
private fun Context.openLog(file: File) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(logUri(file), "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(Intent.createChooser(intent, "Open log with"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app on this device can open a text file.", Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * Shares the log, if the user asks for it.
 *
 * A capture carries whatever the target app logged — tokens, URLs, other people's data — so this is
 * deliberately only ever reachable from an explicit tap, and it goes to the system chooser rather
 * than anywhere Loki decides (`AGENTS.md`, the privileged surface rules).
 *
 * `clipData` as well as `EXTRA_STREAM`: some receivers read the clip instead of the extra, and
 * without it they get a URI they have no grant for.
 */
private fun Context.shareLog(file: File) {
    val uri = logUri(file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, "Share log"))
}
