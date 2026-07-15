# HeartOSC

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

HeartOSC is an Android app that streams heart rate data from Bluetooth Low Energy (BLE) heart rate monitors to VRChat using OSC (Open Sound Control).

## Features

- **Bluetooth LE heart rate monitor support** - Connects to standard BLE heart rate monitors.
- **VRChat OSC integration** - Sends real-time heart rate data to VRChat avatars.
- **Automatic reconnection** - Reconnects with exponential backoff.
- **Multi-language support** - Supports English, Japanese, Korean, and Simplified Chinese.
- **Material Design 3** - Provides a modern Android interface.
- **Foreground service** - Keeps monitoring in the background with a notification.
- **Configurable parameters** - Customizes OSC parameters and pulse behavior.

<!-- ## Screenshots -->

<!-- Add screenshots here when available -->

## Requirements

- Android 8.0 (API 26) or higher
- Bluetooth Low Energy (BLE) support
- A compatible heart rate monitor using the standard Bluetooth Heart Rate Service
- VRChat with OSC enabled

## Installation

### Download

Install HeartOSC from Google Play:

https://play.google.com/store/apps/details?id=red.kitsu.heartosc

### Build from Source

1. Clone the repository:

   ```bash
   git clone https://github.com/hizkifw/HeartOSC.git
   cd HeartOSC
   ```

2. Open the project in Android Studio.

3. Build and run:

   ```bash
   ./gradlew assembleDebug
   ```

## Usage

### First Time Setup

1. Grant permissions:
   - Bluetooth permissions for scanning and connecting to heart rate monitors
   - Location permission, required by Android for BLE scanning
   - Notification permission, used for the background service notification

2. Enable Bluetooth on your Android device.

3. Configure OSC settings:
   - **OSC Host**: IP address of your VRChat instance
   - **OSC Port**: OSC port number, defaulting to `9000`
   - **Parameters**: OSC parameter paths for your avatar

4. Connect your heart rate monitor:
   - Tap **Connect to Device**
   - Select your heart rate monitor from the list
   - Wait for the connection to establish

### OSC Parameters

HeartOSC sends the following OSC parameters to VRChat:

| Parameter        | Type | Description                | Default Path                         |
|------------------|------|----------------------------|--------------------------------------|
| Heart Rate       | Int  | Current BPM value          | `/avatar/parameters/HR`              |
| HR Connected     | Bool | Monitor connection status  | `/avatar/parameters/isHRConnected`   |
| Heartbeat Toggle | Bool | Toggles with each beat     | `/avatar/parameters/HeartBeatToggle` |
| Heartbeat Pulse  | Bool | True during pulse duration | `/avatar/parameters/isHRBeat`        |

For compatibility with VRCOSC Bluetooth Heartrate, HeartOSC also broadcasts the
standard `VRCOSC/Heartrate` parameters alongside the customizable parameters above:

| Parameter  | Type  | Default OSC path                                  |
|------------|-------|---------------------------------------------------|
| Connected  | Bool  | `/avatar/parameters/VRCOSC/Heartrate/Connected`  |
| Value      | Int   | `/avatar/parameters/VRCOSC/Heartrate/Value`      |
| Normalised | Float | `/avatar/parameters/VRCOSC/Heartrate/Normalised` |
| Average    | Int   | `/avatar/parameters/VRCOSC/Heartrate/Average`    |
| Beat       | Bool  | `/avatar/parameters/VRCOSC/Heartrate/Beat`       |
| Enabled    | Bool  | `/avatar/parameters/VRCOSC/Heartrate/Enabled`    |
| Units      | Float | `/avatar/parameters/VRCOSC/Heartrate/Units`      |
| Tens       | Float | `/avatar/parameters/VRCOSC/Heartrate/Tens`       |
| Hundreds   | Float | `/avatar/parameters/VRCOSC/Heartrate/Hundreds`   |

These compatibility parameters can be enabled or disabled in Settings. They are
enabled by default.

### Settings

Use settings to customize:

- **OSC configuration**: Host, port, and parameter paths
- **Pulse duration**: Duration of each heartbeat pulse, from 1 to 5000 ms. The default is 200 ms.

## VRChat Avatar Setup

To use heart rate data in your VRChat avatar:

1. Enable OSC in VRChat.
2. Add parameters to your avatar matching the configured paths.
3. Use the parameters in your avatar animations or expressions:
   - `HR` - Displays BPM or controls animations
   - `isHRConnected` - Shows or hides heart rate UI elements
   - `HeartBeatToggle` - Triggers pulse animations
   - `isHRBeat` - Drives a visual pulse effect during each heartbeat

### Example Avatar Prefab

For a ready-to-use heart rate display implementation, check out [nullstalgia's Heart Rate Display prefab](https://nullstalgia.booth.pm/items/5156075) on BOOTH. This prefab provides a visual heart rate monitor that works with HeartOSC.

## Supported Heart Rate Monitors

Any Bluetooth LE heart rate monitor that implements the standard [Bluetooth Heart Rate Service](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/) should work, including:

- Polar H10
- Wahoo TICKR
- Garmin HRM-Dual
- Coospo H6/H9
- Many others

## Troubleshooting

### Heart rate monitor not found

- Make sure your monitor is turned on and in pairing mode.
- Check that Bluetooth is enabled on your device.
- Ensure location permissions are granted.
- Try restarting Bluetooth on your device.

### VRChat not receiving data

- Verify the OSC host IP address is correct.
- Check that VRChat OSC is enabled.
- Ensure you are on the same network as your VRChat instance.
- Try restarting VRChat.

### Connection drops frequently

- Check the battery level on your heart rate monitor.
- Ensure your device stays within Bluetooth range.
- Check for Bluetooth interference from other devices.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
