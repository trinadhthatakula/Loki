package com.valhalla.loki

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.valhalla.loki.model.Packages
import com.valhalla.loki.model.PermissionManager
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

    /** Null until DataStore has answered once. The splash screen stays up while it is. */
    private val themeSettings = MutableStateFlow<ThemeSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

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
                        onRequestShizuku = { requestShizuku() },
                    )
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

    private fun requestShizuku() {
        // isRootAvailable() suspends — a root probe must not run on the main thread.
        lifecycleScope.launch {
            try {
                if (permissionManager.isRootAvailable()) {
                    Log.d(TAG, "checkShizukuPermission: root found")
                    return@launch
                }

                if (packages.getApplicationInfoOrNull("moe.shizuku.privileged.api") == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Shizuku is not installed, please install it and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
                Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                Log.d(TAG, "root not found trying shizuku")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                } else {
                    requestReadLogs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun requestReadLogs() {
        if (shizukuBound)
            grantReadLogs()
        else {
            onShizukuChange = {
                if (shizukuBound)
                    grantReadLogs()
            }
        }
    }

    /**
     * Runs the actual `pm grant`. The result is surfaced rather than discarded — a privileged
     * command that silently failed is the worst of both worlds.
     */
    private fun grantReadLogs() {
        lifecycleScope.launch {
            val granted = permissionManager.grantReadLogsViaShizuku()
            Toast.makeText(
                this@MainActivity,
                if (granted) "READ_LOGS granted via Shizuku."
                else "Shizuku could not grant READ_LOGS.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var shizukuBound = false
    private var onShizukuChange: (() -> Unit)? = null

    val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBound = false
        onShizukuChange?.invoke()
    }

    val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuBound = true
        onShizukuChange?.invoke()
    }

    val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Shizuku permission granted")
                    requestReadLogs()
                } else {
                    Log.d(TAG, "Shizuku permission denied")
                    Toast.makeText(
                        this,
                        "Shizuku permission denied, please grant it and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    private companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}
