package com.valhalla.loki.model

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

/** Where a self-grant sweep has got to. Collected by the UI with `collectAsState()`. */
sealed interface SelfGrantState {

    /** Nothing to do, or nothing to do *yet* — no privilege is live. */
    data object Idle : SelfGrantState

    /** A sweep is running. */
    data object Working : SelfGrantState

    /**
     * A grant is available but needs the user's word, because taking it closes Loki.
     *
     * Only ever reached on the Shizuku path. Root does not ask, because root can put Loki back —
     * see [PermissionManager.armSelfRelaunchViaRoot].
     */
    data class Offered(
        val permissions: List<String>,
        val channel: GrantChannel,
    ) : SelfGrantState

    /** The sweep finished. [refused] is what a channel accepted but the device did not grant. */
    data class Done(
        val granted: List<String>,
        val refused: List<String>,
    ) : SelfGrantState
}

/**
 * Takes the permissions Loki declares, using the privilege the user has already given it.
 *
 * The shape is ported from Thor's `SelfPermissionGranter`, and three things about Loki's version
 * are different in ways that matter:
 *
 *  - **The rule had to change.** Thor grants what is `dangerous`; `READ_LOGS` is
 *    `signature|privileged|development`, so a verbatim port grants nothing that Loki cares about.
 *    [planSelfGrant] carries the reasoning.
 *  - **One of the grants ends this process.** Changing a permission that carries supplementary gids
 *    kills the whole appId, and `READ_LOGS` is `gids=[1007, 1096]`. So the sweep orders survivable
 *    grants first, treats everything after a fatal grant as unreachable, and — on root only — arms
 *    a detached relaunch *before* issuing it. Thor never needed any of this: none of its
 *    permissions kill it.
 *  - **The verdict lives in the next process.** Thor re-reads and reports. Here the re-read that
 *    matters is the one at the top of the *next* launch — but a process-memory latch turned out not
 *    to be the whole story, because two decisions have to outlive the process that made them.
 *    [SelfGrantStore] holds those two markers and says why.
 *
 * Constructor dependencies rather than a `Context`, so the pieces worth testing — [planSelfGrant],
 * [selfGrantCommand], [selfRelaunchCommand] — stay reachable from a JVM test. `:app` has no mocking
 * library by policy, so anything that needs a live `PackageManager` is device-verified by hand.
 */
