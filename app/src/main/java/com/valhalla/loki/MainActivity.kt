package com.valhalla.loki

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.valhalla.loki.model.Packages
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.model.rootAvailable
import com.valhalla.loki.ui.home.HomeScreen
import com.valhalla.loki.ui.onboarding.OnboardingScreen
import com.valhalla.loki.ui.theme.LokiTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent {
            LokiTheme {
                var canGoForward by remember {
                    mutableStateOf(
                        PermissionManager.isRootAvailable() || PermissionManager.hasReadLogsPermission(
                            this
                        )
                    )
                }
                //if (canGoForward) {
                    HomeScreen(onExitConfirmed = { finish() })
                /*} else {
                    OnboardingScreen(
                        onShizukuRequested = {
                            requestShizuku()
                        },
                        onSetupComplete = {
                            canGoForward =
                                PermissionManager.isRootAvailable() || PermissionManager.hasReadLogsPermission(
                                    this
                                )
                        }
                    )
                }*/
            }
        }
    }

    private fun requestShizuku() {
        try {
            if (rootAvailable().not()) {
                Packages(this).getApplicationInfoOrNull(packageName = "moe.shizuku.privileged.api")
                    .let {
                        if (it == null) {
                            Toast.makeText(
                                this,
                                "Shizuku is not installed, please install it and try again.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
                            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
                            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
                            Log.d("HomeActivity", "root not found trying shizuku")
                            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                Shizuku.requestPermission(1001)
                            } else {
                                requestReadLogs()
                            }
                        }
                    }
            } else {
                Log.d("HomeActivity", "checkShizukuPermission: root found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestReadLogs() {
        if (shizukuBound)
            PermissionManager.grantReadLogsViaShizuku(this)
        else {
            onShizukuChange = {
                if (shizukuBound)
                    PermissionManager.grantReadLogsViaShizuku(this)
            }
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
            if (requestCode == 1001) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.d("HomeActivity", "Shizuku permission granted")
                    requestReadLogs()
                } else {
                    Log.d("HomeActivity", "Shizuku permission denied")
                    Toast.makeText(
                        this,
                        "Shizuku permission denied, please grant it and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

}
