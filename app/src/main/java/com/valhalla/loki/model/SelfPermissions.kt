package com.valhalla.loki.model

/**
 * How the running OS describes one permission that **Loki's own manifest** declares.
 *
 * Two booleans and not Thor's one, and that difference is the whole reason this rule is ported
 * rather than copied. Thor's `DeclaredPermission` carries `isDangerous` alone, computed as
 * `(protectionLevel and PROTECTION_MASK_BASE) == PROTECTION_DANGEROUS`. `READ_LOGS` is
 * `signature|privileged|development` — device-verified on API 36 and API 37 — so its base
 * protection is `signature`, and Thor's test is `2 == 1`. A verbatim port never grants the one
 * permission Loki exists to hold.
 *
 * The rejection was never about grantability. `pm grant` reaches
 * `grantRuntimePermissionInternal`, which accepts `isRuntimePermission || bp.isDevelopment()`, so a
 * `development` permission is as grantable as a `dangerous` one. Asking the base protection alone
 * is simply the wrong question.
 */
data class DeclaredPermission(
    /** Base protection is `dangerous` — `PermissionInfo.getProtection()`. */
    val isDangerous: Boolean,
    /** The `development` protection *flag* is set — `PermissionInfo.getProtectionFlags()`. */
    val isDevelopment: Boolean,
)

/**
 * What the running OS says about one of Loki's declared permissions.
 *
 * Three states and not two, for the reason Thor's version of this documents: "this build has never
 * heard of it" is an authoritative answer about the device, and "the package manager would not tell
 * us" is not an answer at all. Folding the second into the first is what would let one unlucky
 * binder call latch a permission off for the life of the process.
 */
sealed interface SelfPermissionDeclaration {

    /**
     * `getPermissionInfo` threw `NameNotFoundException` — the definitive "not on this build".
     *
     * The common case, not an edge one. Loki's merged manifest declares `POST_NOTIFICATIONS`,
     * which does not exist below API 33, and two `shizuku.permission.API_V23` spellings that exist
     * only where Shizuku is installed. `minSdk` is 28, so both are ordinary devices.
     */
    data object Undefined : SelfPermissionDeclaration

    /** The question failed for some reason other than "no such permission". Costs the run its latch. */
    data object Unknown : SelfPermissionDeclaration

    /** The OS defines it, and described it. */
    data class Declared(val permission: DeclaredPermission) : SelfPermissionDeclaration
}

/** One permission out of Loki's own `requestedPermissions`, as the running device describes it. */
data class SelfPermission(
    val name: String,
    val declaration: SelfPermissionDeclaration,
    val isGranted: Boolean,
)

/**
 * What a privileged self-grant should do this run.
 *
 * Three fields rather than a `List<String>`, because the *absence* of a permission from [toGrant]
 * does not say which of four reasons put it there, and because one of the grants ends the process.
 */
data class SelfGrantPlan(
    /**
     * The permissions worth a `pm grant` — **survivable ones first, in declaration order, then
     * the fatal ones**.
     *
     * The ordering is load-bearing and it is where this rule parts company with Thor's, whose test
     * suite pins `theManifestsDeclarationOrderIsPreserved` outright. Granting a permission that
     * carries supplementary gids kills Loki's whole appId mid-loop, so anything queued behind it is
     * never issued — not the remaining grants, not the post-grant verification, not the latch.
     * `dumpsys package com.valhalla.loki` lists `READ_LOGS` sixth of eight, so declaration order
     * alone would drop the two after it on every device.
     */
    val toGrant: List<String>,
    /**
     * The subset of [toGrant] whose grant is expected to kill this process.
     *
     * `development ⇒ fatal` is a deliberate over-approximation. The kill is the platform's reaction
     * to a change in the app's supplementary group set — `READ_LOGS` is `gids=[1007, 1096]`, and
     * `POST_NOTIFICATIONS` is `gids=[]`, which is why one kills and the other does not — and
     * `PermissionInfo` exposes no gids, so no rule can ask the question directly. Every gid-bearing
     * permission Loki declares is `development`-flagged, and the only cost of being wrong is
     * issuing a grant last that need not have been.
     */
    val fatal: Set<String>,
    /**
     * True when at least one permission came back [SelfPermissionDeclaration.Unknown].
     *
     * The caller must not latch its once-per-process guard on such a run. Without this, a package
     * manager that hiccupped on the one probe that mattered would disable the feature for the life
     * of the process and report success while doing it.
     */
    val hasUnanswered: Boolean,
)

