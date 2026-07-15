package red.kitsu.heartosc

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class WearOSManager(private val context: Context) : MessageClient.OnMessageReceivedListener {
    companion object {
        private const val TAG = "WearOSManager"
        private const val WEAR_PATH_HR = "/heartrate"
        private const val TIMEOUT_MS = 6000L
    }

    private val _connectionState = MutableStateFlow<HeartRateMonitorManager.ConnectionState>(HeartRateMonitorManager.ConnectionState.Disconnected)
    val connectionState: StateFlow<HeartRateMonitorManager.ConnectionState> = _connectionState.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private var isListening = false
    private var lastReceivedTime = 0L
    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startListening() {
        if (isListening) return
        isListening = true
        _connectionState.value = HeartRateMonitorManager.ConnectionState.Connecting
        Wearable.getMessageClient(context).addListener(this)
        startTimeoutChecker()
        Log.d(TAG, "Started listening to Wear OS messages")
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        Wearable.getMessageClient(context).removeListener(this)
        _connectionState.value = HeartRateMonitorManager.ConnectionState.Disconnected
        _heartRate.value = null
        timeoutJob?.cancel()
        Log.d(TAG, "Stopped listening to Wear OS messages")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == WEAR_PATH_HR) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            if (buffer.remaining() >= 4) {
                val bpm = buffer.int
                _heartRate.value = bpm
                _connectionState.value = HeartRateMonitorManager.ConnectionState.Connected
                lastReceivedTime = System.currentTimeMillis()
                Log.d(TAG, "Received Wear OS Heart Rate: $bpm bpm")
            }
        }
    }

    private fun startTimeoutChecker() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (_connectionState.value is HeartRateMonitorManager.ConnectionState.Connected &&
                    System.currentTimeMillis() - lastReceivedTime > TIMEOUT_MS) {
                    _connectionState.value = HeartRateMonitorManager.ConnectionState.Connecting
                    _heartRate.value = null
                    Log.d(TAG, "Watch connection timed out, waiting for messages...")
                }
            }
        }
    }

    fun cleanup() {
        stopListening()
    }

    fun destroy() {
        cleanup()
        scope.cancel()
    }
}
