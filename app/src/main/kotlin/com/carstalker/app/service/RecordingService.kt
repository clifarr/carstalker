package com.carstalker.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carstalker.app.CarStalkerApp
import com.carstalker.app.MainActivity
import com.carstalker.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Keeps the polling loop alive while a drive is recorded.
 *
 * Android freezes an ordinary background process shortly after the activity leaves the
 * foreground, which used to leave a long empty stretch in the middle of a recording. A
 * foreground service raises the process priority, and the partial wake lock keeps the CPU
 * running so the poll loop can keep asking the adapter for samples. The Wi-Fi lock does the
 * same for adapters that are reached over a network instead of Bluetooth.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var failureReported = false
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeat: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as CarStalkerApp).graph.session.stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }
        val session = (application as CarStalkerApp).graph.session
        if (!session.isRecording) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
            acquireLocks()
            startHeartbeat()
        } catch (t: Throwable) {
            // A denied permission or a background-start restriction must not crash the app.
            // Recording still works while the activity is in the foreground, but the user must
            // be told that lock-screen capture is no longer protected.
            val reason = "Background protection failed: ${t.message ?: "the recording service could not start"}. " +
                "Recording continues only while the app stays open."
            reportFailure(session, reason)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        heartbeat?.cancel()
        heartbeat = null
        heartbeatScope.cancel()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val session = (application as CarStalkerApp).graph.session
        if (session.isRecording && !failureReported) {
            reportFailure(
                session,
                "Android stopped the recording service. Background capture has ended; " +
                    "check the recordings list for a partial file.",
            )
        }
        super.onDestroy()
    }

    private fun reportFailure(session: ObdSession, reason: String) {
        failureReported = true
        session.reportBackgroundFailure(reason)
        postFailureNotification(reason)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        // connectedDevice is correct for every supported transport: the app is talking to an
        // external adapter over Bluetooth Classic, BLE, Wi-Fi or USB. dataSync is for file and
        // cloud transfer and is subject to Android 15 time limits, so it must not be used here.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * Keeps the ongoing notification honest: the sample count and the clock time of the newest
     * sample. If the phone freezes the process, this text stops advancing, which is the only
     * visible sign the user gets that nothing is being written.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() guards the call; notify is in runCatching.
    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = heartbeatScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!canPostNotifications()) continue
                runCatching {
                    NotificationManagerCompat.from(this@RecordingService)
                        .notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): android.app.Notification {
        val progress = (application as CarStalkerApp).graph.session.recordingProgress()
        val text = if (progress.lastSampleAt <= 0L) {
            getString(R.string.recording_notification_waiting)
        } else {
            val clock = android.text.format.DateFormat.getTimeFormat(this)
                .format(Date(progress.lastSampleAt))
            getString(R.string.recording_notification_progress, progress.rows, clock)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.recording_notification_stop), stopIntent())
            .build()
            .apply {
                // The heartbeat updates the notification with notify() instead of calling
                // startForeground again (which would re-check the while-in-use FGS permission
                // from the background). Keep the flag so the update stays non-dismissible.
                flags = flags or android.app.Notification.FLAG_FOREGROUND_SERVICE
            }
    }

    private fun postFailureNotification(reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(getString(R.string.recording_failed_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent())
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(FAILURE_NOTIFICATION_ID, notification)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            runCatching {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.recording_alerts_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.recording_alerts_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannels(listOf(channel, alerts))
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val ALERT_CHANNEL_ID = "recording_alerts"
        private const val NOTIFICATION_ID = 42
        private const val FAILURE_NOTIFICATION_ID = 43
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val ACTION_STOP = "com.carstalker.app.action.STOP_RECORDING"
        private const val WAKE_LOCK_TAG = "CarStalker:recording"
        private const val WIFI_LOCK_TAG = "CarStalker:recording"

        /** How often the ongoing notification's sample count and last-sample clock refresh. */
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** Returns false when the OS refused to start the foreground service at all. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java),
            )
            true
        }.getOrDefault(false)

        // stopService matches the component, not the Intent instance; lint mistakes the new
        // Intent for a SAM instance.
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
