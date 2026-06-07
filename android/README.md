# Activity Tracker Android MVP

Native Android/Kotlin MVP for the Activity Tracker embedded project.

The app is Android-only and works locally: no user accounts, no cloud, and no
backend. It connects directly to the nRF52840 over BLE and also retains a mock
data source for UI development and demonstrations without the board.

## Current Status

Implemented:
- Jetpack Compose app shell with Home, Map, Settings, and Debug screens
- real BLE scan, GATT connection, notifications, and command writes
- mock BLE-like device data source
- BLE contract v1 UUID constants and text payload parsers
- live activity, confidence, battery, session duration, steps, and calories UI
- MET-based calorie estimate
- phone GPS location preview on the map
- foreground location service for recording sessions with the screen locked
- OSMDroid route map with activity-colored segments
- grouped stationary markers for sitting and lying
- local settings with DataStore Preferences
- unit tests for BLE payload parsing and calorie calculation

Not implemented yet:
- persisted session history/export
- manual selection from a list of multiple matching BLE devices
- production-grade UI polish

The firmware now exposes a first BLE prototype with real battery values,
placeholder `unknown` activity/summary values, notifications, and the `status`
command. The Android app can connect to this prototype directly.

## Technology Stack

- Kotlin
- Jetpack Compose and Material 3
- AndroidX ViewModel and StateFlow
- DataStore Preferences
- Fused Location Provider
- OSMDroid maps
- Gradle Android Plugin

## Project Layout

```text
android/
  app/
    src/main/
      AndroidManifest.xml
      java/pl/edu/activitytracker/
        app/          dependency container
        ble/          BLE contract and text payload parsers
        data/         repository and device data source interfaces
        domain/       activity, route, calorie, and reading models
        gps/          Android location tracker
        permissions/  runtime permission helpers
        session/      foreground recording service
        storage/      DataStore settings
        ui/           Compose screens and navigation
    src/test/         unit tests
```

## Open in Android Studio

Open this directory as the Android project:

```text
activity_tracker/android
```

Android Studio should use its embedded JDK. If you build from a terminal and
`java` is not in `PATH`, set `JAVA_HOME` to Android Studio's bundled runtime,
for example on Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
```

## Build and Test

From `android/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

Build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated under:

```text
android/app/build/outputs/apk/debug/
```

## Install on a Phone

Recommended path:
1. Open `android/` in Android Studio.
2. Enable Developer options and USB debugging on the phone.
3. Connect the phone over USB and accept the RSA prompt.
4. Select the phone in Android Studio.
5. Press Run.

The app requests:
- location permission for map preview and session route recording
- notification permission on Android 13+ for the foreground session service
- nearby-device BLE permissions for scanning and connecting

## App Behavior

After opening the app:
1. Power the XIAO running the normal firmware.
2. Tap `Scan & connect` and grant the Bluetooth permissions.
3. The app finds the first matching `ActivityTracker` service and connects.
4. Live activity, battery, summary, and raw debug values start updating.
5. Open Map to allow GPS and see the current location.
6. Tap `Start session` to record a session.
7. Lock the phone if needed; the foreground service keeps GPS and BLE alive.
8. Tap `Stop session` to stop recording.

Live mode:
- starts after connecting to the BLE or mock source
- shows current activity, confidence, battery, and debug payloads
- does not record a route by itself

Session mode:
- starts after `Start session`
- resets route, calories, and session duration
- sends the `start` command to the current data source
- starts foreground GPS recording
- stores route points only while the session is active
- sends `stop` when the session ends

## Map Behavior

The Map screen starts location preview when opened. It asks for location
permission automatically because the map is not useful without GPS.

The camera behavior is intentionally restrained:
- when the map opens, it animates once to the current location or last route
  point
- later GPS updates move the `You` marker but do not move the camera
- the floating location button recenters on the user
- zoom changes are animated only when the current zoom is far from the target

Route rendering:
- walking, running, and cycling are drawn as colored line segments
- sitting and lying are shown as grouped stop markers
- noisy GPS points are filtered before they are added to the recorded route

## Calories

Calories are estimated with:

```text
kcal = MET * 3.5 * weight_kg / 200 * minutes
```

Default MET values:
- lying: 1.0
- sitting: 1.3
- walking: 3.5
- cycling: 6.8
- running: 8.0
- unknown: 0.0

These calories are an approximate estimate, not a medical measurement.

## BLE Contract v1

Service UUID:

```text
7b7d0000-8f7a-4f6a-9f4f-1d2c3b4a5000
```

Characteristics:

| Name | UUID | Properties | Payload |
| --- | --- | --- | --- |
| `current_activity` | `7b7d0001-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify | `activity,confidence_percent,duration_s` |
| `battery` | `7b7d0002-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify | `voltage_mv,percent` |
| `summary` | `7b7d0003-8f7a-4f6a-9f4f-1d2c3b4a5000` | read, notify | `session_duration_s,current_activity,steps` |
| `command` | `7b7d0004-8f7a-4f6a-9f4f-1d2c3b4a5000` | write | `start`, `stop`, `status`, ... |

Examples:

```text
walking,82,14
3910,76
320,walking,410
```

Allowed activity values:

```text
walking
running
sitting
lying
cycling
unknown
```

Unknown or unrecognized activity values are mapped to `unknown`.

The current firmware implementation publishes:

```text
unknown,0,0
voltage_mv,percent
uptime_s,unknown,0
```

It automatically notifies activity and summary approximately once per second,
battery approximately every 30 seconds, and immediately republishes all values
after receiving the UTF-8 command `status`.

## Real BLE Device Source

Real BLE is the default data source. After tapping `Scan & connect`, the app:
1. requests the required Android Bluetooth permissions
2. scans for the Activity Tracker service UUID and configured device name
3. connects with Android `BluetoothGatt`
4. enables notifications sequentially for activity, battery, and summary
5. writes `status` so the firmware immediately republishes all values
6. forwards received UTF-8 payloads through `BlePayloadParser` to the existing UI

The current firmware recognizes only `status`. Android also writes `start` and
`stop` when a phone session changes, but the firmware safely ignores those
commands until firmware session handling is implemented.

## Mock Device Source

Enable `Mock data source` in Settings to use the app without the board. It emits:
- current activity at about 1 Hz
- summary at about 1 Hz
- battery periodically
- raw debug events

Changing the source disconnects the currently active source. Both
implementations use the same `DeviceDataSource` interface and existing UI.

## Next Steps

Suggested implementation order:
1. replace placeholder activity and summary with inference results
2. implement firmware handling for `start` and `stop`
3. save finished sessions locally as JSON or CSV
4. add session export for thesis analysis
