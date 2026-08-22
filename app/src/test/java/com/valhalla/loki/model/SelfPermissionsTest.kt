package com.valhalla.loki.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [planSelfGrant], [selfGrantCommand] and [selfRelaunchCommand].
 *
 * Permission names are spelled out rather than read from `android.Manifest`, as Thor's equivalent
 * test does, so a reader can see which of Loki's declarations each case is actually about.
 *
 * The device-shaped cases use the order `dumpsys package com.valhalla.loki` really reports on a
 * device with Shizuku installed, which is not the order of `app/src/main/AndroidManifest.xml` —
 * the merged manifest adds what Shizuku's provider AAR contributes, and `READ_LOGS` lands sixth of
 * eight. That is exactly why the ordering assertions below are not cosmetic.
 */
class SelfPermissionsTest {

    private companion object {
        const val READ_LOGS = "android.permission.READ_LOGS"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
        const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"
        const val SHIZUKU_API_V23 = "shizuku.permission.API_V23"
        const val SHIZUKU_MANAGER_API_V23 = "moe.shizuku.manager.permission.API_V23"
        const val RECEIVER_NOT_EXPORTED =
            "com.valhalla.loki.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** `dangerous`, like POST_NOTIFICATIONS: grantable, `gids=[]`, does not kill us. */
    private fun dangerous(name: String, granted: Boolean = false) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Declared(
            DeclaredPermission(isDangerous = true, isDevelopment = false)
        ),
        isGranted = granted,
    )

