package com.valhalla.loki.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.valhalla.loki.R
import com.valhalla.loki.model.AppInfo
import com.valhalla.loki.model.LogcatCapture
import com.valhalla.loki.model.logsDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

private const val TAG = "LogcatService"

/**
 * The foreground service that owns one capture at a time.
 *
 * **The capture writes straight to its final location.** There used to be a fixed temp file —
 * `cacheDir/loki_temp_log.log` — copied into `logsDir/<package>/` when the user pressed stop, and
 * that one indirection was responsible for four separate defects:
 *
 *  - *Cross-app attribution.* The promote step keyed off "does the temp file exist", not "did *this*
 *    capture write it". A capture that ended without ever opening its writer — no privilege, or the
 *    package not installed for this user — promoted whatever the *previous* capture had left behind,
 *    filing app A's logcat under app B's name. Under AGENTS.md rule 2 that is a disclosure bug, not
 *    a cosmetic one.
 *  - *Truncation mid-save.* The "already running" flag was released before the copy finished, so
 *    starting the next capture reopened the same fixed path and truncated the file being copied —
 *    losing both logs, silently.
 *  - *Lost captures on abnormal teardown.* The save was a coroutine on the service scope, and
 *    `onDestroy` cancelled that scope. Any stop the *system* initiated — battery restriction, the
 *    `dataSync` timeout below — cancelled the save before it ran, and the whole capture went with it.
 *  - *A full copy of a sensitive log left in the cache.* It was only unlinked on the success path.
 *
 * Writing to the destination directly removes all four by construction rather than by guarding: the
 * log is saved *as it is captured*, so there is no window in which it can be misfiled, truncated or
 * dropped, and nothing has to survive teardown for the user to keep their data. The filename is
 * per-capture, so two captures can never contend for one path.
 *
 * ### Teardown has exactly one path
 *
 * Every way a capture can end — the notification action, the UI, the six-hour timeout, no privilege
 * being available at all — funnels into [LogcatCapture]'s `onExit` and from there into
 * [onCaptureEnded]. Stop requests only ask the capture to stop; they do not tear the service down
 * themselves. That is what keeps "who calls `stopSelf`" from depending on which of three racing
 * callers arrived first, which is how the previous version lost saves.
 */
class LogcatService : Service() {

    private val logcatCapture: LogcatCapture by inject()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val ACTION_START = "com.valhalla.loki.ACTION_START"
        const val ACTION_STOP = "com.valhalla.loki.ACTION_STOP"
        const val EXTRA_APP_INFO = "EXTRA_APP_INFO"

        private const val NOTIFICATION_CHANNEL_ID = "LOKI_LOGCAT_CHANNEL"
        private const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        /**
         * The file the running capture is writing, or null.
         *
         * Now a real saved log rather than a scratch file, which is why the live view can tail it
         * and why the same path is what ends up in the Saved list.
         */
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
                val appInfo = AppInfo.fromString(intent.getStringExtra(EXTRA_APP_INFO))
                if (appInfo == null) {
                    // No state claimed yet, so there is nothing to unwind — but the service was
                    // started, so it still has to be stopped or it lingers with no notification.
                    Log.w(TAG, "ACTION_START without a usable AppInfo; ignoring")
                    stopSelf()
                } else {
                    startLogging(appInfo)
                }
            }

            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    private fun startLogging(appInfo: AppInfo) {
        if (!_isRunning.compareAndSet(expect = false, update = true)) {
            Log.w(TAG, "a capture is already running; ignoring start")
            return
        }

        val dir = File(logsDir, appInfo.packageName)
        if (!dir.exists() && !dir.mkdirs()) {
            // Without the directory the capture's first write throws somewhere far less obvious,
            // so fail here, where it can actually be reported to the user.
            Log.e(TAG, "could not create ${dir.path}")
            _isRunning.value = false
            toast("Could not create a place to save the log.")
            stopSelf()
            return
        }
        val destination = File(dir, "${System.currentTimeMillis()}.log")

        _currentLogFile.value = destination
        startForeground(NOTIFICATION_ID, createNotification(appInfo.appName ?: "Unknown"))

        logcatCapture.start(
            appInfo = appInfo,
            scope = serviceScope,
            outputFile = destination,
            onExit = { onCaptureEnded(destination) }
        )
    }

    /**
     * Asks the capture to stop; [onCaptureEnded] does the actual teardown.
     *
     * Idempotent, and safe to call when nothing is running — which it is, when the notification
     * action is tapped as the capture is already ending.
     */
    private fun requestStop() {
        if (!_isRunning.value) {
            // No capture to end, so no `onExit` is coming and nothing else will stop the service.
            stopSelf()
            return
        }
        logcatCapture.stop()
    }

    /**
     * The single teardown path, invoked on the main thread once per capture.
     *
     * [LogcatCapture] calls this from its own `finally`, which runs after the writer's `use` block
     * has closed — so [captured]'s length is final here and safe to act on. Doing the tidy-up on
     * the stop *request* instead would race the last flush.
     */
    private fun onCaptureEnded(captured: File) {
        _isRunning.value = false
        _currentLogFile.value = null

        // stopSelf() waits for the tidy: the service must not be destroyed out from under it,
        // because onDestroy cancels this scope.
        serviceScope.launch {
            val empty = captured.length() == 0L
            if (empty) {
                captured.delete()
                // Do not leave a package directory behind that only ever held the empty file.
                captured.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
            }
            toast(if (empty) "No log output was captured." else "Log saved successfully!")
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * The `dataSync` foreground-service timeout, API 35+.
     *
     * A `dataSync` service is capped at six hours per 24, and the default implementation of this
     * does nothing — which ends with the process killed by
     * `ForegroundServiceDidNotStopInTimeException`. An overnight capture is squarely within what
     * Loki is for, so stop cleanly and say why rather than being force-stopped. The capture is
     * already saved either way; this is about the app surviving to tell the user.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "foreground service timed out; stopping the capture")
        toast("Logging stopped: Android limits background capture to six hours.")
        requestStop()
    }

    private fun createNotification(appName: String): android.app.Notification {
        val stopSelfIntent = Intent(this, LogcatService::class.java).apply {
            action = ACTION_STOP
        }
        val pStopSelf = PendingIntent.getService(
            this, 0, stopSelfIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

    /** Toasts from whatever thread the caller is on. */
    private fun toast(message: String) {
        val context = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // Close the privileged handle before the scope goes away — cancelling the coroutine alone
        // would leave a root `logcat` running with nobody reading it. Unlike the previous version,
        // no user data depends on what runs after this point: the capture was written to its final
        // location as it went, so the worst a cancelled teardown can now cost is an empty file left
        // in the list.
        logcatCapture.stop()
        _isRunning.value = false
        _currentLogFile.value = null
        serviceJob.cancel()
        // Teardown first, super last — the mirror of onCreate. Inert as it stands, because
        // Service.onDestroy() is empty, but it stops the ordering from being a thing a future reader
        // has to check.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