/**
 * Which of Loki's own declared permissions a privileged `pm grant` could actually change.
 *
 * **The device is asked, and no name is hardcoded.** Loki's manifest is the input, so a permission
 * added to it later is covered without a second edit here — the failure mode of a hand-kept list is
 * that the list and the manifest agree on the day they are written and never again. It also makes
 * the answer honest per device: on API 36 with Shizuku installed this returns `POST_NOTIFICATIONS`
 * then `READ_LOGS`, on API 28 it returns `READ_LOGS` alone.
 *
 * The five rejections all exist because `pm grant` *fails* on them, or because issuing it would be
 * actively harmful, and a privileged command is not free — it is a round trip through a root shell
 * or the Shizuku binder:
 *
 * 1. **Not defined on this build** ([SelfPermissionDeclaration.Undefined]).
 *    `PackageManagerShellCommand.runGrantRevokePermission` resolves the name first and answers
 *    `Unknown permission: …`. Nothing to grant, and nothing a retry could improve.
 * 2. **Defined, but neither `dangerous` nor `development`.** `grantRuntimePermission` throws
 *    `SecurityException("… is not a changeable permission type")` for anything else. That covers
 *    `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `QUERY_ALL_PACKAGES` and Loki's own
 *    `signature` receiver permission — four of the eight names on a real device.
 * 3. **Already held.** Re-granting is a no-op that still costs the round trip — and for a fatal
 *    permission it is far worse than a wasted round trip: grant → platform kill → relaunch →
 *    grant is a launch loop. This guard is the only thing standing between one death and an
 *    unusable app.
 * 4. **Taken once already, and gone.** A `dangerous` name in [grantedOnce] that Loki no longer holds
 *    was *revoked*, and the actor who can do that through the UI is the user. See below.
 * 5. A [SelfPermissionDeclaration.Unknown] is not a rejection: it is left out of [toGrant] *and*
 *    recorded in [SelfGrantPlan.hasUnanswered], so the run can be repeated.
 *
 * Pure, because everything above is a decision and none of it is a binder call. `PackageManager` is
 * abstract and `:app` has no mocking library by policy, so a rule that touches an Android type is a
 * rule no test can reach.
 *
 * ⚠️ **On a first run this overrides a decision the user may have made deliberately**, because a
 * permission is in [toGrant] precisely for being ungranted and nothing on the device distinguishes
 * "never asked" from "denied". That is the owner's explicit call for privileged users — the point of
 * granting Loki root or Shizuku is not to keep being asked — and it is why the sweep runs *only*
 * once a privilege gateway is live, and why nothing here touches another package's permissions.
 *
 * What it must never do is override the *same* decision twice, and [grantedOnce] is the whole of
 * that fix. `POST_NOTIFICATIONS` is granted, the user turns Loki's notifications off in Settings,
 * and the next cold start used to hand it straight back — an app arguing with its own user. A name
 * we have already taken is therefore taken once and never again.
 *
 * The exemption is deliberate and asymmetric: [grantedOnce] is consulted for `dangerous` permissions
 * only. A `development` permission has **no user-visible switch** anywhere in Settings, so its
 * absence cannot be a user's choice — it is a reinstall, an `adb revoke`, or a factory reset — and
 * re-granting `READ_LOGS` is the behaviour the feature exists for.
 */
fun planSelfGrant(
    permissions: List<SelfPermission>,
    grantedOnce: Set<String> = emptySet(),
): SelfGrantPlan {
    val survivable = mutableListOf<String>()
    val fatal = mutableListOf<String>()
    var hasUnanswered = false

    for (permission in permissions) {
        when (val declaration = permission.declaration) {
            SelfPermissionDeclaration.Unknown -> hasUnanswered = true
            SelfPermissionDeclaration.Undefined -> Unit
            is SelfPermissionDeclaration.Declared -> {
                if (permission.isGranted) continue
                when {
                    // Checked before isDangerous, which is what keeps a development permission out
                    // of the grantedOnce exemption rather than merely usually out of it.
                    declaration.permission.isDevelopment -> fatal += permission.name
                    declaration.permission.isDangerous && permission.name !in grantedOnce ->
                        survivable += permission.name
                }
            }
        }
    }

    return SelfGrantPlan(
        toGrant = survivable + fatal,
        fatal = fatal.toSet(),
        hasUnanswered = hasUnanswered,
    )
}

