# Activity Tracker Android MVP

Native Android/Kotlin MVP for the Activity Tracker embedded project.

The app is Android-only and works locally: no user accounts, no cloud, and no
backend. Its first implementation uses a mock device data source, so the UI,
GPS, map, session, and calorie logic can be developed before the nRF52840 BLE
firmware exposes the final service.

## Current Status

Implemented:
- Jetpack Compose app shell with Home, Map, Settings, and Debug screens
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
- real BLE scanning and GATT connection
- real writes to the firmware command characteristic
- persisted session history/export
- production-grade UI polish

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
- BLE permissions for the future real BLE implementation

## App Behavior

After opening the app:
1. Tap `Connect mock`.
2. Live mock activity and battery values start updating.
3. Open Map to allow GPS and see the current location.
4. Tap `Start session` to record a session.
5. Lock the phone if needed; the foreground service keeps GPS recording alive.
6. Tap `Stop session` to stop recording.

Live mode:
- starts after connecting to the mock source
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

## Mock Device Source

The MVP uses `MockDeviceDataSource` by default. It emits:
- current activity at about 1 Hz
- summary at about 1 Hz
- battery periodically
- raw debug events

The UI depends on the `DeviceDataSource` interface, so the mock source can be
replaced by a real BLE implementation later without rewriting screens.

## Next Steps

Suggested implementation order:
1. add real BLE scan/connect flow
2. subscribe to `current_activity`, `battery`, and `summary`
3. write `start`, `stop`, and `status` to the `command` characteristic
4. save finished sessions locally as JSON or CSV
5. add session export for thesis analysis
