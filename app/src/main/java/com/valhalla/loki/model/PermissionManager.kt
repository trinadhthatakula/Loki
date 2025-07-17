package com.valhalla.loki.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

enum class PermissionMethod {
    NONE,
    ADB, // Indicates permission was granted via ADB/Shizuku
    ROOT   // Indicates we are using a root shell
}

object PermissionManager {

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
     */
    fun isRootAvailable(): Boolean {
        return ShellUtils.fastCmd("id -u") == "0"
    }

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
    fun grantReadLogsViaShizuku(context: Context): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            val command = "pm grant ${context.packageName} android.permission.READ_LOGS"

            // --- CORRECTED IMPLEMENTATION ---
            // Get the IShizukuService binder and call newProcess on it.
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)

            // A successful execution will have an exit code of 0.
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
