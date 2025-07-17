package com.valhalla.loki

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.ui.home.HomeScreen
import com.valhalla.loki.ui.onboarding.OnboardingScreen
import com.valhalla.loki.ui.theme.LokiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent {
            LokiTheme {
                if (PermissionManager.isRootAvailable() || PermissionManager.hasReadLogsPermission(
                        this
                    )
                ) HomeScreen(onExitConfirmed = { finish() })
                else OnboardingScreen {
                    this.recreate()
                }
            }
        }
    }
}
