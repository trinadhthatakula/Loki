package com.valhalla.loki.model

import android.content.Context
import android.util.Log.e
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.valhalla.loki.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Future

//pid=$(adb shell ps | grep <package name> | cut -c11-15) ; adb logcat | grep $pid
private const val TAG = "SuCli"

object SuCli {
    val SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) SuCli.GLOBAL_MNT_SHELL else {
        SuCli.SHELL
    }
}

@Suppress("unused")
inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build("su")
        } else {
            builder.build("su", "-mm")
        }
    } catch (e: Throwable) {
        e(TAG, "su failed: ", e)
        builder.build("sh")
    }
}

fun rootAvailable(): Boolean {
    try {
        val shell = getRootShell()
        return shell.isRoot
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}

/**
 * A sealed class to represent a running logcat job, abstracting away
 * whether it's a root process managed by libsu or a standard process.
 */
private sealed class RunningJob {
    data class RootJob(val future: Future<Shell.Result>) : RunningJob()
    data class PermissionJob(val process: Process) : RunningJob()
}

private var currentJob: RunningJob? = null

/**
 * A single function to stop the currently active logger, regardless of its type.
 */
var stopLogger: (() -> Unit)? = {
    when (val job = currentJob) {
        is RunningJob.RootJob -> if (!job.future.isDone) job.future.cancel(true)
        is RunningJob.PermissionJob -> job.process.destroy()
        null -> { /* No job to stop */ }
    }
    currentJob = null
}

/**
 * The main entry point for fetching logs.
 * This function checks for the best available permission and delegates to the
 * appropriate implementation.
 *
 * @param context The application context, needed for permission checks.
 * @param scope The coroutine scope to run the logging task in.
 * @param outputFile The file to write the logs to.
 * @param onExit A callback to be invoked when the logging process terminates.
 */
fun AppInfo.fetchLogs(
    context: Context,
    scope: CoroutineScope,
    outputFile: File,
    onExit: () -> Unit
) {
    // Decide which method to use based on available permissions.
    when {
        PermissionManager.hasReadLogsPermission(context) ->
            fetchLogsWithPermission(scope, outputFile, onExit)

        PermissionManager.isRootAvailable() ->
            fetchLogs(scope, outputFile, onExit)

        else -> {
            // No permissions available, exit gracefully.
            scope.launch { onExit() }
        }
    }
}

/**
 * Private implementation for fetching logs when READ_LOGS permission is granted.
 * Uses a standard Java/Kotlin Process.
 */
private fun AppInfo.fetchLogsWithPermission(
    scope: CoroutineScope,
    outputFile: File,
    onExit: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        var logcatProcess: Process? = null
        try {
            // Get the PID using a normal shell command. No root needed.
            val pidProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof $packageName"))
            val pId = pidProcess.inputStream.bufferedReader().readLine()?.trim() ?: ""
            pidProcess.waitFor()

            // --- CORRECTED LOGIC ---
            // Choose the best logcat command based on whether we found a PID.
            val logCommand = if (pId.isNotEmpty()) {
                // If we have a PID, use it. This is the most accurate filter.
                "logcat --pid=$pId"
            } else {
                // If no PID is found (app might not be running yet), fall back to filtering by log tag.
                // The log tag is usually the package name. This is less precise but will still capture logs
                // if the app starts later.
                "logcat -s $packageName"
            }

            // Clear logs before starting
            Runtime.getRuntime().exec("logcat -c").waitFor()

            logcatProcess = Runtime.getRuntime().exec(logCommand)
            currentJob = RunningJob.PermissionJob(logcatProcess)

            // Write output to file
            outputFile.bufferedWriter().use { writer ->
                logcatProcess.inputStream.bufferedReader().forEachLine { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }
        } catch (e: IOException) {
            // This is expected when the process is destroyed by stopLogger
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            currentJob = null
            withContext(Dispatchers.Main) {
                onExit()
            }
        }
    }
}

/**
 * Private implementation for fetching logs when root access is available.
 * Uses the libsu library.
 */
private fun AppInfo.fetchLogsWithRoot(
    scope: CoroutineScope,
    outputFile: File,
    onExit: () -> Unit
) {
    val shell = createRootShell()
    shell.newJob().add("logcat -c").exec()

    val pId = com.topjohnwu.superuser.ShellUtils.fastCmd(shell, "pidof $packageName").trim()
    val logCommand = if (pId.isNotEmpty()) {
        "logcat --pid=$pId"
    } else {
        "logcat | grep $packageName"
    }

    scope.launch(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter().use { writer ->
                val logcatFuture = shell.newJob()
                    .add(logCommand)
                    .to(object : ArrayList<String>() {
                        override fun add(element: String): Boolean {
                            writer.write(element)
                            writer.newLine()
                            return true
                        }
                    })
                    .enqueue()

                currentJob = RunningJob.RootJob(logcatFuture)
                logcatFuture.get() // Block until done or cancelled
            }
        } catch (e: CancellationException) {
            // Expected on cancellation
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            currentJob = null
            withContext(Dispatchers.Main) {
                onExit()
            }
        }
    }
}

private var logcatFuture: Future<Shell.Result>? = null

@Suppress("unused")
fun AppInfo.fetchLogs(
    scope: CoroutineScope,
    outputFile: File,
    onExit: () -> Unit
) {
    val shell = getRootShell()
    shell.newJob().add("logcat -c").exec()

    val pId = fastCmd(shell, "pidof $packageName").trim()
    val logCommand = if (pId.isNotEmpty()) {
        "logcat --pid=$pId"
    } else {
        "logcat | grep $packageName"
    }

    stopLogger = {
        if (logcatFuture?.isDone == false) {
            logcatFuture?.cancel(true)
        }
    }

    scope.launch(Dispatchers.IO) {
        try {
            outputFile.bufferedWriter().use { writer ->
                logcatFuture = shell.newJob()
                    .add(logCommand)
                    .to(object : ArrayList<String>() {
                        override fun add(element: String): Boolean {
                            // Write each log line to the file
                            writer.write(element)
                            writer.newLine()
                            return true
                        }
                    })
                    .enqueue()

                logcatFuture?.get() // Block until done or cancelled
            }
        } catch (e: CancellationException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Use the main dispatcher to call the exit callback if needed
            withContext(Dispatchers.Main) {
                onExit()
            }
        }
    }
}
