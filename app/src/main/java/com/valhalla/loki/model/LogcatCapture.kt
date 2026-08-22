package com.valhalla.loki.model

import android.util.Log
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.asFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

private const val TAG = "LogcatCapture"

/** Which privilege a capture actually used. */
enum class CaptureMode { NONE, READ_LOGS, ROOT }

/**
 * Streams one app's logcat output into a file.
 *
 * Two privilege paths, in preference order:
 *  - **READ_LOGS** granted directly (Shizuku or ADB) — a plain [ProcessBuilder], no shell at all.
 *  - **Root** — a *dedicated* Odin shell, not the process-wide main shell.
 *
 * The dedicated shell is not a stylistic choice. `logcat` without `-d` never exits, Odin cannot
 * interrupt an in-flight command, and cancelling an `asFlow()` collector only stops emission while
 * the command keeps draining. Closing a shell we own is the one thing that actually kills it — and
 * owning it also avoids `Shell.cmd(...)`, whose pending-job retry would rebuild a root shell and
 * restart `logcat` at the very moment we were trying to stop it.
 *
 * Both paths filter by the target's **uid**, resolved from `PackageManager`. That is not
 * interchangeable with filtering by pid: `/proc` is mounted `hidepid=invisible`, so a normal app
 * cannot see another app's pid at all and `pidof` returns nothing every single time — which is
 * exactly how this used to fail, silently falling through to a filter that matched nothing and
 * producing a running capture with a zero-byte file. A uid needs no `/proc` access, survives the
 * target restarting, and covers every process the app runs under one filter.
 *
 * Two limits worth knowing: packages that share a `sharedUserId` share a uid, so capturing one
 * captures its siblings; and processes the app runs under an *isolated* uid (Chrome's renderers,
 * for instance) fall outside it, because the platform gives them a uid of their own.
 *
 * Only one capture runs at a time; [start] on a busy instance is ignored.
 */
