package com.valhalla.loki.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.valhalla.loki.R
import com.valhalla.loki.model.AppInfo
import com.valhalla.loki.model.LogcatCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import java.io.File

class LogcatService : Service() {

    private val logcatCapture: LogcatCapture by inject()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // --- ADDED: To keep track of the app being logged ---
    private var currentAppInfo: AppInfo? = null

    companion object {
        const val ACTION_START = "com.valhalla.loki.ACTION_START"
        const val ACTION_STOP = "com.valhalla.loki.ACTION_STOP"
        const val EXTRA_APP_INFO = "EXTRA_APP_INFO"

        private const val NOTIFICATION_CHANNEL_ID = "LOKI_LOGCAT_CHANNEL"
        private const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _currentLogFile = MutableStateFlow<File?>(null)
        val currentLogFile = _currentLogFile.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val appInfoString = intent.getStringExtra(EXTRA_APP_INFO)
                val appInfo = AppInfo.fromString(appInfoString)
                appInfo?.let { startLogging(it) }
            }
            ACTION_STOP -> {
                // The stop logic now includes saving the file
                stopLogging()
            }
        }
        return START_NOT_STICKY
    }

    private fun startLogging(appInfo: AppInfo) {
        if (_isRunning.value) return

        _isRunning.value = true
        currentAppInfo = appInfo // Store the current app's info

        // Use the cache directory for the temporary log file
        val logFile = File(cacheDir, "loki_temp_log.log")
        _currentLogFile.value = logFile

        startForeground(NOTIFICATION_ID, createNotification(appInfo.appName ?: "Unknown"))

        logcatCapture.start(
            appInfo = appInfo,
            scope = serviceScope,
            outputFile = logFile,
            onExit = {
                // Fires when the capture ends for any reason, including no privilege being
                // available at all.
                stopLogging()
            }
        )
    }

    private fun stopLogging() {
        // Claim the stop. `stop()` below ends the capture, which fires onExit, which calls back
        // in here — without this guard the log would be saved twice and the service stopped twice.
        if (!_isRunning.compareAndSet(expect = true, update = false)) return

        // Get values before they are reset
        val appToLog = currentAppInfo
        val tempLogFile = _currentLogFile.value

        // Stop the underlying logcat process
        logcatCapture.stop()

        // Launch a coroutine to handle file I/O without blocking
        serviceScope.launch {
            if (appToLog != null && tempLogFile != null && tempLogFile.exists()) {
                try {
                    // Define the permanent storage location
                    val destinationDir = File(filesDir, "logs/${appToLog.packageName}")
                    if (!destinationDir.exists()) {
                        destinationDir.mkdirs()
                    }
                    val destinationFile = File(destinationDir, "${System.currentTimeMillis()}.log")

                    // Copy the temp file to the permanent location and delete the temp file
                    tempLogFile.copyTo(destinationFile, overwrite = true)
                    tempLogFile.delete()

                    // Show a confirmation toast on the main thread
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Log saved successfully!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Failed to save log file.", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                }
            }

            // Reset state and stop the service from the main thread after I/O is done.
            // _isRunning was already cleared by the compareAndSet guard above.
            withContext(Dispatchers.Main) {
                _currentLogFile.value = null
                currentAppInfo = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }


    private fun createNotification(appName: String): android.app.Notification {
        val stopSelfIntent = Intent(this, LogcatService::class.java).apply {
            action = ACTION_STOP
        }
        val pStopSelf = PendingIntent.getService(
            this, 0, stopSelfIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Loki Logger")
            .setContentText("Actively logging: $appName")
            .setSmallIcon(R.drawable.launch_foreground)
            .setOngoing(true)
            .addAction(R.drawable.force_close, "Stop", pStopSelf)
            .build()
    }

    private fun createNotificationChannel() {
        // No SDK_INT guard: notification channels arrived in O (26) and minSdk is 28.
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Loki Logging Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the privileged shell before the scope goes away — cancelling the coroutine alone
        // would leave a root `logcat` running with nobody reading it.
        logcatCapture.stop()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
