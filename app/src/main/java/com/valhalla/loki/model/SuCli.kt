package com.valhalla.loki.model

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log.e
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.valhalla.loki.BuildConfig
import java.io.File

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

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (rootAvailable()) {
            if (globalMnt) {
                builder.build("su")
            } else {
                builder.build("su", "-mm")
            }
        } else {
            e(TAG, "su failed: root not available")
            builder.build("sh")
        }
    } catch (e: Exception) {
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

fun exportLogs(
    appInfo: AppInfo,
    file: File,
    observer: (String) -> Unit,
    exit: (Result<Boolean>) -> Unit
) {
    observer("Exporting logs for ${appInfo.appName}")
    try {
        observer("searching PID for ${appInfo.appName}")
        val pid = fastCmd(getRootShell(), (if(rootAvailable())"su -c " else "")+"pidof ${appInfo.packageName}")
        observer("Exporting logs.., Please wait")
        val commands = if(pid.isNotEmpty())" logcat | grep $pid > ${file.absolutePath}" else "logcat | grep ${appInfo.packageName} > ${file.absolutePath}"
        fastCmd(getRootShell(), if(rootAvailable())"su -c $commands" else " commands")
        observer("logs exported")
        exit(Result.success(true))
    } catch (e: Exception) {
        exit(Result.failure(e))
    }
}