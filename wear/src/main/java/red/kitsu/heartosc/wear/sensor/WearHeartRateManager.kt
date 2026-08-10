package red.kitsu.heartosc.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.*

class WearHeartRateManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "WearHeartRateManager"
    }

    private var onBpmListener: ((Int) -> Unit)? = null
    private var isTracking = false

    // Health Services (Primary for Wear OS 3+ / One UI Watch)
    private val healthServicesClient by lazy { HealthServices.getClient(context) }
    private val measureClient by lazy { healthServicesClient.measureClient }
    private var isUsingHealthServices = false

    // Legacy SensorManager (Fallback)
    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var heartRateSensor: Sensor? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val measureCallback = object : MeasureCallback {
        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            for (point in points) {
                val bpm = point.value.toInt()
                if (bpm > 0) {
                    Log.d(TAG, "HealthServices HR BPM: $bpm")
                    onBpmListener?.invoke(bpm)
                }
            }
        }

        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability
        ) {
            Log.d(TAG, "HealthServices availability changed for $dataType: $availability")
        }
    }

    fun start(onBpm: (Int) -> Unit) {
        if (isTracking) return
        isTracking = true
        onBpmListener = onBpm

        scope.launch {
            try {
                val capabilities = measureClient.getCapabilitiesAsync().await()
                if (DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure) {
                    Log.d(TAG, "Registering MeasureClient for HEART_RATE_BPM")
                    measureClient.registerMeasureCallback(
                        DataType.HEART_RATE_BPM,
                        measureCallback
                    )
                    isUsingHealthServices = true
                    return@launch
                }
            } catch (e: Throwable) {
                Log.w(TAG, "HealthServices register failed, falling back to SensorManager", e)
            }

            // Fallback to standard SensorManager
            startSensorManagerFallback()
        }
    }

    private fun startSensorManagerFallback() {
        isUsingHealthServices = false
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        heartRateSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "Started tracking with legacy SensorManager")
        } ?: run {
            Log.e(TAG, "No heart rate sensor available on device")
        }
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false

        if (isUsingHealthServices) {
            scope.launch {
                try {
                    measureClient.unregisterMeasureCallbackAsync(
                        DataType.HEART_RATE_BPM,
                        measureCallback
                    ).await()
                    Log.d(TAG, "Unregistered MeasureClient callback")
                } catch (e: Throwable) {
                    Log.e(TAG, "Error unregistering MeasureClient callback", e)
                }
            }
        } else {
            sensorManager.unregisterListener(this)
            Log.d(TAG, "Unregistered legacy SensorManager listener")
        }
        onBpmListener = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isUsingHealthServices && event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            if (bpm > 0) {
                Log.d(TAG, "SensorManager HR BPM: $bpm")
                onBpmListener?.invoke(bpm)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
