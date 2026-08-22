package com.valhalla.loki.ui.saved

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.loki.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * How many lines the viewer keeps in memory at once.
 *
 * There has to be a number. Logcat output is unbounded — a busy device produces tens to hundreds of
 * megabytes — and the contribution read the whole file into a `String` and then split it into a
 * boxed `List<Pair<Int, String>>`, three to five times the file size in heap. An app whose entire
 * purpose is producing these files cannot OOM opening one.
 *
 * 20,000 lines of logcat is roughly 5 MB retained: enough to scroll through a session's worth of
 * output, small enough to survive on a low-heap device with the rest of the app still running.
 */
private const val MAX_LINES = 20_000

/** Lines read per cancellation check while streaming. Cheap enough to be free, often enough to feel instant. */
private const val CANCEL_CHECK_INTERVAL = 1_024

/**
 * How long a keystroke waits before the search actually runs.
 *
 * Short enough not to feel laggy, long enough that typing a six-letter word scans the log once
 * instead of six times. `Flow.debounce` would say this in one line but is `@FlowPreview`; a `delay`
 * inside `collectLatest` is the same behaviour with a stable API, because the next emission cancels
 * the delay before it completes.
 */
private const val SEARCH_DEBOUNCE_MS = 220L

/** One line of a log, carrying the line number it has **in the file**, not in the filtered view. */
data class LogLine(val number: Int, val text: String, val level: LogLevel)

/**
 * The level filter, as "this level and above".
 *
 * Not the contribution's exact-level match, which answered "show me Warn" with warnings only and
 * hid the errors that followed them — the opposite of what someone triaging a log wants. There is no
 * `Verbose+` entry because verbose *is* the lowest level, so it would duplicate [ALL].
 */
enum class LevelFilter(val label: String, val min: LogLevel?) {
    ALL("All", null),
    DEBUG("Debug+", LogLevel.DEBUG),
    INFO("Info+", LogLevel.INFO),
    WARN("Warn+", LogLevel.WARN),
    ERROR("Error+", LogLevel.ERROR),
}

data class LogViewerUiState(
    val isLoading: Boolean = true,
    /** Set when the file could not be read at all; the screen shows this instead of a list. */
    val error: String? = null,
    val level: LevelFilter = LevelFilter.ALL,
    val query: String = "",
    /** The lines the level filter admits, in file order. Exactly what the list renders. */
    val lines: List<LogLine> = emptyList(),
    /** Indices **into [lines]** whose text contains [query]. Empty while the query is blank. */
    val matches: List<Int> = emptyList(),
    /** Lines in the file, including the ones past the retention cap. */
    val totalLines: Int = 0,
    /** How many lines were dropped off the front to stay under the cap. Zero for a normal log. */
    val droppedLines: Int = 0,
    val isExporting: Boolean = false,
) {
    /** True when the file was too long to hold and the view starts partway in. */
    val isTruncated: Boolean get() = droppedLines > 0
}

/**
 * Backs [LogViewerScreen] for one file.
 *
 * Everything expensive happens off the main thread and exactly once: the file is streamed on
 * [Dispatchers.IO] and never held whole, priorities are parsed once at load rather than per
 * recomposition, and filtering and searching run on [Dispatchers.Default] as a single derivation of
 * (file, level, query) rather than as two mutually-inconsistent caches.
 */
