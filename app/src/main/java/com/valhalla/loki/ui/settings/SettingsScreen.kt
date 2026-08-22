package com.valhalla.loki.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.valhalla.asgard.components.AsgardBanner
import com.valhalla.asgard.components.AsgardDialogScaffold
import com.valhalla.asgard.components.AsgardHeader
import com.valhalla.asgard.components.AsgardListRow
import com.valhalla.asgard.components.AsgardSectionCard
import com.valhalla.asgard.components.AsgardSettingRow
import com.valhalla.asgard.components.AsgardSettingToggleRow
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.loki.BuildConfig
import com.valhalla.loki.model.ThemeMode
import com.valhalla.loki.services.LokiDocumentsProvider
import com.valhalla.loki.ui.theme.monoFontFamily
import com.valhalla.loki.ui.theme.success
import com.valhalla.loki.ui.widgets.formatBytes
import org.koin.androidx.compose.koinViewModel

/** The repository URL, shown and opened by the About section. */
private const val SOURCE_URL = "https://github.com/trinadhthatakula/Loki"

/**
 * `EXTRA_INITIAL_URI` for the browse row — a document URI for [LokiDocumentsProvider]'s root, which
 * is the form `ACTION_OPEN_DOCUMENT_TREE` documents for that extra.
 */
private val LOKI_LOGS_ROOT_URI: Uri = DocumentsContract.buildDocumentUri(
    LokiDocumentsProvider.AUTHORITY,
    LokiDocumentsProvider.ROOT_DOCUMENT_ID,
)

/**
 * The whole command, quoted so `am force-stop` runs **on the device**.
 *
 * The contribution wrote `adb shell pm grant … && am force-stop …` unquoted, which the *host*
 * shell splits: `pm grant` runs on the device and `am` then runs on the PC, where it does not
 * exist. The force-stop matters because a granted permission does not reach an already-running
 * process.
 */