class SelfPermissionGrabber(
    private val packageName: String,
    private val packageManager: PackageManager,
    private val permissionManager: PermissionManager,
    private val logcatCapture: LogcatCapture,
    private val store: SelfGrantStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises sweeps.
     *
     * [start] and the sticky Shizuku callback can both arrive within a millisecond of each other on
     * a cold start, and two concurrent sweeps would each read "not granted" and each issue the same
     * privileged `pm grant`.
     */
    private val sweeping = Mutex()

    private val _state = MutableStateFlow<SelfGrantState>(SelfGrantState.Idle)
    val state: StateFlow<SelfGrantState> = _state.asStateFlow()

    /**
     * Latched only on a run that genuinely finished.
     *
     * A declined offer, an unanswered probe or a refused grant all leave it open, so the next
     * [refresh] retries. `@Volatile` because the sweep runs on an IO thread and [start] is called
     * from the main one.
     */
    @Volatile
    private var completed = false

    private var started = false

    /**
     * Set once an Activity has existed in this process, and never cleared.
     *
     * `Application.onCreate` runs on **every** process start, not only one the user asked for. Loki
     * exports `LokiDocumentsProvider` with a `DOCUMENTS_PROVIDER` intent-filter, so DocumentsUI
     * starts this process to read `queryRoots` whenever *any* app opens a file picker — and the
     * sticky Shizuku callback re-enters [refresh] in that process too. Ungated, the sweep would
     * probe `su` there (a root-manager prompt over somebody else's picker), take the fatal grant,
     * and let the armed relaunch put Loki in the foreground on top of whatever the user was
     * actually doing.
     *
     * [PermissionManager.selfRelaunchCommand]'s `pidof` guard cannot cover that: the process really
     * did die, so there is nothing for `pidof` to find and the `am start` fires. That guard answers
     * "did the grant fail?"; this flag answers "was there a UI to come back to?" — two different
     * questions, and only the second one is about headless starts.
     *
     * `@Volatile` because it is written from the main thread and read on the sweep's IO thread.
     */
    @Volatile
    private var uiPresent = false

    /**
     * Registers the Shizuku listener and runs one sweep. Idempotent.
     *
     * The sweep it starts does nothing until an Activity exists — see [uiPresent] and
     * [onUiPresent]. The listener registration is what has to happen this early; the grant does
     * not, and must not.
     *
     * `Sticky` is the load-bearing word, and Loki has already paid for learning it once (see
     * `MainActivity.onCreate`): `ShizukuProvider` is a ContentProvider, and providers are installed
     * *before* `Application.onCreate`, so the binder has usually arrived by the time this runs and a
     * plain `addBinderReceivedListener` would sit dead forever. The sticky variant fires immediately
     * when the binder is present **and** on a later delivery, which is the whole of the
     * "is a privilege gateway live?" signal Thor buys with a `PrivilegeStateProvider` state flow.
     *
     * The listener is never removed. This is an application-scoped singleton, so there is nothing
     * to leak and no lifecycle to outlive — unlike the Activity, which does remove its own.
     * `@Synchronized` and the [started] flag because Shizuku keeps its listeners in static
     * `ArrayList`s without de-duplicating.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
            .onFailure { Log.d(TAG, "Shizuku listener could not be registered", it) }
        refresh()
    }

    /**
     * An Activity now exists in this process, so a sweep may spend privilege. Idempotent.
     *
     * Called from `MainActivity.onCreate` — the only Activity Loki declares. The first call runs
     * the sweep that [start] deliberately declined to; a rotation re-enters and returns. Never
     * unset: a process that has shown a UI once is one the user opened, and the armed relaunch has
     * somewhere to come back to for the rest of its life.
     */
    fun onUiPresent() {
        if (uiPresent) return
        uiPresent = true
        refresh()
    }

    /**
     * Re-runs the sweep. Cheap when there is nothing to do, and never spends a `su` probe that a
     * previous launch already answered — see [SelfGrantStore.rootUnavailable].
     */
    fun refresh() {
        launchSweep(force = false)
    }

    /**
     * Re-runs the sweep on the user's behalf, re-probing root even if the last probe found none.
     *
     * The other half of [SelfGrantStore.rootUnavailable]: the marker is what stops an unrooted
     * device paying for a `su` spawn on every cold start, and this is what stops that marker being
     * permanent for someone who roots their phone afterwards. Wired to the "Tap to re-check" row in
     * Settings, which is the only place in the app where re-probing is what the user just asked for.
     */
    fun recheck() {
        launchSweep(force = true)
    }

    private fun launchSweep(force: Boolean) {
        scope.launch { sweeping.withLock { sweep(force) } }
    }

    /**
     * The user accepted an [SelfGrantState.Offered]. Issues the grants it named.
     *
     * Expect this call to be the last thing the process does — with one exception, and it is the
     * reason [grantFatal] claims the capture rather than trusting [sweep]'s check: a capture may have
     * been started while the dialog was on screen, and then nothing is granted and Loki stays up.
     */
    fun confirmOffered() {
        val offered = _state.value as? SelfGrantState.Offered ?: return
        scope.launch {
            sweeping.withLock {
                _state.value = SelfGrantState.Working
                val granted = grantFatal(offered.permissions, offered.channel)
                finish(granted, offered.permissions - granted.toSet(), hasUnanswered = false)
            }
        }
    }

    /** The user declined. Dropped for this process only — the latch stays open. */
    fun dismissOffered() {
        if (_state.value is SelfGrantState.Offered) _state.value = SelfGrantState.Idle
    }

    /**
     * One pass. Never throws at the caller: a failure here means a permission the user can still
     * grant by hand, which is what every screen already offers.
     */
    private suspend fun sweep(force: Boolean) {
        if (completed) return
        // Nothing below may spend privilege in a process the user did not open — see [uiPresent].
        // Ahead of the plan, so a headless start costs no binder traffic either, and deliberately
        // without latching: the next start that does have a UI sweeps normally.
        if (!uiPresent) {
            Log.d(TAG, "sweep skipped: no Activity in this process")
            return
        }
        try {
            // The cheap early exit. The kill is appId-wide, so it takes the foreground service and
            // its half-written file with it, and nothing here is urgent enough to cost someone a
            // capture in progress. It is not the guarantee, though — everything below this line
            // takes time, so the fatal grant claims the capture rather than re-reading this flag.
            if (logcatCapture.isCapturing) {
                Log.d(TAG, "sweep skipped: a capture is running")
                return
            }

            // The plan is built before any privilege is probed, and that ordering is the direct
            // answer to the objection that disabled the old onboarding gate: probing root means
            // spawning `su`, and possibly a root-manager prompt, on every cold start. Classifying
            // permissions is binder traffic only, so once there is nothing left to grant — which is
            // every launch after the first successful one — this method spawns no shell at all.
            // Note that a *fresh* install on API 33+ does have something to grant, because
            // POST_NOTIFICATIONS starts out ungranted, so "the common launch is free" is a claim
            // about steady state and not about the first run.
            val plan = planSelfGrant(readSelfPermissions(), store.grantedOnce())
            if (plan.toGrant.isEmpty()) {
                finish(granted = emptyList(), refused = emptyList(), hasUnanswered = plan.hasUnanswered)
                return
            }

            _state.value = SelfGrantState.Working
            val channel = resolveChannel(force)
            if (channel == GrantChannel.NONE) {
                // Not a failure and not a completed run: the user may authorise Shizuku or grant
                // root in a minute, and the sticky Shizuku listener or `recheck()` brings us back.
                Log.d(TAG, "no privilege gateway; ${plan.toGrant.size} permission(s) left alone")
                _state.value = SelfGrantState.Idle
                return
            }

            val survivable = plan.toGrant.filterNot { it in plan.fatal }
            val granted = grant(survivable, channel).toMutableList()
            // Recorded here rather than in finish(), because the Shizuku path returns below without
            // reaching it. What is remembered is only what the device confirmed, and only the
            // survivable half — a name here is one the user can now revoke in Settings and expect
            // that to stick.
            store.recordGranted(granted)

            if (plan.fatal.isEmpty()) {
                finish(granted, survivable - granted.toSet(), plan.hasUnanswered)
                return
            }

            val fatal = plan.toGrant.filter { it in plan.fatal }
            if (channel == GrantChannel.SHIZUKU) {
                // Shizuku cannot put us back, so it has to ask. A `newProcess` shell is torn down
                // with the client that asked for it — device-verified, and recorded in
                // PermissionManager.grantReadLogsViaShizuku.
                _state.value = SelfGrantState.Offered(fatal, channel)
                return
            }

            granted += grantFatal(fatal, channel)
            finish(granted, plan.toGrant - granted.toSet(), plan.hasUnanswered)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "self-grant sweep failed", e)
            _state.value = SelfGrantState.Idle
        }
    }

    /**
     * Which gateway to grant through, and the only place in this class that spends a `su` probe.
     *
     * Root is preferred because it grants silently and can put Loki back, and it is the expensive
     * question: `isRootAvailable()` spawns or reuses `su` and, on a device whose root manager asks,
     * raises a prompt. On a phone that has no root and never will, that used to happen on every
     * single cold start, because `READ_LOGS` is permanently ungranted there and so the plan is
     * permanently non-empty. [SelfGrantStore.rootUnavailable] is the memory that stops it, and
     * [recheck] is how a user who roots their phone later gets the probe back.
     *
     * Shizuku is checked either way and costs nothing — `Shizuku.checkSelfPermission()` is a local
     * call against a binder that is either there or not. One consequence worth naming: a user who is
     * running Shizuku *and* grants root after the marker is set will be offered the Shizuku path,
     * with its dialog, rather than root's silent grant, until they tap re-check.
     */
    private suspend fun resolveChannel(force: Boolean): GrantChannel {
        if (force || !store.rootUnavailable()) {
            val rooted = permissionManager.isRootAvailable()
            store.setRootUnavailable(!rooted)
            if (rooted) return GrantChannel.ROOT
        } else {
            Log.d(TAG, "root probe skipped: a previous launch found none")
        }
        return if (permissionManager.isShizukuAvailable()) {
            GrantChannel.SHIZUKU
        } else {
            GrantChannel.NONE
        }
    }

    /** Issues [permissions] and returns the ones the device confirms afterwards. */
    private suspend fun grant(permissions: List<String>, channel: GrantChannel): List<String> =
        permissions.filter { permission ->
            val attempt = when (channel) {
                GrantChannel.ROOT -> permissionManager.grantSelfViaRoot(permission)
                GrantChannel.SHIZUKU -> permissionManager.grantSelfViaShizuku(permission)
                GrantChannel.NONE -> GrantAttempt(GrantChannel.NONE, false)
            }
            // Re-read, never the exit code. `pm grant` exits 0 on some ROMs while the permission
            // stays ungranted, and a channel can report failure for a grant that landed.
            val held = permissionManager.isHeld(permission)
            if (!held) Log.w(TAG, "$permission still not held after ${channel.name}: ${attempt.detail}")
            held
        }

    /**
     * Issues the grants that are expected to kill us, arming a relaunch first where one is possible.
     *
     * Everything after the first fatal grant is best effort. It is written as a loop because the
     * plan may name more than one, not because the second iteration is expected to happen.
     *
     * The claim on [LogcatCapture] is what makes the capture check at the top of [sweep] mean
     * something. That check is minutes stale by the time we get here — the root probe alone can take
     * Odin's full ten seconds, and on the Shizuku path a human has been reading a dialog in between —
     * and a check-then-act cannot be repaired by checking again. If the claim is refused, the capture
     * wins and the retry stays open.
     */
    private suspend fun grantFatal(
        permissions: List<String>,
        channel: GrantChannel,
    ): List<String> {
        if (!logcatCapture.blockForPrivilegedGrant()) {
            Log.i(TAG, "fatal grant abandoned: a capture is running and this would kill it")
            return emptyList()
        }
        try {
            if (channel == GrantChannel.ROOT) {
                val armed = permissionManager.armSelfRelaunchViaRoot()
                Log.d(TAG, "relaunch armed=$armed before ${permissions.joinToString()}")
            }
            Log.i(TAG, "granting ${permissions.joinToString()} — this may be the last line this process logs")
            return grant(permissions, channel)
        } finally {
            // Reached only if the grant did not kill us, i.e. did not land. On the success path the
            // flag goes with the process.
            logcatCapture.releaseAfterPrivilegedGrant()
        }
    }

    /** Publishes the outcome and latches only a run that left nothing behind. */
    private fun finish(granted: List<String>, refused: List<String>, hasUnanswered: Boolean) {
        if (refused.isEmpty() && !hasUnanswered) completed = true
        _state.value = SelfGrantState.Done(granted, refused)
    }

    /**
     * Loki's own declared permissions, as the running device describes each one.
     *
     * `requestedPermissions` is the *merged* manifest, so it includes what Shizuku's provider AAR
     * contributes — eight names on a device with Shizuku installed, not the six in
     * `app/src/main/AndroidManifest.xml`. That is the point of asking rather than keeping a list.
     */
    private fun readSelfPermissions(): List<SelfPermission> {
        val requested = runCatching { requestedPermissions() }
            .onFailure { Log.w(TAG, "could not read our own requested permissions", it) }
            .getOrNull()
            ?: return emptyList()

        return requested.map { name ->
            SelfPermission(
                name = name,
                declaration = declarationOf(name),
                isGranted = permissionManager.isHeld(name),
            )
        }
    }

    private fun requestedPermissions(): List<String> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions?.toList().orEmpty()
    }

    /**
     * What the OS says about one permission name.
     *
     * The three-way split is the whole reason [SelfPermissionDeclaration] is not a `Boolean?`:
     * `NameNotFoundException` is an authoritative "not on this build", and any other failure is not
     * an answer at all and must leave the run repeatable.
     */
    private fun declarationOf(name: String): SelfPermissionDeclaration = try {
        // No Tiramisu branch here, unlike requestedPermissions() above: the `long`-flags overloads
        // added in API 33 cover PackageInfo and friends, and `getPermissionInfo(String, Int)` was
        // never deprecated alongside them.
        val info = packageManager.getPermissionInfo(name, 0)
        SelfPermissionDeclaration.Declared(
            DeclaredPermission(
                isDangerous = info.protection == PermissionInfo.PROTECTION_DANGEROUS,
                // The parentheses are mandatory: Kotlin's infix `and` binds looser than `!=`.
                isDevelopment =
                    (info.protectionFlags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0,
            )
        )
    } catch (_: PackageManager.NameNotFoundException) {
        SelfPermissionDeclaration.Undefined
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "could not classify $name", e)
        SelfPermissionDeclaration.Unknown
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }

    private companion object {
        private const val TAG = "SelfPermissionGrabber"
    }
}
