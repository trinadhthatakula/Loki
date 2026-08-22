package com.valhalla.loki.ui.explorer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.valhalla.asgard.components.AsgardDialogScaffold
import com.valhalla.asgard.components.AsgardEmptyState
import com.valhalla.asgard.components.AsgardLoadingState
import com.valhalla.asgard.components.AsgardSearchBar
import org.koin.compose.viewmodel.koinViewModel
import java.io.File

/**
 * The saved-logs tree, browsable in Loki.
 *
 * The Saved tab is the curated view: captures grouped by app, newest first. This is the raw one —
 * the directories as they are on disk, with bulk select, bulk delete, and share-as-zip. It is rooted
 * at `filesDir/logs` and cannot leave it, which is the whole reason it can be a file browser at all
 * without asking for a storage permission. The contribution rooted it at
 * `getExternalFilesDir()/Loki`, a directory Loki does not write to.
 */
@Composable
fun LogsExplorerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsExplorerViewModel = koinViewModel(),
    onOpenLog: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Selection first, then one level up, then out. Unwinding in that order is what stops a back
    // press from closing a browser the user only meant to deselect in. One lambda, so the hardware
    // gesture and the header's arrow cannot drift apart.
    //
    // This handler is registered inside the nav entry, so it sits below NavDisplay's own and wins —
    // which is what keeps the two inner stages from being swallowed by a pop of the whole screen.
    val goBack = {
        if (state.isSelecting) viewModel.clearSelection() else if (!viewModel.up()) onClose()
    }
    BackHandler(onBack = goBack)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { context.share(it) }
    }

    Column(modifier.fillMaxSize()) {
        ExplorerHeader(
            state = state,
            onBack = goBack,
            onSortChange = viewModel::setSortMode,
            onShare = viewModel::shareSelected,
            onDelete = { showDeleteConfirm = true },
            onSelectAll = viewModel::toggleSelectAll,
        )

        if (state.crumbs.size > 1) {
            BreadcrumbBar(
                crumbs = state.crumbs,
                enabled = !state.isSelecting,
                onNavigate = viewModel::navigateTo,
            )
        }

        when {
            state.isLoading -> AsgardLoadingState(
                text = "Reading the logs folder…",
                modifier = Modifier.fillMaxSize(),
            )

            else -> {
                AsgardSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = "Search this folder",
                    leadingIcon = Icons.Filled.FolderOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (state.entries.isEmpty()) {
                    AsgardEmptyState(
                        text = if (state.query.isEmpty()) "Nothing here" else "Nothing matches “${state.query}”",
                        description = if (state.query.isEmpty()) {
                            "Captures appear in this folder as soon as a logging session stops."
                        } else {
                            "Try a shorter search."
                        },
                        icon = Icons.Filled.FolderOpen,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.file.absolutePath }) { entry ->
                            ExplorerRow(
                                entry = entry,
                                selected = entry.file.absolutePath in state.selection,
                                selecting = state.isSelecting,
                                onClick = {
                                    when {
                                        state.isSelecting -> viewModel.toggleSelection(entry)
                                        entry.isDirectory -> viewModel.open(entry)
                                        else -> onOpenLog(entry.file)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(entry) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val count = state.selection.size
        AsgardDialogScaffold(
            onDismissRequest = { showDeleteConfirm = false },
            title = if (count == 1) "Delete this item?" else "Delete $count items?",
            text = "Whatever is selected is removed permanently, folders and everything in them " +
                "included. It cannot be undone.",
            icon = Icons.Filled.DeleteForever,
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                viewModel.deleteSelected()
                showDeleteConfirm = false
            },
        )
    }
}

@Composable
private fun ExplorerHeader(
    state: LogsExplorerUiState,
    onBack: () -> Unit,
    onSortChange: (SortMode) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = if (state.isSelecting) {
                    Icons.Filled.Close
                } else {
                    Icons.AutoMirrored.Filled.ArrowBack
                },
                contentDescription = if (state.isSelecting) "Clear the selection" else "Back",
            )
        }
        Text(
            text = if (state.isSelecting) {
                "${state.selection.size} selected"
            } else {
                state.crumbs.last().label
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (state.isSelecting) {
            IconButton(onClick = onShare, enabled = !state.isPreparingShare) {
                if (state.isPreparingShare) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Share, contentDescription = "Share the selection")
                }
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Select everything on screen")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = "Delete the selection",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = {
                                if (state.sortMode == mode) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Current")
                                } else {
                                    // Holds the text column in line with the checked row above it.
                                    Box(Modifier.size(24.dp))
                                }
                            },
                            onClick = {
                                onSortChange(mode)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    crumbs: List<Crumb>,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            val isLast = index == crumbs.lastIndex
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    // The last crumb is where you already are, and a selection in progress owns the
                    // taps — so both are dead rather than merely ignored.
                    .clickable(enabled = enabled && !isLast) { onNavigate(crumb.relativePath) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
            if (!isLast) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplorerRow(
    entry: ExplorerEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        // combinedClickable rather than ListItem's own onClick, because the row needs a long press
        // as well and that overload takes only the one. Long-press-to-select is the idiom every
        // file manager on the device already uses.
        //
        // Both actions are labelled. A long press is the *only* way into selection mode here, and an
        // unlabelled one is announced by TalkBack as a bare "long press" — the gesture without what
        // it does, on a screen where nothing else advertises that selecting is possible. The click
        // label varies because the same tap opens a directory, opens a log, or toggles a selection
        // depending on where you are.
        modifier = Modifier.combinedClickable(
            onClickLabel = when {
                selecting -> if (selected) "Deselect" else "Select"
                entry.isDirectory -> "Open folder"
                else -> "Open log"
            },
            onLongClickLabel = if (selecting) "Select" else "Start selecting",
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        // The content colours move with the container, not just the container. A selected row that
        // keeps onSurface text on a secondaryContainer background is a contrast ratio nobody
        // measured; the paired role is the one M3 guarantees against it.
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        supportingContent = {
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            val icon = entry.icon
            if (icon != null) {
                Image(
                    painter = rememberDrawablePainter(icon),
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else {
                Icon(
                    imageVector = if (entry.isDirectory) {
                        Icons.Filled.Folder
                    } else {
                        Icons.AutoMirrored.Filled.Article
                    },
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
        },
        // No explicit tints in these slots: ListItem publishes the paired content colour through
        // LocalContentColor, and hardcoding one here is how a selected row ends up with an icon in
        // the unselected palette.
        trailingContent = when {
            selected -> {
                { Icon(Icons.Filled.CheckCircle, contentDescription = "Selected") }
            }
            // No chevron while selecting: the row's tap toggles the checkbox then, and an affordance
            // that says "opens" would be lying about what it does.
            entry.isDirectory && !selecting -> {
                { Icon(Icons.Filled.ChevronRight, contentDescription = null) }
            }

            else -> null
        },
    ) {
        Text(
            text = entry.title,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val ICON_SIZE = 40.dp

/**
 * Hands a bundle to whichever app the user picks.
 *
 * The chooser, never a destination Loki chose: a capture carries whatever the target app logged —
 * tokens, URLs, other people's data — so this is only ever reachable from an explicit tap
 * (`AGENTS.md`, the privileged surface rules). `clipData` as well as `EXTRA_STREAM` because some
 * receivers read the clip instead of the extra, and without it they get a URI they have no grant
 * for. Read-only: nothing that leaves here can be written back.
 */
private fun Context.share(bundle: ShareBundle) {
    val uri = FileProvider.getUriForFile(this, "$packageName.provider", bundle.file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = bundle.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(bundle.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        startActivity(Intent.createChooser(intent, "Share logs"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app on this device can receive that.", Toast.LENGTH_SHORT).show()
    }
}
