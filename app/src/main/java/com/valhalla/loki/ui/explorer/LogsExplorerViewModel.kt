package com.valhalla.loki.ui.explorer

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.directoryChanges
import com.valhalla.loki.ui.widgets.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the root of the tree is called on screen. The directory itself is `filesDir/logs`. */
const val LOGS_ROOT_LABEL = "Saved logs"

/** How long the filesystem has to stay quiet before a change is turned into a reload. */
private const val DEBOUNCE_MS = 500L

/**
 * How deep [LogsExplorerViewModel.addToZip] will follow directories.
 *
 * `logs/<package>/<file>` is two levels, so this is slack rather than a limit. It exists because a
 * root shell can put a symlink anywhere in that tree, and a self-referential one turns an unguarded
 * recursive zip into an archive that grows until the cache partition is full. The canonical-path
 * check catches links pointing out of the tree; this catches ones pointing back into it.
 */
private const val MAX_ZIP_DEPTH = 8

/**
 * How long a share bundle stays in the cache before the next share prunes it.
 *
 * Not "clear the directory on every share", which is what the contribution did: a mail client
 * holds a URI into this directory for as long as the user takes to write the mail, and deleting the
 * file under it turns the attachment into an error. An hour is longer than that and short enough
 * that a multi-megabyte zip is not left behind for the life of the install.
 */
private const val SHARE_CACHE_TTL_MS = 60L * 60L * 1_000L

/**
 * The three orders that mean something for captures.
 *
 * The contribution had a fourth, by file type. Everything Loki writes under `logs/` is a `.log`, so
 * that one sorted nothing and cost a menu row.
 */
enum class SortMode(val label: String) {
    NAME("Name"),
    NEWEST("Newest first"),
    LARGEST("Largest first"),
}

/** One step of the breadcrumb trail. [relativePath] is empty for the root. */
data class Crumb(val label: String, val relativePath: String)

/**
 * One row, with every value it displays already computed.
 *
 * `File.length()`, `File.lastModified()`, a directory's child count and an app's icon are all disk
 * or IPC. The contribution read all four from composition, inside a `remember` keyed on the
 * selection size — so every selection tap re-listed the directory on the main thread.
 */
data class ExplorerEntry(
    val file: File,
    val relativePath: String,
    val title: String,
    val detail: String,
    val isDirectory: Boolean,
    val icon: Drawable?,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    fun matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) || file.name.contains(query, ignoreCase = true)
}

/** A file that is ready to leave the app, and the type to offer it as. */
data class ShareBundle(val file: File, val mimeType: String, val displayName: String)

data class LogsExplorerUiState(
    val isLoading: Boolean = true,
    val relativePath: String = "",
    val crumbs: List<Crumb> = listOf(Crumb(LOGS_ROOT_LABEL, "")),
    val entries: List<ExplorerEntry> = emptyList(),
    val query: String = "",
    val sortMode: SortMode = SortMode.NAME,
    /** Absolute paths, not `File`s: a reload rebuilds the entries, and a path outlives them. */
    val selection: Set<String> = emptySet(),
    val isPreparingShare: Boolean = false,
) {
    val isSelecting: Boolean get() = selection.isNotEmpty()
}

/**
 * The logs tree, browsable.
 *
 * Rooted at `filesDir/logs` and unable to leave it. Position is held as a path *relative* to that
 * root and re-checked against it on every use, which is the difference from the contribution: its
 * `Saver` restored an absolute path out of saved instance state and navigated straight to it, so
 * whatever that string said the browser went there.
 */
