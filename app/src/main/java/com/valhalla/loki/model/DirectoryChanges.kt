package com.valhalla.loki.model

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * The events that can change what a two-level listing of a directory looks like.
 *
 * `CLOSE_WRITE` is in here because the saved-logs list shows file sizes: a capture creates its file
 * at the start and closes it at the end, and without the close the list would keep showing the size
 * it had when it appeared.
 */
private const val WATCH_MASK = FileObserver.CREATE or
    FileObserver.DELETE or
    FileObserver.MOVED_TO or
    FileObserver.MOVED_FROM or
    FileObserver.DELETE_SELF or
    FileObserver.MOVE_SELF or
    FileObserver.CLOSE_WRITE

/**
 * Emits every time something is created, deleted, moved or finished being written in this directory
 * **or in any directory directly inside it**.
 *
 * Two levels, not one, because logs live at `logs/<package>/<timestamp>.log`: inotify watches a
 * single directory rather than a tree, so an observer on `logs/` alone — which is what the
 * contribution registered — never sees the log file at all. It only ever notices the package
 * directory appearing (`docs/review-anon-contribution.md` §1.4).
 *
 * The flow does not debounce. Collectors decide that, once, rather than each event spawning its own
 * `delay`.
 */
fun File.directoryChanges(): Flow<Unit> = callbackFlow {
    // inotify cannot watch a path that does not exist, and the logs directory only appears when the
    // first capture is saved. One mkdirs here is what makes that first capture show up without a
    // manual refresh.
    mkdirs()
    val observer = ShallowTreeObserver(this@directoryChanges) { trySend(Unit) }
    observer.start()
    awaitClose { observer.stop() }
}
    // Conflated, and this buffer fuses into the channel above: `trySend` is called from
    // FileObserver's own thread and must never fail, or a change that lands while the collector is
    // busy would be the *last* one and the listing would stay stale forever. Coalescing a burst
    // into one signal is exactly what is wanted.
    .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // mkdirs() and the listFiles() below are disk I/O; neither belongs on the collector's thread.
    .flowOn(Dispatchers.IO)

/**
 * A [FileObserver] on [root] plus one on each of its immediate subdirectories, kept in step as
 * subdirectories come and go.
 *
 * [onChange] is invoked on FileObserver's thread, so it must be cheap and thread-safe.
 */
private class ShallowTreeObserver(
    private val root: File,
    private val onChange: () -> Unit,
) {

    private val lock = Any()

    /** Keyed by absolute path. Guarded by [lock] — events arrive on FileObserver's thread. */
    private val observers = mutableMapOf<String, FileObserver>()

    private var watching = false

    fun start() = synchronized(lock) {
        watching = true
        resync()
    }

    fun stop() = synchronized(lock) {
        watching = false
        observers.values.forEach(FileObserver::stopWatching)
        observers.clear()
    }

    private fun onEvent() {
        // A new package directory needs its own observer before anything is written into it, and a
        // deleted one should stop being watched. Doing this here rather than on a timer is why the
        // watch stays correct for the whole life of the screen.
        synchronized(lock) { if (watching) resync() }
        onChange()
    }

    private fun resync() {
        val wanted = buildMap {
            put(root.absolutePath, root)
            root.listFiles()?.forEach { if (it.isDirectory) put(it.absolutePath, it) }
        }
        (observers.keys - wanted.keys).forEach { gone -> observers.remove(gone)?.stopWatching() }
        wanted.forEach { (path, dir) ->
            if (path !in observers) {
                observers[path] = observerFor(dir).also { it.startWatching() }
            }
        }
    }

    private fun observerFor(dir: File): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) = this@ShallowTreeObserver.onEvent()
            }
        } else {
            // FileObserver(File, Int) arrived in API 29. minSdk is 28, so on Android 9 the only
            // constructor that exists is the deprecated String one — and calling the File overload
            // unconditionally, as the contribution did, is a NoSuchMethodError on launch for every
            // user on that release (§1.4). Deprecated is not the same as absent.
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) = this@ShallowTreeObserver.onEvent()
            }
        }
}