private const val ADB_GRANT_COMMAND =
    "adb shell \"pm grant ${BuildConfig.APPLICATION_ID} android.permission.READ_LOGS && " +
        "am force-stop ${BuildConfig.APPLICATION_ID}\""

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
    onRequestShizuku: () -> Unit = {},
    onBrowseLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // One ACTION_OPEN_DOCUMENT_TREE, pre-pointed at Loki's own DocumentsProvider root, which is
    // what §5.2 recommends in place of the contribution's two broken external routes: one
    // mis-parsed secondary-user paths and Android 11+ refuses Android/data document IDs, the other
    // called FileProvider.getUriForFile on a *directory* with a fabricated MIME type.
    //
    // The returned tree URI is deliberately unused — Loki already owns this directory and needs no
    // grant to it. The intent is used as a browse entry point, which is what the row's subtitle
    // promises: the picker opens showing the saved logs, and the user can read, copy out or delete
    // from there. That is the whole feature.
    val browseLogsFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { /* nothing to persist */ }

    var showAdbSheet by remember { mutableStateOf(false) }
    var showLicenceSheet by remember { mutableStateOf(false) }
    var showThanksSheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val privilege = uiState.privilege
    val theme = uiState.theme
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(modifier.fillMaxSize()) {
        AsgardHeader(title = "Settings", icon = Icons.Filled.Settings)

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // tertiaryContainer / errorContainer rather than the contribution's hardcoded
                // 0xFF4CAF50 and 0xFFF44336: those bypass the theme entirely and read wrong in
                // dark and AMOLED. Both roles here come with a matching on-colour, which is what
                // AsgardBanner derives its content colour from.
                AsgardBanner(
                    title = when {
                        privilege == null -> "Checking privilege…"
                        privilege.readLogsGranted -> "READ_LOGS granted"
                        privilege.rootAvailable -> "Root available"
                        else -> "No privilege"
                    },
                    description = when {
                        privilege == null -> "Probing root and Shizuku."
                        privilege.readLogsGranted ->
                            "Loki can read other applications' logs directly."

                        privilege.rootAvailable ->
                            "Captures run through a root shell."

                        privilege.shizukuReady ->
                            "Shizuku is connected but READ_LOGS is not granted yet. Grant it below."

                        else ->
                            "Loki can only read its own logs. Grant READ_LOGS via Shizuku or ADB " +
                                "below."
                    },
                    icon = when {
                        privilege == null -> Icons.Filled.HourglassEmpty
                        privilege.canCapture -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.ErrorOutline
                    },
                    containerColor = when {
                        privilege == null -> MaterialTheme.colorScheme.surfaceContainerHigh
                        privilege.canCapture -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                AsgardSectionCard(title = "Privilege", modifier = Modifier.fillMaxWidth()) {
                    AsgardSettingRow(
                        title = "READ_LOGS",
                        subtitle = "Tap to re-check",
                        icon = Icons.Filled.AdminPanelSettings,
                        value = when {
                            privilege == null -> "Checking…"
                            privilege.readLogsGranted -> "Granted"
                            privilege.rootAvailable -> "Root"
                            else -> "Not granted"
                        },
                        // `success` is the one place a semantic green belongs: it is defined per
                        // theme in ui/theme/Color.kt and measured against the section surface.
                        valueColor = when {
                            privilege == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            privilege.canCapture -> MaterialTheme.colorScheme.success
                            else -> MaterialTheme.colorScheme.error
                        },
                        onClick = { viewModel.refresh() },
                    )
                    AsgardSettingRow(
                        title = "Grant via Shizuku",
                        // Not "needs Shizuku installed": `shizukuReady` is false both when
                        // Shizuku is absent and when it is running but has not authorised Loki,
                        // and PermissionManager cannot tell those apart. Saying "not installed"
                        // to someone who has it installed is worse than saying nothing.
                        subtitle = when {
                            privilege == null -> "Checking…"
                            privilege.readLogsGranted -> "Already granted"
                            privilege.shizukuReady -> "Shizuku has authorised Loki — tap to grant"
                            else -> "Tap to request access through Shizuku"
                        },
                        icon = Icons.Filled.Key,
                        enabled = privilege != null && !privilege.readLogsGranted,
                        onClick = onRequestShizuku,
                    )
                    AsgardSettingRow(
                        title = "Copy ADB command",
                        subtitle = "Grants READ_LOGS from a computer or an on-device ADB app",
                        icon = Icons.Filled.Terminal,
                        onClick = { showAdbSheet = true },
                    )
                }
            }

            item {
                AsgardSectionCard(title = "Appearance", modifier = Modifier.fillMaxWidth()) {
                    // A closed set of three does not deserve a modal surface. A sheet costs a
                    // window, an animation and two taps to change nothing, and it hides the two
                    // options you did not pick; the segmented control shows all three and settles
                    // in one tap. This is the shape Thor uses for the same job (SettingsPickerRow
                    // in presentation/settings/SettingsComponents.kt): the row states what is being
                    // chosen, the group below it does the choosing.
                    AsgardListRow(
                        title = "Theme",
                        subtitle = "Light, dark, or follow the system",
                        icon = theme.mode.icon,
                    )
                    ConnectedButtonGroup(
                        items = ThemeMode.entries.map {
                            ConnectedButtonGroupItem.Label(it.label)
                        },
                        selectedIndex = ThemeMode.entries.indexOf(theme.mode),
                        onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        // The Material default gives unchecked buttons `surfaceContainer`, which is
                        // the grey immediately above the card's own `surfaceContainerLow` — one step
                        // apart in the ladder, and in the AMOLED scheme that is #0A0A0A on black.
                        // Verified on device: the two unpicked options read as bare text, so the
                        // control does not look like a control. `surfaceContainerHighest` is the
                        // one token that stays visible against all three schemes.
                        colors = ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentDescription = "Theme",
                    )
                    AsgardSettingToggleRow(
                        title = "AMOLED black",
                        subtitle = "Pure black surfaces in dark mode",
                        icon = Icons.Filled.Contrast,
                        checked = theme.amoled,
                        onCheckedChange = { viewModel.setAmoled(it) },
                    )
                    AsgardSettingToggleRow(
                        title = "Dynamic colour",
                        subtitle = if (dynamicColorSupported) {
                            "Take colours from your wallpaper"
                        } else {
                            "Needs Android 12 or newer"
                        },
                        icon = Icons.Filled.Palette,
                        checked = theme.dynamicColor,
                        enabled = dynamicColorSupported,
                        onCheckedChange = { viewModel.setDynamicColor(it) },
                    )
                }
            }

            item {
                val stats = uiState.stats
                AsgardSectionCard(title = "Saved logs", modifier = Modifier.fillMaxWidth()) {
                    AsgardSettingRow(
                        title = "Browse in Loki",
                        subtitle = "Read, bulk-delete or share as a zip",
                        icon = Icons.Filled.Folder,
                        onClick = onBrowseLogs,
                    )
                    AsgardSettingRow(
                        title = "Browse in a file manager",
                        subtitle = "Opens the picker at Loki's own logs root",
                        icon = Icons.Filled.FolderOpen,
                        onClick = {
                            runCatching { browseLogsFolder.launch(LOKI_LOGS_ROOT_URI) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "No file picker on this device.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        },
                    )
                    AsgardSettingRow(
                        title = "Clear all saved logs",
                        subtitle = when {
                            uiState.isClearing -> "Deleting…"
                            stats == null -> "Measuring…"
                            stats.fileCount == 0 -> "Nothing saved yet"
                            else -> "${stats.fileCount} " +
                                (if (stats.fileCount == 1) "file" else "files") +
                                " · ${formatBytes(stats.totalBytes)}"
                        },
                        icon = Icons.Filled.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        enabled = !uiState.isClearing && (stats?.fileCount ?: 0) > 0,
                        onClick = { showClearConfirm = true },
                    )
                }
            }

            item {
                AsgardSectionCard(title = "About", modifier = Modifier.fillMaxWidth()) {
                    AsgardSettingRow(
                        title = "Version",
                        subtitle = if (BuildConfig.DEBUG) "Debug build" else null,
                        icon = Icons.Filled.Info,
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                    AsgardSettingRow(
                        title = "Licence",
                        subtitle = "Free software — the source travels with it",
                        icon = Icons.Filled.Gavel,
                        value = "GPL-3.0-or-later",
                        onClick = { showLicenceSheet = true },
                    )
                    AsgardSettingRow(
                        title = "Source code",
                        subtitle = "github.com/trinadhthatakula/Loki",
                        icon = Icons.Filled.Code,
                        onClick = { context.openUrl(SOURCE_URL) },
                    )
                    AsgardSettingRow(
                        title = "Acknowledgements",
                        subtitle = "Thanks to an anonymous contributor",
                        icon = Icons.Filled.Favorite,
                        onClick = { showThanksSheet = true },
                    )
                }
            }
        }
    }

    if (showAdbSheet) {
        ModalBottomSheet(onDismissRequest = { showAdbSheet = false }) {
            SheetBody(title = "Grant READ_LOGS over ADB") {
                Text(
                    "Run this from a computer with the device connected, or from an on-device ADB " +
                        "app over wireless debugging.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    ADB_GRANT_COMMAND,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monoFontFamily,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                TextButton(onClick = {
                    context.copyToClipboard("ADB command", ADB_GRANT_COMMAND)
                    showAdbSheet = false
                }) {
                    Text("Copy command")
                }
            }
        }
    }

    if (showLicenceSheet) {
        ModalBottomSheet(onDismissRequest = { showLicenceSheet = false }) {
            SheetBody(title = "Licence") {
                Text(
                    "Loki is free software under the GNU General Public License, version 3 or " +
                        "any later version. You may use, study, modify and redistribute it, " +
                        "provided your version also ships its complete corresponding source " +
                        "under the same terms.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The Loki name and icon are trademarks and are not covered by the GPL — a " +
                        "fork must rename and re-icon.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Bundled fonts, under the SIL Open Font License 1.1:\n" +
                        "• Outfit — Copyright 2021 The Outfit Project Authors\n" +
                        "• Fira Code — Copyright 2014–2020 The Fira Code Project Authors",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(
                    onClick = { context.openUrl("$SOURCE_URL/blob/master/LICENSE") },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Read the full licence")
                }
            }
        }
    }

    if (showThanksSheet) {
        ModalBottomSheet(onDismissRequest = { showThanksSheet = false }) {
            SheetBody(title = "Acknowledgements") {
                // No name, handle or link. The contributor shared their work on the condition of
                // not being identified, so the credit has to be real without identifying them.
                Text(
                    "The settings screen, the theme system and several other improvements began " +
                        "as a source contribution from a developer who asked not to be named. " +
                        "Thank you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Loki also stands on Odin, Shizuku, Jetpack Compose, Koin and Asgard UI.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (showClearConfirm) {
        val stats = uiState.stats
        AsgardDialogScaffold(
            onDismissRequest = { showClearConfirm = false },
            title = "Delete all saved logs?",
            text = buildString {
                append("This permanently deletes ")
                append(
                    if (stats == null || stats.fileCount == 0) {
                        "every saved log"
                    } else {
                        "${stats.fileCount} " +
                            (if (stats.fileCount == 1) "file" else "files") +
                            " (${formatBytes(stats.totalBytes)})"
                    }
                )
                append(". It cannot be undone.")
            },
            icon = Icons.Filled.DeleteForever,
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                viewModel.clearAllLogs()
                showClearConfirm = false
            },
        )
    }
}

/**
 * Shared padding and title for the bottom sheets above.
 *
 * `navigationBarsPadding` rather than a fixed bottom inset: a sheet's own content is not covered by
 * [ModalBottomSheet]'s window insets handling, so on a gesture-navigation device the last row would
 * otherwise sit under the home pill.
 */
@Composable
private fun SheetBody(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        content()
    }
}

/**
 * The button labels. One word each, because each one has to fit a third of the group's width at any
 * font scale — `ConnectedButtonGroup` ellipsises rather than overflowing, so "Follow system" would
 * read as "Follow s…". The row's subtitle above carries the longer explanation instead.
 *
 * Never persisted — see [ThemeMode.token] for the stored form.
 */
private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
    }

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and up show their own "copied" confirmation, so a toast here would double up.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No browser installed.", Toast.LENGTH_SHORT).show()
    }
}
