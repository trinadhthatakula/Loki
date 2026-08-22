package com.valhalla.loki.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.superuser.ktx.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

enum class PermissionMethod {
    NONE,
    ADB, // Indicates permission was granted via ADB/Shizuku
    ROOT   // Indicates we are using a root shell
}

/** Which privilege gateway a grant went through. */
enum class GrantChannel { NONE, ROOT, SHIZUKU }

/**
 * What a privilege channel *reported* about one grant.
 *
 * Never the answer to "is it granted now" — [PermissionManager.isHeld] is. `pm grant` exits 0 on
 * some ROMs while the permission stays ungranted, and a channel can report failure for a grant that
 * landed, so the two questions are kept apart on purpose.
 */
data class GrantAttempt(
    val channel: GrantChannel,
    val reportedSuccess: Boolean,
    val detail: String = "",
)

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
     * Whether Loki itself holds [permission] right now.
     *
     * The authority every verification asks, and the reason a grant is never trusted on the
     * strength of an exit code.
     */
    fun isHeld(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks if the app has been granted the READ_LOGS permission directly.
     */
    fun hasReadLogsPermission(): Boolean = isHeld(Manifest.permission.READ_LOGS)

    /**
     * Checks if a root shell is available.
     *
     * Backed by Odin's bounded probe: it never throws and cannot hang forever — a shell that
     * fails to initialise inside Odin's timeout reports `false` rather than blocking.
     *
     * **The close is not defensive tidying, it is the whole reason re-checking works.** Odin caches
     * one main shell process-wide, and `BuilderImpl.start()` falls back to `exec("sh")` when `su` is
     * refused — then caches *that* as the main shell. `MainShell.cached` only drops a shell whose
     * `status < 0`, and a live non-root shell has `NON_ROOT_SHELL == 0`, so without this every
     * later probe answers the *first* probe's question forever. A user who grants root in their root
     * manager while Loki is open could never be seen to have done so, which silently falsified
     * every "tap to re-check" in the app. `Shell.close()` sets `status = UNKNOWN`, which is what
     * lets the next `get()` rebuild through `su`.
     */
    suspend fun isRootAvailable(): Boolean {
        runCatching { Shell.cachedShell?.takeIf { !it.isRoot }?.close() }
            .onFailure { Log.d(TAG, "could not invalidate the cached non-root shell", it) }
        return shell.isRootGranted()
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
     * Grants one of **Loki's own** permissions through the root shell.
     *
     * Goes through the injected [ShellRepository] rather than `Shell.Builder`, per AGENTS.md: the
     * privileged surface stays swappable in a test and countable by grep. Odin's `exec` never
     * throws for a command failure — `code == ShellResult.JOB_NOT_EXECUTED` is a dead shell rather
     * than a rejected command — so branching on the code is how rule 3 is satisfied here.
     *
     * One caveat that inverts the usual reading of a return value: for a `development` permission
     * the platform kills us *during* this call, so getting a value back at all usually means the
     * grant did **not** land. Verification belongs to the next process, which is what
     * [isHeld] answers on its first cheap read at startup.
     */
    suspend fun grantSelfViaRoot(permission: String): GrantAttempt {
        val command = selfGrantCommand(context.packageName, permission)
            ?: return GrantAttempt(GrantChannel.ROOT, false, "refused an unsafe argument")
        val result = shell.exec(command)
        val detail = result.stderr.joinToString(" ").trim()
        if (!result.isSuccess) {
            val why = if (result.code == ShellResult.JOB_NOT_EXECUTED) "no shell" else "exit ${result.code}"
            Log.w(TAG, "root pm grant of $permission failed ($why): $detail")
        }
        return GrantAttempt(GrantChannel.ROOT, result.isSuccess, detail)
    }

    /**
     * Arms a detached root command that reopens Loki after the platform kills it for a grant.
     *
     * **Call this before the fatal grant, never after.** The two dead ends recorded on
     * [grantReadLogsViaShizuku] are both about ordering: a chained `am start` was delivered to the
     * already-dying Activity, and a version that forced the death first never reached its second
     * command. Arming first, with a sleep long enough for the kill to complete, is what is left.
     *
     * Root-only, and that is not a policy choice. Shizuku ties a `newProcess` shell to the client
     * that asked for it, so nothing started from one can outlive us; a Magisk root shell is forked
     * from `magiskd` and carries no death signal, so a fully detached grandchild should survive.
     * *Should* — this is the one claim here that no emulator can settle, and the PR says so.
     *
     * Returns whether the shell accepted the arming command, which is not the same as "Loki will
     * come back". The command cannot be called off once armed, so it calls *itself* off: it starts
     * the Activity only if our process has actually gone, which is what stops a failed grant from
     * being followed two seconds later by an app that launches itself over whatever the user is
     * doing. [selfRelaunchCommand] has the rest of that reasoning.
     */
    suspend fun armSelfRelaunchViaRoot(): Boolean {
        val component = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.component
        if (component == null) {
            Log.w(TAG, "no launcher component; cannot arm a relaunch")
            return false
        }
        val command = selfRelaunchCommand(
            packageName = component.packageName,
            activityClassName = component.className,
            delaySeconds = RELAUNCH_DELAY_SECONDS,
        )
        if (command == null) {
            Log.w(TAG, "refused to arm a relaunch for ${component.flattenToShortString()}")
            return false
        }
        val result = shell.exec(command)
        if (!result.isSuccess) {
            Log.w(TAG, "could not arm the relaunch (exit ${result.code})")
        }
        return result.isSuccess
    }

    /**
     * Grants one of **Loki's own** permissions through Shizuku.
     *
     * The command stays an argv list rather than a `sh -c` string, so nothing here is a shell
     * string at all — see [grantReadLogsViaShizuku] for the rest of the reasoning, and for why a
     * `true` from this is a value almost nobody lives to read.
     */
    suspend fun grantSelfViaShizuku(permission: String): GrantAttempt = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) {
            return@withContext GrantAttempt(GrantChannel.SHIZUKU, false, "Shizuku is not available")
        }
        return@withContext try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(
                arrayOf("pm", "grant", context.packageName, permission),
                null,
                null,
            )
            val code = process.waitFor()
            if (code != 0) Log.w(TAG, "Shizuku pm grant of $permission exited $code")
            GrantAttempt(GrantChannel.SHIZUKU, code == 0, if (code == 0) "" else "exit $code")
        } catch (e: Exception) {
            // Log.w, not printStackTrace(): the latter lands under the `System.err` tag, which is
            // exactly why this failing silently cost a device session to find.
            Log.w(TAG, "Shizuku could not grant $permission", e)
            GrantAttempt(GrantChannel.SHIZUKU, false, e.message.orEmpty())
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
     * user reopens Loki — see the confirmation in `SettingsScreen`. **Root is different**, and
     * [armSelfRelaunchViaRoot] is where that difference is spent.
     *
     * The command stays an argv list rather than a `sh -c` string. Nothing here needs a shell any
     * more, and the package name is ours, so there is nothing an attacker controls — but keeping
     * the argv form is what makes that still true if the argument ever stops being ours.
     */
    suspend fun grantReadLogsViaShizuku(): Boolean =
        grantSelfViaShizuku(Manifest.permission.READ_LOGS).reportedSuccess

    private companion object {
        private const val TAG = "PermissionManager"

        /**
         * How long the armed relaunch waits before calling `am start`.
         *
         * Long enough for the platform's kill to finish — an `am start` that races it is delivered
         * to the dying Activity and thrown away with it, which is the first of the two dead ends
         * above. Short enough that the app appears to restart rather than to have crashed.
         */
        private const val RELAUNCH_DELAY_SECONDS = 2
    }
}
