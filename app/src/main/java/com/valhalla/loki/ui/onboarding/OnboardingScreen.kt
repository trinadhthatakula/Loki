package com.valhalla.loki.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

import com.valhalla.loki.R
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = koinViewModel(),
    onSetupComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // If Shizuku grant was successful, trigger the onSetupComplete callback
    LaunchedEffect(uiState.grantViaShizukuSuccess) {
        if (uiState.grantViaShizukuSuccess == true) {
            Toast.makeText(context, "Permission granted successfully!", Toast.LENGTH_SHORT).show()
            onSetupComplete()
        } else if (uiState.grantViaShizukuSuccess == false) {
            Toast.makeText(context, "Failed to grant permission via Shizuku.", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome to Loki", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("To read logs from other apps, Loki needs special permissions. Please choose a method below.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))

        // --- Root Option ---
        if (uiState.isRootAvailable) {
            PermissionCard(
                title = "Root Access",
                description = "Use the existing root access on your device. This is the most powerful method.",
                buttonText = "Continue with Root",
                onClick = onSetupComplete // Just proceed, no extra action needed
            )
            Spacer(Modifier.height(16.dp))
        }

        // --- Shizuku Option ---
        if (uiState.isShizukuAvailable) {
            PermissionCard(
                title = "Shizuku",
                description = "Use the Shizuku app to grant the necessary permission automatically. This does not require root.",
                buttonText = "Grant via Shizuku",
                isLoading = uiState.grantViaShizukuInProgress,
                onClick = { viewModel.grantPermissionViaShizuku(context) }
            )
            Spacer(Modifier.height(16.dp))
        }

        // --- ADB Option ---
        AdbCard()
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClick,
                enabled = !isLoading,
                modifier = Modifier.align(Alignment.End)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun AdbCard() {
    val context = LocalContext.current
    val adbCommand = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Manual ADB Command", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Connect your device to a computer with ADB and run the following command in your terminal:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = adbCommand,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("ADB Command", adbCommand)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(painterResource(R.drawable.copy), "Copy command")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { /* User needs to manually confirm */ },
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(painterResource(R.drawable.done), "Done", modifier = Modifier.padding(end = 8.dp))
                Text("I have run the command")
            }
        }
    }
}