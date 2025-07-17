package com.valhalla.loki.model

import android.util.Log.e
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.valhalla.loki.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

@Suppress("unused")
fun exportLogs(
    appInfo: AppInfo,
    file: File,
    observer: (String) -> Unit,
    exit: (Result<Boolean>) -> Unit
) {
    observer("Logs for ${appInfo.appName}")
    try {
        observer("searching PID for ${appInfo.appName}")
        val pid = fastCmd(
            getRootShell(),
            (if (rootAvailable()) "su -c " else "") + "pidof ${appInfo.packageName}"
        )
        val commands = if (pid.isNotEmpty()) {
            observer("searching for logs for ${appInfo.appName} with pid: $pid")
            " logcat | grep $pid > ${file.absolutePath}"
        } else {
            "logcat | grep ${appInfo.packageName} > ${file.absolutePath}"
        }
        observer("Exporting logs.., Please wait")
        getRootShell().newJob()
            .add(if (rootAvailable()) "su -c $commands" else " commands")
            .submit { cb ->
                if (cb.isSuccess) {
                    observer("logs exported")
                    exit(Result.success(true))
                } else {
                    exit(Result.failure(Throwable(cb.err.joinToString("\n"))))
                }
            }
    } catch (e: Exception) {
        exit(Result.failure(e))
    }
}

var stopLogger: (() -> Unit)? = null
// You should pass in a CoroutineScope to launch the observer on the main thread

private var logcatFuture: Future<Shell.Result>? = null

// MODIFIED FUNCTION
fun AppInfo.showLogs(
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