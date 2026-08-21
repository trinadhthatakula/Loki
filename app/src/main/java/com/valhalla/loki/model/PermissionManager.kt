package com.valhalla.loki.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.valhalla.superuser.ktx.ShellRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

enum class PermissionMethod {
    NONE,
    ADB, // Indicates permission was granted via ADB/Shizuku
    ROOT   // Indicates we are using a root shell
}

/**
 * The single place that answers "what privilege does Loki actually have right now?".
 *
 * Root is reached through Odin's [ShellRepository]. Every root answer is therefore a
 * `suspend` one: probing root means spawning or reusing a `su` process, which must never
 * happen on the main thread.
 */
class PermissionManager(
    private val shell: ShellRepository
) {

    /**
     * Checks if the app has been granted the READ_LOGS permission directly.
     */
    fun hasReadLogsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_LOGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if a root shell is available.
     *
     * Backed by Odin's bounded probe: it never throws and cannot hang forever — a shell that
     * fails to initialise inside Odin's timeout reports `false` rather than blocking.
     */
    suspend fun isRootAvailable(): Boolean = shell.isRootGranted()

    /**
     * Checks if Shizuku is installed, running, and if LOKI has been granted permission.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Uses a Shizuku shell to grant the READ_LOGS permission to this app.
     * Returns true on success.
     */
    suspend fun grantReadLogsViaShizuku(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext false
        try {
            // context.packageName is our own package, so there is nothing here an attacker
            // controls — but the command is still built as an argv list rather than a shell
            // string so that stays true if the argument ever stops being ours.
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(
                arrayOf("pm", "grant", context.packageName, Manifest.permission.READ_LOGS),
                null,
                null
            )

            // A successful execution will have an exit code of 0.
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