    /** `signature|privileged|development`, like READ_LOGS: grantable, and it kills us. */
    private fun development(name: String, granted: Boolean = false) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Declared(
            DeclaredPermission(isDangerous = false, isDevelopment = true)
        ),
        isGranted = granted,
    )

    /** `normal` or `signature`: defined, and `pm grant` refuses it. */
    private fun installTime(name: String, granted: Boolean = false) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Declared(
            DeclaredPermission(isDangerous = false, isDevelopment = false)
        ),
        isGranted = granted,
    )

    private fun undefined(name: String) =
        SelfPermission(name, SelfPermissionDeclaration.Undefined, isGranted = false)

    private fun unanswered(name: String) =
        SelfPermission(name, SelfPermissionDeclaration.Unknown, isGranted = false)

    // --- the case Thor's suite structurally cannot contain --------------------------------------

    @Test
    fun `a development permission that is not held is grantable, and fatal`() {
        val plan = planSelfGrant(listOf(development(READ_LOGS)))

        assertEquals(listOf(READ_LOGS), plan.toGrant)
        assertEquals(setOf(READ_LOGS), plan.fatal)
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `an already held development permission plans nothing`() {
        // The guard that separates one death from a launch loop: grant, get killed, relaunch,
        // grant again, forever.
        val plan = planSelfGrant(listOf(development(READ_LOGS, granted = true)))

        assertTrue(plan.toGrant.isEmpty())
        assertTrue(plan.fatal.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    // --- the rules inherited from Thor ---------------------------------------------------------

    @Test
    fun `a dangerous permission is grantable and is not fatal`() {
        val plan = planSelfGrant(listOf(dangerous(POST_NOTIFICATIONS)))

        assertEquals(listOf(POST_NOTIFICATIONS), plan.toGrant)
        assertTrue(plan.fatal.isEmpty())
    }

    @Test
    fun `an already held dangerous permission plans nothing`() {
        val plan = planSelfGrant(listOf(dangerous(POST_NOTIFICATIONS, granted = true)))

        assertTrue(plan.toGrant.isEmpty())
    }

    @Test
    fun `an ungranted install-time permission is still skipped`() {
        // pm grant answers "not a changeable permission type" for all four of these.
        val plan = planSelfGrant(
            listOf(
                installTime(FOREGROUND_SERVICE),
                installTime(FOREGROUND_SERVICE_DATA_SYNC),
                installTime(QUERY_ALL_PACKAGES),
                installTime(RECEIVER_NOT_EXPORTED),
            )
        )

        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `an undefined permission is skipped and the run still completes`() {
        // Shizuku not installed. An authoritative answer about the device, so a retry finds
        // nothing new and the latch must be allowed to close.
        val plan = planSelfGrant(listOf(undefined(SHIZUKU_API_V23)))

        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `a permission the device would not classify keeps the run repeatable`() {
        val plan = planSelfGrant(listOf(unanswered(READ_LOGS)))

        assertTrue(plan.toGrant.isEmpty())
        assertTrue(plan.hasUnanswered)
    }

    @Test
    fun `one unanswered probe does not hold back the grants that are known`() {
        val plan = planSelfGrant(listOf(unanswered(SHIZUKU_API_V23), development(READ_LOGS)))

        assertEquals(listOf(READ_LOGS), plan.toGrant)
        assertTrue(plan.hasUnanswered)
    }

    // --- the user's revoke has to win ----------------------------------------------------------

    @Test
    fun `a dangerous permission taken once is never taken again`() {
        // The defect this closes: grant POST_NOTIFICATIONS, user turns Loki's notifications off in
        // Settings, next cold start hands it straight back. An app arguing with its own user.
        val plan = planSelfGrant(
            listOf(dangerous(POST_NOTIFICATIONS)),
            grantedOnce = setOf(POST_NOTIFICATIONS),
        )

        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `a development permission is taken again even after being revoked`() {
        // The asymmetry, and it is deliberate: READ_LOGS has no switch anywhere in Settings, so its
        // absence cannot be something the user chose. A reinstall or an `adb revoke` is the only way
        // to get here, and re-granting is the whole point of the feature.
        val plan = planSelfGrant(
            listOf(development(READ_LOGS)),
            grantedOnce = setOf(READ_LOGS),
        )

        assertEquals(listOf(READ_LOGS), plan.toGrant)
        assertEquals(setOf(READ_LOGS), plan.fatal)
    }

    @Test
    fun `remembering a permission does not disturb the rest of the plan`() {
        val plan = planSelfGrant(
            listOf(
                dangerous(POST_NOTIFICATIONS),
                dangerous("android.permission.SOMETHING_ELSE"),
                development(READ_LOGS),
            ),
            grantedOnce = setOf(POST_NOTIFICATIONS),
        )

        assertEquals(listOf("android.permission.SOMETHING_ELSE", READ_LOGS), plan.toGrant)
        assertEquals(setOf(READ_LOGS), plan.fatal)
    }

    @Test
    fun `an unknown name in the remembered set changes nothing`() {
        // The set only ever grows, so it outlives a permission being dropped from the manifest.
        val plan = planSelfGrant(
            listOf(dangerous(POST_NOTIFICATIONS)),
            grantedOnce = setOf("android.permission.LONG_GONE"),
        )

        assertEquals(listOf(POST_NOTIFICATIONS), plan.toGrant)
    }

    // --- ordering, which is where this parts company with Thor ---------------------------------

    @Test
    fun `the fatal grant is issued last`() {
        // Declaration order would put READ_LOGS first and POST_NOTIFICATIONS would never be
        // attempted, because the process is gone by then.
        val plan = planSelfGrant(listOf(development(READ_LOGS), dangerous(POST_NOTIFICATIONS)))

        assertEquals(listOf(POST_NOTIFICATIONS, READ_LOGS), plan.toGrant)
        assertEquals(setOf(READ_LOGS), plan.fatal)
    }

    @Test
    fun `declaration order is preserved among the survivable grants`() {
        val plan = planSelfGrant(
            listOf(
                dangerous("android.permission.B"),
                dangerous("android.permission.A"),
                development(READ_LOGS),
                dangerous("android.permission.C"),
            )
        )

        assertEquals(
            listOf("android.permission.B", "android.permission.A", "android.permission.C", READ_LOGS),
            plan.toGrant,
        )
    }

    // --- whole-device fixtures ----------------------------------------------------------------

    @Test
    fun `Loki's manifest on API 36 plans two grants with READ_LOGS last`() {
        val plan = planSelfGrant(
            listOf(
                installTime(RECEIVER_NOT_EXPORTED, granted = true),
                dangerous(POST_NOTIFICATIONS),
                installTime(FOREGROUND_SERVICE, granted = true),
                undefined(SHIZUKU_API_V23),
                installTime(FOREGROUND_SERVICE_DATA_SYNC, granted = true),
                development(READ_LOGS),
                undefined(SHIZUKU_MANAGER_API_V23),
                installTime(QUERY_ALL_PACKAGES, granted = true),
            )
        )

        assertEquals(listOf(POST_NOTIFICATIONS, READ_LOGS), plan.toGrant)
        assertEquals(setOf(READ_LOGS), plan.fatal)
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `on API 28 only READ_LOGS is grantable`() {
        // POST_NOTIFICATIONS arrived in API 33 and FOREGROUND_SERVICE_DATA_SYNC in API 34, so on
        // Loki's minSdk both are Undefined — and neither absence may block the latch.
        val plan = planSelfGrant(
            listOf(
                undefined(POST_NOTIFICATIONS),
                installTime(FOREGROUND_SERVICE, granted = true),
                undefined(FOREGROUND_SERVICE_DATA_SYNC),
                development(READ_LOGS),
                undefined(SHIZUKU_API_V23),
            )
        )

        assertEquals(listOf(READ_LOGS), plan.toGrant)
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun `an empty manifest plans nothing and is complete`() {
        val plan = planSelfGrant(emptyList())

        assertTrue(plan.toGrant.isEmpty())
        assertTrue(plan.fatal.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    // --- the command lines --------------------------------------------------------------------

    @Test
    fun `selfGrantCommand builds the exact pm grant line`() {
        assertEquals(
            "pm grant com.valhalla.loki android.permission.READ_LOGS",
            selfGrantCommand("com.valhalla.loki", READ_LOGS),
        )
    }

    @Test
    fun `selfGrantCommand refuses an argument carrying shell metacharacters`() {
        assertNull(selfGrantCommand("com.valhalla.loki; rm -rf /", READ_LOGS))
        assertNull(selfGrantCommand("com.valhalla.loki", "$(id)"))
        assertNull(selfGrantCommand("com.valhalla.loki", "a b"))
        assertNull(selfGrantCommand("", READ_LOGS))
    }

    @Test
    fun `selfRelaunchCommand detaches, sleeps, then starts our own launcher activity`() {
        // The `pidof … ||` is not decoration. Arming happens before the grant, so nothing can call
        // the command off afterwards; a grant that failed leaves Loki alive, and an unconditional
        // `am start` would then haul the app to the foreground for no reason. Asserted as an exact
        // string because it is a root shell command line and every character of it matters.
        assertEquals(
            "nohup sh -c 'sleep 2; pidof com.valhalla.loki >/dev/null 2>&1 || " +
                "am start -n com.valhalla.loki/com.valhalla.loki.MainActivity' >/dev/null 2>&1 &",
            selfRelaunchCommand("com.valhalla.loki", "com.valhalla.loki.MainActivity", 2),
        )
    }

    @Test
    fun `selfRelaunchCommand refuses anything it would have to quote`() {
        assertNull(selfRelaunchCommand("com.valhalla.loki", "Main Activity", 2))
        assertNull(selfRelaunchCommand("pkg'; id; '", "com.valhalla.loki.MainActivity", 2))
        assertNull(selfRelaunchCommand("com.valhalla.loki", "com.valhalla.loki.MainActivity", -1))
    }
}
