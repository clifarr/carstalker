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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carstalker.app.CarStalkerApp
import com.carstalker.app.MainActivity
import com.carstalker.app.R

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
        if (!session.recording.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            startForegroundCompat()
            acquireLocks()
        }.onFailure {
            // A denied Bluetooth permission or a background start restriction must not crash
            // the app; recording still works while the activity is in the foreground.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.recording_notification_stop), stopIntent())
            .build()
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            hasBluetoothPermission() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

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
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val ACTION_STOP = "com.carstalker.app.action.STOP_RECORDING"
        private const val WAKE_LOCK_TAG = "CarStalker:recording"
        private const val WIFI_LOCK_TAG = "CarStalker:recording"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RecordingService::class.java),
                )
            }
        }

        // stopService matches the component, not the Intent instance; lint mistakes the new
        // Intent for a SAM instance.
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
