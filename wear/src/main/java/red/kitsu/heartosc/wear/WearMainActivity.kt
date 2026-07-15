package red.kitsu.heartosc.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

class WearMainActivity : ComponentActivity(), SensorEventListener {
    companion object {
        private const val TAG = "WearMainActivity"
        private const val WEAR_PATH_HR = "/heartrate"
    }

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var isMonitoring by mutableStateOf(false)
    private var currentBpm by mutableStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startHeartRateMonitoring()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the watch screen awake while the app is active to prevent sensor sleep
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE91E63), // Vibrant pink/red heart color
                    background = Color.Black,
                    onBackground = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isMonitoring && currentBpm > 0) "$currentBpm" else "--",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMonitoring && currentBpm > 0) Color(0xFFE91E63) else Color.Gray
                        )
                        Text(
                            text = "BPM",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (isMonitoring) {
                                    stopHeartRateMonitoring()
                                } else {
                                    checkAndRequestPermissions()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMonitoring) Color.DarkGray else Color(0xFFE91E63)
                            )
                        ) {
                            Text(
                                text = if (isMonitoring) "Stop" else "Start",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            startHeartRateMonitoring()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun startHeartRateMonitoring() {
        if (isMonitoring) return
        heartRateSensor?.let { sensor ->
            // Use standard or fast delay for real-time tracking
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            isMonitoring = true
            Log.d(TAG, "Started heart rate sensor monitoring")
        } ?: run {
            Log.e(TAG, "Heart rate sensor not available on this device")
        }
    }

    private fun stopHeartRateMonitoring() {
        if (!isMonitoring) return
        sensorManager?.unregisterListener(this)
        isMonitoring = false
        currentBpm = 0
        Log.d(TAG, "Stopped heart rate sensor monitoring")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            if (bpm > 0) {
                currentBpm = bpm
                sendHeartRateToPhone(bpm)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendHeartRateToPhone(bpm: Int) {
        val payload = ByteBuffer.allocate(4).putInt(bpm).array()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            val messageClient = Wearable.getMessageClient(this)
            for (node in nodes) {
                messageClient.sendMessage(node.id, WEAR_PATH_HR, payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "Sent BPM $bpm to companion device: ${node.displayName}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send BPM to device ${node.displayName}", e)
                    }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartRateMonitoring()
    }
}
