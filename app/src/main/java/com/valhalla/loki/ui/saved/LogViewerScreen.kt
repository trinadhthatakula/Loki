package com.valhalla.loki.ui.saved

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.asgard.components.AsgardEmptyState
import com.valhalla.loki.ui.theme.logLevelColor
import com.valhalla.loki.ui.theme.monoFontFamily
import com.valhalla.loki.ui.widgets.formatCount
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How much of the log a single "Copy" can put on the clipboard. See [copyToClipboard]. */
private const val MAX_CLIP_CHARS = 100_000

/** How close to an edge a drag has to get before the list starts scrolling itself. */
private val AUTO_SCROLL_EDGE = 96.dp

/** Top speed of that self-scroll, in dp per second, reached at the very edge. */
private const val AUTO_SCROLL_DP_PER_SECOND = 1_400f

/**
 * One saved log, read.
 *
 * Rendered in place of the saved-logs list rather than as its own destination, because Loki still
 * navigates by pager; step 9 turns this into a real route and this composable does not have to
 * change for that.
 *
 * `key` on the ViewModel is the file path, so opening a second log builds a second ViewModel instead
 * of showing the first one's lines while the new file loads.
 */
@Composable
fun LogViewerScreen(
    logFile: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogViewerViewModel = koinViewModel(
        key = logFile.absolutePath,
        parameters = { parametersOf(logFile) },
    ),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    // Captured so the null check below actually narrows the type — `state` is a delegated read, and
    // two reads of it are not the same value as far as the compiler is concerned.
    val error = state.error

    // Staged, not a single hop out. Someone who has typed a search and presses back means "leave the
    // search", not "close the file". It also stops one stray back press from discarding the viewer on
    // devices whose floating IME does not consume BACK itself — which is how a Pixel Fold behaves, so
    // the single-hop version threw the whole log away on the first press after typing a query.
    BackHandler { if (state.query.isEmpty()) onBack() else viewModel.setQuery("") }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Writes wherever the user points it. Nothing is exported to a path Loki chose.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let(viewModel::export) }

    Column(modifier = modifier.fillMaxSize()) {
        ViewerHeader(
            logFile = logFile,
            enabled = !state.isLoading && error == null,
            isExporting = state.isExporting,
            onBack = onBack,
            onCopy = {
                val copied = context.copyToClipboard(state.lines)
                Toast.makeText(context, copyMessage(copied, state.lines.size), Toast.LENGTH_SHORT)
                    .show()
            },
            onExport = { exportLauncher.launch("${logFile.nameWithoutExtension}-excerpt.txt") },
        )

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null -> AsgardEmptyState(
                text = "This log could not be opened",
                description = error,
                icon = Icons.Filled.Warning,
                modifier = Modifier.fillMaxSize(),
            )

            else -> LogBody(
                state = state,
                onLevelChange = viewModel::setLevel,
                onQueryChange = viewModel::setQuery,
            )
        }
    }
}

@Composable
private fun ViewerHeader(
    logFile: File,
    enabled: Boolean,
    isExporting: Boolean,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    val title = remember(logFile) { logFile.readableTitle() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to saved logs")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            logFile.parentFile?.name?.let { packageName ->
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        IconButton(onClick = onCopy, enabled = enabled) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the lines on screen")
        }
        IconButton(onClick = onExport, enabled = enabled && !isExporting) {
            if (isExporting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Save, contentDescription = "Export the lines on screen")
            }
        }
    }
}

