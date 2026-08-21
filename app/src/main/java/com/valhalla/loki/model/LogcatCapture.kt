package com.valhalla.loki.model

import android.content.Context
import android.util.Log
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.asFlow
import com.valhalla.superuser.ktx.await
import com.valhalla.superuser.utils.escapeForShell
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
 * Only one capture runs at a time; [start] on a busy instance is ignored.
 */
class LogcatCapture(
    private val context: Context,
    private val permissionManager: PermissionManager
) {

    private var job: Job? = null

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

        job = scope.launch(Dispatchers.IO) {
            val mode = try {
                when {
                    permissionManager.hasReadLogsPermission() -> {
                        captureWithReadLogs(appInfo.packageName, outputFile)
                        CaptureMode.READ_LOGS
                    }

                    permissionManager.isRootAvailable() -> {
                        captureWithRoot(appInfo.packageName, outputFile)
                        CaptureMode.ROOT
                    }

                    else -> {
                        Log.w(TAG, "no privilege available; nothing to capture")
                        CaptureMode.NONE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "capture failed", e)
                CaptureMode.NONE
            } finally {
                release()
                // NonCancellable: this runs on the cancellation path too, and the caller still
                // needs its teardown callback.
                withContext(NonCancellable + Dispatchers.Main) { onExit() }
            }
            Log.d(TAG, "capture of ${appInfo.packageName} ended (mode=$mode)")
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

    // --- READ_LOGS path -----------------------------------------------------------------------

    /**
     * Capture with the READ_LOGS permission held directly. Every command is an argv list, so
     * there is no shell to interpret [packageName] and no injection surface at all.
     */
    private fun captureWithReadLogs(packageName: String, outputFile: File) {
        val pid = readPid(listOf("pidof", packageName))

        // Start from "now" rather than replaying whatever was already buffered.
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
            .onFailure { Log.w(TAG, "logcat -c failed", it) }

        val argv = if (pid != null) {
            listOf("logcat", "--pid=$pid")
        } else {
            // Weak fallback: -s filters by log *tag*, which only matches apps that happen to tag
            // with their package name. It is what we can do before the process exists.
            Log.d(TAG, "no pid for $packageName; falling back to tag filter")
            listOf("logcat", "-s", packageName)
        }

        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        if (!adopt(process = process)) return
        try {
            outputFile.bufferedWriter().use { writer ->
                process.inputStream.bufferedReader().forEachLine { line -> writer.appendLog(line) }
            }
        } finally {
            process.destroy()
        }
    }

    private fun readPid(argv: List<String>): String? = runCatching {
        val process = ProcessBuilder(argv).start()
        val first = process.inputStream.bufferedReader().use { it.readLine() }
        process.waitFor()
        first.toPid()
    }.getOrNull()

    // --- Root path ----------------------------------------------------------------------------

    /** Capture through a dedicated root shell. */
    private suspend fun captureWithRoot(packageName: String, outputFile: File) {
        val shell = openRootShell() ?: return
        if (!adopt(shell = shell)) return

        // AGENTS.md rule 1: never interpolate unvalidated input into a shell string. packageName
        // comes from PackageManager today, but escaping it means that stays safe if it ever
        // stops being ours.
        val quoted = packageName.escapeForShell()

        // A missing pid is normal (the app may not be running yet), not a failure.
        val pidResult = shell.newJob().add("pidof $quoted").await()
        val pid = pidResult.stdout.firstOrNull().toPid()

        val clear = shell.newJob().add("logcat -c").await()
        if (!clear.isSuccess) {
            // Rule 3: don't assume a privileged command succeeded. Branch on the code, never on
            // whether stderr happens to be populated.
            Log.w(TAG, "logcat -c exited ${clear.code}: ${clear.stderr.joinToString("; ")}")
        }

        val command = if (pid != null) {
            "logcat --pid=$pid"
        } else {
            // -F treats the package name as a literal (dots are regex wildcards otherwise) and
            // `--` stops a name that begins with '-' being read as an option.
            Log.d(TAG, "no pid for $packageName; falling back to a literal grep")
            "logcat | grep -F -- $quoted"
        }

        outputFile.bufferedWriter().use { writer ->
            shell.newJob().add(command).asFlow().collect { line ->
                // logcat writes to stdout; anything on stderr is the shell complaining, and it
                // belongs in our own log rather than mixed into the user's capture.
                if (line.isError) Log.w(TAG, "logcat stderr: ${line.text}")
                else writer.appendLog(line.text)
            }
        }
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

/** First whitespace-separated token of `pidof` output, if it really is a pid. */
private fun String?.toPid(): String? = this
    ?.trim()
    ?.substringBefore(' ')
    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
