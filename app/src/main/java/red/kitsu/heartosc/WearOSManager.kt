package red.kitsu.heartosc

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class WearOSManager(private val context: Context) : MessageClient.OnMessageReceivedListener {
    companion object {
        private const val TAG = "WearOSManager"
        private const val WEAR_PATH_HR = "/heartrate"
        private const val WEAR_CAPABILITY = "heartosc_wear_app"
        private const val TIMEOUT_MS = 30000L
    }

    private val _connectionState = MutableStateFlow<HeartRateMonitorManager.ConnectionState>(HeartRateMonitorManager.ConnectionState.Disconnected)
    val connectionState: StateFlow<HeartRateMonitorManager.ConnectionState> = _connectionState.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _latestHeartRateSample = MutableStateFlow<HeartRateSample?>(null)
    internal val latestHeartRateSample: StateFlow<HeartRateSample?> =
        _latestHeartRateSample.asStateFlow()

    private val _heartRateSamples = MutableSharedFlow<HeartRateSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val heartRateSamples: SharedFlow<HeartRateSample> = _heartRateSamples

    @Volatile
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
        clearHeartRate()
        timeoutJob?.cancel()
        Log.d(TAG, "Stopped listening to Wear OS messages")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!isListening) return

        if (messageEvent.path == WEAR_PATH_HR) {
            val buffer = ByteBuffer.wrap(messageEvent.data)
            if (buffer.remaining() >= 4) {
                val bpm = buffer.int
                publishHeartRate(bpm)
                _connectionState.value = HeartRateMonitorManager.ConnectionState.Connected
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
                    clearHeartRate()
                    Log.d(TAG, "Watch connection timed out, waiting for messages...")
                }
            }
        }
    }

    private fun publishHeartRate(bpm: Int) {
        val sample = HeartRateSample(bpm, System.currentTimeMillis())
        lastReceivedTime = sample.receivedAtMillis
        _heartRate.value = bpm
        _latestHeartRateSample.value = sample
        _heartRateSamples.tryEmit(sample)
    }

    private fun clearHeartRate() {
        _heartRate.value = null
        _latestHeartRateSample.value = null
    }

    suspend fun findTargetNode(): Node? = withContext(Dispatchers.IO) {
        try {
            val capabilityClient = Wearable.getCapabilityClient(context)
            val capabilityInfo = Tasks.await(capabilityClient.getCapability(WEAR_CAPABILITY, CapabilityClient.FILTER_ALL))
            var node = capabilityInfo.nodes.firstOrNull { it.isNearby } ?: capabilityInfo.nodes.firstOrNull()
            if (node == null) {
                val nodeClient = Wearable.getNodeClient(context)
                val connected = Tasks.await(nodeClient.connectedNodes)
                node = connected.firstOrNull { it.isNearby } ?: connected.firstOrNull()
            }
            node
        } catch (e: Exception) {
            Log.e(TAG, "Error finding target Wear OS node", e)
            null
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
