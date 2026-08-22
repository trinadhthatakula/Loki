package com.valhalla.loki.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
 *
 * The [Context] is a constructor dependency rather than a per-call parameter. Both facts this
 * class reads from it — our own granted permissions and our own package name — are properties of
 * the application, not of whatever screen happens to be asking, so threading an Activity through
 * every call only invited a ViewModel to hold one as a field. This is the application context, from
 * Koin's `androidContext()`, and `PermissionManager` is a singleton that lives as long as it does.
 */
class PermissionManager(
    private val context: Context,
    private val shell: ShellRepository
) {

    /**
     * Checks if the app has been granted the READ_LOGS permission directly.
     */
    fun hasReadLogsPermission(): Boolean {
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
     * Uses a Shizuku shell to grant `READ_LOGS` to this app.
     *
     * **A successful grant kills this process, and there is no way around that.** `READ_LOGS`
     * carries the `development` protection flag, and the platform kills the target UID whenever
     * such a permission changes — on API 36 the reason is logged verbatim as
     * `Killing …: permission grant or revoke changed gids`. So `true` is a value almost nobody
     * ever reads: by the time the shell exits, the caller is usually gone. `false` is the
     * meaningful return, and it means the grant did not happen.
     *
     * Loki therefore cannot restart itself here, and must not pretend to. Two device-verified
     * dead ends, recorded so they are not retried:
     *
     *  - Chaining `am start` after the grant in one `sh -c` "worked" once by winning a
     *    millisecond-wide race, and was then delivered to the already-dying Activity and thrown
     *    away with it.
     *  - Making the death deterministic first — `pm grant … ; am force-stop … ; sleep 1;
     *    am start …` — never reached the second command at all. A marker written after each step
     *    showed the shell dying immediately after the grant: Shizuku ties the lifetime of a
     *    `newProcess` shell to the client that asked for it, so it cannot outlive us.
     *
     * Surviving that would need a Shizuku *user service* (`addUserService`), which is a real
     * feature, not a detail of this method. Until then the UI warns before calling this and the
     * user reopens Loki — see the confirmation in `SettingsScreen`.
     *
     * The command stays an argv list rather than a `sh -c` string. Nothing here needs a shell any
     * more, and the package name is ours, so there is nothing an attacker controls — but keeping
     * the argv form is what makes that still true if the argument ever stops being ours.
     */
    suspend fun grantReadLogsViaShizuku(): Boolean = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) return@withContext false
        return@withContext try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(
                arrayOf("pm", "grant", context.packageName, Manifest.permission.READ_LOGS),
                null,
                null,
            )
            process.waitFor() == 0
        } catch (e: Exception) {
            // Log.w, not printStackTrace(): the latter lands under the `System.err` tag, which is
            // exactly why this failing silently cost a device session to find.
            Log.w(TAG, "Shizuku could not grant READ_LOGS", e)
            false
        }
    }

    private companion object {
        private const val TAG = "PermissionManager"
    }
}