@Composable
private fun LogBody(
    state: LogViewerUiState,
    onLevelChange: (LevelFilter) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // Which of `state.matches` the stepper is on. Screen state, not ViewModel state: it only means
    // anything alongside a scroll position, and that lives here.
    var currentMatch by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.matches) { currentMatch = 0 }
    // One effect owns the scrolling, keyed on both, so a new result set and a step through the
    // existing one cannot both try to scroll at once.
    LaunchedEffect(currentMatch, state.matches) {
        state.matches.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it) }
    }

    // Pointer state for the drag-to-select auto-scroll. Written from the pointer loop and read only
    // inside the frame loop below, so neither write recomposes anything.
    var listHeightPx by remember { mutableIntStateOf(0) }
    var pointerY by remember { mutableFloatStateOf(-1f) }
    var isPointerDown by remember { mutableStateOf(false) }

    LaunchedEffect(isPointerDown, listHeightPx) {
        if (!isPointerDown || listHeightPx == 0) return@LaunchedEffect
        val edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
        val maxPxPerSecond = with(density) { AUTO_SCROLL_DP_PER_SECOND.dp.toPx() }
        var previousFrame = 0L
        var delta = 0f
        // withFrameNanos, not delay(16): it is the frame clock rather than an approximation of one,
        // it stops costing anything when the window is not drawing, and pairing it with the real
        // elapsed time makes the scroll speed the same on a 60 Hz phone and a 120 Hz foldable. The
        // contribution's fixed px-per-frame step ran twice as fast on this emulator as on a phone.
        while (true) {
            withFrameNanos { now ->
                val seconds =
                    if (previousFrame == 0L) 0f else (now - previousFrame) / 1_000_000_000f
                previousFrame = now
                delta = autoScrollSpeed(pointerY, listHeightPx.toFloat(), edgePx) *
                        maxPxPerSecond * seconds
            }
            if (delta != 0f) listState.scrollBy(delta)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.level == filter,
                    onClick = { onLevelChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        SearchRow(
            query = state.query,
            matchCount = state.matches.size,
            currentMatch = currentMatch,
            onQueryChange = onQueryChange,
            onStep = { step ->
                val next = currentMatch + step
                if (next in state.matches.indices) {
                    currentMatch = next
                }
            },
        )

        // Only reachable via the level filter or a genuinely empty capture — a query that matches
        // nothing shows "0" in the stepper and leaves the lines on screen, because losing your place
        // in a log because you mistyped is worse than the empty result being subtle.
        if (state.lines.isEmpty()) {
            AsgardEmptyState(
                text = if (state.totalLines == 0) "This log is empty" else "Nothing at this level",
                description = if (state.totalLines == 0) {
                    "The capture produced no output at all."
                } else {
                    "None of this log's ${formatCount(state.totalLines)} lines are " +
                            "${state.level.label.removeSuffix("+").lowercase()} or above. Try All."
                },
                icon = Icons.Filled.Search,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        val numberStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = monoFontFamily,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lineStyle = remember {
            TextStyle(fontFamily = monoFontFamily, fontSize = 12.sp, lineHeight = 18.sp)
        }
        // Measured rather than guessed at n × 7.dp, so the gutter is exactly as wide as the widest
        // line number at the reader's font scale and the numbers line up at any of them.
        val measurer = rememberTextMeasurer()
        val widestNumber = state.lines.lastOrNull()?.number ?: 0
        val gutterWidth = remember(measurer, numberStyle, widestNumber) {
            with(density) {
                measurer.measure(AnnotatedString(widestNumber.toString()), numberStyle)
                    .size.width.toDp()
            }
        }
        // The file line number of the match the stepper is on, resolved once rather than per row.
        // getOrNull twice because `matches` and `lines` are published together but a recomposition
        // can still read a stepper index from before the last result set.
        val currentMatchNumber = state.matches.getOrNull(currentMatch)
            ?.let { state.lines.getOrNull(it)?.number }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { listHeightPx = it.height }
                // Observed on the Final pass so SelectionContainer's own gesture detector claims
                // the events first; this only watches where the finger is, and consumes nothing.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull()
                            pointerY = change?.position?.y ?: -1f
                            isPointerDown = change?.pressed == true
                        }
                    }
                },
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    items(state.lines, key = { it.number }) { line ->
                        LogLineRow(
                            line = line,
                            query = state.query,
                            isCurrentMatch = line.number == currentMatchNumber,
                            gutterWidth = gutterWidth,
                            numberStyle = numberStyle,
                            lineStyle = lineStyle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    matchCount: Int,
    currentMatch: Int,
    onQueryChange: (String) -> Unit,
    onStep: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search this log") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Clearing focus is what actually lowers the keyboard, and with the keyboard up a phone
            // viewport shows almost none of the log. LocalFocusManager rather than
            // LocalSoftwareKeyboardController because it needs no opt-in.
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        if (query.isNotBlank()) {
            Text(
                text = if (matchCount == 0) "0" else "${currentMatch + 1}/$matchCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onStep(-1) }, enabled = currentMatch > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = { onStep(1) }, enabled = currentMatch < matchCount - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
        }
    }
}

@Composable
private fun LogLineRow(
    line: LogLine,
    query: String,
    isCurrentMatch: Boolean,
    gutterWidth: Dp,
    numberStyle: TextStyle,
    lineStyle: TextStyle,
) {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val rowBackground =
        if (isCurrentMatch) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground),
    ) {
        Text(
            text = line.number.toString(),
            style = numberStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(gutterWidth),
        )
        Text(
            text = annotate(line.text, query, highlight),
            style = lineStyle.copy(color = MaterialTheme.colorScheme.logLevelColor(line.level)),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

/**
 * The line with every occurrence of [query] given a [highlight] background.
 *
 * Keyed on all three inputs and only ever built for lines that are actually on screen, so a 20,000
 * line log costs about forty of these per frame rather than 20,000.
 */
@Composable
private fun annotate(text: String, query: String, highlight: Color): AnnotatedString =
    remember(text, query, highlight) {
        if (query.isBlank()) return@remember AnnotatedString(text)
        buildAnnotatedString {
            append(text)
            var index = text.indexOf(query, startIndex = 0, ignoreCase = true)
            while (index >= 0) {
                addStyle(
                    SpanStyle(background = highlight, fontWeight = FontWeight.Bold),
                    index,
                    index + query.length,
                )
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
    }

/**
 * How fast, as a fraction of the top speed, a drag at [y] should scroll a list [height] tall.
 *
 * Negative is up. Zero everywhere except within [edge] of either end, and ramping linearly to ±1 at
 * the very edge, so the list creeps when the finger is near the boundary and races when it is past
 * it. `y < 0` is "no pointer", which is not the same as "pointer at the top".
 */
private fun autoScrollSpeed(y: Float, height: Float, edge: Float): Float = when {
    y < 0f || height <= 0f -> 0f
    y < edge -> -((edge - y) / edge).coerceIn(0f, 1f)
    y > height - edge -> ((y - (height - edge)) / edge).coerceIn(0f, 1f)
    else -> 0f
}

/** `20 Aug 2025, 07:56:40 PM`, or the bare file name if it is not a timestamp after all. */
private fun File.readableTitle(): String {
    val millis = nameWithoutExtension.toLongOrNull() ?: return name
    return SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date(millis))
}

/** `1,204 of 3,908 lines · last 20,000 of 143,912 in the file` — whichever parts apply. */
private fun LogViewerUiState.summary(): String {
    val retained = totalLines - droppedLines
    return buildString {
        append(formatCount(lines.size))
        if (lines.size != retained) {
            append(" of ")
            append(formatCount(retained))
        }
        append(if (lines.size == 1) " line" else " lines")
        if (isTruncated) {
            append(" · last ")
            append(formatCount(retained))
            append(" of ")
            append(formatCount(totalLines))
            append(" in the file")
        }
    }
}

/**
 * Copies as much of [lines] as the clipboard will take, and reports how many made it.
 *
 * Two fixes over the contribution, which copied the entire filtered log unconditionally. The cap is
 * the load-bearing one: the clipboard crosses a Binder transaction, and a few megabytes of logcat
 * throws `TransactionTooLargeException` — so on a real log the copy button did nothing at all.
 * [ClipDescription.EXTRA_IS_SENSITIVE] is the other: without it Android 13+ renders a preview of the
 * copied text in a system popup, putting whatever tokens and URLs the log captured on screen.
 */
private fun Context.copyToClipboard(lines: List<LogLine>): Int {
    val builder = StringBuilder()
    var copied = 0
    for (line in lines) {
        if (builder.length + line.text.length + 1 > MAX_CLIP_CHARS) break
        builder.append(line.text).append('\n')
        copied++
    }
    val clip = ClipData.newPlainText("Loki log", builder.toString())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    return copied
}

private fun copyMessage(copied: Int, total: Int): String = when {
    copied == 0 -> "That first line is too long for the clipboard. Use Export."
    copied < total -> "Copied the first ${formatCount(copied)} of ${formatCount(total)} lines — " +
            "the rest would not fit on the clipboard. Use Export for all of it."

    else -> "Copied ${formatCount(copied)} lines."
}
