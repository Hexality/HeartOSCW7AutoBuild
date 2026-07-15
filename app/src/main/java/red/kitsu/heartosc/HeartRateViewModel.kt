package red.kitsu.heartosc

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private data class OscConfig(
    val host: String,
    val port: Int,
    val hrParam: String,
    val hrConnectedParam: String,
    val heartbeatToggleParam: String,
    val heartbeatPulseParam: String
)

class HeartRateViewModel(application: Application) : AndroidViewModel(application) {

    private val heartRateManager = HeartRateMonitorManager(application)
    private val wearOSManager = WearOSManager(application)
    private val settingsManager = SettingsManager(application)
    private val pulseGenerator = HeartbeatPulseGenerator(viewModelScope)
    private var oscSender: VRChatOSCSender? = null
    var heartRateService: HeartRateService? = null

    private val _connectionState = MutableStateFlow<HeartRateMonitorManager.ConnectionState>(HeartRateMonitorManager.ConnectionState.Disconnected)
    val connectionState: StateFlow<HeartRateMonitorManager.ConnectionState> = _connectionState.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    val energyExpended = heartRateManager.energyExpended
    val rrIntervals = heartRateManager.rrIntervals
    val discoveredDevices = heartRateManager.discoveredDevices
    val scanningState = heartRateManager.scanningState

    val inputSource = settingsManager.inputSource
    val oscHost = settingsManager.oscHost
    val oscPort = settingsManager.oscPort
    val hrParam = settingsManager.hrParam
    val hrConnectedParam = settingsManager.hrConnectedParam
    val heartbeatToggleParam = settingsManager.heartbeatToggleParam
    val heartbeatPulseParam = settingsManager.heartbeatPulseParam
    val heartbeatPulseDuration = settingsManager.heartbeatPulseDuration

    // Expose pulse state for UI
    val heartbeatPulse = pulseGenerator.pulseState

    init {
        // Route connectionState based on active inputSource
        viewModelScope.launch {
            combine(inputSource, heartRateManager.connectionState, wearOSManager.connectionState) { source, bleState, wearState ->
                if (source == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) wearState else bleState
            }.collect { state ->
                _connectionState.value = state
            }
        }

        // Route heartRate based on active inputSource
        viewModelScope.launch {
            combine(inputSource, heartRateManager.heartRate, wearOSManager.heartRate) { source, bleHr, wearHr ->
                if (source == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) wearHr else bleHr
            }.collect { bpm ->
                _heartRate.value = bpm
            }
        }

        // Stop active source when switching input sources
        viewModelScope.launch {
            inputSource.collect { source ->
                if (source == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) {
                    @Suppress("MissingPermission")
                    heartRateManager.cleanup()
                } else {
                    wearOSManager.cleanup()
                }
                _connectionState.value = HeartRateMonitorManager.ConnectionState.Disconnected
                _heartRate.value = null
            }
        }

        // Initialize OSC sender with current settings
        viewModelScope.launch {
            combine(
                combine(oscHost, oscPort) { host, port -> Pair(host, port) },
                combine(hrParam, hrConnectedParam) { hr, hrConn -> Pair(hr, hrConn) },
                combine(heartbeatToggleParam, heartbeatPulseParam) { hbToggle, hbPulse -> Pair(hbToggle, hbPulse) }
            ) { hostPort, hrParams, hbParams ->
                OscConfig(
                    hostPort.first,
                    hostPort.second,
                    hrParams.first,
                    hrParams.second,
                    hbParams.first,
                    hbParams.second
                )
            }.collect { config ->
                // Recreate OSC sender when settings change
                oscSender?.cleanup()
                oscSender = VRChatOSCSender(
                    config.host,
                    config.port,
                    pulseGenerator,
                    config.hrParam,
                    config.hrConnectedParam,
                    config.heartbeatToggleParam,
                    config.heartbeatPulseParam
                )
            }
        }

        // Monitor heart rate changes and manage pulse generator
        viewModelScope.launch {
            heartRate.collect { bpm ->
                oscSender?.updateHeartRate(bpm)
                heartRateService?.updateHeartRate(bpm)

                // Start/stop pulse generator based on BPM
                if (bpm != null && bpm > 0) {
                    pulseGenerator.start(bpm)
                } else {
                    pulseGenerator.stop()
                }
            }
        }

        // Monitor connection state and send to OSC
        viewModelScope.launch {
            connectionState.collect { state ->
                val isConnected = state is HeartRateMonitorManager.ConnectionState.Connected ||
                                 state is HeartRateMonitorManager.ConnectionState.Discovering
                // During reconnection, maintain the last connected state for OSC
                val shouldSendConnected = isConnected || state is HeartRateMonitorManager.ConnectionState.Reconnecting
                oscSender?.updateConnectionState(shouldSendConnected)
                heartRateService?.updateConnectionState(shouldSendConnected)

                // Stop pulse generator when disconnected (but not when reconnecting)
                if (!isConnected && state !is HeartRateMonitorManager.ConnectionState.Reconnecting) {
                    pulseGenerator.stop()
                }
            }
        }

        // Monitor pulse duration changes and update pulse generator
        viewModelScope.launch {
            heartbeatPulseDuration.collect { duration ->
                pulseGenerator.setPulseDuration(duration.toLong())
            }
        }
    }

    fun checkPermissions(): Boolean {
        return heartRateManager.checkPermissions()
    }

    fun isBluetoothEnabled(): Boolean {
        return heartRateManager.isBluetoothEnabled()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION])
    fun startScan() {
        viewModelScope.launch {
            heartRateManager.startScan()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        viewModelScope.launch {
            heartRateManager.stopScan()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            heartRateManager.connectToDevice(device)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        viewModelScope.launch {
            heartRateManager.disconnect()
        }
    }

    fun toggleWearOSConnection() {
        if (connectionState.value is HeartRateMonitorManager.ConnectionState.Disconnected) {
            wearOSManager.startListening()
        } else {
            wearOSManager.stopListening()
        }
    }

    fun setInputSource(source: String) {
        settingsManager.setInputSource(source)
    }

    fun setOscHost(host: String) {
        settingsManager.setOscHost(host)
    }

    fun setOscPort(port: Int) {
        settingsManager.setOscPort(port)
    }

    fun setHrParam(param: String) {
        settingsManager.setHrParam(param)
    }

    fun setHrConnectedParam(param: String) {
        settingsManager.setHrConnectedParam(param)
    }

    fun setHeartbeatToggleParam(param: String) {
        settingsManager.setHeartbeatToggleParam(param)
    }

    fun setHeartbeatPulseParam(param: String) {
        settingsManager.setHeartbeatPulseParam(param)
    }

    fun setHeartbeatPulseDuration(duration: Int) {
        settingsManager.setHeartbeatPulseDuration(duration)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCleared() {
        super.onCleared()
        pulseGenerator.cleanup()
        oscSender?.cleanup()
        heartRateManager.cleanup()
        wearOSManager.destroy()
    }
}
