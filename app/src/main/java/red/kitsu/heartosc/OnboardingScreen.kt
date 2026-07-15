package red.kitsu.heartosc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    viewModel: HeartRateViewModel,
    onComplete: () -> Unit,
    onPermissionsGranted: () -> Unit,
    checkPermissions: () -> Boolean,
    isBluetoothEnabled: Boolean
) {
    val context = LocalContext.current
    var selectedInputSource by remember { mutableStateOf(SettingsManager.VAL_INPUT_SOURCE_BLE) }
    val pageCount = if (selectedInputSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) 5 else 6
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    // Helper function to check if Bluetooth is enabled
    fun isBluetoothCurrentlyEnabled(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }

    // Helper function to check notification permission
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No notification permission needed on older Android versions
        }
    }

    // Permission states
    var bluetoothPermissionsGranted by remember { mutableStateOf(false) }
    var bluetoothEnabledState by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }

    // OSC settings
    val oscHost by viewModel.oscHost.collectAsState()
    val oscPort by viewModel.oscPort.collectAsState()
    var hostText by remember { mutableStateOf(oscHost) }
    var portText by remember { mutableStateOf(oscPort.toString()) }

    // Permission launchers
    val bluetoothPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        } else {
            permissions[Manifest.permission.BLUETOOTH] == true &&
            permissions[Manifest.permission.BLUETOOTH_ADMIN] == true &&
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        bluetoothPermissionsGranted = bluetoothGranted
        bluetoothEnabledState = isBluetoothCurrentlyEnabled()
        if (bluetoothGranted) {
            onPermissionsGranted()
            // Auto-advance to next page after granting permissions
            if (bluetoothEnabledState) {
                scope.launch {
                    kotlinx.coroutines.delay(500) // Small delay for better UX
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bluetoothEnabledState = isBluetoothCurrentlyEnabled()
        // Auto-advance if bluetooth is now enabled
        if (bluetoothEnabledState && bluetoothPermissionsGranted) {
            scope.launch {
                kotlinx.coroutines.delay(500)
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        // Auto-advance after granting or denying
        scope.launch {
            kotlinx.coroutines.delay(500)
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    // Check initial permission states
    LaunchedEffect(Unit) {
        bluetoothPermissionsGranted = checkPermissions()
        bluetoothEnabledState = isBluetoothCurrentlyEnabled()
        notificationPermissionGranted = hasNotificationPermission()
    }

    // Re-check Bluetooth enabled state when returning to the app
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) {
            bluetoothEnabledState = isBluetoothCurrentlyEnabled()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false
            ) { page ->
                if (selectedInputSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> InputSourcePage(
                            selectedSource = selectedInputSource,
                            onSourceSelected = { selectedInputSource = it }
                        )
                        2 -> NotificationPermissionPage(
                            isGranted = notificationPermissionGranted,
                            onRequestPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notificationPermissionGranted = true
                                }
                            },
                            onSkip = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        )
                        3 -> OscSetupPage(
                            hostText = hostText,
                            portText = portText,
                            onHostChanged = { hostText = it },
                            onPortChanged = { portText = it }
                        )
                        4 -> CompletePage()
                    }
                } else {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> InputSourcePage(
                            selectedSource = selectedInputSource,
                            onSourceSelected = { selectedInputSource = it }
                        )
                        2 -> BluetoothPermissionPage(
                            isGranted = bluetoothPermissionsGranted,
                            isBluetoothEnabled = bluetoothEnabledState,
                            onRequestPermission = {
                                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                } else {
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH,
                                        Manifest.permission.BLUETOOTH_ADMIN,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                }
                                bluetoothPermissionsLauncher.launch(permissions)
                            },
                            onEnableBluetooth = {
                                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                enableBluetoothLauncher.launch(enableBtIntent)
                            }
                        )
                        3 -> NotificationPermissionPage(
                            isGranted = notificationPermissionGranted,
                            onRequestPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notificationPermissionGranted = true
                                }
                            },
                            onSkip = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        )
                        4 -> OscSetupPage(
                            hostText = hostText,
                            portText = portText,
                            onHostChanged = { hostText = it },
                            onPortChanged = { portText = it }
                        )
                        5 -> CompletePage()
                    }
                }
            }

            // Page indicator and navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(pageCount) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                // Next/Complete button
                Button(
                    onClick = {
                        if (pagerState.currentPage == pageCount - 1) {
                            // Save OSC settings
                            viewModel.setOscHost(hostText)
                            val port = portText.toIntOrNull()
                            if (port != null && port in 1..65535) {
                                viewModel.setOscPort(port)
                            }
                            viewModel.setInputSource(selectedInputSource)
                            onComplete()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    enabled = if (selectedInputSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) {
                        when (pagerState.currentPage) {
                            3 -> hostText.isNotBlank() && portText.toIntOrNull()?.let { it in 1..65535 } == true
                            else -> true
                        }
                    } else {
                        when (pagerState.currentPage) {
                            2 -> bluetoothPermissionsGranted && bluetoothEnabledState
                            4 -> hostText.isNotBlank() && portText.toIntOrNull()?.let { it in 1..65535 } == true
                            else -> true
                        }
                    }
                ) {
                    Text(if (pagerState.currentPage == pageCount - 1) stringResource(R.string.onboarding_button_get_started) else stringResource(R.string.onboarding_button_next))
                }
            }
        }
    }
}

