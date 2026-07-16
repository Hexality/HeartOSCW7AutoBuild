package red.kitsu.heartosc

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VRChatOSCSender(
    private val host: String,
    private val port: Int,
    private val pulseGenerator: HeartbeatPulseGenerator,
    private val hrParam: String,
    private val hrConnectedParam: String,
    private val heartbeatToggleParam: String,
    private val heartbeatPulseParam: String,
    val vrcoscCompatibilityEnabled: Boolean
) {
    companion object {
        private const val TAG = "VRChatOSCSender"
    }

    private var socket: DatagramSocket? = null
    private var currentHeartRate: Int? = null
    private var isConnected: Boolean = false
    private val vrcoscTracker = VrcoscHeartRateTracker()
    private var toggleObserverJob: Job? = null
    private var pulseObserverJob: Job? = null
    private var receivingTimeoutJob: Job? = null

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    init {
        try {
            socket = DatagramSocket()
            Log.d(TAG, "OSC sender initialized for $host:$port")
            observePulseGenerator()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket", e)
        }
    }

    private fun observePulseGenerator() {
        // Observe toggle state changes
        toggleObserverJob = scope.launch {
            pulseGenerator.toggleState.collect { toggleState ->
                sendBoolParameter(heartbeatToggleParam, toggleState)
                if (vrcoscCompatibilityEnabled &&
                    vrcoscTracker.isReceiving(System.currentTimeMillis())
                ) {
                    sendBoolParameter(VrcoscHeartrateParameters.BEAT, toggleState)
                }
                // Also send connection state with each heartbeat
                sendBoolParameter(hrConnectedParam, isConnected)
            }
        }

        // Observe pulse state changes
        pulseObserverJob = scope.launch {
            pulseGenerator.pulseState.collect { pulseState ->
                sendBoolParameter(heartbeatPulseParam, pulseState)
            }
        }
    }

    internal fun updateHeartRateSample(sample: HeartRateSample) {
        if (currentHeartRate != sample.bpm) {
            currentHeartRate = sample.bpm
            sendIntParameter(hrParam, sample.bpm)
            Log.d(TAG, "Sent HR: ${sample.bpm} bpm")
        }

        if (vrcoscCompatibilityEnabled) {
            val values = vrcoscTracker.record(sample)
            sendVrcoscAvailability(true)
            sendVrcoscHeartRate(values)
            scheduleReceivingTimeout()
        }
    }

    internal fun replayHeartRate(sample: HeartRateSample?) {
        if (sample == null ||
            sample.receivedAtMillis + VrcoscHeartrateParameters.RECEIVING_TIMEOUT_MS < System.currentTimeMillis()
        ) {
            markHeartRateUnavailable()
            return
        }

        updateHeartRateSample(sample)
    }

    fun markHeartRateUnavailable() {
        currentHeartRate = null
        receivingTimeoutJob?.cancel()
        receivingTimeoutJob = null
        vrcoscTracker.clear()
        if (vrcoscCompatibilityEnabled) {
            sendVrcoscAvailability(false)
            sendBoolParameter(VrcoscHeartrateParameters.BEAT, false)
            sendVrcoscHeartRateUnavailable()
        }
    }

    fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        sendBoolParameter(hrConnectedParam, isConnected)
        Log.d(TAG, "Sent isHRConnected: $isConnected")
    }

    private fun sendVrcoscHeartRate(values: VrcoscHeartRateValues) {
        sendIntParameter(VrcoscHeartrateParameters.VALUE, values.bpm)
        sendFloatParameter(VrcoscHeartrateParameters.NORMALISED, values.normalised)
        sendIntParameter(VrcoscHeartrateParameters.AVERAGE, values.average)
        sendFloatParameter(VrcoscHeartrateParameters.UNITS, values.units)
        sendFloatParameter(VrcoscHeartrateParameters.TENS, values.tens)
        sendFloatParameter(VrcoscHeartrateParameters.HUNDREDS, values.hundreds)
    }

    private fun sendVrcoscAvailability(receiving: Boolean) {
        sendBoolParameter(VrcoscHeartrateParameters.CONNECTED, receiving)
        sendBoolParameter(VrcoscHeartrateParameters.ENABLED, receiving)
    }

    private fun sendVrcoscHeartRateUnavailable() {
        sendIntParameter(VrcoscHeartrateParameters.VALUE, 0)
        sendFloatParameter(VrcoscHeartrateParameters.NORMALISED, 0f)
        sendIntParameter(VrcoscHeartrateParameters.AVERAGE, 0)
        sendFloatParameter(VrcoscHeartrateParameters.UNITS, 0f)
        sendFloatParameter(VrcoscHeartrateParameters.TENS, 0f)
        sendFloatParameter(VrcoscHeartrateParameters.HUNDREDS, 0f)
    }

    private fun scheduleReceivingTimeout() {
        receivingTimeoutJob?.cancel()
        receivingTimeoutJob = scope.launch {
            delay(vrcoscTracker.millisecondsUntilStale(System.currentTimeMillis()))
            if (!vrcoscTracker.isReceiving(System.currentTimeMillis())) {
                vrcoscTracker.clear()
                sendVrcoscAvailability(false)
                sendBoolParameter(VrcoscHeartrateParameters.BEAT, false)
                sendVrcoscHeartRateUnavailable()
            }
        }
    }

    suspend fun clearVrcoscCompatibility() {
        if (!vrcoscCompatibilityEnabled) return

        receivingTimeoutJob?.cancelAndJoin()
        toggleObserverJob?.cancelAndJoin()
        pulseObserverJob?.cancelAndJoin()
        scopeJob.cancelAndJoin()
        vrcoscTracker.clear()

        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.CONNECTED, false))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.ENABLED, false))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.BEAT, false))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.VALUE, 0))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.NORMALISED, 0f))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.AVERAGE, 0))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.UNITS, 0f))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.TENS, 0f))
        sendMessage(buildOSCMessage(VrcoscHeartrateParameters.HUNDREDS, 0f))
    }

    private fun sendIntParameter(address: String, value: Int) {
        scope.launch {
            try {
                val message = buildOSCMessage(address, value)
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send int parameter $address", e)
            }
        }
    }

    private fun sendBoolParameter(address: String, value: Boolean) {
        scope.launch {
            try {
                val message = buildOSCMessage(address, value)
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send bool parameter $address", e)
            }
        }
    }

    private fun sendFloatParameter(address: String, value: Float) {
        scope.launch {
            try {
                val message = buildOSCMessage(address, value)
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send float parameter $address", e)
            }
        }
    }

    private fun buildOSCMessage(address: String, value: Int): ByteArray {
        val addressBytes = padString(address)
        val typeTag = padString(",i")
        val valueBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

        return addressBytes + typeTag + valueBytes
    }

    private fun buildOSCMessage(address: String, value: Boolean): ByteArray {
        val addressBytes = padString(address)
        // OSC uses 'T' for true, 'F' for false in type tag
        val typeTag = padString(if (value) ",T" else ",F")

        return addressBytes + typeTag
    }

    private fun buildOSCMessage(address: String, value: Float): ByteArray {
        val addressBytes = padString(address)
        val typeTag = padString(",f")
        val valueBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array()

        return addressBytes + typeTag + valueBytes
    }

    private fun padString(str: String): ByteArray {
        val bytes = str.toByteArray(Charsets.US_ASCII)
        val paddedSize = ((bytes.size + 4) / 4) * 4 // Round up to multiple of 4
        return bytes + ByteArray(paddedSize - bytes.size) // Pad with zeros
    }

    private suspend fun sendMessage(message: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(host)
                val packet = DatagramPacket(message, message.size, address, port)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send OSC message to $host:$port", e)
            }
        }
    }

    fun updateHostPort(newHost: String, newPort: Int) {
        // Recreation is handled by ViewModel when settings change
        Log.d(TAG, "Host/Port update requested: $newHost:$newPort")
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up OSC sender")
        toggleObserverJob?.cancel()
        pulseObserverJob?.cancel()
        receivingTimeoutJob?.cancel()
        scopeJob.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
    }
}
