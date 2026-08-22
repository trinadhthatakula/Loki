package com.valhalla.loki

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.valhalla.asgard.components.AsgardDialogScaffold
import com.valhalla.loki.model.Packages
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.model.SelfGrantState
import com.valhalla.loki.model.SelfPermissionGrabber
import com.valhalla.loki.model.ThemeManager
import com.valhalla.loki.model.ThemeMode
import com.valhalla.loki.model.ThemeSettings
import com.valhalla.loki.ui.home.HomeScreen
import com.valhalla.loki.ui.onboarding.OnboardingScreen
import com.valhalla.loki.ui.theme.LokiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val permissionManager: PermissionManager by inject()
    private val packages: Packages by inject()
    private val themeManager: ThemeManager by inject()
    private val selfPermissionGrabber: SelfPermissionGrabber by inject()

    /** Null until DataStore has answered once. The splash screen stays up while it is. */
    private val themeSettings = MutableStateFlow<ThemeSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

        // Registered once for the Activity's lifetime, and removed in onDestroy. Shizuku holds
        // these in static ArrayLists and adds without de-duplicating, so registering them inside
        // the button handler — as this did — added another copy of all three, and leaked another
        // Activity, on every tap and every rotation.
        //
        // Sticky is the load-bearing word. ShizukuProvider delivers the binder during application
        // startup, so by the time any Activity exists `onBinderReceived` has already fired; the
        // plain addBinderReceivedListener only fires on a *later* one and would sit there dead
        // forever. That is precisely what silently broke the grant: the flag it set was never set,
        // so the grant was never issued and nothing was logged.
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // The sweep Loki.onCreate() kicked off deliberately did nothing. Application.onCreate runs
        // for headless process starts too — any app opening a file picker makes DocumentsUI read
        // LokiDocumentsProvider, which starts this process — and a privileged grant there kills the
        // appId and lets the armed relaunch put Loki over whatever the user was doing. This is the
        // first point at which a UI provably exists, so it is where the sweep is allowed to spend
        // privilege. Idempotent: a rotation re-enters here and returns.
        selfPermissionGrabber.onUiPresent()

        // DataStore answers asynchronously, so the first composition would otherwise paint the
        // DEFAULT theme and flip to the stored one a frame or two later — a visible flash on every
        // cold start for anyone who is not on the defaults. Holding the splash costs nothing (it is
        // already on screen) and cannot deadlock: ThemeManager.settings catches IOException and
        // emits defaults, so the flow always produces a first value.
        splashScreen.setKeepOnScreenCondition { themeSettings.value == null }
        lifecycleScope.launch {
            themeManager.settings.collect { themeSettings.value = it }
        }

        setContent {
            val settings by themeSettings.collectAsState()
            // collectAsState, not collectAsStateWithLifecycle: lifecycle-runtime-compose is not a
            // dependency, so the lifecycle-aware variant does not exist here.
            val grantState by selfPermissionGrabber.state.collectAsState()
            // Nothing to draw until the stored theme arrives; the splash screen covers this.
            settings?.let { theme ->
                LokiTheme(
                    darkTheme = when (theme.mode) {
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    },
                    dynamicColor = theme.dynamicColor,
                    amoledMode = theme.amoled,
                ) {
                    // The onboarding gate is currently disabled. `canGoForward` is commented out
                    // with it deliberately: probing root is a `su` spawn (and possibly a
                    // root-manager prompt), and doing that on every cold start for a value nothing
                    // reads is noise. Restore it together with the OnboardingScreen branch below.
                    /*
                    var canGoForward by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        canGoForward = permissionManager.isRootAvailable() ||
                                permissionManager.hasReadLogsPermission()
                    }
                    */
                    //if (canGoForward) {
                    HomeScreen(
                        onExitConfirmed = { finish() },
                        onRequestPrivilege = { requestPrivilegeGrant() },
                    )

                    // Only the Shizuku path ever reaches this. Root grants silently and puts Loki
                    // back on its own, which is the difference the user chose; Shizuku cannot,
                    // because the shell running the grant is torn down with us — so it asks.
                    (grantState as? SelfGrantState.Offered)?.let { offer ->
                        // Keyed on the offer, so it logs once per offer rather than once per
                        // recomposition — a bare Log.d in a composable body is a log per frame.
                        LaunchedEffect(offer) {
                            Log.d(TAG, "offering ${offer.permissions.joinToString()} via ${offer.channel}")
                        }
                        AsgardDialogScaffold(
                            onDismissRequest = { selfPermissionGrabber.dismissOffered() },
                            title = "Grant READ_LOGS and close Loki?",
                            // Deliberately does not promise prompt-free capture: holding
                            // READ_LOGS is what subjects Loki to logd's per-request consent
                            // dialog. What the grant actually buys is persistence — it survives a
                            // reboot, and the user can stop using Shizuku entirely.
                            text = "Loki can take this permission for itself using the access " +
                                "you have already granted it. Android closes an app when its " +
                                "permissions change, so Loki will shut down as soon as the grant " +
                                "lands — that is expected, not a crash. Reopen it afterwards and " +
                                "it can read other apps' logs without any further setup.",
                            icon = Icons.Filled.Key,
                            confirmText = "Grant and close",
                            dismissText = "Not now",
                            onConfirm = { selfPermissionGrabber.confirmOffered() },
                        )
                    }
                    /*} else {
                        OnboardingScreen(
                            onShizukuRequested = {
                                requestShizuku()
                            },
                            onSetupComplete = {
                                canGoForward = permissionManager.isRootAvailable() ||
                                        permissionManager.hasReadLogsPermission()
                            }
                        )
                    }*/
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    /**
     * Set while the user is actually waiting on a Shizuku grant.
     *
     * Shizuku's callbacks are process-wide and fire for reasons that have nothing to do with a
     * button press — restarting the Shizuku app raises `onBinderReceived` on its own — so without
     * this, resuming the request from a listener would run a privileged `pm grant` nobody asked
     * for.
     */
    private var shizukuGrantPending = false

    /**
     * The user asked for `READ_LOGS`. Routes to whichever privilege can actually deliver it.
     *
     * Root first, and that arm used to be a dead end: this method logged "root available; no
     * Shizuku grant needed" and returned, so a rooted user confirmed a dialog promising "Grant and
     * close" and nothing happened, with no message. Root can grant — it just could not before,
     * because there was no root grant path to route to.
     */
    private fun requestPrivilegeGrant() {
        // isRootAvailable() suspends — a root probe must not run on the main thread.
        lifecycleScope.launch {
            try {
                if (permissionManager.isRootAvailable()) {
                    // No confirmation and no toast on success, because there is nobody left to
                    // show one to: the grant kills this process, and the armed relaunch inside
                    // grantViaRoot is what brings Loki back.
                    grantReadLogsViaRoot()
                    return@launch
                }

                if (packages.getApplicationInfoOrNull(SHIZUKU_PACKAGE) == null) {
                    toast("Shizuku is not installed, please install it and try again.")
                    return@launch
                }

                shizukuGrantPending = true
                if (!Shizuku.pingBinder()) {
                    // Installed but not running. The request is deliberately left pending: the
                    // sticky listener picks it back up if the user starts Shizuku and returns.
                    toast("Shizuku is installed but not running. Start it, then try again.")
                    return@launch
                }
                continueShizukuGrant()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shizukuGrantPending = false
                Log.w(TAG, "Shizuku request failed", e)
            }
        }
    }

    /**
     * Arms the relaunch, then grants through the root shell.
     *
     * Already on a coroutine and already known to have root, so this is only the ordering: arm
     * first, grant second. The reasoning for that order — and the two device-verified failures that
     * produced it — is on `PermissionManager.armSelfRelaunchViaRoot`.
     */
    private suspend fun grantReadLogsViaRoot() {
        permissionManager.armSelfRelaunchViaRoot()
        val attempt = permissionManager.grantSelfViaRoot(android.Manifest.permission.READ_LOGS)
        // Reaching this at all means we survived, which for READ_LOGS means the grant did not land.
        if (!permissionManager.hasReadLogsPermission()) {
            Log.w(TAG, "root could not grant READ_LOGS: ${attempt.detail}")
            toast("Root could not grant READ_LOGS.")
        }
    }

    /**
     * Second half of [requestPrivilegeGrant] — reached directly when Shizuku is already up, or from
     * [shizukuBinderReceivedListener] once it arrives.
     */
    private fun continueShizukuGrant() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                grantReadLogs()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // Shizuku's own "denied, and do not ask again". requestPermission() would return
                // denied without drawing anything, so explain it instead of appearing to do
                // nothing.
                shizukuGrantPending = false
                toast("Loki was denied Shizuku access. Allow it in the Shizuku app, then retry.")
                return
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) // resumes in the permission listener
        } catch (e: Exception) {
            shizukuGrantPending = false
            Log.w(TAG, "Shizuku grant could not be started", e)
        }
    }

    /**
     * Runs the actual `pm grant`.
     *
     * There is no success toast, because there is nobody left to show one to: granting
     * `READ_LOGS` kills this process (see `PermissionManager.grantReadLogsViaShizuku`). Reaching
     * the toast below at all means the grant failed and we survived to say so.
     *
     * Nothing restarts Loki on this path, and an earlier version of this comment claimed otherwise.
     * Shizuku ties its `newProcess` shell to the client, so the shell dies with us; only the root
     * path can arm a relaunch, which is what `grantReadLogsViaRoot` does.
     */
    private fun grantReadLogs() {
        shizukuGrantPending = false
        lifecycleScope.launch {
            if (!permissionManager.grantReadLogsViaShizuku()) {
                toast("Shizuku could not grant READ_LOGS.")
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (shizukuGrantPending) continueShizukuGrant()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        // Nothing to undo. Shizuku.pingBinder() is the source of truth everywhere it is needed,
        // so there is no cached "bound" flag here left to go stale — which is what the previous
        // version got wrong. A request that is still pending simply waits for the next
        // onBinderReceived.
        Log.d(TAG, "Shizuku binder died")
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE || !shizukuGrantPending) {
                return@OnRequestPermissionResultListener
            }
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Shizuku permission granted")
                grantReadLogs()
            } else {
                Log.d(TAG, "Shizuku permission denied")
                shizukuGrantPending = false
                toast("Shizuku permission denied, please grant it and try again.")
            }
        }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