@Composable
fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(48.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.onboarding_feature_bluetooth))
                Text(stringResource(R.string.onboarding_feature_customizable))
            }
        }
    }
}

@Composable
fun BluetoothPermissionPage(
    isGranted: Boolean,
    isBluetoothEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_bluetooth_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_bluetooth_subtitle),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (!isGranted) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_button_grant_bluetooth))
            }
        } else if (!isBluetoothEnabled) {
            Button(
                onClick = onEnableBluetooth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_button_enable_bluetooth))
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_bluetooth_granted),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionPage(
    isGranted: Boolean,
    onRequestPermission: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_notifications_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_notifications_subtitle),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_button_enable_notifications))
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_button_skip))
            }
        } else if (isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_notifications_enabled),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun OscSetupPage(
    hostText: String,
    portText: String,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_osc_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_osc_subtitle),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = hostText,
            onValueChange = onHostChanged,
            label = { Text(stringResource(R.string.label_osc_host)) },
            placeholder = { Text(SettingsManager.DEFAULT_OSC_HOST) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = portText,
            onValueChange = {
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    onPortChanged(it)
                }
            },
            label = { Text(stringResource(R.string.label_osc_port)) },
            placeholder = { Text(SettingsManager.DEFAULT_OSC_PORT.toString()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = portText.isNotEmpty() && (portText.toIntOrNull() == null ||
                     portText.toInt() !in 1..65535)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_help_change_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun CompletePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.onboarding_complete_title),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_complete_subtitle),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun InputSourcePage(
    selectedSource: String,
    onSourceSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.settings_section_input_source),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose how you want to receive heart rate data.",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSourceSelected(SettingsManager.VAL_INPUT_SOURCE_BLE) },
            colors = CardDefaults.cardColors(
                containerColor = if (selectedSource == SettingsManager.VAL_INPUT_SOURCE_BLE)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (selectedSource == SettingsManager.VAL_INPUT_SOURCE_BLE)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.input_source_ble),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Connect directly to a Bluetooth Heart Rate Monitor strap or band.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                RadioButton(
                    selected = selectedSource == SettingsManager.VAL_INPUT_SOURCE_BLE,
                    onClick = { onSourceSelected(SettingsManager.VAL_INPUT_SOURCE_BLE) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSourceSelected(SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) },
            colors = CardDefaults.cardColors(
                containerColor = if (selectedSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Watch,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (selectedSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.input_source_wearos),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Stream heart rate from the HeartOSC Wear OS companion app on your watch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                RadioButton(
                    selected = selectedSource == SettingsManager.VAL_INPUT_SOURCE_WEAR_OS,
                    onClick = { onSourceSelected(SettingsManager.VAL_INPUT_SOURCE_WEAR_OS) }
                )
            }
        }
    }
}