/**
 * Everything a privileged command line here is allowed to contain.
 *
 * Deliberately narrower than "valid package name": no `-`, no `/`, no whitespace, nothing a shell
 * gives meaning to. A component's class name and a permission name fit inside it as well.
 */
private val SAFE = Regex("[A-Za-z0-9._]+")

/**
 * The `pm grant` command line for one of **Loki's own** permissions.
 *
 * Returns `null` — meaning "run nothing" — for any argument outside [SAFE]. Both arguments come
 * from `PackageManager` today, so the guard can never fire; it exists so that stops being
 * load-bearing if either ever stops being ours. Everywhere else in Loki, AGENTS.md rule 1 holds
 * because only an `Int` uid from `PackageManager` reaches a command line; a root shell takes a
 * string rather than an argv list, which makes this the one place that needs saying out loud.
 *
 * **No `--user` flag.** A `development` permission is recorded as an *install* permission, which
 * applies to every user — verified on device, where `dumpsys package com.valhalla.loki` lists
 * `READ_LOGS: granted=true` under `install permissions:` and leaves `runtime permissions:` without
 * it.
 */
fun selfGrantCommand(packageName: String, permission: String): String? =
    if (!SAFE.matches(packageName) || !SAFE.matches(permission)) {
        null
    } else {
        "pm grant $packageName $permission"
    }

/**
 * A detached command that brings Loki back after the platform has killed it for the grant.
 *
 * **This has to be issued *before* the fatal grant, not after.** Both halves of that sentence cost
 * a device session to learn, and both are recorded in
 * [PermissionManager.grantReadLogsViaShizuku]: chaining `am start` directly after the grant won a
 * millisecond-wide race and delivered the intent to the already-dying Activity, and making the
 * death deterministic first never reached the second command at all.
 *
 * So the shape is: arm this, then grant. [delaySeconds] is what lets the kill complete before the
 * launch, and `nohup … &` with both streams closed is what stops Odin's `exec` from waiting on a
 * command that outlives the call — and, on Magisk, what lets the grandchild outlive *us*, since the
 * root shell is forked from `magiskd` rather than from Loki and carries no death signal.
 *
 * ⚠️ **Root only.** Shizuku ties a `newProcess` shell to the client that asked for it, so nothing
 * launched from one can survive the client's death; the Shizuku path warns the user instead.
 *
 * **The `pidof` guard is the point of the second half of the line.** Arming has to happen before the
 * grant, so by the time the command runs nothing can call it off — and a grant that *failed* leaves
 * Loki alive, whereupon an unconditional `am start` two seconds later drags the app to the
 * foreground over whatever the user has since moved on to. The previous version of this comment
 * called that harmless. It is not: it is an app launching itself for no reason, and the failure it
 * follows is precisely the case where nothing should happen at all. So the relaunch fires only when
 * our process is genuinely gone.
 *
 * `pidof <packageName>` is the process name, not the package: they coincide because Loki declares no
 * `android:process` anywhere in its manifest, and moving a component to its own process means
 * revisiting this. The lookup works because the command runs as **root**, for which `/proc` is not
 * `hidepid`-restricted — the same reason a `pidof` for *another* app fails everywhere else in Loki.
 *
 * The guard errs in the mild direction. If the platform revives the process headlessly inside the
 * delay — a query to `LokiDocumentsProvider`, say — `pidof` finds it and no Activity is started, so
 * the user taps the icon. That is a worse outcome than a relaunch and a much better one than a
 * hijack.
 *
 * Returns `null` if either component name falls outside [SAFE].
 */
fun selfRelaunchCommand(
    packageName: String,
    activityClassName: String,
    delaySeconds: Int,
): String? {
    if (!SAFE.matches(packageName) || !SAFE.matches(activityClassName)) return null
    if (delaySeconds < 0) return null
    return "nohup sh -c 'sleep $delaySeconds; " +
        "pidof $packageName >/dev/null 2>&1 || " +
        "am start -n $packageName/$activityClassName' >/dev/null 2>&1 &"
}
