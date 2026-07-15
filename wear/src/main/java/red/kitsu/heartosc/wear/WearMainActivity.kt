package red.kitsu.heartosc.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
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
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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
            WearHeartOSCTheme {
                var isDimmed by remember { mutableStateOf(false) }
                var interactionCount by remember { mutableStateOf(0) }

                // Reset timer when interaction count changes, and dim after 10 seconds of inactivity
                LaunchedEffect(interactionCount, isDimmed) {
                    if (!isDimmed) {
                        delay(10000L)
                        isDimmed = true
                    }
                }

                // Smoothly animate scale and alpha of elements when dimmed
                val scale by animateFloatAsState(
                    targetValue = if (isDimmed) 0.5f else 1.0f,
                    animationSpec = tween(durationMillis = 500),
                    label = "dim_scale"
                )
                val alpha by animateFloatAsState(
                    targetValue = if (isDimmed) 0.2f else 1.0f,
                    animationSpec = tween(durationMillis = 500),
                    label = "dim_alpha"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // Intercept all pointer events to reset the inactivity timer
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    interactionCount++
                                }
                            }
                        },
                    color = Color.Black
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    alpha = alpha
                                )
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isMonitoring && currentBpm > 0) "$currentBpm" else "--",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMonitoring && currentBpm > 0) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Text(
                                text = "BPM",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
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
                                    containerColor = if (isMonitoring) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = if (isMonitoring) "Stop" else "Start",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMonitoring) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        // Full-screen transparent overlay to intercept touches and wake up the screen when dimmed
                        if (isDimmed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                            isDimmed = false
                                            interactionCount++
                                        }
                                    }
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
        
        // Acquire wake lock to keep CPU running when screen goes off
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "HeartOSC::TrackingWakeLock").apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max timeout
        }

        heartRateSensor?.let { sensor ->
            // Use standard or fast delay for real-time tracking
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            isMonitoring = true
            Log.d(TAG, "Started heart rate sensor monitoring with wake lock")
        } ?: run {
            wakeLock?.release()
            wakeLock = null
            Log.e(TAG, "Heart rate sensor not available on this device")
        }
    }

    private fun stopHeartRateMonitoring() {
        if (!isMonitoring) return
        sensorManager?.unregisterListener(this)
        isMonitoring = false
        currentBpm = 0
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        Log.d(TAG, "Stopped heart rate sensor monitoring and released wake lock")
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

private val Purple80 = Color(0xFFD0BCFF)
private val PurpleGrey80 = Color(0xFFCCC2DC)
private val Pink80 = Color(0xFFEFB8C8)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1B1F),
    onSurface = Color.White
)

@Composable
fun WearHeartOSCTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
