package red.kitsu.heartosc

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class VRChatOSCSender(
    private val host: String,
    private val port: Int,
    private val pulseGenerator: HeartbeatPulseGenerator,
    private val hrParam: String,
    private val hrConnectedParam: String,
    private val heartbeatToggleParam: String,
    private val heartbeatPulseParam: String,
    private val vrcoscCompatibilityEnabled: Boolean
) {
    companion object {
        private const val TAG = "VRChatOSCSender"
    }

    private var socket: DatagramSocket? = null
    private var currentHeartRate: Int? = null
    private var isConnected: Boolean = false
    private val heartRateSamples = ArrayDeque<Pair<Long, Int>>()
    private var toggleObserverJob: Job? = null
    private var pulseObserverJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                if (vrcoscCompatibilityEnabled) {
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

    fun updateHeartRate(bpm: Int?) {
        if (currentHeartRate == bpm) return

        currentHeartRate = bpm

        if (bpm != null) {
            sendIntParameter(hrParam, bpm)
            if (vrcoscCompatibilityEnabled) {
                sendVrcoscHeartRate(bpm)
            }
            Log.d(TAG, "Sent HR: $bpm bpm")
        } else {
            heartRateSamples.clear()
            if (vrcoscCompatibilityEnabled) {
                sendVrcoscHeartRateUnavailable()
            }
        }
    }

    fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        sendBoolParameter(hrConnectedParam, isConnected)
        if (vrcoscCompatibilityEnabled) {
            sendBoolParameter(VrcoscHeartrateParameters.CONNECTED, isConnected)
            sendBoolParameter(VrcoscHeartrateParameters.ENABLED, isConnected)
        }
        Log.d(TAG, "Sent isHRConnected: $isConnected")
    }

    private fun sendVrcoscHeartRate(bpm: Int) {
        val now = System.currentTimeMillis()
        heartRateSamples.addLast(now to bpm)
        while (heartRateSamples.firstOrNull()?.first?.let {
                it + VrcoscHeartrateParameters.AVERAGE_PERIOD_MS <= now
            } == true
        ) {
            heartRateSamples.removeFirst()
        }

        val average = heartRateSamples.map { it.second }.average().roundToInt()
        val (units, tens, hundreds) = VrcoscHeartrateParameters.legacyDigits(bpm)

        sendIntParameter(VrcoscHeartrateParameters.VALUE, bpm)
        sendFloatParameter(VrcoscHeartrateParameters.NORMALISED, VrcoscHeartrateParameters.normalised(bpm))
        sendIntParameter(VrcoscHeartrateParameters.AVERAGE, average)
        sendFloatParameter(VrcoscHeartrateParameters.UNITS, units)
        sendFloatParameter(VrcoscHeartrateParameters.TENS, tens)
        sendFloatParameter(VrcoscHeartrateParameters.HUNDREDS, hundreds)
    }

    private fun sendVrcoscHeartRateUnavailable() {
        sendIntParameter(VrcoscHeartrateParameters.VALUE, 0)
        sendFloatParameter(VrcoscHeartrateParameters.NORMALISED, 0f)
        sendIntParameter(VrcoscHeartrateParameters.AVERAGE, 0)
        sendFloatParameter(VrcoscHeartrateParameters.UNITS, 0f)
        sendFloatParameter(VrcoscHeartrateParameters.TENS, 0f)
        sendFloatParameter(VrcoscHeartrateParameters.HUNDREDS, 0f)
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
        scope.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
    }
}