class LogcatCapture(
    private val permissionManager: PermissionManager,
    private val packages: Packages
) {

    private var job: Job? = null

    /**
     * Set the moment [stop] is asked for, cleared by [start].
     *
     * Destroying `logcat` makes it exit 143, so without this the exit-code check below would cry
     * failure on every ordinary stop and become the kind of warning people learn to ignore.
     */
    @Volatile
    private var stopping = false

    /** The shell owning the streaming `logcat`. Closing it is how the capture stops. */
    private var captureShell: Shell? = null

    /** The unprivileged `logcat` process, when running via READ_LOGS. */
    private var captureProcess: Process? = null

    val isCapturing: Boolean
        get() = job?.isActive == true

    /**
     * Begins capturing [appInfo]'s logs into [outputFile].
     *
     * [onExit] is invoked on the main thread exactly once, when the capture ends for any reason —
     * including "no privilege was available", so a caller that starts a foreground service can
     * always rely on it to tear that service down.
     */
    @Synchronized
    fun start(
        appInfo: AppInfo,
        scope: CoroutineScope,
        outputFile: File,
        onExit: () -> Unit
    ) {
        if (isCapturing) {
            Log.w(TAG, "start() ignored: a capture is already running")
            return
        }

        stopping = false
        job = scope.launch(Dispatchers.IO) {
            // Resolved once, here, so that neither capture function ever sees the package name.
            // What reaches the privileged command line is an integer that came from
            // PackageManager, which is what makes AGENTS.md rule 1 hold by construction rather
            // than by remembering to escape.
            val uid = packages.getApplicationInfoOrNull(appInfo.packageName)?.uid
            var mode = CaptureMode.NONE
            try {
                mode = when {
                    uid == null -> {
                        Log.e(TAG, "no uid for ${appInfo.packageName}; not installed for this user")
                        CaptureMode.NONE
                    }

                    permissionManager.hasReadLogsPermission() -> {
                        captureWithReadLogs(uid, outputFile)
                        CaptureMode.READ_LOGS
                    }

                    permissionManager.isRootAvailable() ->
                        if (captureWithRoot(uid, outputFile)) CaptureMode.ROOT else CaptureMode.NONE

                    else -> {
                        Log.w(TAG, "no privilege available; nothing to capture")
                        CaptureMode.NONE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
            } finally {
                release()
                // Inside the `finally`, and before the hop below: stop() cancels this job, so
                // `withContext` rethrows on the way out and anything placed after the try/finally
                // never runs. This line used to sit there, which meant the one diagnostic saying
                // which privilege a capture actually used was silent on the normal stop path —
                // the only path it really needed to cover.
                Log.d(TAG, "capture of ${appInfo.packageName} ended (mode=$mode)")
                // NonCancellable: this runs on the cancellation path too, and the caller still
                // needs its teardown callback.
                withContext(NonCancellable + Dispatchers.Main) { onExit() }
            }
        }
    }

    /**
     * Stops the running capture.
     *
     * Closes the shell (or destroys the process) *before* cancelling the coroutine: cancelling
     * first would tear down the collector while `logcat` kept running with nothing reading it.
     */
    @Synchronized
    fun stop() {
        stopping = true
        release()
        job?.cancel()
        job = null
    }

    /**
     * Takes ownership of a privileged handle, or rejects it because we have already been stopped.
     *
     * Without this, [stop] landing in the window between creating a handle and storing it would
     * leave an orphan: the coroutine is cancelled but a blocking read never notices, so a root
     * shell or a `logcat` process would survive with nothing able to kill it. `@Synchronized`
     * also orders this after [start] has assigned `job`, so a handle adopted during a legitimate
     * start is never mistaken for a late one.
     */
    @Synchronized
    private fun adopt(shell: Shell? = null, process: Process? = null): Boolean {
        if (job?.isActive != true) {
            Log.d(TAG, "capture already stopped; discarding the handle we just opened")
            shell?.let { runCatching { it.close() } }
            process?.let { runCatching { it.destroy() } }
            return false
        }
        shell?.let { captureShell = it }
        process?.let { captureProcess = it }
        return true
    }

    /** Idempotent teardown of whatever privileged handle the capture is holding. */
    @Synchronized
    private fun release() {
        captureShell?.let {
            runCatching { it.close() }
                .onFailure { e -> Log.w(TAG, "closing capture shell failed", e) }
        }
        captureShell = null

        captureProcess?.let { runCatching { it.destroy() } }
        captureProcess = null
    }

    /**
     * Runs [block], treating the [IOException] that [release] provokes as the ordinary end of a
     * capture rather than a failure.
     *
     * Stopping means closing, from another thread, the very handle the capture is parked reading.
     * A read blocked in `forEachLine` — or in Odin's collector — surfaces that as
     * `InterruptedIOException: read interrupted by close() on another thread`, not as EOF. That is
     * the teardown working exactly as designed, but it used to reach the `catch` in [start] and
     * print `E LogcatCapture: capture failed` with a full stack trace on *every* stop, which reads
     * like a crash in a bug report. It also threw away the [CaptureMode] the capture had really
     * run in, because the exception unwound past the point where that value is produced.
     *
     * The guard is deliberately narrow: without [stopping] set, an [IOException] here is a genuine
     * read failure and still propagates.
     */
    private inline fun drainUntilClosed(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            if (!stopping) throw e
            Log.d(TAG, "capture stream closed by stop()")
        }
    }

    // --- READ_LOGS path -----------------------------------------------------------------------

    /**
     * Capture with the READ_LOGS permission held directly. An argv list, so there is no shell
     * involved and nothing to quote.
     *
     * `-T 1` replaces what used to be a preceding `logcat -c`. There is only one logcat buffer and
     * every reader on the device shares it, so clearing it to get a clean start threw away log
     * data that belonged to everyone else — including, on a slow start, the first lines of the very
     * capture the user asked for. `-T 1` begins one line back and destroys nothing.
     *
     * stderr is deliberately *not* folded into the capture. A user's log file should contain log
     * lines; a diagnostic from our own tooling in the middle of it is corruption, and it is also
     * how a permission failure would come to look like ordinary content.
     */
    private fun captureWithReadLogs(uid: Int, outputFile: File) {
        val process = ProcessBuilder("logcat", "-T", "1", "--uid=$uid").start()
        if (!adopt(process = process)) return
        try {
            drainUntilClosed {
                outputFile.bufferedWriter().use { writer ->
                    process.inputStream.bufferedReader()
                        .forEachLine { line -> writer.appendLog(line) }
                }
                reportExit(process)
            }
        } finally {
            process.destroy()
        }
    }

    /**
     * Rule 3: never assume a privileged command succeeded.
     *
     * A `logcat` refused the buffer, or handed an option this platform version does not know,
     * exits immediately and non-zero — leaving a capture file that is merely *empty*, which is
     * indistinguishable from a quiet app unless something says otherwise. Nothing did, and that
     * cost a whole device session to find.
     *
     * stderr is drained here rather than concurrently, which in principle could block `logcat` on a
     * full pipe. It does not in practice: what `logcat` writes there is a line or two immediately
     * before exiting, not a running commentary, so the buffer is nowhere near the 64 KB it would
     * take to stall.
     */
    private fun reportExit(process: Process) {
        val code = process.waitFor()
        if (code == 0 || stopping) return
        val stderr = runCatching {
            process.errorStream.bufferedReader().use { it.readText() }
        }.getOrNull()?.trim().orEmpty()
        Log.w(TAG, "logcat exited $code" + if (stderr.isEmpty()) "" else ": $stderr")
    }

    // --- Root path ----------------------------------------------------------------------------

    /**
     * Capture through a dedicated root shell.
     *
     * The command is a fixed string plus an integer. Root previously got a different filter from
     * the READ_LOGS path — `pidof` works under root, so this branch had a working pid lookup and a
     * `logcat | grep -F -- <package>` fallback behind it — and that divergence is gone: the uid
     * filter is correct under both privileges, so both run the same command and there is one
     * behaviour to reason about rather than two. It also means AGENTS.md rule 1 stops depending on
     * `escapeForShell`, because there is no longer any caller-supplied text in the string at all.
     *
     * The probe below is this branch's half of AGENTS.md rule 3. [reportExit] covers the READ_LOGS
     * path because a [Process] hands back an exit code; `asFlow()` does not. Odin closes that flow
     * with a `NoShellException` only for `JOB_NOT_EXECUTED` — a dead shell — so a `logcat` that ran
     * and *failed*, exit 1, completes the flow normally and empty. The capture then ends with a
     * zero-length file and nothing anywhere saying why, which is indistinguishable from a quiet app.
     *
     * Returns whether a capture actually ran, so the caller does not record [CaptureMode.ROOT] for a
     * privilege it never got to use. `mode` is the one line of a bug report that says which path was
     * taken; claiming ROOT for a shell that refused the command sends the next reader after the
     * wrong bug.
     */
    private suspend fun captureWithRoot(uid: Int, outputFile: File): Boolean {
        val shell = openRootShell() ?: return false
        if (!adopt(shell = shell)) return false

        // Same options as the stream, minus the follow: if `logcat` is going to reject the uid
        // filter or the buffer under this privilege, it rejects a one-line dump too, and here the
        // exit code is legible. `-t` implies `-d`, so this returns immediately instead of following.
        // Runs on the capture's own shell before the stream starts, so nothing races it.
        val probe = shell.newJob().add("logcat -d -t 1 --uid=$uid").exec()
        if (!probe.isSuccess) {
            val why = probe.stderr.joinToString(" ").trim()
            Log.e(TAG, "root logcat probe exited ${probe.code}" + if (why.isEmpty()) "" else ": $why")
            return false
        }

        drainUntilClosed {
            outputFile.bufferedWriter().use { writer ->
                shell.newJob().add("logcat -T 1 --uid=$uid").asFlow().collect { line ->
                    // logcat writes to stdout; anything on stderr is the shell complaining, and it
                    // belongs in our own log rather than mixed into the user's capture.
                    if (line.isError) Log.w(TAG, "logcat stderr: ${line.text}")
                    else writer.appendLog(line.text)
                }
            }
        }
        return true
    }

    /**
     * Opens a root shell for exclusive use by this capture.
     *
     * `build("su")` deliberately bypasses Odin's mount-master → su → sh fallback ladder: a
     * capture that silently degraded to an unprivileged `sh` would produce a log file that looks
     * perfectly normal and contains almost nothing. Better to fail here and say so.
     */
    private fun openRootShell(): Shell? = try {
        val shell = Shell.Builder.create().build("su")
        if (shell.isRoot) {
            shell
        } else {
            Log.e(TAG, "shell is not root; refusing to capture unprivileged")
            runCatching { shell.close() }
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "could not open a root shell", e)
        null
    }
}

/**
 * Writes one log line and flushes it. The flush is deliberate: the UI tails this file live, and a
 * buffered writer would leave a quiet app looking like a broken capture for kilobytes at a time.
 */
private fun BufferedWriter.appendLog(line: String) {
    write(line)
    newLine()
    flush()
}