class LogsExplorerViewModel(
    private val logsDir: File,
    private val shareCacheDir: File,
    private val appInfoGrabber: AppInfoGrabber,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsExplorerUiState())
    val uiState: StateFlow<LogsExplorerUiState> = _uiState.asStateFlow()

    /** One-shot user-facing messages, same shape and reasoning as `SavedLogsViewModel.messages`. */
    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * A bundle, once it is on disk.
     *
     * The ViewModel owns the zip; the screen owns the chooser. That split is why the share no longer
     * needs `FLAG_ACTIVITY_NEW_TASK` — the contribution called `startActivity` from inside the
     * worker thread that built the archive, and the flag was there to make that legal.
     */
    private val _shares = MutableSharedFlow<ShareBundle>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val shares: SharedFlow<ShareBundle> = _shares.asSharedFlow()

    /** Icons and labels by package name, misses cached too so a stale directory is asked about once. */
    private val iconCache = mutableMapOf<String, Drawable?>()
    private val labelCache = mutableMapOf<String, String?>()

    /**
     * Built once. Only ever touched from the single reload coroutine below, which is the only reason
     * one `SimpleDateFormat` can be shared at all.
     */
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    /** The current directory as last read from disk, before any filter or sort is applied. */
    private var listing: List<ExplorerEntry> = emptyList()

    private enum class Reload { NOW, DEBOUNCED }

    /** Conflated to one slot, for the reasons written out in `SavedLogsViewModel`. */
    private val reloads = Channel<Reload>(capacity = Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (request in reloads) {
                if (request == Reload.DEBOUNCED) delay(DEBOUNCE_MS)
                load()
            }
        }
        reloads.trySend(Reload.NOW)
        viewModelScope.launch {
            logsDir.directoryChanges().collect { reloads.trySend(Reload.DEBOUNCED) }
        }
    }

    fun setQuery(query: String) = reproject { it.copy(query = query) }

    fun setSortMode(sortMode: SortMode) = reproject { it.copy(sortMode = sortMode) }

    /** Opens a directory. A file is the screen's business — it hands it to the viewer. */
    fun open(entry: ExplorerEntry) {
        if (entry.isDirectory) navigateTo(entry.relativePath)
    }

    /** Moves up one level. `false` when there is nowhere to go, which is the screen's cue to close. */
    fun up(): Boolean {
        val relative = _uiState.value.relativePath
        if (relative.isEmpty()) return false
        navigateTo(relative.substringBeforeLast('/', missingDelimiterValue = ""))
        return true
    }

    fun navigateTo(relativePath: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                relativePath = relativePath,
                crumbs = crumbsFor(relativePath),
                entries = emptyList(),
                // Both are per-directory. A selection carried across a navigation is how a delete
                // ends up removing something the user cannot see, and a search carried into another
                // folder reads as an empty folder.
                selection = emptySet(),
                query = "",
            )
        }
        reloads.trySend(Reload.NOW)
    }

    fun toggleSelection(entry: ExplorerEntry) {
        val path = entry.file.absolutePath
        _uiState.update {
            val selection = if (path in it.selection) it.selection - path else it.selection + path
            it.copy(selection = selection)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selection = emptySet()) }

    /**
     * Selects everything on screen, or clears if everything on screen is already selected.
     *
     * On screen, not in the directory: with a search active the rows the user can see are the rows
     * they mean, and selecting the hidden ones too would make the next delete a surprise.
     */
    fun toggleSelectAll() {
        _uiState.update { state ->
            val visible = state.entries.mapTo(mutableSetOf()) { it.file.absolutePath }
            val selection = if (visible.isNotEmpty() && state.selection.containsAll(visible)) {
                emptySet()
            } else {
                visible
            }
            state.copy(selection = selection)
        }
    }

    /**
     * Deletes the selection, and says how much of it actually went.
     *
     * `deleteRecursively()` returns false rather than throwing when it cannot finish, and it can
     * finish partly — so the count is of confirmed deletions, not of attempts.
     */
    fun deleteSelected() {
        val targets = _uiState.value.selection.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                targets.count { path ->
                    val file = insideRoot(File(path))
                    if (file == null) {
                        false
                    } else {
                        // Already gone counts as deleted: the listing was stale, and the user's
                        // intent is satisfied either way.
                        val gone = !file.exists() || file.deleteRecursively()
                        if (gone) pruneEmptyPackageDir(file.parentFile)
                        gone
                    }
                }
            }
            _uiState.update { it.copy(selection = emptySet()) }
            _messages.tryEmit(deleteMessage(deleted, targets.size))
            reloads.trySend(Reload.NOW)
        }
    }

    /**
     * Packages the selection and offers it to [shares].
     *
     * One file goes as itself; anything else goes as a zip. Nothing is sent anywhere here — the
     * screen puts it in front of the system chooser, and the user picks the destination
     * (`AGENTS.md`, the privileged surface rules).
     */
    fun shareSelected() {
        val state = _uiState.value
        val targets = state.selection.toList()
        if (targets.isEmpty() || state.isPreparingShare) return
        _uiState.update { it.copy(isPreparingShare = true) }
        viewModelScope.launch {
            val bundle = try {
                withContext(Dispatchers.IO) { bundleFor(targets) }
            } catch (e: IOException) {
                _uiState.update { it.copy(isPreparingShare = false) }
                _messages.tryEmit("Could not build that bundle: ${e.message ?: "write failed"}")
                return@launch
            }
            _uiState.update { it.copy(isPreparingShare = false) }
            if (bundle == null) {
                _messages.tryEmit("Those files are no longer there.")
            } else {
                _shares.tryEmit(bundle)
            }
        }
    }

    // ---------- listing ----------

    private suspend fun load() {
        val relative = _uiState.value.relativePath
        val dir = resolveInsideRoot(relative)
        val readable = dir != null && dir.isDirectory
        val entries =
            if (readable) withContext(Dispatchers.IO) { read(dir, relative) } else emptyList()
        listing = entries

        // A file can vanish between two reloads — a delete from the saved-logs tab, or a capture the
        // service rotated away. Intersecting rather than keeping means a share or a delete can never
        // name a path that is no longer on screen.
        val present = entries.mapTo(mutableSetOf()) { it.file.absolutePath }
        _uiState.update {
            it.copy(
                isLoading = false,
                entries = project(entries, it.query, it.sortMode),
                selection = it.selection intersect present,
            )
        }

        // The directory itself is gone — the app whose logs these were was cleared from elsewhere.
        // Fall back to the root rather than sit on a breadcrumb pointing at nothing. Guarded on a
        // non-empty path so a first run with no `logs/` yet shows the empty state instead of looping.
        if (!readable && relative.isNotEmpty()) navigateTo("")
    }

    private fun read(dir: File, relativePath: String): List<ExplorerEntry> {
        // Depth 1 is `logs/<package>`, so a directory here is named after an installed app.
        val holdsPackageDirs = relativePath.isEmpty()
        return dir.listFiles()?.map { file -> entryFor(file, relativePath, holdsPackageDirs) }
            .orEmpty()
    }

    private fun entryFor(
        file: File,
        parentRelativePath: String,
        isPackageDir: Boolean,
    ): ExplorerEntry {
        val relativePath =
            if (parentRelativePath.isEmpty()) file.name else "$parentRelativePath/${file.name}"
        val isDirectory = file.isDirectory
        val packageName = file.name.takeIf { isDirectory && isPackageDir }
        val label = packageName?.let { labelFor(it) }

        return if (isDirectory) {
            val children = file.listFiles().orEmpty()
            val bytes = children.sumOf { if (it.isFile) it.length() else 0L }
            ExplorerEntry(
                file = file,
                relativePath = relativePath,
                title = label ?: file.name,
                detail = buildString {
                    // The package name only earns a place here when the title is showing something
                    // else. A directory titled by its own name would otherwise say it twice.
                    if (label != null) append(file.name).append(" · ")
                    append(children.size).append(if (children.size == 1) " item" else " items")
                    if (bytes > 0) append(" · ").append(formatBytes(bytes))
                },
                isDirectory = true,
                icon = packageName?.let { iconFor(it) },
                sizeBytes = bytes,
                lastModified = file.lastModified(),
            )
        } else {
            ExplorerEntry(
                file = file,
                relativePath = relativePath,
                title = file.name,
                detail = "${formatBytes(file.length())} · ${dateFormat.format(Date(file.lastModified()))}",
                isDirectory = false,
                icon = null,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
            )
        }
    }

    /**
     * Re-filters and re-sorts what is already in memory.
     *
     * Search and sort are not reasons to touch the disk again. The contribution keyed its `remember`
     * on the query, the sort mode *and* the selection size, so all three re-listed the directory.
     */
    private fun reproject(transform: (LogsExplorerUiState) -> LogsExplorerUiState) {
        _uiState.update { current ->
            val next = transform(current)
            // From [listing], never from `current.entries`: those are already filtered, and
            // re-filtering them would mean widening a query could only ever find less.
            next.copy(entries = project(listing, next.query, next.sortMode))
        }
    }

    private fun project(
        entries: List<ExplorerEntry>,
        query: String,
        sortMode: SortMode,
    ): List<ExplorerEntry> {
        val matching = if (query.isBlank()) entries else entries.filter { it.matches(query) }
        // Directories first in every order. A folder is a place rather than a file, and sorting the
        // two together by size drops the app you are looking for between two captures.
        val within = when (sortMode) {
            SortMode.NAME -> compareBy<ExplorerEntry> { it.title.lowercase(Locale.getDefault()) }
            SortMode.NEWEST -> compareByDescending { it.lastModified }
            SortMode.LARGEST -> compareByDescending { it.sizeBytes }
        }
        return matching.sortedWith(compareByDescending<ExplorerEntry> { it.isDirectory }.then(within))
    }

    private fun crumbsFor(relativePath: String): List<Crumb> {
        val crumbs = mutableListOf(Crumb(LOGS_ROOT_LABEL, ""))
        if (relativePath.isEmpty()) return crumbs
        var accumulated = ""
        relativePath.split('/').forEach { segment ->
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            // Cache reads only, never a PackageManager query: this runs on the main thread, and the
            // only way to be here is to have tapped a row whose label was resolved off it already.
            crumbs += Crumb(labelCache[segment] ?: segment, accumulated)
        }
        return crumbs
    }

    private fun labelFor(packageName: String): String? =
        if (packageName in labelCache) {
            labelCache[packageName]
        } else {
            appInfoGrabber.getAppInfo(packageName)?.appName
                .also { labelCache[packageName] = it }
        }

    private fun iconFor(packageName: String): Drawable? =
        if (packageName in iconCache) {
            iconCache[packageName]
        } else {
            appInfoGrabber.getAppIcon(packageName).also { iconCache[packageName] = it }
        }

    // ---------- delete ----------

    /**
     * Removes a package directory that has nothing left in it.
     *
     * Same rule as `SavedLogsViewModel`: only for a directory whose own parent is the logs root, and
     * `delete()` refuses a non-empty one, so the worst case is that nothing happens.
     */
    private fun pruneEmptyPackageDir(dir: File?) {
        if (dir == null) return
        if (dir.parentFile?.canonicalPathOrNull() != logsDir.canonicalPathOrNull()) return
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    private fun deleteMessage(deleted: Int, total: Int): String = when {
        deleted == 0 -> "Nothing could be deleted."
        deleted == total && total == 1 -> "Deleted."
        deleted == total -> "Deleted $total items."
        else -> "Deleted $deleted of $total — the rest could not be removed."
    }

    // ---------- share ----------

    private fun bundleFor(paths: List<String>): ShareBundle? {
        val files = paths.mapNotNull { insideRoot(File(it)) }.filter { it.exists() }
        if (files.isEmpty()) return null

        val single = files.singleOrNull()
        if (single != null && single.isFile) {
            // No archive for one file. A receiver that can show a log should be handed the log, and
            // `text/plain` is what reaches one — the contribution sent `*/*`, which resolves to a
            // download manager as readily as to a text viewer.
            return ShareBundle(single, mimeType = "text/plain", displayName = single.name)
        }

        val now = System.currentTimeMillis()
        pruneShareCache(now)
        // Its own subdirectory per bundle, so two shares of the same shape — say two different
        // pairs of packages — cannot collide on one name and hand the first receiver the second
        // one's bytes. The name the user sees in the share sheet stays clean.
        val bundleDir = File(shareCacheDir, now.toString())
        if (!bundleDir.mkdirs() && !bundleDir.isDirectory) {
            throw IOException("could not create a bundle directory")
        }
        val zip = File(bundleDir, zipNameFor(files))
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            files.forEach { addToZip(zos, it, it.name, depth = 0) }
        }
        return ShareBundle(zip, mimeType = "application/zip", displayName = zip.name)
    }

    private fun zipNameFor(files: List<File>): String {
        val single = files.singleOrNull()
        return if (single != null) "${single.name}.zip" else "loki-logs-${files.size}-items.zip"
    }

    private fun pruneShareCache(now: Long) {
        shareCacheDir.listFiles()?.forEach { bundle ->
            if (now - bundle.lastModified() > SHARE_CACHE_TTL_MS) bundle.deleteRecursively()
        }
    }

    /**
     * Writes [file] into [zos] under [entryPath], recursing into directories.
     *
     * Two guards the contribution's version did not have. [MAX_ZIP_DEPTH] stops a symlink loop from
     * producing an archive that never ends, and [insideRoot] stops one pointing out of the tree from
     * copying whatever it points at into a file the user is about to send somewhere.
     */
    private fun addToZip(zos: ZipOutputStream, file: File, entryPath: String, depth: Int) {
        if (depth > MAX_ZIP_DEPTH) return
        if (insideRoot(file) == null) return
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryPath/"))
            zos.closeEntry()
            file.listFiles()?.forEach { child ->
                addToZip(zos, child, "$entryPath/${child.name}", depth + 1)
            }
        } else {
            zos.putNextEntry(ZipEntry(entryPath))
            file.inputStream().buffered().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    // ---------- the root check ----------

    private fun resolveInsideRoot(relativePath: String): File? =
        insideRoot(if (relativePath.isEmpty()) logsDir else File(logsDir, relativePath))

    /**
     * [file] if it really is inside the logs root, `null` otherwise.
     *
     * Canonical, so a symlink is judged by where it points rather than by where it sits, and with
     * the separator appended so a sibling called `logs-elsewhere` cannot pass as a child of `logs`.
     * Every navigation, delete and zip entry goes through here, because the paths they are given
     * come from saved state and from a directory a root shell can write to.
     */
    private fun insideRoot(file: File): File? {
        val root = logsDir.canonicalPathOrNull() ?: return null
        val path = file.canonicalPathOrNull() ?: return null
        return file.takeIf { path == root || path.startsWith(root + File.separator) }
    }
}

/** `canonicalPath` throws on an unreadable path; nothing here can do anything useful with that. */
private fun File.canonicalPathOrNull(): String? = try {
    canonicalPath
} catch (_: IOException) {
    null
}