class LogViewerViewModel(
    private val file: File,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    /** The retained window, immutable once loaded. `null` until the read finishes. */
    private class Loaded(val lines: List<LogLine>, val totalLines: Int, val dropped: Int)

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val level = MutableStateFlow(LevelFilter.ALL)
    private val query = MutableStateFlow("")

    private val _uiState = MutableStateFlow(LogViewerUiState())
    val uiState: StateFlow<LogViewerUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            // Not `runCatching`: it catches Throwable, and [readTail] throws CancellationException
            // on purpose — `coroutineContext.ensureActive()` is how it notices the user leaving a
            // huge file mid-read. Caught, that cancellation lands in `exceptionOrNull()` and is
            // written into `error` as "Could not read this log: ...", turning a control-flow signal
            // into a failure the user is shown; and since `_uiState.update` does not suspend, it
            // runs even though the job is already cancelled. Only `onCleared` cancels today, which
            // keeps the damage to a screen that is leaving anyway — but the pattern breaks the
            // moment the read is restarted or moved under a cancellable parent.
            try {
                loaded.value = readTail()
                _uiState.update { it.copy(isLoading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not read this log: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(loaded, level, query) { log, lvl, q -> Triple(log, lvl, q) }
                .collectLatest { (log, lvl, q) ->
                    if (log == null) return@collectLatest
                    // Only a query needs debouncing. Tapping a level chip is one discrete action and
                    // should land immediately.
                    if (q.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
                    val (lines, matches) = withContext(Dispatchers.Default) { project(log, lvl, q) }
                    _uiState.update {
                        it.copy(
                            lines = lines,
                            matches = matches,
                            totalLines = log.totalLines,
                            droppedLines = log.dropped,
                        )
                    }
                }
        }
    }

    /**
     * Echoes into [_uiState] as well as into the derivation.
     *
     * The echo is what keeps the text field responsive: the field renders from `uiState.query`, and
     * that has to update on the keystroke, not [SEARCH_DEBOUNCE_MS] later once the scan has run.
     */
    fun setQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value) }
    }

    fun setLevel(value: LevelFilter) {
        level.value = value
        _uiState.update { it.copy(level = value) }
    }

    /**
     * Writes the lines currently on screen to a location the user picked.
     *
     * The destination is a `Uri` from `ACTION_CREATE_DOCUMENT` rather than a path this app chose.
     * The contribution wrote `<name>_filtered.txt` next to the original, inside the saved-logs
     * directory — which made a filtered excerpt reappear as a new capture in the list, and put a
     * second copy of sensitive output on disk without asking.
     */
    fun export(destination: Uri) {
        if (_uiState.value.isExporting) return
        val lines = _uiState.value.lines
        if (lines.isEmpty()) {
            _messages.tryEmit("There is nothing to export.")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(destination)
                        ?: error("the chosen location refused to open")
                    stream.bufferedWriter().use { writer ->
                        lines.forEach { line ->
                            writer.append(line.text)
                            writer.append('\n')
                        }
                    }
                }
            }
            _uiState.update { it.copy(isExporting = false) }
            _messages.tryEmit(
                result.fold(
                    onSuccess = { "Exported ${lines.size} lines." },
                    onFailure = { "Export failed: ${it.message ?: it.javaClass.simpleName}" },
                )
            )
        }
    }

    /**
     * Streams the file, keeping only the last [MAX_LINES] lines.
     *
     * The tail rather than the head: a capture is stopped *after* the thing you were looking for
     * happened, so the end of the file is the part worth showing. `useLines` means the whole file is
     * read but never held — only the deque is retained, and it is bounded.
     *
     * Priorities are parsed after the read, over the surviving window only. Parsing during the read
     * would run the regex over every line of a 500 MB file to then throw almost all of them away.
     */
    private suspend fun readTail(): Loaded = withContext(Dispatchers.IO) {
        val window = ArrayDeque<String>()
        var total = 0
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                total++
                if (window.size == MAX_LINES) window.removeFirst()
                window.addLast(raw.trimEnd())
                // A tight read loop has no suspension point of its own, so cancellation — the user
                // pressing back on a huge file — would otherwise not be noticed until EOF.
                if (total % CANCEL_CHECK_INTERVAL == 0) coroutineContext.ensureActive()
            }
        }
        val firstNumber = total - window.size + 1
        Loaded(
            lines = window.mapIndexed { index, text ->
                LogLine(number = firstNumber + index, text = text, level = LogLevel.of(text))
            },
            totalLines = total,
            dropped = total - window.size,
        )
    }

    /**
     * Derives what the list shows from (window, level, query). Pure, and called on [Dispatchers.Default].
     *
     * `contains(ignoreCase = true)` region-matches in place. The contribution called `.lowercase()`
     * on every line for every search, allocating a second copy of the whole log per keystroke — on
     * the main thread.
     */
    private fun project(log: Loaded, level: LevelFilter, query: String): Pair<List<LogLine>, List<Int>> {
        val min = level.min
        // Reused rather than copied when the filter admits everything, which is the common case.
        val lines = if (min == null) log.lines else log.lines.filter { it.level >= min }
        val matches = if (query.isBlank()) {
            emptyList()
        } else {
            lines.indices.filter { lines[it].text.contains(query, ignoreCase = true) }
        }
        return lines to matches
    }
}
