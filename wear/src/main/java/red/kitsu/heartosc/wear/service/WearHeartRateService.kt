package red.kitsu.heartosc.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import red.kitsu.heartosc.wear.WearMainActivity
import red.kitsu.heartosc.wear.sensor.WearHeartRateManager
import java.nio.ByteBuffer

class WearHeartRateService : Service() {

    companion object {
        private const val TAG = "WearHeartRateService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "wear_heart_rate_channel"
        private const val WEAR_PATH_HR = "/heartrate"

        const val ACTION_START = "red.kitsu.heartosc.wear.ACTION_START"
        const val ACTION_STOP = "red.kitsu.heartosc.wear.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentBpm = MutableStateFlow(0)
        val currentBpm: StateFlow<Int> = _currentBpm.asStateFlow()
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: WearHeartRateManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): WearHeartRateService = this@WearHeartRateService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = WearHeartRateManager(this)
        Log.d(TAG, "WearHeartRateService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startForegroundTracking()
            ACTION_STOP -> stopForegroundTracking()
        }
        return START_STICKY
    }

    private fun startForegroundTracking() {
        if (_isRunning.value) return

        val notification = createNotification(0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isRunning.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground health service", e)
            _isRunning.value = false
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground tracking service", e)
            _isRunning.value = false
            stopSelf()
            return
        }

        acquireWakeLock()

        sensorManager?.start { bpm ->
            _currentBpm.value = bpm
            updateNotification(bpm)
            sendHeartRateToPhone(bpm)
        }
        Log.d(TAG, "Started foreground tracking on Wear OS")
    }

    private fun stopForegroundTracking() {
        if (!_isRunning.value) return

        sensorManager?.stop()
        releaseWakeLock()

        _isRunning.value = false
        _currentBpm.value = 0
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }
        stopSelf()
        Log.d(TAG, "Stopped foreground tracking on Wear OS")
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HeartOSC::WearTrackingWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max timeout
            }
            Log.d(TAG, "Acquired partial wake lock for Wear OS background streaming")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
        wakeLock = null
        Log.d(TAG, "Released wake lock")
    }

    private fun sendHeartRateToPhone(bpm: Int) {
        if (!_isRunning.value) return
        val payload = ByteBuffer.allocate(4).putInt(bpm).array()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (!_isRunning.value) return@addOnSuccessListener
            val messageClient = Wearable.getMessageClient(this)
            for (node in nodes) {
                messageClient.sendMessage(node.id, WEAR_PATH_HR, payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "Sent BPM $bpm to companion node: ${node.displayName}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed sending BPM to node ${node.displayName}", e)
                    }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed getting connected nodes", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HeartOSC Wear Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous Heart Rate Streaming Service for Wear OS"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(bpm: Int): Notification {
        val intent = Intent(this, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, WearHeartRateService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (bpm > 0) "Streaming: $bpm BPM" else "Measuring Heart Rate..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeartOSC Wear")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(bpm: Int) {
        if (!_isRunning.value) return
        try {
            val notification = createNotification(bpm)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundTracking()
        Log.d(TAG, "WearHeartRateService destroyed")
    }
}
